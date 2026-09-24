package com.filesync.lab.link;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.lab.Trace;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Frame-level tampering: frames are recognised, rules fire in order, everything else passes. */
class FrameTapTest {

    private final List<TamperRule> noRules = new ArrayList<>();

    private final List<byte[]> passed = new ArrayList<>();
    private final List<byte[]> delayed = new ArrayList<>();
    private final List<String> traceLines = new ArrayList<>();

    private final Trace trace = new Trace(true, (File) null, traceLines::add);

    private final FrameTap.Sink sink =
            new FrameTap.Sink() {
                @Override
                public void pass(byte[] data, int off, int len) {
                    passed.add(java.util.Arrays.copyOfRange(data, off, off + len));
                }

                @Override
                public void passDelayed(byte[] data, int off, int len, long delayMillis) {
                    delayed.add(java.util.Arrays.copyOfRange(data, off, off + len));
                }
            };

    private void process(String chunk, FrameTap tap) {
        byte[] data = chunk.getBytes(StandardCharsets.ISO_8859_1);
        tap.process(data, 0, data.length, sink);
    }

    private void process(byte[] chunk, FrameTap tap) {
        tap.process(chunk, 0, chunk.length, sink);
    }

    private List<String> passedText() {
        List<String> out = new ArrayList<>();
        for (byte[] b : passed) {
            out.add(new String(b, StandardCharsets.ISO_8859_1));
        }
        return out;
    }

