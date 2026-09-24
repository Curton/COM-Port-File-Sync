package com.filesync.protocol;

import com.filesync.sync.CompressionUtil;
import com.filesync.sync.FileChangeDetector;
import com.filesync.sync.SafePaths;
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
 * ENTRY = PATH_LEN(2) | PATH(utf8) | LAST_MODIFIED(8) | FLAGS(1) [ | MD5(16 raw) ]
 *         | RAW_OR_COMPRESSED_LEN(4) | DATA
 * FLAGS: bit 0 = compressed, bit 1 = entry carries a manifest MD5
 * </pre>
 *
 * The optional 16 raw MD5 bytes are the sender's manifest hash of the entry's decoded content. The
 * receiver verifies every hashed entry before writing it — an entry whose content does not
 * reproduce the announced hash is not written and is reported as a failure, so line-corrupted
 * content can never silently land on disk. Entries without a hash (fast mode leaves files unhashed)
 * are written unverified, exactly as before.
 *
 * <p>On the wire the whole batch is one XMODEM payload. The receiver decodes each entry and writes
 * the file. If the XMODEM transfer fails the entire batch is retried; individual files cannot be
 * resumed mid-batch.
 */
public class BatchTransferSession {

    /** Magic bytes identifying a batch payload: "BTH\0" */
    private static final byte[] MAGIC = new byte[] {0x42, 0x54, 0x48, 0x00};

    /** Version 2 adds the per-entry manifest MD5 and its verification (version 1 had neither). */
    private static final int VERSION = 2;

    private static final int MAX_ENTRIES_PER_BATCH = 256;

    /** Hard ceiling on total decoded batch size (defense in depth against OOM). */
    private static final int MAX_BATCH_TOTAL_BYTES = 64 * 1024 * 1024; // 64 MB

    /** Hard ceiling on a single entry's data payload. */
    private static final int MAX_ENTRY_DATA_BYTES = 64 * 1024 * 1024; // 64 MB

    /** Reasonable upper bound for a relative path within a batch entry. */
    private static final int MAX_PATH_LENGTH = 4096;

    private static final int MD5_BYTES = 16;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BatchTransferSession() {}

    /**
     * Build a binary batch from the given file list and encode it into a byte array.
     *
     * @param files list of entries; each entry is a Object[] { File file, String relativePath[,
     *     String manifestMd5] } — the optional third element is the sender's manifest md5 hex of
     *     the file's content, carried in the entry so the receiver can verify it after decoding
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

        if (files.size() > MAX_ENTRIES_PER_BATCH) {
            throw new IllegalArgumentException(
                    "Batch exceeds maximum entries: "
                            + files.size()
                            + " > "
                            + MAX_ENTRIES_PER_BATCH);
        }
        int count = files.size();
        byte[] countBytes = ByteBuffer.allocate(4).putInt(count).array();
        out.write(countBytes[0]);
        out.write(countBytes[1]);
        out.write(countBytes[2]);
        out.write(countBytes[3]);

        for (int i = 0; i < count; i++) {
            Object[] entry = files.get(i);
            java.io.File file = (java.io.File) entry[0];
            String relativePath = (String) entry[1];
            String manifestMd5 = entry.length > 2 ? (String) entry[2] : null;

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

            // FLAGS (1 byte): bit 0 = compressed, bit 1 = manifest md5 present
            byte flags = (byte) ((wasCompressed ? 1 : 0) | (hasMd5(manifestMd5) ? 2 : 0));
            out.write(flags);

            // MD5 (16 raw bytes, only when announced in FLAGS)
            if (hasMd5(manifestMd5)) {
                out.write(decodeMd5Hex(manifestMd5), 0, MD5_BYTES);
            }

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
     * directory. Any entry whose write fails aborts the whole batch (legacy behavior).
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
        return decodeAndWriteBatch(baseDir, batch, totalEntries, progressCallback, null);
    }

    /**
     * Decode a batch produced by {@link #buildBatch} and write each file into the given base
     * directory. When a {@code failureHandler} is provided, an entry whose write fails (e.g. the
     * target file is locked by another program) or whose content does not reproduce its announced
     * manifest md5 is reported through the handler and the remaining entries are still written
     * instead of aborting the whole batch.
     *
     * @param baseDir the directory to extract files under
     * @param batch encoded batch bytes
     * @param totalEntries the total number of files in the full sync operation (used in the
     *     callback to report correct overall progress)
     * @param progressCallback called with (entryIndex, totalEntries, relativePath) after each file
     *     is written; may be null
     * @param failureHandler called with (relativePath, data, lastModified, errorMessage, cause)
     *     when a single entry cannot be written or fails verification; when null the failure is
     *     rethrown
     * @return the number of files written
     */
    public static int decodeAndWriteBatch(
            java.io.File baseDir,
            byte[] batch,
            int totalEntries,
            BatchProgressCallback progressCallback,
            WriteFailureHandler failureHandler)
            throws IOException {
        return decodeAndWriteBatch(
                baseDir, batch, totalEntries, progressCallback, failureHandler, null);
    }

