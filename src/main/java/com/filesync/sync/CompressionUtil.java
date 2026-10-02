package com.filesync.sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Utility class for GZIP compression/decompression of files. Provides smart content-based detection
 * for high compression potential files.
 */
public class CompressionUtil {

    // Text file extensions - used as hints for compression
    private static final Set<String> TEXT_EXTENSIONS =
            new HashSet<>(
                    Arrays.asList(
                            "txt",
                            "java",
                            "xml",
                            "json",
                            "html",
                            "htm",
                            "css",
                            "js",
                            "ts",
                            "py",
                            "rb",
                            "php",
                            "c",
                            "cpp",
                            "h",
                            "hpp",
                            "cs",
                            "go",
                            "rs",
                            "md",
                            "yaml",
                            "yml",
                            "ini",
                            "cfg",
                            "conf",
                            "properties",
                            "sql",
                            "sh",
                            "bat",
                            "ps1",
                            "log",
                            "csv",
                            "tsv"));

    // Already compressed file extensions - skip compression
    private static final Set<String> COMPRESSED_EXTENSIONS =
            new HashSet<>(
                    Arrays.asList(
                            "zip", "gz", "bz2", "xz", "7z", "rar", "tar", "jpg", "jpeg", "png",
                            "gif", "webp", "avif", "mp3", "mp4", "avi", "mkv", "mov", "flv", "wmv",
                            "aac", "ogg", "flac", "wma", "pdf", "docx", "xlsx", "pptx"));

    // GZIP magic number header
    private static final byte[] GZIP_MAGIC = new byte[] {0x1f, (byte) 0x8b};

    // Compression analysis thresholds
    private static final double ENTROPY_THRESHOLD = 7.5; // Shannon entropy threshold (max is 8.0)
    private static final double MIN_COMPRESSION_RATIO =
            0.85; // Only compress if result is < 85% of original
    private static final int SAMPLE_SIZE = 4096; // Sample size for trial compression
    private static final double BINARY_THRESHOLD =
            0.10; // Max 10% non-text bytes allowed for text detection
    // Below this size (bytes), skip trial compression and rely on extension heuristics only.
    // Trial GZIP on tiny data adds measurable CPU overhead with negligible gain.
    private static final int TRIAL_COMPRESSION_SIZE_THRESHOLD = 256;

    /** Check if a file extension suggests text content (used as a hint) */
    public static boolean isTextExtension(String fileName) {
        String extension = getExtension(fileName);
        return extension != null && TEXT_EXTENSIONS.contains(extension);
    }

    /** Check if a file extension suggests already-compressed content */
    public static boolean isCompressedExtension(String fileName) {
        String extension = getExtension(fileName);
        return extension != null && COMPRESSED_EXTENSIONS.contains(extension);
    }

