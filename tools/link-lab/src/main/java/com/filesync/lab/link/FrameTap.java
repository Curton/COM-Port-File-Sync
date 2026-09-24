package com.filesync.lab.link;

import com.filesync.lab.Trace;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Inspects the byte stream for control frames ({@code [[SYNC:...]]} lines) and applies the
 * configured {@link TamperRule}s. Everything that is not a control frame - XMODEM blocks, raw
 * handshake bytes, noise - passes through untouched, so file transfers keep working while specific
 * commands are dropped, delayed or surrounded by injected frames.
 *
 * <p>Frame detection mirrors the app's own parser: a frame starts at the last {@code [[SYNC:} on
 * the line and must end with {@code ]]}. The same tolerance is used here, so stray bytes left
 * behind by a cancelled XMODEM session are ignored instead of breaking the stream.
 *
 * <p>Only bytes that can still become a frame start are ever held back: a trailing partial {@code
 * [[SYNC:} prefix at a chunk boundary, or a started frame waiting for its tail. Anything else -
 * including raw handshake bytes that arrive in the same chunk right after a frame - is forwarded
 * immediately, because a serial driver merges whatever is ready into one read and a real link does
 * not wait for a frame terminator before delivering the next byte.
 */
final class FrameTap {

    private static final byte[] START = "[[SYNC:".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] TAIL = "]]\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;

    /** Where processed bytes go. */
    interface Sink {
        void pass(byte[] data, int off, int len);

        void passDelayed(byte[] data, int off, int len, long delayMillis);
    }

    private final List<TamperRule> rules;
    private final Trace trace;
    private final Trace.Dir dir;
    private final WireStats stats;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private boolean accumulating;

    FrameTap(List<TamperRule> rules, Trace trace, Trace.Dir dir, WireStats stats) {
        this.rules = new ArrayList<>(rules);
        this.trace = trace;
        this.dir = dir;
        this.stats = stats;
    }

    /** Adds a rule while the link is running. */
    void addRule(TamperRule rule) {
        rules.add(rule);
    }

    void process(byte[] data, int off, int len, Sink sink) {
        int pos = off;
        int end = off + len;
        while (pos < end) {
            if (accumulating) {
                writeToPending(data, pos, end - pos);
                pos = end;
                drain(sink);
                continue;
            }
            int idx = indexOf(data, pos, end, START);
            if (idx < 0) {
                // Keep only the trailing bytes that could still open a frame in the next chunk.
                int keep = trailingStartPrefix(data, pos, end);
                sink.pass(data, pos, end - pos - keep);
                if (keep > 0) {
                    writeToPending(data, end - keep, keep);
                    accumulating = true;
                }
                pos = end;
                continue;
            }
            if (idx > pos) {
                sink.pass(data, pos, idx - pos);
            }
            writeToPending(data, idx, end - idx);
            accumulating = true;
            pos = end;
            drain(sink);
        }
    }

    /**
     * Dispatches every complete frame held in {@link #pending} and releases whatever can no longer
     * become one. Bytes left over after a frame are ordinary stream bytes (handshake characters,
     * block data) and must reach the peer exactly as a real driver would deliver them.
     */
    private void drain(Sink sink) {
        while (accumulating) {
            byte[] buf = pending.toByteArray();
            int tail = indexOf(buf, 0, buf.length, TAIL);
            if (tail < 0) {
                if (!canStillOpenFrame(buf) || buf.length > MAX_FRAME_BYTES) {
                    // Either a false start marker inside raw data, or a frame that never got its
                    // tail: hand the bytes to the peer instead of swallowing them.
                    pending.reset();
                    accumulating = false;
                    sink.pass(buf, 0, buf.length);
                }
                return;
            }
            int frameEnd = tail + TAIL.length;
            byte[] frame = Arrays.copyOfRange(buf, 0, frameEnd);
            pending.reset();
            if (buf.length > frameEnd) {
                pending.write(buf, frameEnd, buf.length - frameEnd);
            } else {
                accumulating = false;
            }
            dispatchFrame(frame, sink);
        }
    }

    /** Whether the buffer is still a frame start prefix or a frame waiting for its tail. */
    private static boolean canStillOpenFrame(byte[] buf) {
        if (buf.length == 0) {
            return false;
        }
        int compare = Math.min(buf.length, START.length);
        for (int i = 0; i < compare; i++) {
            if (buf[i] != START[i]) {
                return false;
            }
        }
        return true;
    }

    /** Length of the trailing run of bytes that is a prefix of the start marker. */
    private static int trailingStartPrefix(byte[] data, int from, int to) {
        int max = Math.min(START.length - 1, to - from);
        for (int keep = max; keep > 0; keep--) {
            boolean match = true;
            for (int i = 0; i < keep; i++) {
                if (data[to - keep + i] != START[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return keep;
            }
        }
        return 0;
    }

    private void writeToPending(byte[] data, int off, int len) {
        pending.write(data, off, len);
    }

    private void dispatchFrame(byte[] frame, Sink sink) {
        String text = new String(frame, StandardCharsets.ISO_8859_1);
        trace.frame(dir, text);
        String command = commandOf(text);
        for (TamperRule rule : rules) {
            TamperRule.Action action = rule.claim(command);
            if (action == null) {
                continue;
            }
            switch (action) {
                case DROP:
                    stats.onFrameDropped();
                    trace.wire(dir, "TAMPER drop - " + rule.describe());
                    return;
                case DELAY:
                    stats.onFrameDelayed();
                    stats.onFramePassed();
                    trace.wire(
                            dir, "TAMPER delay " + rule.delayMillis() + "ms - " + rule.describe());
                    sink.passDelayed(frame, 0, frame.length, rule.delayMillis());
                    return;
                case CORRUPT:
                    byte[] broken = frame.clone();
                    int flipAt =
                            Math.min(broken.length - 1, Math.max(START.length, broken.length / 2));
                    broken[flipAt] ^= 0x20;
                    stats.onFramePassed();
                    trace.wire(dir, "TAMPER corrupt byte " + flipAt + " - " + rule.describe());
                    sink.pass(broken, 0, broken.length);
                    return;
                case INJECT_BEFORE:
                case INJECT_AFTER:
                    byte[] injected = rule.injectFrameBytes();
                    stats.onFramePassed();
                    if (action == TamperRule.Action.INJECT_BEFORE && injected != null) {
                        stats.onFrameInjected();
                        trace.wire(
                                dir, "TAMPER inject before " + command + " - " + rule.describe());
                        sink.pass(injected, 0, injected.length);
                    }
                    sink.pass(frame, 0, frame.length);
                    if (action == TamperRule.Action.INJECT_AFTER && injected != null) {
                        stats.onFrameInjected();
                        trace.wire(dir, "TAMPER inject after " + command + " - " + rule.describe());
                        sink.pass(injected, 0, injected.length);
                    }
                    return;
                default:
                    break;
            }
        }
        stats.onFramePassed();
        sink.pass(frame, 0, frame.length);
    }

    /** The command name of a frame line: {@code [[SYNC:CMD:...]]} -> {@code CMD}. */
    static String commandOf(String frameLine) {
        int s = frameLine.indexOf("[[SYNC:");
        if (s < 0) {
            return "";
        }
        int e = frameLine.indexOf("]]", s);
        String body = frameLine.substring(s + START.length, e < 0 ? frameLine.length() : e);
        int c = body.indexOf(':');
        return c < 0 ? body : body.substring(0, c);
    }

    private static int indexOf(byte[] data, int from, int to, byte[] needle) {
        if (needle.length == 0 || to - from < needle.length) {
            return -1;
        }
        int last = to - needle.length;
        for (int i = from; i <= last; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }
}
