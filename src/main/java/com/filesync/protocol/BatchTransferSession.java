package com.filesync.protocol;

import com.filesync.sync.CompressionUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes multiple small files into a single binary batch for one XMODEM transfer, dramatically
 * reducing per-file handshake overhead. Each batch is a self-contained stream:
 *
 * <pre>
 * MAGIC(4) | VERSION(1) | ENTRY_COUNT(4) | ENTRY[0] | ... | ENTRY[N-1]
 *
 * ENTRY = PATH_LEN(2) | PATH(utf8) | LAST_MODIFIED(8) | FLAGS(1) | RAW_OR_COMPRESSED_LEN(4) | DATA
 * FLAGS: bit 0 = compressed
 * </pre>
 *
 * On the wire the whole batch is one XMODEM payload. The receiver decodes each entry and writes the
 * file. If the XMODEM transfer fails the entire batch is retried; individual files cannot be
 * resumed mid-batch.
 */
public class BatchTransferSession {

    /** Magic bytes identifying a batch payload: "BTH\0" */
    private static final byte[] MAGIC = new byte[] {0x42, 0x54, 0x48, 0x00};

    private static final int VERSION = 1;
    private static final int MAX_ENTRIES_PER_BATCH = 256;

    private BatchTransferSession() {}

    /**
     * Build a binary batch from the given file list and encode it into a byte array.
     *
     * @param files list of entries; each entry is a Object[] { File file, String relativePath }
     * @param maxBatchSizeBytes soft upper bound on total encoded bytes (count + content, approx.)
     * @return encoded batch bytes, ready for XMODEM.send()
     */
    public static byte[] buildBatch(List<Object[]> files, int maxBatchSizeBytes)
            throws IOException {
        ByteArrayOutputStream out =
                new ByteArrayOutputStream(maxBatchSizeBytes > 0 ? maxBatchSizeBytes : 65536);

        out.write(MAGIC[0]);
        out.write(MAGIC[1]);
        out.write(MAGIC[2]);
        out.write(MAGIC[3]);
        out.write(VERSION);

        int count = Math.min(files.size(), MAX_ENTRIES_PER_BATCH);
        byte[] countBytes = ByteBuffer.allocate(4).putInt(count).array();
        out.write(countBytes[0]);
        out.write(countBytes[1]);
        out.write(countBytes[2]);
        out.write(countBytes[3]);

        for (int i = 0; i < count; i++) {
            Object[] entry = files.get(i);
            java.io.File file = (java.io.File) entry[0];
            String relativePath = (String) entry[1];

            byte[] pathBytes = relativePath.getBytes(StandardCharsets.UTF_8);
            byte[] content = readFileContent(file);
            CompressionUtil.CompressedData compressedData =
                    CompressionUtil.compressIfBeneficial(relativePath, content);
            boolean wasCompressed = compressedData.isCompressed();
            byte[] data = compressedData.getData();
            long lastModified = file.lastModified();

            // PATH_LEN (2 bytes, big-endian)
            byte[] pathLenBytes = ByteBuffer.allocate(2).putShort((short) pathBytes.length).array();
            out.write(pathLenBytes[0]);
            out.write(pathLenBytes[1]);

            // PATH
            out.write(pathBytes, 0, pathBytes.length);

            // LAST_MODIFIED (8 bytes, big-endian)
            byte[] lmBytes = ByteBuffer.allocate(8).putLong(lastModified).array();
            out.write(lmBytes, 0, 8);

            // FLAGS (1 byte): bit 0 = compressed
            byte flags = (byte) (wasCompressed ? 1 : 0);
            out.write(flags);

            // RAW_OR_COMPRESSED_LEN (4 bytes, big-endian)
            byte[] lenBytes = ByteBuffer.allocate(4).putInt(data.length).array();
            out.write(lenBytes[0]);
            out.write(lenBytes[1]);
            out.write(lenBytes[2]);
            out.write(lenBytes[3]);

            // DATA
            out.write(data, 0, data.length);
        }

        return out.toByteArray();
    }

