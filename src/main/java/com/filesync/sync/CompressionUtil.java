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
 * Provides methods to detect text files and compress them for transfer.
 */
public class CompressionUtil {

    // Text file extensions that should be compressed
    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "txt", "java", "xml", "json", "html", "htm", "css", "js", "ts",
            "py", "rb", "php", "c", "cpp", "h", "hpp", "cs", "go", "rs",
            "md", "yaml", "yml", "ini", "cfg", "conf", "properties",
            "sql", "sh", "bat", "ps1", "log", "csv", "tsv"
    ));

    // GZIP magic number header
    private static final byte[] GZIP_MAGIC = new byte[]{0x1f, (byte) 0x8b};

    /**
     * Check if a file should be compressed based on its extension
     */
    public static boolean isTextFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }

        String extension = fileName.substring(dotIndex + 1).toLowerCase();
        return TEXT_EXTENSIONS.contains(extension);
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
     * Compress data if the file is a text file
     */
    public static CompressedData compressIfText(String fileName, byte[] data) throws IOException {
        if (isTextFile(fileName)) {
            byte[] compressed = compress(data);
            // Only use compression if it actually reduces size
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

