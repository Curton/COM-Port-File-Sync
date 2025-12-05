package com.filesync.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Detects file changes by comparing file metadata including MD5 hashes.
 * Generates file manifests for synchronization comparison.
 */
public class FileChangeDetector {
    
    /**
     * Callback interface for manifest generation progress
     */
    public interface ManifestProgressCallback {
        void onProgress(int processedFiles, int totalFiles);
        
        void onFileProcessed(String fileName);
    }
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    /**
     * Generate a manifest of all files in a directory
     */
    public static FileManifest generateManifest(File directory) throws IOException {
        return generateManifest(directory, false, false);
    }
    
    /**
     * Generate a manifest of all files in a directory with optional .gitignore support
     *
     * @param directory        the directory to scan
     * @param respectGitignore if true, files matching .gitignore patterns will be excluded
     */
    public static FileManifest generateManifest(File directory, boolean respectGitignore) throws IOException {
        return generateManifest(directory, respectGitignore, false);
    }
    
    /**
     * Generate a manifest of all files in a directory with optional .gitignore support and fast mode
     *
     * @param directory        the directory to scan
     * @param respectGitignore if true, files matching .gitignore patterns will be excluded
     * @param useQuickHash     if true, use file size + lastModified as hash instead of full MD5 for faster generation
     */
    public static FileManifest generateManifest(File directory, boolean respectGitignore, boolean useQuickHash) throws IOException {
        return generateManifest(directory, respectGitignore, useQuickHash, null);
    }
    
    /**
     * Generate a manifest of all files in a directory with progress reporting
     *
     * @param directory        the directory to scan
     * @param respectGitignore if true, files matching .gitignore patterns will be excluded
     * @param useQuickHash     if true, use file size + lastModified as hash instead of full MD5 for faster generation
     * @param progressCallback callback for progress reporting, can be null
     */
    public static FileManifest generateManifest(File directory, boolean respectGitignore, boolean useQuickHash, ManifestProgressCallback progressCallback) throws IOException {
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IOException("Invalid directory: " + directory.getAbsolutePath());
        }
        
        // Initialize gitignore parser if needed
        GitignoreParser gitignoreParser = null;
        if (respectGitignore) {
            gitignoreParser = new GitignoreParser(directory);
            gitignoreParser.loadGitignoreFiles();
        }
        final GitignoreParser parser = gitignoreParser;
        
        Map<String, FileInfo> files = new HashMap<>();
        java.util.Set<String> directories = new java.util.HashSet<>();
        java.util.Set<String> emptyDirectories = new java.util.HashSet<>();
        Map<String, Boolean> dirHasChildren = new HashMap<>();
        dirHasChildren.put("", false);
        Path basePath = directory.toPath();
        
        // Count total files for progress reporting (only when callback provided)
        AtomicInteger totalFiles = new AtomicInteger(0);
        AtomicInteger processedFiles = new AtomicInteger(0);
        