    /** Read all bytes from a file into memory. */
    private static byte[] readFileContent(java.io.File file) throws IOException {
        long fileSize = file.length();
        if (fileSize > Integer.MAX_VALUE) {
            throw new IOException(
                    "File too large: "
                            + fileSize
                            + " bytes (max: "
                            + Integer.MAX_VALUE
                            + " bytes)");
        }
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] data = new byte[(int) fileSize];
            int totalRead = 0;
            while (totalRead < data.length) {
                int read = fis.read(data, totalRead, data.length - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
            return data;
        }
    }

    /**
     * Decode a batch produced by {@link #buildBatch} and write each file into the given base
     * directory.
     *
     * @param baseDir the directory to extract files under
     * @param batch encoded batch bytes
     * @param totalEntries the total number of files in the full sync operation (used in the
     *     callback to report correct overall progress)
     * @param progressCallback called with (entryIndex, totalEntries, relativePath) after each file
     *     is written; may be null
     * @return the number of files written
     */
    public static int decodeAndWriteBatch(
            java.io.File baseDir,
            byte[] batch,
            int totalEntries,
            BatchProgressCallback progressCallback)
            throws IOException {
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(batch);

        // Verify magic
        byte[] magicRead = new byte[4];
        if (in.read(magicRead) != 4
                || magicRead[0] != MAGIC[0]
                || magicRead[1] != MAGIC[1]
                || magicRead[2] != MAGIC[2]
                || magicRead[3] != MAGIC[3]) {
            throw new IOException("Invalid batch: bad magic bytes");
        }

        int version = in.read();
        if (version != VERSION) {
            throw new IOException("Unsupported batch version: " + version);
        }

        byte[] countBuf = new byte[4];
        readFully(in, countBuf);
        int count = ByteBuffer.wrap(countBuf).getInt();

        int written = 0;
        for (int i = 0; i < count; i++) {
            // PATH_LEN
            byte[] pathLenBuf = new byte[2];
            readFully(in, pathLenBuf);
            int pathLen = ByteBuffer.wrap(pathLenBuf).getShort() & 0xFFFF;

            // PATH
            byte[] pathBytes = new byte[pathLen];
            readFully(in, pathBytes);
            String relativePath = new String(pathBytes, StandardCharsets.UTF_8);
            if (relativePath.contains("..")) {
                throw new IOException("Path traversal in batch entry: " + relativePath);
            }

            // LAST_MODIFIED
            byte[] lmBuf = new byte[8];
            readFully(in, lmBuf);
            long lastModified = ByteBuffer.wrap(lmBuf).getLong();

            // FLAGS
            int flags = in.read();
            boolean compressed = (flags & 1) != 0;

            // LEN
            byte[] lenBuf = new byte[4];
            readFully(in, lenBuf);
            int dataLen = ByteBuffer.wrap(lenBuf).getInt();

            // DATA
            byte[] data = new byte[dataLen];
            readFully(in, data);

            // Decompress if needed
            if (compressed) {
                data = CompressionUtil.decompress(data);
            }

            // Write file
            java.io.File targetFile = new java.io.File(baseDir, relativePath);
            java.io.File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
                fos.write(data);
            }
            if (lastModified > 0) {
                targetFile.setLastModified(lastModified);
            }

            written++;
            if (progressCallback != null) {
                progressCallback.onEntryProcessed(i, totalEntries, relativePath);
            }
        }

        return written;
    }

    private static void readFully(java.io.InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = in.read(buf, total, buf.length - total);
            if (r == -1) throw new IOException("Unexpected end of batch stream");
            total += r;
        }
    }

    /** Callback for batch decode progress. */
    public interface BatchProgressCallback {
        void onEntryProcessed(int entryIndex, int totalEntries, String relativePath);
    }
}