    /**
     * Full decode variant, additionally notifying {@code confirmationListener} after each entry
     * that was verified (when it announced an md5) and written. The listener receives the entry's
     * manifest md5 hex and decoded size, which is exactly the confirmed state a receiver records
     * for the path.
     *
     * @param confirmationListener called after each successfully written entry; may be null. For
     *     entries without a hash the md5 argument is null.
     */
    public static int decodeAndWriteBatch(
            java.io.File baseDir,
            byte[] batch,
            int totalEntries,
            BatchProgressCallback progressCallback,
            WriteFailureHandler failureHandler,
            EntryConfirmationListener confirmationListener)
            throws IOException {
        if (batch.length > MAX_BATCH_TOTAL_BYTES) {
            throw new IOException(
                    "Batch payload too large: "
                            + batch.length
                            + " bytes (max: "
                            + MAX_BATCH_TOTAL_BYTES
                            + " bytes)");
        }
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
        if (count < 0 || count > MAX_ENTRIES_PER_BATCH) {
            throw new IOException(
                    "Invalid batch entry count: "
                            + count
                            + " (max: "
                            + MAX_ENTRIES_PER_BATCH
                            + ")");
        }

        int written = 0;
        for (int i = 0; i < count; i++) {
            // PATH_LEN
            byte[] pathLenBuf = new byte[2];
            readFully(in, pathLenBuf);
            int pathLen = ByteBuffer.wrap(pathLenBuf).getShort() & 0xFFFF;
            if (pathLen < 0 || pathLen > MAX_PATH_LENGTH || pathLen > in.available()) {
                throw new IOException("Invalid path length in batch entry: " + pathLen);
            }

            // PATH
            byte[] pathBytes = new byte[pathLen];
            readFully(in, pathBytes);
            String relativePath = new String(pathBytes, StandardCharsets.UTF_8);
            // Containment is checked canonically, exactly as every other remote-supplied path in
            // the protocol. A substring test for ".." both rejects legitimate names such as
            // "notes..txt" (failing the whole batch) and misses forms the canonical check catches.
            java.io.File targetFile = SafePaths.resolveWithin(baseDir, relativePath);

            // LAST_MODIFIED
            byte[] lmBuf = new byte[8];
            readFully(in, lmBuf);
            long lastModified = ByteBuffer.wrap(lmBuf).getLong();

            // FLAGS
            int flags = in.read();
            boolean compressed = (flags & 1) != 0;
            boolean hasMd5 = (flags & 2) != 0;

            // MD5 (16 raw bytes, present iff announced)
            String manifestMd5 = null;
            if (hasMd5) {
                byte[] md5Buf = new byte[MD5_BYTES];
                readFully(in, md5Buf);
                manifestMd5 = encodeHex(md5Buf);
            }

            // LEN
            byte[] lenBuf = new byte[4];
            readFully(in, lenBuf);
            int dataLen = ByteBuffer.wrap(lenBuf).getInt();
            if (dataLen < 0 || dataLen > MAX_ENTRY_DATA_BYTES || dataLen > in.available()) {
                throw new IOException(
                        "Invalid data length in batch entry: "
                                + dataLen
                                + " (max: "
                                + MAX_ENTRY_DATA_BYTES
                                + ", remaining: "
                                + in.available()
                                + ")");
            }

            // DATA
            byte[] data = new byte[dataLen];
            readFully(in, data);

            // Decompress if needed
            if (compressed) {
                data = CompressionUtil.decompress(data);
            }

            // Verify the announced manifest md5 before anything touches the disk; an entry that
            // fails was corrupted in transit and must not be written or retried from these bytes.
            if (manifestMd5 != null && !manifestMd5.equals(FileChangeDetector.manifestMd5(data))) {
                if (failureHandler == null) {
                    throw new IOException(
                            "Batch entry md5 mismatch for '"
                                    + relativePath
                                    + "': announced "
                                    + manifestMd5
                                    + ", decoded content differs");
                }
                failureHandler.onWriteFailed(
                        relativePath,
                        data,
                        lastModified,
                        "manifest md5 mismatch (announced " + manifestMd5 + ")",
                        WriteFailureCause.HASH_MISMATCH);
                continue;
            }

            // Write file; a failure (e.g. target locked by another program) is reported through
            // the failure handler and the rest of the batch still proceeds.
            java.io.File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
                fos.write(data);
                if (lastModified > 0) {
                    targetFile.setLastModified(lastModified);
                }
                written++;
                if (confirmationListener != null) {
                    confirmationListener.onEntryConfirmed(relativePath, manifestMd5, data.length);
                }
                if (progressCallback != null) {
                    progressCallback.onEntryProcessed(i, totalEntries, relativePath);
                }
            } catch (IOException e) {
                if (failureHandler == null) {
                    throw e;
                }
                failureHandler.onWriteFailed(
                        relativePath,
                        data,
                        lastModified,
                        e.getMessage(),
                        WriteFailureCause.IO_ERROR);
            }
        }

