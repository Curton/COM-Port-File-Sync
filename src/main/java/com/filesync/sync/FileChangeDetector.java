package com.filesync.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Detects file changes by comparing file metadata including MD5 hashes.
 * Generates file manifests for synchronization comparison.
 */
public class FileChangeDetector {
    
    /**
     * Callback interface for manifest generation progress.
     * Default methods allow callers to override only the hooks they need.
     */
    public interface ManifestProgressCallback {
        default void onStart(int totalFiles) { }
        
        default void onProgress(int processedFiles, int totalFiles) { }
        
        default void onFileProcessed(String fileName) { }
        
        default void onFileProcessed(String fileName, int processedFiles, int totalFiles) {
            onFileProcessed(fileName);
        }
        
        default void onComplete(FileManifest manifest) { }
    }
    
    /**
     * Strategy for computing file hashes. Allows tests to inject counters or mocks.
     */
    public interface FileHasher {
        String hash(File file) throws IOException;
    }
    
    /**
     * Options controlling manifest generation behavior.
     */
    public static final class ManifestGenerationOptions {
        private final boolean respectGitignore;
        private final boolean useQuickHash;
        private final ManifestProgressCallback progressCallback;
        private final int hashThreadPoolSize;
        private final File persistedManifestFile;
        private final boolean persistResult;
        private final FileManifest previousManifest;
        private final FileHasher hasher;
        
        private ManifestGenerationOptions(Builder builder) {
            this.respectGitignore = builder.respectGitignore;
            this.useQuickHash = builder.useQuickHash;
            this.progressCallback = builder.progressCallback;
            this.hashThreadPoolSize = Math.max(1, builder.hashThreadPoolSize);
            this.persistedManifestFile = builder.persistedManifestFile;
            this.persistResult = builder.persistResult;
            this.previousManifest = builder.previousManifest;
            this.hasher = builder.hasher != null ? builder.hasher : FileChangeDetector::calculateMD5;
        }
        
        public boolean isRespectGitignore() {
            return respectGitignore;
        }
        
        public boolean isUseQuickHash() {
            return useQuickHash;
        }
        
        public ManifestProgressCallback getProgressCallback() {
            return progressCallback;
        }
        
        public int getHashThreadPoolSize() {
            return hashThreadPoolSize;
        }
        
        public File getPersistedManifestFile() {
            return persistedManifestFile;
        }
        
        public boolean isPersistResult() {
            return persistResult;
        }
        
        public FileManifest getPreviousManifest() {
            return previousManifest;
        }
        
        public FileHasher getHasher() {
            return hasher;
        }
        
        public static Builder builder() {
            return new Builder();
        }
        
        public static final class Builder {
            private boolean respectGitignore;
            private boolean useQuickHash;
            private ManifestProgressCallback progressCallback;
            private int hashThreadPoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
            private File persistedManifestFile;
            private boolean persistResult = true;
            private FileManifest previousManifest;
            private FileHasher hasher;
            
            public Builder withRespectGitignore(boolean respectGitignore) {
                this.respectGitignore = respectGitignore;
                return this;
            }
            
            public Builder withUseQuickHash(boolean useQuickHash) {
                this.useQuickHash = useQuickHash;
                return this;
            }
            
            public Builder withProgressCallback(ManifestProgressCallback progressCallback) {
                this.progressCallback = progressCallback;
                return this;
            }
            
            public Builder withHashThreadPoolSize(int hashThreadPoolSize) {
                this.hashThreadPoolSize = hashThreadPoolSize;
                return this;
            }
            
            public Builder withPersistedManifestFile(File persistedManifestFile) {
                this.persistedManifestFile = persistedManifestFile;
                return this;
            }
            
            public Builder withPersistResult(boolean persistResult) {
                this.persistResult = persistResult;
                return this;
            }
            
            public Builder withPreviousManifest(FileManifest previousManifest) {
                this.previousManifest = previousManifest;
                return this;
            }
            
            public Builder withHasher(FileHasher hasher) {
                this.hasher = hasher;
                return this;
            }
            
            public ManifestGenerationOptions build() {
                return new ManifestGenerationOptions(this);
            }
        }
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
        ManifestGenerationOptions options = ManifestGenerationOptions.builder()
                .withRespectGitignore(respectGitignore)
                .withUseQuickHash(useQuickHash)
                .withProgressCallback(progressCallback)
                .build();
        return generateManifest(directory, options);
    }
    
    /**
     * Generate a manifest of all files in a directory with extended options.
     */
    public static FileManifest generateManifest(File directory, ManifestGenerationOptions options) throws IOException {
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IOException("Invalid directory: " + directory.getAbsolutePath());
        }
        ManifestGenerationOptions resolvedOptions = options != null ? options : ManifestGenerationOptions.builder().build();
        
        // Initialize gitignore parser if needed
        GitignoreParser gitignoreParser = null;
        if (resolvedOptions.isRespectGitignore()) {
            gitignoreParser = new GitignoreParser(directory);
            gitignoreParser.loadGitignoreFiles();
        }
        final GitignoreParser parser = gitignoreParser;
        
