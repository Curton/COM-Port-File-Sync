package com.filesync.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Detects file changes by comparing file metadata including MD5 hashes.
 * Generates file manifests for synchronization comparison.
 */
public class FileChangeDetector {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Generate a manifest of all files in a directory
     */
    public static FileManifest generateManifest(File directory) throws IOException {
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IOException("Invalid directory: " + directory.getAbsolutePath());
        }

        Map<String, FileInfo> files = new HashMap<>();
        Path basePath = directory.toPath();

        try (Stream<Path> paths = Files.walk(basePath)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            String relativePath = basePath.relativize(path).toString().replace('\\', '/');
                            File file = path.toFile();
                            String md5 = calculateMD5(file);
                            FileInfo info = new FileInfo(
                                    relativePath,
                                    file.length(),
                                    file.lastModified(),
                                    md5
                            );
                            files.put(relativePath, info);
                        } catch (IOException e) {
                            // Log and skip problematic files
                            System.err.println("Error processing file: " + path + " - " + e.getMessage());
                        }
                    });
        }

        return new FileManifest(files);
    }

    /**
     * Compare two manifests and return files that need to be synced
     * Returns files that exist in source but are different or missing in target
     */
    public static List<FileInfo> getChangedFiles(FileManifest source, FileManifest target) {
        List<FileInfo> changedFiles = new ArrayList<>();

        for (Map.Entry<String, FileInfo> entry : source.getFiles().entrySet()) {
            String path = entry.getKey();
            FileInfo sourceInfo = entry.getValue();
            FileInfo targetInfo = target.getFiles().get(path);

            if (targetInfo == null) {
                // File doesn't exist in target
                changedFiles.add(sourceInfo);
            } else if (!sourceInfo.getMd5().equals(targetInfo.getMd5())) {
                // File exists but is different
                changedFiles.add(sourceInfo);
            }
        }

        return changedFiles;
    }

    /**
     * Compare two manifests and return files that need to be deleted from target
     * Returns files that exist in target but not in source (for strict sync mode)
     */
    public static List<String> getFilesToDelete(FileManifest source, FileManifest target) {
        List<String> filesToDelete = new ArrayList<>();

        for (String path : target.getFiles().keySet()) {
            if (!source.getFiles().containsKey(path)) {
                // File exists in target but not in source - should be deleted
                filesToDelete.add(path);
            }
        }

        return filesToDelete;
    }

    /**
     * Calculate MD5 hash of a file
     */
    public static String calculateMD5(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }
    }

    /**
     * Serialize manifest to JSON
     */
    public static String manifestToJson(FileManifest manifest) {
        return GSON.toJson(manifest);
    }

    /**
     * Deserialize manifest from JSON
     */
    public static FileManifest manifestFromJson(String json) {
        return GSON.fromJson(json, FileManifest.class);
    }

    /**
     * File manifest containing all file information for a directory
     */
    public static class FileManifest {
        private final Map<String, FileInfo> files;

        public FileManifest() {
            this.files = new HashMap<>();
        }

        public FileManifest(Map<String, FileInfo> files) {
            this.files = files;
        }

        public Map<String, FileInfo> getFiles() {
            return files;
        }

        public int getFileCount() {
            return files.size();
        }
    }

    /**
     * Information about a single file
     */
    public static class FileInfo {
        private final String path;
        private final long size;
        private final long lastModified;
        private final String md5;

        public FileInfo(String path, long size, long lastModified, String md5) {
            this.path = path;
            this.size = size;
            this.lastModified = lastModified;
            this.md5 = md5;
        }

        public String getPath() {
            return path;
        }

        public long getSize() {
            return size;
        }

        public long getLastModified() {
            return lastModified;
        }

        public String getMd5() {
            return md5;
        }

        @Override
        public String toString() {
            return "FileInfo{" +
                    "path='" + path + '\'' +
                    ", size=" + size +
                    ", md5='" + md5 + '\'' +
                    '}';
        }
    }
}

