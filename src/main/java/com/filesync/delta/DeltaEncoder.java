package com.filesync.delta;

import com.filesync.util.IoUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces a delta stream that transforms the receiver's existing file into the sender's version,
 * using rsync-style rolling-hash block matching.
 *
 * <p>The encoder builds a lookup table keyed by each signature block's weak checksum. It then scans
 * the source with a sliding window of {@code blockSize} bytes: at each offset it computes the
 * rolling weak checksum, and on a hit confirms with the strong MD5 hash. A confirmed match emits a
 * COPY token and the window jumps forward by a full block; otherwise the current byte is emitted as
 * a LITERAL and the window slides one byte. Only full {@code blockSize}-length windows are matched;
 * trailing bytes shorter than a block are always sent as literals.
 *
 * <p>Consecutive matched blocks are coalesced into a single COPY token spanning the whole run (the
 * decoder accepts any COPY length that stays within the existing file), so a file whose prefix is
 * unchanged — e.g. an append-only log — costs one 9-byte token for the entire prefix instead of one
 * per block.
 *
 * <p>The source is consumed as a stream: the scan window lives in a ring buffer of {@code
 * blockSize} bytes and literal runs are emitted as length-prefixed LITERAL tokens in bounded
 * chunks, so no full-size source array is ever held. The decoder accepts any sequence of LITERAL
 * tokens, so chunking is transparent to the wire format.
 */
public final class DeltaEncoder {

    /** Literal bytes are tokenized in chunks of this size to bound the encoder's memory. */
    private static final int LITERAL_CHUNK_SIZE = 64 * 1024;

    private DeltaEncoder() {}

    /**
     * Encode the delta from the receiver's existing file (described by {@code sigs}) to the
     * sender's {@code source} bytes.
     *
     * @throws IOException if the streamed read of {@code source} fails; a plain {@code byte[]}
     *     source cannot actually fail, but the streaming core {@link #encode(InputStream, long,
     *     FileSignatures)} is shared
     */
    public static byte[] encode(byte[] source, FileSignatures sigs) throws IOException {
        return encode(new ByteArrayInputStream(source), source.length, sigs);
    }

    /**
     * Encode the delta from a streamed source. The stream must deliver exactly {@code sourceSize}
     * bytes — the sender's file length, which is written into the delta header — otherwise an
     * {@code IOException} is thrown. The stream is read once, sequentially; the caller must buffer
     * nothing beyond what this method returns.
     *
     * @param source the sender's file content, consumed sequentially
     * @param sourceSize the exact number of bytes {@code source} will deliver
     * @param sigs signatures of the receiver's existing file
     * @return the delta stream
     * @throws IOException if reading {@code source} fails or it delivers a different byte count
     */
    public static byte[] encode(InputStream source, long sourceSize, FileSignatures sigs)
            throws IOException {
        int blockSize = sigs.getBlockSize();
        int blockCount = sigs.getBlockCount();
        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        DeltaCodec.writeHeader(delta, blockSize, sourceSize);

        if (blockSize <= 0 || blockCount == 0 || sourceSize < blockSize) {
            // No matchable blocks: emit the whole source as literals.
            LiteralSink literals = new LiteralSink(delta);
            pumpLiterals(source, literals, sourceSize);
            literals.closeRun();
            return delta.toByteArray();
        }

        Map<Integer, List<BlockSignature>> table = new HashMap<>();
        for (BlockSignature bs : sigs.getSignatures()) {
            table.computeIfAbsent(bs.getWeakHash(), k -> new ArrayList<>()).add(bs);
        }

        MessageDigest md5 = Md5.newDigest();
        // md5.digest() allocates its 16-byte result per weak-hash hit — negligible next to the
        // eliminated block-sized window copy; only the first STRONG_HASH_LENGTH bytes compare.
        byte[] digestScratch = new byte[md5.getDigestLength()];

        RollingHash rh = new RollingHash(blockSize);
        // Ring buffer holding the current window source[i..i+blockSize): ring[ringPos] is the
        // window's leading byte. Sequential access only, so no full source buffer is needed.
        byte[] ring = new byte[blockSize];
        int ringPos = 0;

        LiteralSink literals = new LiteralSink(delta);

        long n = sourceSize;
        long i = 0;

        // Deferred COPY run: consecutive block matches extend one token instead of emitting one
        // token per block. pendingCopyBlockIndex == -1 means no run is open.
        int pendingCopyBlockIndex = -1;
        int pendingCopyLength = 0;

        // Initialise the rolling window over [0, blockSize).
        readFully(source, ring, 0, blockSize);
        rh.reset();
        for (int k = 0; k < blockSize; k++) {
            rh.update(ring[k]);
        }

        while (i < n) {
            boolean matched = false;
            if (i + blockSize <= n) {
                int weak = rh.value();
                List<BlockSignature> cands = table.get(weak);
                if (cands != null) {
                    md5.reset();
                    // Hash the ring window in its (at most two) contiguous spans: no copy.
                    md5.update(ring, ringPos, blockSize - ringPos);
                    if (ringPos > 0) {
                        md5.update(ring, 0, ringPos);
                    }
                    byte[] digest = md5.digest();
                    for (BlockSignature bs : cands) {
                        if (Arrays.equals(
                                bs.strongHashInternal(),
                                0,
                                BlockSignature.STRONG_HASH_LENGTH,
                                digest,
                                0,
                                BlockSignature.STRONG_HASH_LENGTH)) {
                            // Flush buffered literals first.
                            literals.closeRun();
                            if (bs.getBlockIndex()
                                    == pendingCopyBlockIndex + pendingCopyLength / blockSize) {
                                pendingCopyLength += blockSize;
                            } else {
                                writeCopyRun(delta, pendingCopyBlockIndex, pendingCopyLength);
                                pendingCopyBlockIndex = bs.getBlockIndex();
                                pendingCopyLength = blockSize;
                            }
                            i += blockSize;
                            matched = true;
                            // After a full-block match, re-initialise the rolling window at the
                            // new position. Once no full window fits ahead, the unread tail is all
                            // literal: drain it straight from the stream.
                            if (i + blockSize <= n) {
                                readFully(source, ring, 0, blockSize);
                                ringPos = 0;
                                rh.reset();
                                for (int k = 0; k < blockSize; k++) {
                                    rh.update(ring[k]);
                                }
                            } else {
                                writeCopyRun(delta, pendingCopyBlockIndex, pendingCopyLength);
                                pendingCopyBlockIndex = -1;
                                pendingCopyLength = 0;
                                pumpLiterals(source, literals, n - i);
                                i = n;
                            }
                            break;
                        }
                    }
                }
            }

            if (!matched) {
                // A literal token must never precede an open COPY run on the wire: close the run
                // before the run's first literal byte is emitted (mirrors the buffered encoder's
                // flush-on-literal-start). A match always closes the literal run first, so a run
                // is open here only while no COPY is pending.
                if (pendingCopyBlockIndex >= 0) {
                    writeCopyRun(delta, pendingCopyBlockIndex, pendingCopyLength);
                    pendingCopyBlockIndex = -1;
                    pendingCopyLength = 0;
                }
                // Slide the window one byte forward: the leading byte joins the literal run, and
                // — while a full window still fits ahead — the next stream byte takes its slot.
                literals.write(ring[ringPos]);
                if (i + blockSize < n) {
                    int b = source.read();
                    if (b < 0) {
                        throw new IOException(
                                "Source ended early while delta-encoding: expected "
                                        + n
                                        + " bytes");
                    }
                    rh.roll(ring[ringPos], (byte) b);
                    ring[ringPos] = (byte) b;
                    ringPos = (ringPos + 1) % blockSize;
                } else {
                    // No new byte fits ahead: the emitted slot is spent, so advance the window
                    // marker even though nothing was read into it.
                    ringPos = (ringPos + 1) % blockSize;
                }
                i++;
            }
        }

        literals.closeRun();
        writeCopyRun(delta, pendingCopyBlockIndex, pendingCopyLength);
        return delta.toByteArray();
    }