        Map<String, FileInfo> files = new ConcurrentHashMap<>();
        Set<String> directories = ConcurrentHashMap.newKeySet();
        Set<String> emptyDirectories = ConcurrentHashMap.newKeySet();
        Map<String, Boolean> dirHasChildren = new ConcurrentHashMap<>();
        dirHasChildren.put("", false);
        Path basePath = directory.toPath();
        
        FileManifest cachedManifest = resolvedOptions.getPreviousManifest();
        if (cachedManifest == null) {
            cachedManifest = loadPersistedManifest(resolvedOptions.getPersistedManifestFile());
        }
        Map<String, FileInfo> cachedFiles = cachedManifest != null ? cachedManifest.getFiles() : java.util.Collections.emptyMap();
        
        // Count total files for progress reporting (only when callback provided)
        AtomicInteger totalFiles = new AtomicInteger(0);
        AtomicInteger processedFiles = new AtomicInteger(0);
        ManifestProgressCallback progressCallback = resolvedOptions.getProgressCallback();
        
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
            progressCallback.onStart(totalFiles.get());
        }
        
        ExecutorService hashExecutor = resolvedOptions.isUseQuickHash() ? null :
                Executors.newFixedThreadPool(resolvedOptions.getHashThreadPoolSize());
        List<Future<?>> hashTasks = new ArrayList<>();
        
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
                long size = fileObj.length();
                long lastModified = fileObj.lastModified();
                
                FileInfo cachedInfo = cachedFiles.get(relativePath);
                // Rsync-style: in quick mode we only rely on metadata and avoid per-file checksums
                if (resolvedOptions.isUseQuickHash() || canReuseHash(cachedInfo, size, lastModified)) {
                    String hash = resolvedOptions.isUseQuickHash() ? cachedInfo != null ? cachedInfo.getMd5() : null : cachedInfo.getMd5();
                    files.put(relativePath, new FileInfo(relativePath, size, lastModified, hash));
                    markParentHasChild(relativePath, dirHasChildren);
                    reportProgress(relativePath, processedFiles, totalFiles, progressCallback);
                } else if (hashExecutor != null) {
                    Future<?> future = hashExecutor.submit(() -> {
                        try {
                            String hash = computeHash(resolvedOptions.getHasher(), fileObj);
                            files.put(relativePath, new FileInfo(relativePath, size, lastModified, hash));
                            markParentHasChild(relativePath, dirHasChildren);
                            reportProgress(relativePath, processedFiles, totalFiles, progressCallback);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    hashTasks.add(future);
                } else {
                    String hash = computeHash(resolvedOptions.getHasher(), fileObj);
                    files.put(relativePath, new FileInfo(relativePath, size, lastModified, hash));
                    markParentHasChild(relativePath, dirHasChildren);
                    reportProgress(relativePath, processedFiles, totalFiles, progressCallback);
                }
                
                return FileVisitResult.CONTINUE;
            }
        });
        
        waitForHashes(hashTasks);
        if (hashExecutor != null) {
            hashExecutor.shutdown();
        }
        
        for (String dir : directories) {
            boolean hasChildren = dirHasChildren.getOrDefault(dir, false);
            if (!hasChildren) {
                emptyDirectories.add(dir);
            }
        }
        
        FileManifest manifest = new FileManifest(files, emptyDirectories);
        if (resolvedOptions.isPersistResult()) {
            persistManifest(resolvedOptions.getPersistedManifestFile(), manifest);
        }
        
        if (progressCallback != null) {
            progressCallback.onComplete(manifest);
        }
        
        return manifest;
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
    
    private static boolean canReuseHash(FileInfo cachedInfo, long size, long lastModified) {
        return cachedInfo != null &&
                cachedInfo.getMd5() != null &&
                cachedInfo.getSize() == size &&
                cachedInfo.getLastModified() == lastModified;
    }
    
    private static String computeHash(FileHasher hasher, File file) throws IOException {
        try {
            return hasher.hash(file);
        } catch (IOException e) {
            System.err.println("Failed to calculate hash for file: " + file.getAbsolutePath() + " - " + e.getMessage());
            throw e;
        }
    }
    
    private static void reportProgress(String relativePath,
                                       AtomicInteger processedFiles,
                                       AtomicInteger totalFiles,
                                       ManifestProgressCallback progressCallback) {
        if (progressCallback == null) {
            return;
        }
        int processed = processedFiles.incrementAndGet();
        progressCallback.onProgress(processed, totalFiles.get());
        progressCallback.onFileProcessed(relativePath, processed, totalFiles.get());
    }
    
    private static FileManifest loadPersistedManifest(File manifestFile) {
        if (manifestFile == null || !manifestFile.exists()) {
            return null;
        }
        try {
            String json = Files.readString(manifestFile.toPath());
            return manifestFromJson(json);
        } catch (IOException e) {
            System.err.println("Failed to load persisted manifest: " + manifestFile.getAbsolutePath() + " - " + e.getMessage());
            return null;
        }
    }
    
    private static void persistManifest(File manifestFile, FileManifest manifest) throws IOException {
        if (manifestFile == null) {
            return;
        }
        Path path = manifestFile.toPath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, manifestToJson(manifest), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    
    private static void waitForHashes(List<Future<?>> hashTasks) throws IOException {
        for (Future<?> future : hashTasks) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Hash computation interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Failed to compute file hash", cause);
            }
        }
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