        return written;
    }

    /** Callback for per-entry write failures during batch decode. */
    @FunctionalInterface
    public interface WriteFailureHandler {
        /**
         * Report one entry that could not be written or failed verification.
         *
         * @param cause {@link WriteFailureCause#IO_ERROR} entries may be retried later from {@code
         *     data}; {@link WriteFailureCause#HASH_MISMATCH} entries must not — their decoded bytes
         *     did not reproduce the announced md5.
         */
        void onWriteFailed(
                String relativePath,
                byte[] data,
                long lastModified,
                String errorMessage,
                WriteFailureCause cause);
    }

    /** Why a batch entry failed: its bytes are either retryable or provably corrupt. */
    public enum WriteFailureCause {
        /** The write itself failed (e.g. the target is locked); the decoded bytes are usable. */
        IO_ERROR,
        /** The decoded content does not reproduce the announced manifest md5; do not write it. */
        HASH_MISMATCH
    }

    /** Callback for entries that were verified and written during batch decode. */
    @FunctionalInterface
    public interface EntryConfirmationListener {
        /**
         * Called after an entry is written. {@code manifestMd5} is the announced md5 hex for a
         * hashed entry, or null when the entry carried no hash (fast mode).
         */
        void onEntryConfirmed(String relativePath, String manifestMd5, long size);
    }

    private static boolean hasMd5(String manifestMd5) {
        return manifestMd5 != null && !manifestMd5.isEmpty();
    }

    private static byte[] decodeMd5Hex(String hex) throws IOException {
        if (hex.length() != MD5_BYTES * 2) {
            throw new IOException("Invalid manifest md5 length for batch entry: " + hex);
        }
        byte[] out = new byte[MD5_BYTES];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IOException("Invalid manifest md5 hex for batch entry: " + hex);
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static String encodeHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
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