    /** Read exactly {@code len} bytes into {@code buf[off..off+len)} or fail. */
    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        IoUtil.readFullyOrThrow(in, buf, off, len, "Source ended early while delta-encoding");
    }

    /** Pump exactly {@code count} remaining source bytes into the literal sink. */
    private static void pumpLiterals(InputStream source, LiteralSink sink, long count)
            throws IOException {
        byte[] buf = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int want = (int) Math.min(buf.length, remaining);
            readFully(source, buf, 0, want);
            sink.write(buf, 0, want);
            remaining -= want;
        }
    }

    /**
     * Accumulates literal bytes and emits them as LITERAL tokens: eagerly in bounded chunks while a
     * run is open, and as one final token (possibly empty) when the run closes.
     */
    private static final class LiteralSink {
        private final ByteArrayOutputStream delta;
        private final ByteArrayOutputStream chunk = new ByteArrayOutputStream(LITERAL_CHUNK_SIZE);
        private boolean runOpen;

        LiteralSink(ByteArrayOutputStream delta) {
            this.delta = delta;
        }

        void write(int b) throws IOException {
            runOpen = true;
            chunk.write(b);
            if (chunk.size() >= LITERAL_CHUNK_SIZE) {
                flushChunk();
            }
        }

        void write(byte[] buf, int off, int len) throws IOException {
            runOpen = true;
            chunk.write(buf, off, len);
            if (chunk.size() >= LITERAL_CHUNK_SIZE) {
                flushChunk();
            }
        }

        /** Close the current literal run, emitting any bytes held back below the chunk size. */
        void closeRun() throws IOException {
            if (runOpen) {
                flushChunk();
                runOpen = false;
            }
        }

        private void flushChunk() throws IOException {
            byte[] bytes = chunk.toByteArray();
            chunk.reset();
            DeltaCodec.writeLiteral(delta, bytes, 0, bytes.length);
        }
    }

    /** Write the deferred COPY run, if one is open. */
    private static void writeCopyRun(ByteArrayOutputStream delta, int blockIndex, int length) {
        if (blockIndex >= 0) {
            DeltaCodec.writeCopy(delta, blockIndex, length);
        }
    }
}
