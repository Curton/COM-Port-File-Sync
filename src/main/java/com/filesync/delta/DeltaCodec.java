package com.filesync.delta;

import java.io.ByteArrayOutputStream;

/**
 * Binary format for a delta stream, shared by {@link DeltaEncoder} and {@link DeltaDecoder}.
 *
 * <pre>
 *   HEADER: MAGIC(4) "DLT\0" | VERSION(1) | BLOCK_SIZE(4) | SOURCE_SIZE(8)
 *   TOKENS: one or more of
 *     COPY:    TAG(1)=0x01 | BLOCK_INDEX(4) | LENGTH(4)
 *     LITERAL: TAG(1)=0x02 | LENGTH(4)      | BYTES(length)
 * </pre>
 *
 * All integers are big-endian. The decoder reconstructs the source by replaying COPY tokens (reading
 * {@code LENGTH} bytes from the receiver's existing file at {@code BLOCK_INDEX * BLOCK_SIZE}) and
 * LITERAL tokens (verbatim bytes) in order.
 */
public final class DeltaCodec {

    private DeltaCodec() {}

    static final byte[] MAGIC = new byte[] {0x44, 0x4C, 0x54, 0x00}; // "DLT\0"
    static final int VERSION = 1;

    static final int TAG_COPY = 0x01;
    static final int TAG_LITERAL = 0x02;

    /** Write the delta header (magic, version, block size, source size). */
    static void writeHeader(ByteArrayOutputStream out, int blockSize, long sourceSize) {
        out.write(MAGIC[0]);
        out.write(MAGIC[1]);
        out.write(MAGIC[2]);
        out.write(MAGIC[3]);
        out.write(VERSION);
        writeInt(out, blockSize);
        writeLong(out, sourceSize);
    }

    /** Write a COPY token referencing {@code blockIndex} for {@code length} bytes. */
    static void writeCopy(ByteArrayOutputStream out, int blockIndex, int length) {
        out.write(TAG_COPY);
        writeInt(out, blockIndex);
        writeInt(out, length);
    }

    /** Write a LITERAL token containing {@code bytes[off..off+len)}. */
    static void writeLiteral(ByteArrayOutputStream out, byte[] bytes, int off, int len) {
        out.write(TAG_LITERAL);
        writeInt(out, len);
        out.write(bytes, off, len);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        writeInt(out, (int) (v >>> 32));
        writeInt(out, (int) v);
    }
}