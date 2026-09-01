package com.filesync.delta;

import java.io.ByteArrayOutputStream;
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
 */
public final class DeltaEncoder {

    private DeltaEncoder() {}

    /**
     * A delta must save at least this fraction of the full compressed transfer to be worth sending.
     */
    public static final double BENEFICIAL_RATIO = 0.8;

    /**
     * Whether a delta of {@code deltaLen} bytes is worth sending instead of the full compressed
     * transfer of {@code fullCompressedLen} bytes.
     */
    public static boolean isBeneficial(int deltaLen, int fullCompressedLen) {
        if (fullCompressedLen <= 0) {
            return false;
        }
        return deltaLen < fullCompressedLen * BENEFICIAL_RATIO;
    }

    /**
     * Encode the delta from the receiver's existing file (described by {@code sigs}) to the
     * sender's {@code source} bytes.
     */
    public static byte[] encode(byte[] source, FileSignatures sigs) {
        int blockSize = sigs.getBlockSize();
        int blockCount = sigs.getBlockCount();
        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        DeltaCodec.writeHeader(delta, blockSize, source.length);

        if (blockSize <= 0 || blockCount == 0 || source.length < blockSize) {
            // No matchable blocks: emit the whole source as one literal.
            if (source.length > 0) {
                DeltaCodec.writeLiteral(delta, source, 0, source.length);
            }
            return delta.toByteArray();
        }

        Map<Integer, List<BlockSignature>> table = new HashMap<>();
        for (BlockSignature bs : sigs.getSignatures()) {
            table.computeIfAbsent(bs.getWeakHash(), k -> new ArrayList<>()).add(bs);
        }

        MessageDigest md5 = Md5.newDigest();

        RollingHash rh = new RollingHash(blockSize);
        int n = source.length;
        int i = 0;

        // Pending literal run: buffered until a COPY or end of input flushes it.
        int literalStart = 0;
        boolean hasLiteral = false;

        // Deferred COPY run: consecutive block matches extend one token instead of emitting one
        // token per block. pendingCopyBlockIndex == -1 means no run is open.
        int pendingCopyBlockIndex = -1;
        int pendingCopyLength = 0;

        // Initialise the rolling window over [0, blockSize).
        rh.reset();
        for (int k = 0; k < blockSize; k++) {
            rh.update(source[k]);
        }

        while (i < n) {
            boolean matched = false;
            if (i + blockSize <= n) {
                int weak = rh.value();
                List<BlockSignature> cands = table.get(weak);
                if (cands != null) {
                    md5.reset();
                    byte[] digest =
                            Arrays.copyOf(
                                    md5.digest(Arrays.copyOfRange(source, i, i + blockSize)),
                                    BlockSignature.STRONG_HASH_LENGTH);
                    for (BlockSignature bs : cands) {
                        if (Arrays.equals(bs.strongHashInternal(), digest)) {
                            // Flush buffered literals first.
                            if (hasLiteral) {
                                DeltaCodec.writeLiteral(
                                        delta, source, literalStart, i - literalStart);
                                hasLiteral = false;
                            }
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
                            break;
                        }
                    }
                }
            }

            if (!matched) {
                if (!hasLiteral) {
                    // A literal cannot join a pending COPY run: flush it so stream order is kept.
                    writeCopyRun(delta, pendingCopyBlockIndex, pendingCopyLength);
                    pendingCopyBlockIndex = -1;
                    pendingCopyLength = 0;
                    literalStart = i;
                    hasLiteral = true;
                }
                i++;
                // Roll the window forward by one if a full window still fits ahead.
                if (i + blockSize <= n) {
                    rh.roll(source[i - 1], source[i + blockSize - 1]);
                }
            } else {
                // After a full-block match, re-initialise the rolling window at the new position.
                rh.reset();
                if (i + blockSize <= n) {
                    for (int k = 0; k < blockSize; k++) {
                        rh.update(source[i + k]);
                    }
                }
            }
        }

        if (hasLiteral) {
            DeltaCodec.writeLiteral(delta, source, literalStart, n - literalStart);
        }
        writeCopyRun(delta, pendingCopyBlockIndex, pendingCopyLength);
        return delta.toByteArray();
    }

    /** Write the deferred COPY run, if one is open. */
    private static void writeCopyRun(ByteArrayOutputStream delta, int blockIndex, int length) {
        if (blockIndex >= 0) {
            DeltaCodec.writeCopy(delta, blockIndex, length);
        }
    }
}