        if (progressCallback != null) {
            try (Stream<Path> countPaths = Files.walk(basePath)) {
                countPaths.forEach(path -> {
                    try {
                        if (!Files.isRegularFile(path)) {
                            return;
                        }
                        
                        // Skip Windows hidden files when generating manifest
                        if (isWindowsHidden(path)) {
                            return;
                        }
                        
                        String relativePath = toRelativePath(basePath, path);
                        
                        // Skip .gitignore files themselves when respectGitignore is enabled
                        if (parser != null && relativePath.endsWith(".gitignore")) {
                            return;
                        }
                        
                        // Check if file should be ignored based on .gitignore
                        if (parser != null && parser.isIgnored(relativePath, false)) {
                            return;
                        }
                        
                        totalFiles.incrementAndGet();
                    } catch (Exception e) {
                        // Skip files that can't be accessed during counting
                    }
                });
            }
        }
        
        Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.equals(basePath)) {
                    return FileVisitResult.CONTINUE;
                }
                
                // Skip Windows hidden directories when generating manifest
                if (isWindowsHidden(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                
                String relativePath = toRelativePath(basePath, dir);
                
                // Skip .gitignore directories when respectGitignore is enabled
                if (parser != null && parser.isIgnored(relativePath, true)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                
                directories.add(relativePath);
                dirHasChildren.putIfAbsent(relativePath, false);
                markParentHasChild(relativePath, dirHasChildren);
                
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Skip Windows hidden files when generating manifest
                if (isWindowsHidden(file)) {
                    return FileVisitResult.CONTINUE;
                }
                
                String relativePath = toRelativePath(basePath, file);
                
                // Skip .gitignore files themselves when respectGitignore is enabled
                if (parser != null && relativePath.endsWith(".gitignore")) {
                    return FileVisitResult.CONTINUE;
                }
                
                // Check if file should be ignored based on .gitignore
                if (parser != null && parser.isIgnored(relativePath, false)) {
                    return FileVisitResult.CONTINUE;
                }
                
                File fileObj = file.toFile();
                // Rsync-style: in quick mode we only rely on metadata and avoid per-file checksums
                String hash = null;
                if (!useQuickHash) {
                    try {
                        hash = calculateMD5(fileObj);
                    } catch (IOException e) {
                        System.err.println("Failed to calculate MD5 for file: " + fileObj.getAbsolutePath() + " - " + e.getMessage());
                    }
                }
                
                FileInfo info = new FileInfo(
                        relativePath,
                        fileObj.length(),
                        fileObj.lastModified(),
                        hash
                );
                files.put(relativePath, info);
                
                markParentHasChild(relativePath, dirHasChildren);
                
                // Report progress
                if (progressCallback != null) {
                    int processed = processedFiles.incrementAndGet();
                    progressCallback.onProgress(processed, totalFiles.get());
                    progressCallback.onFileProcessed(relativePath);
                }
                
                return FileVisitResult.CONTINUE;
            }
        });
        
        for (String dir : directories) {
            boolean hasChildren = dirHasChildren.getOrDefault(dir, false);
            if (!hasChildren) {
                emptyDirectories.add(dir);
            }
        }
        
        return new FileManifest(files, emptyDirectories);
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
            } else {
                boolean sameByChecksum = false;
                // If both sides have checksums, compare them
                if (sourceInfo.getMd5() != null && targetInfo.getMd5() != null) {
                    sameByChecksum = sourceInfo.getMd5().equals(targetInfo.getMd5());
                }
                
                // Rsync-style quick check based on metadata only
                boolean sameByMetadata =
                        sourceInfo.getSize() == targetInfo.getSize() &&
                                sourceInfo.getLastModified() == targetInfo.getLastModified();
                
                if (!(sameByChecksum || sameByMetadata)) {
                    // File exists but appears different
                    changedFiles.add(sourceInfo);
                }
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
     * Compare two manifests and return empty directories that need to be created
     * Returns directories that exist in source but not in target
     */
    public static List<String> getEmptyDirectoriesToCreate(FileManifest source, FileManifest target) {
        List<String> dirsToCreate = new ArrayList<>();
        
        for (String dir : source.getEmptyDirectories()) {
            if (!target.getEmptyDirectories().contains(dir)) {
                dirsToCreate.add(dir);
            }
        }
        
        return dirsToCreate;
    }
    
    /**
     * Compare two manifests and return empty directories that need to be deleted
     * Returns directories that exist in target but not in source (for strict sync mode)
     */
    public static List<String> getEmptyDirectoriesToDelete(FileManifest source, FileManifest target) {
        List<String> dirsToDelete = new ArrayList<>();
        
        for (String dir : target.getEmptyDirectories()) {
            if (!source.getEmptyDirectories().contains(dir)) {
                // Directory exists in target but not in source - should be deleted
                dirsToDelete.add(dir);
            }
        }
        
        // Sort in reverse order so deeper directories are deleted first
        dirsToDelete.sort((a, b) -> b.length() - a.length());
        
        return dirsToDelete;
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
    
    private static String toRelativePath(Path basePath, Path path) {
        return basePath.relativize(path).toString().replace('\\', '/');
    }
    
    /**
     * Determine whether a path is hidden using Windows DOS attributes.
     * This ignores Unix-style hidden semantics (names starting with '.')
     * and only treats entries with the DOS hidden attribute as hidden.
     */
    private static boolean isWindowsHidden(Path path) {
        try {
            DosFileAttributes attrs = Files.readAttributes(path, DosFileAttributes.class);
            return attrs.isHidden();
        } catch (UnsupportedOperationException | IOException e) {
            // DOS attributes not supported (e.g. non-Windows filesystem) - do not treat as hidden
            // If attributes cannot be read, fail open and do not treat as hidden
            return false;
        }
    }
    
    private static void markParentHasChild(String relativePath, Map<String, Boolean> dirHasChildren) {
        int lastSeparator = relativePath.lastIndexOf('/');
        String parent = lastSeparator == -1 ? "" : relativePath.substring(0, lastSeparator);
        dirHasChildren.put(parent, true);
    }
    
    /**
     * File manifest containing all file information for a directory
     */
    public static class FileManifest {
        private final Map<String, FileInfo> files;
        private final java.util.Set<String> emptyDirectories;
        
        public FileManifest() {
            this.files = new HashMap<>();
            this.emptyDirectories = new java.util.HashSet<>();
        }
        
        public FileManifest(Map<String, FileInfo> files) {
            this.files = files;
            this.emptyDirectories = new java.util.HashSet<>();
        }
        
        public FileManifest(Map<String, FileInfo> files, java.util.Set<String> emptyDirectories) {
            this.files = files;
            this.emptyDirectories = emptyDirectories != null ? emptyDirectories : new java.util.HashSet<>();
        }
        
        public Map<String, FileInfo> getFiles() {
            return files;
        }
        
        public java.util.Set<String> getEmptyDirectories() {
            return emptyDirectories;
        }
        
        public int getFileCount() {
            return files.size();
        }
        
        public int getEmptyDirectoryCount() {
            return emptyDirectories.size();
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