    /** Get file extension in lowercase, or null if none */
    private static String getExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }

    /** Calculate Shannon entropy of data (0-8 scale, higher = more random/compressed) */
    public static double calculateEntropy(byte[] data) {
        if (data == null || data.length == 0) {
            return 0.0;
        }

        // Count byte frequencies
        int[] frequency = new int[256];
        for (byte b : data) {
            frequency[b & 0xFF]++;
        }

        // Calculate entropy
        double entropy = 0.0;
        double length = data.length;
        for (int count : frequency) {
            if (count > 0) {
                double probability = count / length;
                entropy -= probability * (Math.log(probability) / Math.log(2));
            }
        }
        return entropy;
    }

    /** Check if content appears to be binary (non-text) data */
    public static boolean isLikelyBinaryContent(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }

        int sampleLength = Math.min(data.length, SAMPLE_SIZE);
        int nonTextCount = 0;

        for (int i = 0; i < sampleLength; i++) {
            int b = data[i] & 0xFF;
            // Non-text: null bytes, or control chars (except tab, newline, carriage return)
            if (b == 0 || (b < 32 && b != 9 && b != 10 && b != 13) || b == 127) {
                nonTextCount++;
            }
        }

        return (double) nonTextCount / sampleLength > BINARY_THRESHOLD;
    }

    /**
     * Estimate compression ratio using trial compression on a sample Returns the ratio (compressed
     * size / original size), lower is better
     */
    public static double estimateCompressionRatio(byte[] data) {
        if (data == null || data.length == 0) {
            return 1.0;
        }

        // Use sample for large files
        byte[] sample = data.length <= SAMPLE_SIZE ? data : Arrays.copyOf(data, SAMPLE_SIZE);

        try {
            byte[] compressed = compress(sample);
            return (double) compressed.length / sample.length;
        } catch (IOException e) {
            return 1.0; // Assume no benefit on error
        }
    }

    /**
     * Smart detection: determine if content has high compression potential Uses multiple
     * heuristics: extension hints, entropy analysis, binary detection, and trial compression
     */
    public static boolean hasHighCompressionPotential(String fileName, byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }

        // Quick reject: already compressed file extensions
        if (isCompressedExtension(fileName)) {
            return false;
        }

        // Quick accept: known text extensions with non-binary content
        if (isTextExtension(fileName) && !isLikelyBinaryContent(data)) {
            return true;
        }

        // Skip expensive trial compression for tiny payloads; the GZIP header alone
        // (minimum 10 bytes) would dominate such data and the CPU cost is not worth it.
        if (data.length < TRIAL_COMPRESSION_SIZE_THRESHOLD) {
            return false;
        }

        // For unknown extensions or no extension: analyze content

        // Check if content is binary-like
        if (isLikelyBinaryContent(data)) {
            // Binary content: check entropy (high entropy = already compressed/encrypted)
            double entropy =
                    calculateEntropy(
                            data.length <= SAMPLE_SIZE ? data : Arrays.copyOf(data, SAMPLE_SIZE));
            if (entropy > ENTROPY_THRESHOLD) {
                return false; // High entropy = likely already compressed
            }
            // Low entropy binary: might be compressible (e.g., BMP, uncompressed data)
        }

        // Final check: trial compression to estimate actual benefit
        double ratio = estimateCompressionRatio(data);
        return ratio < MIN_COMPRESSION_RATIO;
    }

    /** Compress data using GZIP at level 9 (best compression). */
    public static byte[] compress(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return data;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos =
                new GZIPOutputStream(baos) {
                    {
                        def.setLevel(Deflater.BEST_COMPRESSION);
                    }
                }) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * Streaming twin of {@link #compressIfBeneficial(String, byte[])} for files: returns the exact
     * wire size the content would occupy — the level-9 GZIP size when compression is beneficial
     * (matching {@link #compress(byte[])}), the raw length otherwise — without ever holding the
     * content or its compressed form in memory. The benefit decision samples the file's first
     * {@value #SAMPLE_SIZE} bytes, which is exactly what the byte-array path inspects, so both
     * paths decide identically for the same content.
     */
    public static long compressedSizeIfBeneficial(String fileName, File file) throws IOException {
        long rawLength = file.length();
        if (rawLength == 0) {
            return 0;
        }
        // The sample stands in for the full array inside the decision: for files up to
        // SAMPLE_SIZE it IS the full content, and above that every internal check only ever
        // inspects the first SAMPLE_SIZE bytes (including the small-payload threshold, since a
        // larger file passes it a fortiori).
        byte[] sample = readPrefix(file, (int) Math.min(SAMPLE_SIZE, rawLength));
        if (!hasHighCompressionPotential(fileName, sample)) {
            return rawLength;
        }
        CountingOutputStream counter = new CountingOutputStream();
        try (FileInputStream in = new FileInputStream(file);
                GZIPOutputStream gzos =
                        new GZIPOutputStream(counter) {
                            {
                                def.setLevel(Deflater.BEST_COMPRESSION);
                            }
                        }) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                gzos.write(buf, 0, read);
            }
        }
        // Same contract as compressIfBeneficial: only claim compression when it actually shrinks.
        return Math.min(counter.count(), rawLength);
    }

    /** Read exactly the first {@code len} bytes of {@code file}; fails if it ends early. */
    private static byte[] readPrefix(File file, int len) throws IOException {
        byte[] buf = new byte[len];
        try (FileInputStream in = new FileInputStream(file)) {
            int done = 0;
            while (done < len) {
                int read = in.read(buf, done, len - done);
                if (read < 0) {
                    throw new IOException("File shrank while sampling: " + file);
                }
                done += read;
            }
        }
        return buf;
    }

    /** Output stream that only counts the bytes written to it. */
    private static final class CountingOutputStream extends OutputStream {
        private long count;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }

        long count() {
            return count;
        }
    }

    /** Decompress GZIP data. Rejects output exceeding 100 MB to prevent OOM. */
    public static byte[] decompress(byte[] compressedData) throws IOException {
        if (compressedData == null || compressedData.length == 0) {
            return compressedData;
        }

        final long MAX_DECOMPRESSED_SIZE = 100L * 1024 * 1024;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressedData))) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalWritten = 0;
            while ((bytesRead = gzis.read(buffer)) != -1) {
                totalWritten += bytesRead;
                if (totalWritten > MAX_DECOMPRESSED_SIZE) {
                    throw new IOException(
                            "Decompressed data exceeds limit of "
                                    + MAX_DECOMPRESSED_SIZE
                                    + " bytes");
                }
                baos.write(buffer, 0, bytesRead);
            }
        }
        return baos.toByteArray();
    }

    /**
     * Decompress GZIP data, tolerating a stream truncated mid-transfer. Everything readable before
     * the truncation point is returned — an exact byte prefix of the original input, because
     * deflate decodes deterministically from the start. Used to salvage the partial content of an
     * interrupted compressed transfer; unlike {@link #decompress(byte[])} no output-size cap is
     * applied, since the caller is recovering data it already accepted from the wire.
     *
     * @return the decompressed prefix (possibly empty when nothing readable was received)
     */
    public static byte[] decompressTruncated(byte[] compressedData) throws IOException {
        if (compressedData == null || compressedData.length == 0) {
            return compressedData;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzis =
                new GZIPInputStream(new ByteArrayInputStream(compressedData), 8192)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = gzis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            // Truncation surfaces as EOF; a cut inside the deflate stream may instead surface as
            // a decode error. Keep whatever decoded cleanly, and only fail when nothing was
            // recovered and the failure is not a plain truncation (e.g. not GZIP data at all).
            if (baos.size() == 0 && !(e instanceof EOFException)) {
                throw e;
            }
        }
        return baos.toByteArray();
    }

    /** Check if data is GZIP compressed by looking at magic number */
    public static boolean isCompressed(byte[] data) {
        if (data == null || data.length < 2) {
            return false;
        }
        return data[0] == GZIP_MAGIC[0] && data[1] == GZIP_MAGIC[1];
    }

    /**
     * Smart compression: compress data if content analysis indicates high compression potential
     * Uses content-based detection instead of just file extensions
     */
    public static CompressedData compressIfBeneficial(String fileName, byte[] data)
            throws IOException {
        if (data == null || data.length == 0) {
            return new CompressedData(data, false);
        }

        // Use smart detection to determine if compression is beneficial
        if (hasHighCompressionPotential(fileName, data)) {
            byte[] compressed = compress(data);
            // Final verification: only use compression if it actually reduces size
            if (compressed.length < data.length) {
                return new CompressedData(compressed, true);
            }
        }
        return new CompressedData(data, false);
    }

    /** Decompress data if it was compressed */
    public static byte[] decompressIfNeeded(byte[] data, boolean wasCompressed) throws IOException {
        if (wasCompressed && isCompressed(data)) {
            return decompress(data);
        }
        return data;
    }

    /** Add a custom text file extension */
    public static void addTextExtension(String extension) {
        TEXT_EXTENSIONS.add(extension.toLowerCase());
    }

    /** Remove a text file extension */
    public static void removeTextExtension(String extension) {
        TEXT_EXTENSIONS.remove(extension.toLowerCase());
    }

    /** Get all registered text file extensions */
    public static Set<String> getTextExtensions() {
        return new HashSet<>(TEXT_EXTENSIONS);
    }

    /** Container class for compressed data with compression flag */
    public static class CompressedData {
        private final byte[] data;
        private final boolean compressed;

        public CompressedData(byte[] data, boolean compressed) {
            this.data = data;
            this.compressed = compressed;
        }

        public byte[] getData() {
            return data;
        }

        public boolean isCompressed() {
            return compressed;
        }
    }
}
