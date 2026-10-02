package com.filesync.delta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Reconstructs the sender's file bytes from a delta stream plus the receiver's existing file. COPY
 * tokens pull ranges from {@code existing} at {@code blockIndex * blockSize}; LITERAL tokens are
 * verbatim. All bounds are validated so a malformed delta cannot read out of range — any violation
 * throws {@link IOException}, signaling the caller to fall back to a full transfer.
 */
public final class DeltaDecoder {

    /** COPY/LITERAL payloads larger than this are moved in bounded scratch chunks. */
    private static final int CHUNK_SIZE = 64 * 1024;

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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        decodeCore(
                new ArrayBase(existing), new DataInputStream(new ByteArrayInputStream(delta)), out);
        return out.toByteArray();
    }

    /**
     * Streaming variant of {@link #decode}: reads the delta from {@code in} without buffering it,
     * pulls COPY ranges from the receiver's file via random access, and writes the reconstruction
     * to {@code out} in bounded chunks — no full-size array of the existing or reconstructed bytes
     * is held. Wrap {@code out} (e.g. in a {@link java.security.DigestOutputStream}) to verify the
     * reconstruction while it is produced.
     *
     * @param existing the receiver's current file, opened read-only; its content must not change
     *     while the decode runs
     * @param in the delta stream produced by {@link DeltaEncoder#encode}, positioned at its start
     * @param out the sink receiving the reconstructed bytes
     * @return the number of reconstructed bytes written to {@code out}
     * @throws IOException if the delta is malformed, references out-of-bounds regions, or a read or
     *     write fails
     */
    public static long decodeInto(RandomAccessFile existing, DataInputStream in, OutputStream out)
            throws IOException {
        return decodeCore(new FileBase(existing), in, out);
    }

    /** Seekable view of the base content that COPY tokens pull from. */
    private interface Base {
        long size() throws IOException;

        void readFully(long offset, byte[] buf, int off, int len) throws IOException;
    }

    private static final class ArrayBase implements Base {
        private final byte[] data;

        ArrayBase(byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public void readFully(long offset, byte[] buf, int off, int len) {
            System.arraycopy(data, (int) offset, buf, off, len);
        }
    }

    private static final class FileBase implements Base {
        private final RandomAccessFile file;

        FileBase(RandomAccessFile file) {
            this.file = file;
        }

        @Override
        public long size() throws IOException {
            return file.length();
        }

        @Override
        public void readFully(long offset, byte[] buf, int off, int len) throws IOException {
            file.seek(offset);
            file.readFully(buf, off, len);
        }
    }

    /** Shared token loop behind both entry points. Returns the reconstructed byte count. */
    private static long decodeCore(Base existing, DataInputStream in, OutputStream out)
            throws IOException {
        if (in.available() <= 0) {
            throw new IOException("Empty delta stream");
        }
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

        byte[] chunk = new byte[CHUNK_SIZE];
        long written = 0;

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
                if (end > existing.size()) {
                    throw new IOException(
                            "COPY out of bounds: block "
                                    + blockIndex
                                    + " len "
                                    + length
                                    + " exceeds existing size "
                                    + existing.size());
                }
                long done = 0;
                while (done < length) {
                    int want = (int) Math.min(chunk.length, length - done);
                    existing.readFully(offset + done, chunk, 0, want);
                    out.write(chunk, 0, want);
                    done += want;
                }
                written += length;
            } else if (tag == DeltaCodec.TAG_LITERAL) {
                int length = in.readInt();
                if (length < 0) {
                    throw new IOException("Negative LITERAL length: " + length);
                }
                long done = 0;
                while (done < length) {
                    int want = (int) Math.min(chunk.length, length - done);
                    in.readFully(chunk, 0, want);
                    out.write(chunk, 0, want);
                    done += want;
                }
                written += length;
            } else {
                throw new IOException("Unknown delta token tag: " + tag);
            }
        }
        return written;
    }
}
