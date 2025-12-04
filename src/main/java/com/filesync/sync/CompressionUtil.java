package com.filesync.sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Utility class for GZIP compression/decompression of files.
 * Provides smart content-based detection for high compression potential files.
 */
public class CompressionUtil {

    // Text file extensions - used as hints for compression
    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "txt", "java", "xml", "json", "html", "htm", "css", "js", "ts",
            "py", "rb", "php", "c", "cpp", "h", "hpp", "cs", "go", "rs",
            "md", "yaml", "yml", "ini", "cfg", "conf", "properties",
            "sql", "sh", "bat", "ps1", "log", "csv", "tsv"
    ));

    // Already compressed file extensions - skip compression
    private static final Set<String> COMPRESSED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "zip", "gz", "bz2", "xz", "7z", "rar", "tar",
            "jpg", "jpeg", "png", "gif", "webp", "avif",
            "mp3", "mp4", "avi", "mkv", "mov", "flv", "wmv",
            "aac", "ogg", "flac", "wma",
            "pdf", "docx", "xlsx", "pptx"
    ));

    // GZIP magic number header
    private static final byte[] GZIP_MAGIC = new byte[]{0x1f, (byte) 0x8b};

    // Compression analysis thresholds
    private static final double ENTROPY_THRESHOLD = 7.5;  // Shannon entropy threshold (max is 8.0)
    private static final double MIN_COMPRESSION_RATIO = 0.85;  // Only compress if result is < 85% of original
    private static final int SAMPLE_SIZE = 4096;  // Sample size for trial compression
    private static final double BINARY_THRESHOLD = 0.10;  // Max 10% non-text bytes allowed for text detection

    /**
     * Check if a file extension suggests text content (used as a hint)
     */
    public static boolean isTextExtension(String fileName) {
        String extension = getExtension(fileName);
        return extension != null && TEXT_EXTENSIONS.contains(extension);
    }

    /**
     * Check if a file extension suggests already-compressed content
     */
    public static boolean isCompressedExtension(String fileName) {
        String extension = getExtension(fileName);
        return extension != null && COMPRESSED_EXTENSIONS.contains(extension);
    }

    /**
     * Get file extension in lowercase, or null if none
     */
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

    /**
     * Calculate Shannon entropy of data (0-8 scale, higher = more random/compressed)
     */
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

    /**
     * Check if content appears to be binary (non-text) data
     */
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
     * Estimate compression ratio using trial compression on a sample
     * Returns the ratio (compressed size / original size), lower is better
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
            return 1.0;  // Assume no benefit on error
        }
    }

    /**
     * Smart detection: determine if content has high compression potential
     * Uses multiple heuristics: extension hints, entropy analysis, binary detection, and trial compression
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

        // For unknown extensions or no extension: analyze content

        // Check if content is binary-like
        if (isLikelyBinaryContent(data)) {
            // Binary content: check entropy (high entropy = already compressed/encrypted)
            double entropy = calculateEntropy(data.length <= SAMPLE_SIZE ? data : Arrays.copyOf(data, SAMPLE_SIZE));
            if (entropy > ENTROPY_THRESHOLD) {
                return false;  // High entropy = likely already compressed
            }
            // Low entropy binary: might be compressible (e.g., BMP, uncompressed data)
        }

        // Final check: trial compression to estimate actual benefit
        double ratio = estimateCompressionRatio(data);
        return ratio < MIN_COMPRESSION_RATIO;
    }

    /**
     * @deprecated Use {@link #isTextExtension(String)} instead
     * Check if a file should be compressed based on its extension
     */
    @Deprecated
    public static boolean isTextFile(String fileName) {
        return isTextExtension(fileName);
    }

    /**
     * Compress data using GZIP
     */
    public static byte[] compress(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return data;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * Decompress GZIP data
     */
    public static byte[] decompress(byte[] compressedData) throws IOException {
        if (compressedData == null || compressedData.length == 0) {
            return compressedData;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressedData))) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = gzis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
        }
        return baos.toByteArray();
    }

    /**
     * Check if data is GZIP compressed by looking at magic number
     */
    public static boolean isCompressed(byte[] data) {
        if (data == null || data.length < 2) {
            return false;
        }
        return data[0] == GZIP_MAGIC[0] && data[1] == GZIP_MAGIC[1];
    }

    /**
     * @deprecated Use {@link #compressIfBeneficial(String, byte[])} instead
     * Compress data if the file is a text file (extension-based only)
     */
    @Deprecated
    public static CompressedData compressIfText(String fileName, byte[] data) throws IOException {
        return compressIfBeneficial(fileName, data);
    }

    /**
     * Smart compression: compress data if content analysis indicates high compression potential
     * Uses content-based detection instead of just file extensions
     */
    public static CompressedData compressIfBeneficial(String fileName, byte[] data) throws IOException {
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

    /**
     * Decompress data if it was compressed
     */
    public static byte[] decompressIfNeeded(byte[] data, boolean wasCompressed) throws IOException {
        if (wasCompressed && isCompressed(data)) {
            return decompress(data);
        }
        return data;
    }

    /**
     * Add a custom text file extension
     */
    public static void addTextExtension(String extension) {
        TEXT_EXTENSIONS.add(extension.toLowerCase());
    }

    /**
     * Remove a text file extension
     */
    public static void removeTextExtension(String extension) {
        TEXT_EXTENSIONS.remove(extension.toLowerCase());
    }

    /**
     * Get all registered text file extensions
     */
    public static Set<String> getTextExtensions() {
        return new HashSet<>(TEXT_EXTENSIONS);
    }

    /**
     * Container class for compressed data with compression flag
     */
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