    /** Compares the forwarded chunks byte for byte (arrays have no value equality). */
    private void assertPassedBytes(List<byte[]> expected) {
        assertEquals(expected.size(), passed.size(), "chunk count: " + passedText());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(expected.get(i), passed.get(i), "chunk " + i);
        }
    }

    private List<String> delayedText() {
        List<String> out = new ArrayList<>();
        for (byte[] b : delayed) {
            out.add(new String(b, StandardCharsets.ISO_8859_1));
        }
        return out;
    }

    @Test
    void plainFramePassesThroughAndIsTraced() {
        FrameTap tap = new FrameTap(noRules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        process("[[SYNC:HEARTBEAT]]\n", tap);
        assertEquals(List.of("[[SYNC:HEARTBEAT]]\n"), passedText());
        assertTrue(
                traceLines.stream().anyMatch(l -> l.contains("FRAME [[SYNC:HEARTBEAT]]")),
                traceLines.toString());
    }

    @Test
    void xmodemBytesBeforeAFramePassThroughUntouched() {
        FrameTap tap = new FrameTap(noRules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        byte[] block = {0x01, 0x01, (byte) 0xFE, 0x00, 0x0A, (byte) 0xFF, 0x04};
        byte[] frame = "[[SYNC:ACK]]\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] mixed = new byte[block.length + frame.length];
        System.arraycopy(block, 0, mixed, 0, block.length);
        System.arraycopy(frame, 0, mixed, block.length, frame.length);
        process(mixed, tap);
        assertEquals(2, passed.size());
        assertArrayEquals(block, passed.get(0));
        assertEquals("[[SYNC:ACK]]\n", new String(passed.get(1), StandardCharsets.ISO_8859_1));
    }

    @Test
    void frameSplitAcrossChunksIsRecognised() {
        FrameTap tap = new FrameTap(noRules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        process("[[SYNC:HEART", tap);
        assertEquals(List.of(), passedText());
        process("BEAT]]\n", tap);
        assertEquals(List.of("[[SYNC:HEARTBEAT]]\n"), passedText());
    }

    @Test
    void frameSplitBetweenEndMarkerAndNewlineIsRecognised() {
        FrameTap tap = new FrameTap(noRules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        process("[[SYNC:ACK]]", tap);
        assertEquals(List.of(), passedText());
        process("\n", tap);
        assertEquals(List.of("[[SYNC:ACK]]\n"), passedText());
    }

    @Test
    void twoFramesInOneChunkAreBothDispatched() {
        FrameTap tap = new FrameTap(noRules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        process("[[SYNC:ACK]]\n[[SYNC:HEARTBEAT_ACK]]\n", tap);
        assertEquals(List.of("[[SYNC:ACK]]\n", "[[SYNC:HEARTBEAT_ACK]]\n"), passedText());
    }

    @Test
    void handshakeByteBehindAFrameInTheSameChunkIsForwarded() {
        // A serial driver merges whatever is ready into one read: the ACK frame and the XMODEM
        // start character 'C' arrive together. The 'C' is not frame data and must not be held
        // waiting for a frame terminator that will never come.
        FrameTap tap = new FrameTap(noRules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        process("[[SYNC:ACK]]\nC", tap);
        assertEquals(List.of("[[SYNC:ACK]]\n", "C"), passedText());
    }

    @Test
    void handshakeBytesAfterAFrameSurviveEveryChunkBoundary() {
        FrameTap tap = new FrameTap(noRules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        process("[[SYNC:ACK]]", tap);
        assertEquals(List.of(), passedText());
        process("\nC", tap);
        assertEquals(List.of("[[SYNC:ACK]]\n", "C"), passedText());
        process("CC", tap);
        assertEquals(List.of("[[SYNC:ACK]]\n", "C", "CC"), passedText());
    }

    @Test
    void blockDataWithBracketBytesPassesThrough() {
        // XMODEM block payloads are arbitrary bytes. A trailing bracket run that could still open
        // a frame is held for the next chunk; the moment the stream proves it is not a frame
        // start, those bytes are released in order. Nothing is lost and nothing is buffered
        // forever.
        FrameTap tap = new FrameTap(noRules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        byte[] block = {(byte) 0x02, 0x01, (byte) 0xFE, '[', '[', 'S'};
        process(block, tap);
        assertPassedBytes(List.of(new byte[] {0x02, 0x01, (byte) 0xFE}));
        process("x", tap);
        assertPassedBytes(
                List.of(new byte[] {0x02, 0x01, (byte) 0xFE}, new byte[] {'[', '[', 'S', 'x'}));
    }

    @Test
    void trailingPartialStartPrefixIsHeldForTheNextChunk() {
        FrameTap tap = new FrameTap(noRules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        process("junk[[SY", tap);
        assertEquals(List.of("junk"), passedText());
        process("NC:ACK]]\n", tap);
        assertEquals(List.of("junk", "[[SYNC:ACK]]\n"), passedText());
    }

    @Test
    void trailingBytesThatCannotOpenAFrameAreForwardedImmediately() {
        FrameTap tap = new FrameTap(noRules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        process("payload[[Z", tap);
        assertEquals(List.of("payload[[Z"), passedText());
        process("more", tap);
        assertEquals(List.of("payload[[Z", "more"), passedText());
    }

    @Test
    void dropRuleRemovesOnlyTheMatchingOccurrence() {
        List<TamperRule> rules = List.of(new TamperRule("ACK", 2, TamperRule.Action.DROP, 0, null));
        FrameTap tap = new FrameTap(rules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        process("[[SYNC:ACK]]\n[[SYNC:ACK]]\n[[SYNC:ACK]]\n", tap);
        assertEquals(List.of("[[SYNC:ACK]]\n", "[[SYNC:ACK]]\n"), passedText());
    }

    @Test
    void dropRuleDropsEveryOccurrenceWhenOccurrenceIsZero() {
        List<TamperRule> rules =
                List.of(new TamperRule("HEARTBEAT_ACK", 0, TamperRule.Action.DROP, 0, null));
        FrameTap tap = new FrameTap(rules, trace, Trace.Dir.PEER_TO_APP, new WireStats());
        process("[[SYNC:HEARTBEAT_ACK]]\n[[SYNC:HEARTBEAT_ACK]]\n", tap);
        assertEquals(List.of(), passedText());
    }

    @Test
    void delayRuleDefersTheFrameInsteadOfDroppingIt() {
        List<TamperRule> rules =
                List.of(new TamperRule("FILE_DATA", 1, TamperRule.Action.DELAY, 500, null));
        FrameTap tap = new FrameTap(rules, trace, Trace.Dir.PEER_TO_APP, new WireStats());
        process("[[SYNC:FILE_DATA:a.txt:10:false:1]]\n", tap);
        assertEquals(List.of(), passedText());
        assertEquals(List.of("[[SYNC:FILE_DATA:a.txt:10:false:1]]\n"), delayedText());
    }

    @Test
    void injectAfterEmitsInjectedFrameBehindTheMatch() {
        List<TamperRule> rules =
                List.of(
                        new TamperRule(
                                "HEARTBEAT_ACK",
                                1,
                                TamperRule.Action.INJECT_AFTER,
                                0,
                                "[[SYNC:DIRECTION_CHANGE:true]]"));
        FrameTap tap = new FrameTap(rules, trace, Trace.Dir.PEER_TO_APP, new WireStats());
        process("[[SYNC:HEARTBEAT_ACK]]\n", tap);
        assertEquals(
                List.of("[[SYNC:HEARTBEAT_ACK]]\n", "[[SYNC:DIRECTION_CHANGE:true]]\n"),
                passedText());
    }

    @Test
    void injectBeforeEmitsInjectedFrameAheadOfTheMatch() {
        List<TamperRule> rules =
                List.of(
                        new TamperRule(
                                "HEARTBEAT",
                                1,
                                TamperRule.Action.INJECT_BEFORE,
                                0,
                                "[[SYNC:DIRECTION_CHANGE:false]]"));
        FrameTap tap = new FrameTap(rules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        process("[[SYNC:HEARTBEAT]]\n", tap);
        assertEquals(
                List.of("[[SYNC:DIRECTION_CHANGE:false]]\n", "[[SYNC:HEARTBEAT]]\n"), passedText());
    }

    @Test
    void corruptRuleFlipsExactlyOneByteOfTheFrame() {
        List<TamperRule> rules =
                List.of(new TamperRule("ACK", 1, TamperRule.Action.CORRUPT, 0, null));
        FrameTap tap = new FrameTap(rules, trace, Trace.Dir.PEER_TO_APP, new WireStats());
        byte[] original = "[[SYNC:ACK]]\n".getBytes(StandardCharsets.ISO_8859_1);
        process(original, tap);
        assertEquals(1, passed.size());
        byte[] out = passed.get(0);
        assertEquals(original.length, out.length);
        int diffs = 0;
        for (int i = 0; i < original.length; i++) {
            if (original[i] != out[i]) {
                diffs++;
            }
        }
        assertEquals(1, diffs);
    }

    @Test
    void nonFrameDataNeverMatchesARule() {
        List<TamperRule> rules = List.of(new TamperRule("ACK", 1, TamperRule.Action.DROP, 0, null));
        FrameTap tap = new FrameTap(rules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        byte[] junk = {0x01, 0x02, 0x18, 0x0A, 0x5B, 0x5B};
        process(junk, tap);
        // The trailing "[[" could still open a frame, so only the rest moves on.
        assertPassedBytes(List.of(new byte[] {0x01, 0x02, 0x18, 0x0A}));
        process("\r\n", tap);
        assertPassedBytes(
                List.of(new byte[] {0x01, 0x02, 0x18, 0x0A}, new byte[] {0x5B, 0x5B, '\r', '\n'}));
    }

    @Test
    void commandOfExtractsTheCommandName() {
        assertEquals("FILE_DATA", FrameTap.commandOf("[[SYNC:FILE_DATA:a:b:1]]"));
        assertEquals("HEARTBEAT", FrameTap.commandOf("[[SYNC:HEARTBEAT]]"));
        assertEquals("ACK", FrameTap.commandOf("garbage[[SYNC:ACK]]"));
        assertEquals("", FrameTap.commandOf("no frame here"));
    }

    @Test
    void oversizedBufferIsFlushedRawRatherThanHeldForever() {
        FrameTap tap = new FrameTap(noRules, trace, Trace.Dir.APP_TO_PEER, new WireStats());
        // A 2 MiB "frame" that never terminates is XMODEM data, not a frame: it must pass through.
        byte[] blob = new byte[3 * 1024 * 1024];
        java.util.Arrays.fill(blob, (byte) 'x');
        System.arraycopy("[[SYNC:".getBytes(StandardCharsets.ISO_8859_1), 0, blob, 0, 7);
        process(blob, tap);
        int emitted = 0;
        for (byte[] b : passed) {
            emitted += b.length;
        }
        assertEquals(blob.length, emitted);
    }
}
