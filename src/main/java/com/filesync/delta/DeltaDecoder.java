package com.filesync.delta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Reconstructs the sender's file bytes from a delta stream plus the receiver's existing file. COPY
 * tokens pull ranges from {@code existing} at {@code blockIndex * blockSize}; LITERAL tokens are
 * verbatim. All bounds are validated so a malformed delta cannot read out of range — any violation
 * throws {@link IOException}, signaling the caller to fall back to a full transfer.
 */
public final class DeltaDecoder {

    private DeltaDecoder() {}

    /**
     * Reconstruct the source bytes.
     *
     * @param existing the receiver's current file content
     * @param delta the delta stream produced by {@link DeltaEncoder#encode}
     * @return the reconstructed source bytes
     * @throws IOException if the delta is malformed or references out-of-bounds regions
     */
    public static byte[] decode(byte[] existing, byte[] delta) throws IOException {
        if (delta == null || delta.length == 0) {
            throw new IOException("Empty delta stream");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(delta))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (!Arrays.equals(magic, DeltaCodec.MAGIC)) {
                throw new IOException("Invalid delta magic");
            }
            int version = in.readUnsignedByte();
            if (version != DeltaCodec.VERSION) {
                throw new IOException("Unsupported delta version: " + version);
            }
            int blockSize = in.readInt();
            long sourceSize = in.readLong();
            if (blockSize <= 0) {
                throw new IOException("Non-positive block size in delta: " + blockSize);
            }
            if (sourceSize < 0) {
                throw new IOException("Negative source size in delta: " + sourceSize);
            }

            int initialCapacity = sourceSize <= Integer.MAX_VALUE ? (int) sourceSize : 65536;
            ByteArrayOutputStream out = new ByteArrayOutputStream(initialCapacity);

            while (in.available() > 0) {
                int tag = in.readUnsignedByte();
                if (tag == DeltaCodec.TAG_COPY) {
                    int blockIndex = in.readInt();
                    int length = in.readInt();
                    if (blockIndex < 0 || length < 0) {
                        throw new IOException(
                                "Negative COPY fields: block=" + blockIndex + " len=" + length);
                    }
                    long offset = (long) blockIndex * blockSize;
                    long end = offset + length;
                    if (end > existing.length) {
                        throw new IOException(
                                "COPY out of bounds: block "
                                        + blockIndex
                                        + " len "
                                        + length
                                        + " exceeds existing size "
                                        + existing.length);
                    }
                    out.write(existing, (int) offset, length);
                } else if (tag == DeltaCodec.TAG_LITERAL) {
                    int length = in.readInt();
                    if (length < 0) {
                        throw new IOException("Negative LITERAL length: " + length);
                    }
                    byte[] buf = new byte[length];
                    in.readFully(buf);
                    out.write(buf);
                } else {
                    throw new IOException("Unknown delta token tag: " + tag);
                }
            }
            return out.toByteArray();
        }
    }
}
