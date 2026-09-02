package com.filesync.sync;

import com.filesync.delta.HashUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Arrays;
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
 * Detects file changes by comparing file metadata including MD5 hashes. Generates file manifests
 * for synchronization comparison.
 */
public class FileChangeDetector {

    // Rsync-style timestamp tolerance (in milliseconds) to handle filesystem precision differences.
    // NTFS has 100ns precision, FAT32 has 2-second precision, and setLastModified() may not
    // preserve exact values across different filesystems. 3 seconds covers most cases.
    static final long MODIFY_WINDOW_MS = 3000;

    // Persisted-manifest cache location, mirroring SignatureCache: one JSON file per folder under
    // the shared cache directory (outside the sync folder so the manifest scan never sees it).
    private static final String MANIFEST_CACHE_PREFIX = "manifest-cache-";
    private static final String MANIFEST_CACHE_SUFFIX = ".json";

    /**
     * Callback interface for manifest generation progress. Default methods allow callers to
     * override only the hooks they need.
     */
    public interface ManifestProgressCallback {
        default void onStart(int totalFiles) {}

        default void onProgress(int processedFiles, int totalFiles) {}

        default void onFileProcessed(String fileName) {}

        default void onFileProcessed(String fileName, int processedFiles, int totalFiles) {
            onFileProcessed(fileName);
        }

        default void onComplete(FileManifest manifest) {}
    }

    /** Strategy for computing file hashes. Allows tests to inject counters or mocks. */
    public interface FileHasher {
        String hash(File file) throws IOException;
    }

    /** Options controlling manifest generation behavior. */
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
            this.hasher =
                    builder.hasher != null ? builder.hasher : FileChangeDetector::calculateMD5;
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
            private int hashThreadPoolSize =
                    Math.max(2, Runtime.getRuntime().availableProcessors());
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
     * Compact JSON for the persisted cache file: a large folder's manifest stays much smaller than
     * the pretty wire format, and {@link #manifestFromJson} parses it back either way.
     */
    private static final Gson PERSIST_GSON = new GsonBuilder().create();

    /** Generate a manifest of all files in a directory */
    public static FileManifest generateManifest(File directory) throws IOException {
        return generateManifest(directory, false, false);
    }

    /**
     * Generate a manifest of all files in a directory with optional .gitignore support
     *
     * @param directory the directory to scan
     * @param respectGitignore if true, files matching .gitignore patterns will be excluded
     */
    public static FileManifest generateManifest(File directory, boolean respectGitignore)
            throws IOException {
        return generateManifest(directory, respectGitignore, false);
    }

    /**
     * Generate a manifest of all files in a directory with optional .gitignore support and fast
     * mode
     *
     * @param directory the directory to scan
     * @param respectGitignore if true, files matching .gitignore patterns will be excluded
     * @param useQuickHash if true, use file size + lastModified as hash instead of full MD5 for
     *     faster generation
     */
    public static FileManifest generateManifest(
            File directory, boolean respectGitignore, boolean useQuickHash) throws IOException {
        return generateManifest(directory, respectGitignore, useQuickHash, null);
    }

    /**
     * Generate a manifest of all files in a directory with progress reporting
     *
     * @param directory the directory to scan
     * @param respectGitignore if true, files matching .gitignore patterns will be excluded
     * @param useQuickHash if true, use file size + lastModified as hash instead of full MD5 for
     *     faster generation
     * @param progressCallback callback for progress reporting, can be null
     */
    public static FileManifest generateManifest(
            File directory,
            boolean respectGitignore,
            boolean useQuickHash,
            ManifestProgressCallback progressCallback)
            throws IOException {
        ManifestGenerationOptions options =
                ManifestGenerationOptions.builder()
                        .withRespectGitignore(respectGitignore)
                        .withUseQuickHash(useQuickHash)
                        .withProgressCallback(progressCallback)
                        .build();
        return generateManifest(directory, options);
    }

    /** Generate a manifest of all files in a directory with extended options. */
    public static FileManifest generateManifest(File directory, ManifestGenerationOptions options)
            throws IOException {
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IOException("Invalid directory: " + directory.getAbsolutePath());
        }
        ManifestGenerationOptions resolvedOptions =
                options != null ? options : ManifestGenerationOptions.builder().build();

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
        Map<String, FileInfo> cachedFiles =
                cachedManifest != null
                        ? cachedManifest.getFiles()
                        : java.util.Collections.emptyMap();

        // Count total files for progress reporting (only when callback provided)
        AtomicInteger totalFiles = new AtomicInteger(0);
        AtomicInteger processedFiles = new AtomicInteger(0);
        ManifestProgressCallback progressCallback = resolvedOptions.getProgressCallback();

        if (progressCallback != null) {
            // Files.find hands the walk's already-read attributes to the predicate, so counting
            // does not re-stat every path.
            try (Stream<Path> countPaths =
                    Files.find(
                            basePath,
                            Integer.MAX_VALUE,
                            (path, attrs) -> {
                                try {
                                    if (!attrs.isRegularFile() || isHidden(attrs)) {
                                        return false;
                                    }

                                    String relativePath = toRelativePath(basePath, path);

                                    // Skip .gitignore files themselves when respectGitignore is
                                    // enabled
                                    if (parser != null && relativePath.endsWith(".gitignore")) {
                                        return false;
                                    }

                                    // Check if file should be ignored based on .gitignore
                                    return parser == null || !parser.isIgnored(relativePath, false);
                                } catch (Exception e) {
                                    // Skip files that can't be accessed during counting
                                    return false;
                                }
                            })) {
                totalFiles.set((int) countPaths.count());
            }
            progressCallback.onStart(totalFiles.get());
        }

        // Always create a hash pool: even in quick mode, text files are hashed (with line-ending
        // normalization) so CRLF/LF differences are ignored; binary/unknown files skip hashing.
        ExecutorService hashExecutor =
                Executors.newFixedThreadPool(resolvedOptions.getHashThreadPoolSize());
        List<Future<?>> hashTasks = new ArrayList<>();

        Files.walkFileTree(
                basePath,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        if (dir.equals(basePath)) {
                            return FileVisitResult.CONTINUE;
                        }

                        // Skip Windows hidden directories when generating manifest
                        if (isHidden(attrs)) {
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
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        // Regular files read their metadata from the attributes walkFileTree
                        // already fetched; other entries (symlinks) keep the link-following File
                        // APIs so their manifest metadata keeps describing the target.
                        boolean regularFile = attrs.isRegularFile();
                        boolean hidden = regularFile ? isHidden(attrs) : isWindowsHidden(file);

                        // Skip Windows hidden files when generating manifest
                        if (hidden) {
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
                        long size = regularFile ? attrs.size() : fileObj.length();
                        long lastModified =
                                regularFile
                                        ? attrs.lastModifiedTime().toMillis()
                                        : fileObj.lastModified();

                        FileInfo cachedInfo = cachedFiles.get(relativePath);
                        boolean quickMode = resolvedOptions.isUseQuickHash();
                        // In quick mode only text-extension files are hashed (with line-ending
                        // normalization) so CRLF/LF differences are ignored; binary and unknown
                        // files rely on size + lastModified alone and are not read. In full mode
                        // every file is hashed.
                        boolean needsHash =
                                !quickMode || CompressionUtil.isTextExtension(relativePath);

                        if (needsHash && canReuseHash(cachedInfo, size, lastModified)) {
                            // Metadata unchanged and a checksum is cached -> reuse without reading.
                            files.put(
                                    relativePath,
                                    new FileInfo(
                                            relativePath, size, lastModified, cachedInfo.getMd5()));
                            markParentHasChild(relativePath, dirHasChildren);
                            reportProgress(
                                    relativePath, processedFiles, totalFiles, progressCallback);
                        } else if (needsHash) {
                            Future<?> future =
                                    hashExecutor.submit(
                                            () -> {
                                                try {
                                                    String hash =
                                                            computeHash(
                                                                    resolvedOptions.getHasher(),
                                                                    fileObj);
                                                    files.put(
                                                            relativePath,
                                                            new FileInfo(
                                                                    relativePath,
                                                                    size,
                                                                    lastModified,
                                                                    hash));
                                                    markParentHasChild(
                                                            relativePath, dirHasChildren);
                                                    reportProgress(
                                                            relativePath,
                                                            processedFiles,
                                                            totalFiles,
                                                            progressCallback);
                                                } catch (IOException e) {
                                                    throw new RuntimeException(e);
                                                }
                                            });
                            hashTasks.add(future);
                        } else {
                            // Quick mode, non-text file: rely on size + lastModified only. Preserve
                            // any previously cached checksum so a later full-mode pass can reuse
                            // it.
                            String hash = cachedInfo != null ? cachedInfo.getMd5() : null;
                            files.put(
                                    relativePath,
                                    new FileInfo(relativePath, size, lastModified, hash));
                            markParentHasChild(relativePath, dirHasChildren);
                            reportProgress(
                                    relativePath, processedFiles, totalFiles, progressCallback);
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
     * Generate a manifest for a sync flow, reusing checksums cached from the previous generation of
     * the same folder.
     *
     * <p>Enables the per-folder persisted manifest (see {@link #persistedManifestFileFor(File)}),
     * so a file whose size and lastModified are unchanged since the last generation reuses its
     * cached MD5 without reading the file. The first generation of a folder is a full hash;
     * subsequent ones only re-read files that visibly changed, which is what makes repeated sync
     * previews of large folders cheap. Correctness is unaffected: the persisted manifest is
     * discarded on any schema mismatch, and entries without a checksum (quick-mode binary files)
     * always fall back to hashing.
     */
    public static FileManifest generateManifestWithCache(
            File directory, boolean respectGitignore, boolean useQuickHash) throws IOException {
        return generateManifestWithCache(
                directory, respectGitignore, useQuickHash, persistedManifestFileFor(directory));
    }

    /**
     * Variant of {@link #generateManifestWithCache(File, boolean, boolean)} backed by the given
     * cache file instead of the default location.
     */
    static FileManifest generateManifestWithCache(
            File directory, boolean respectGitignore, boolean useQuickHash, File cacheFile)
            throws IOException {
        return generateManifest(
                directory,
                ManifestGenerationOptions.builder()
                        .withRespectGitignore(respectGitignore)
                        .withUseQuickHash(useQuickHash)
                        .withPersistedManifestFile(cacheFile)
                        .build());
    }

    /**
     * Persisted-manifest cache file for a sync folder: {@code
     * manifest-cache-<md5-of-absolute-path>.json} under {@link CacheLocations#cacheDir()}, one file
     * per folder.
     */
    public static File persistedManifestFileFor(File directory) {
        String folderKey =
                HashUtil.md5Hex(directory.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        return new File(
                CacheLocations.cacheDir(),
                MANIFEST_CACHE_PREFIX + folderKey + MANIFEST_CACHE_SUFFIX);
    }

    /**
     * Compare two manifests and return files that need to be synced Returns files that exist in
     * source but are different or missing in target
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

                // Rsync-style quick check based on metadata with timestamp tolerance.
                // Uses MODIFY_WINDOW_MS to handle filesystem timestamp precision differences
                // (similar to rsync's --modify-window option).
                boolean sameByMetadata =
                        sourceInfo.getSize() == targetInfo.getSize()
                                && Math.abs(
                                                sourceInfo.getLastModified()
                                                        - targetInfo.getLastModified())
                                        <= MODIFY_WINDOW_MS;

                if (!(sameByChecksum || sameByMetadata)) {
                    // File exists but appears different
                    changedFiles.add(sourceInfo);
                }
            }
        }

        return changedFiles;
    }

    /**
     * Compare two manifests and return files that need to be deleted from target Returns files that
     * exist in target but not in source (for strict sync mode)
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
     * Compare two manifests and return empty directories that need to be created Returns
     * directories that exist in source but not in target
     */
    public static List<String> getEmptyDirectoriesToCreate(
            FileManifest source, FileManifest target) {
        List<String> dirsToCreate = new ArrayList<>();

        for (String dir : source.getEmptyDirectories()) {
            if (!target.getEmptyDirectories().contains(dir)) {
                dirsToCreate.add(dir);
            }
        }

        return dirsToCreate;
    }

    /**
     * Compare two manifests and return empty directories that need to be deleted Returns
     * directories that exist in target but not in source (for strict sync mode)
     */
    public static List<String> getEmptyDirectoriesToDelete(
            FileManifest source, FileManifest target) {
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

    /** Sample size (bytes) inspected to decide text vs. binary before hashing. */
    private static final int BINARY_SAMPLE_SIZE = 4096;

    /** Read buffer size for streaming file hashing. */
    private static final int HASH_BUFFER_SIZE = 8192;

    /**
     * Calculate MD5 hash of a file.
     *
     * <p>For text files, line endings are normalized before hashing so that CRLF (Windows) and LF
     * (Unix) variants of the same content produce identical hashes. Binary files are hashed using
     * their raw bytes. The first {@value #BINARY_SAMPLE_SIZE} bytes are inspected to decide text
     * vs. binary via {@link CompressionUtil#isLikelyBinaryContent(byte[])}.
     */
    public static String calculateMD5(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(file)) {
                // Read a sample for binary detection.
                byte[] sample = new byte[BINARY_SAMPLE_SIZE];
                int sampleRead = readFully(fis, sample, 0, BINARY_SAMPLE_SIZE);

                boolean binary = false;
                if (sampleRead > 0) {
                    // isLikelyBinaryContent inspects up to its own sample length, so pass an array
                    // sized exactly to the bytes read (trailing zeros would skew the ratio).
                    byte[] sampleForDetection =
                            sampleRead == BINARY_SAMPLE_SIZE
                                    ? sample
                                    : Arrays.copyOf(sample, sampleRead);
                    binary = CompressionUtil.isLikelyBinaryContent(sampleForDetection);
                }

                if (binary) {
                    // Hash raw bytes: feed the sample we already consumed, then the remainder.
                    if (sampleRead > 0) {
                        md.update(sample, 0, sampleRead);
                    }
                    byte[] buffer = new byte[HASH_BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        md.update(buffer, 0, bytesRead);
                    }
                } else {
                    // Hash with line-ending normalization (CRLF / lone CR / LF all collapse to LF).
                    byte[] outBuffer = new byte[HASH_BUFFER_SIZE];
                    boolean pendingCR = false;
                    if (sampleRead > 0) {
                        pendingCR =
                                updateNormalized(md, sample, 0, sampleRead, outBuffer, pendingCR);
                    }
                    byte[] buffer = new byte[HASH_BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        pendingCR =
                                updateNormalized(md, buffer, 0, bytesRead, outBuffer, pendingCR);
                    }
                    // Flush a trailing lone CR as LF.
                    if (pendingCR) {
                        md.update((byte) '\n');
                    }
                }
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }
    }

    /**
     * Calculate the manifest-equivalent MD5 of the first {@code length} bytes of {@code data}.
     *
     * <p>Applies the same classification and line-ending normalization as {@link
     * #calculateMD5(File)}: text content is hashed with CRLF/CR/LF collapsed to LF, binary content
     * is hashed raw. This lets a sender verify that a prefix of its file matches the receiver's
     * file as described by the receiver's manifest, without a round trip.
     *
     * @param data the full content bytes
     * @param length the prefix length to hash; clamped to {@code data.length}
     */
    public static String calculateMD5OfPrefix(byte[] data, int length) throws IOException {
        int len = Math.min(length, data.length);
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            if (len <= 0) {
                return toHex(md.digest());
            }
            int sampleLen = Math.min(len, BINARY_SAMPLE_SIZE);
            byte[] sample = Arrays.copyOf(data, sampleLen);
            boolean binary = CompressionUtil.isLikelyBinaryContent(sample);
            if (binary) {
                md.update(data, 0, len);
            } else {
                byte[] outBuffer = new byte[HASH_BUFFER_SIZE];
                boolean pendingCR = false;
                // Feed in HASH_BUFFER_SIZE chunks so the normalized output always fits outBuffer.
                for (int off = 0; off < len; off += HASH_BUFFER_SIZE) {
                    int chunkLen = Math.min(HASH_BUFFER_SIZE, len - off);
                    pendingCR = updateNormalized(md, data, off, chunkLen, outBuffer, pendingCR);
                }
                // Flush a trailing lone CR as LF, mirroring calculateMD5.
                if (pendingCR) {
                    md.update((byte) '\n');
                }
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }
    }

    /**
     * Hash the first {@code prefixLength} bytes of {@code file} straight off disk, without holding
     * the file in memory. Text prefixes are hashed with CRLF/CR/LF collapsed to LF, binary prefixes
     * raw — classified from the first {@value #BINARY_SAMPLE_SIZE} bytes exactly like {@link
     * #calculateMD5(File)} — so the returned manifest digest equals {@link
     * #calculateMD5OfPrefix(byte[], int)} over the same bytes.
     *
     * <p>The companion raw digest has consumed exactly the prefix bytes and can be continued over
     * the rest of the file (see {@link PrefixHash#rawMd5With(byte[])}), letting a caller compute
     * both the manifest gate hash and the raw full-file hash in a single streaming pass.
     *
     * @param file the file to hash
     * @param prefixLength number of bytes to hash; must not be negative
     * @throws java.io.EOFException if the file holds fewer than {@code prefixLength} bytes
     */
    public static PrefixHash hashFilePrefix(File file, long prefixLength) throws IOException {
        if (prefixLength < 0) {
            throw new IllegalArgumentException(
                    "prefixLength must not be negative: " + prefixLength);
        }
        try {
            MessageDigest manifestMd = MessageDigest.getInstance("MD5");
            MessageDigest rawMd = MessageDigest.getInstance("MD5");
            long remaining = prefixLength;
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] sample = new byte[BINARY_SAMPLE_SIZE];
                int sampleRead =
                        readFully(fis, sample, 0, (int) Math.min(BINARY_SAMPLE_SIZE, remaining));
                remaining -= sampleRead;

                boolean binary = false;
                if (sampleRead > 0) {
                    // isLikelyBinaryContent inspects up to its own sample length, so pass an
                    // array sized exactly to the bytes read (trailing zeros would skew the ratio).
                    byte[] sampleForDetection =
                            sampleRead == BINARY_SAMPLE_SIZE
                                    ? sample
                                    : Arrays.copyOf(sample, sampleRead);
                    binary = CompressionUtil.isLikelyBinaryContent(sampleForDetection);
                }

                if (binary) {
                    if (sampleRead > 0) {
                        manifestMd.update(sample, 0, sampleRead);
                        rawMd.update(sample, 0, sampleRead);
                    }
                    byte[] buffer = new byte[HASH_BUFFER_SIZE];
                    while (remaining > 0) {
                        int read = readBounded(fis, buffer, remaining);
                        manifestMd.update(buffer, 0, read);
                        rawMd.update(buffer, 0, read);
                        remaining -= read;
                    }
                } else {
                    byte[] outBuffer = new byte[HASH_BUFFER_SIZE];
                    boolean pendingCR = false;
                    if (sampleRead > 0) {
                        pendingCR =
                                updateNormalized(
                                        manifestMd, sample, 0, sampleRead, outBuffer, pendingCR);
                        rawMd.update(sample, 0, sampleRead);
                    }
                    byte[] buffer = new byte[HASH_BUFFER_SIZE];
                    while (remaining > 0) {
                        int read = readBounded(fis, buffer, remaining);
                        pendingCR =
                                updateNormalized(manifestMd, buffer, 0, read, outBuffer, pendingCR);
                        rawMd.update(buffer, 0, read);
                        remaining -= read;
                    }
                    // Flush a trailing lone CR as LF, mirroring calculateMD5.
                    if (pendingCR) {
                        manifestMd.update((byte) '\n');
                    }
                }
            }
            return new PrefixHash(toHex(manifestMd.digest()), rawMd);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }
    }

    /**
     * Read up to {@code Math.min(HASH_BUFFER_SIZE, remaining)} bytes fully, throwing {@link
     * java.io.EOFException} when the stream ends early.
     */
    private static int readBounded(InputStream in, byte[] buffer, long remaining)
            throws IOException {
        int want = (int) Math.min(HASH_BUFFER_SIZE, remaining);
        int total = readFully(in, buffer, 0, want);
        if (total != want) {
            throw new java.io.EOFException("file ended inside the expected prefix");
        }
        return total;
    }

    /** Hashes of a file prefix produced by {@link #hashFilePrefix}. */
    public static final class PrefixHash {

        private final String manifestMd5;
        private final MessageDigest rawDigest;

        private PrefixHash(String manifestMd5, MessageDigest rawDigest) {
            this.manifestMd5 = manifestMd5;
            this.rawDigest = rawDigest;
        }

        /** The manifest-equivalent md5 of the prefix (line endings normalized for text). */
        public String manifestMd5() {
            return manifestMd5;
        }

        /**
         * Feed additional bytes (e.g. the appended tail) to the raw digest and return the raw MD5
         * hex of prefix + extra — the full-file hash a receiver verifies before writing.
         */
        public String rawMd5With(byte[] extra) {
            rawDigest.update(extra);
            return toHex(rawDigest.digest());
        }
    }

    /**
     * Read up to {@code len} bytes into {@code buf[off..off+len)}, returning the number actually
     * read. Unlike {@link java.io.InputStream#read(byte[], int, int)} this loops until either the
     * requested length is filled or EOF is reached.
     */
    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int read = in.read(buf, off + total, len - total);
            if (read == -1) {
                break;
            }
            total += read;
        }
        return total;
    }

    /**
     * Feed {@code chunk[off..off+len)} into {@code md} with line-ending normalization: CRLF, a lone
     * CR, and a standalone LF all map to a single LF. Reuses {@code outBuffer} for normalized
     * output (never larger than {@code len}). Returns the updated pending-CR state so callers can
     * carry it across chunk boundaries.
     */
    private static boolean updateNormalized(
            MessageDigest md, byte[] chunk, int off, int len, byte[] outBuffer, boolean pendingCR) {
        int outLen = 0;
        for (int i = off; i < off + len; i++) {
            int b = chunk[i] & 0xFF;
            if (b == '\r') {
                // A previous pending CR (lone CR not followed by LF) flushes as LF first.
                if (pendingCR) {
                    outBuffer[outLen++] = (byte) '\n';
                }
                pendingCR = true;
            } else if (b == '\n') {
                // Either CRLF (pending CR consumed) or a standalone LF -> single LF.
                outBuffer[outLen++] = (byte) '\n';
                pendingCR = false;
            } else {
                if (pendingCR) {
                    // Lone CR followed by a non-LF byte -> LF then the byte.
                    outBuffer[outLen++] = (byte) '\n';
                    pendingCR = false;
                }
                outBuffer[outLen++] = (byte) b;
            }
        }
        if (outLen > 0) {
            md.update(outBuffer, 0, outLen);
        }
        return pendingCR;
    }

    /** Format a digest as a lowercase hex string. */
    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Serialize manifest to JSON */
    public static String manifestToJson(FileManifest manifest) {
        return GSON.toJson(manifest);
    }

    /** Deserialize manifest from JSON */
    public static FileManifest manifestFromJson(String json) {
        return GSON.fromJson(json, FileManifest.class);
    }

    private static String toRelativePath(Path basePath, Path path) {
        return basePath.relativize(path).toString().replace('\\', '/');
    }

    /**
     * Whether an entry is hidden by Windows DOS attributes, taken from the attributes the walk
     * already read (on Windows those implement {@link DosFileAttributes}). Ignores Unix-style
     * hidden semantics (names starting with '.') and reports not-hidden on filesystems without DOS
     * attributes — same semantics as {@link #isWindowsHidden(Path)} minus the extra attribute read.
     */
    private static boolean isHidden(BasicFileAttributes attrs) {
        return attrs instanceof DosFileAttributes dosAttrs && dosAttrs.isHidden();
    }

    /**
     * Determine whether a path is hidden using Windows DOS attributes. Reads through symbolic
     * links, so it is used for walk entries whose manifest metadata also follows the target
     * (non-regular files); regular files use {@link #isHidden(BasicFileAttributes)} instead. This
     * ignores Unix-style hidden semantics (names starting with '.') and only treats entries with
     * the DOS hidden attribute as hidden.
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

    private static void markParentHasChild(
            String relativePath, Map<String, Boolean> dirHasChildren) {
        int lastSeparator = relativePath.lastIndexOf('/');
        String parent = lastSeparator == -1 ? "" : relativePath.substring(0, lastSeparator);
        dirHasChildren.put(parent, true);
    }

    private static boolean canReuseHash(FileInfo cachedInfo, long size, long lastModified) {
        return cachedInfo != null
                && cachedInfo.getMd5() != null
                && cachedInfo.getSize() == size
                && cachedInfo.getLastModified() == lastModified;
    }

    private static String computeHash(FileHasher hasher, File file) throws IOException {
        try {
            return hasher.hash(file);
        } catch (IOException e) {
            System.err.println(
                    "Failed to calculate hash for file: "
                            + file.getAbsolutePath()
                            + " - "
                            + e.getMessage());
            throw e;
        }
    }

    private static void reportProgress(
            String relativePath,
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
            // Reject manifests persisted by an incompatible schema (e.g. pre line-ending
            // normalization, which used raw-byte hashes) so stale hashes are never reused as a
            // cache. Reading the version from the JSON tree directly avoids depending on Gson's
            // final-field overwrite semantics.
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("schemaVersion")
                    || obj.get("schemaVersion").getAsInt() != FileManifest.CURRENT_VERSION) {
                System.err.println(
                        "Discarding persisted manifest with incompatible schema: "
                                + manifestFile.getAbsolutePath());
                return null;
            }
            return manifestFromJson(json);
        } catch (IOException | RuntimeException e) {
            System.err.println(
                    "Failed to load persisted manifest: "
                            + manifestFile.getAbsolutePath()
                            + " - "
                            + e.getMessage());
            return null;
        }
    }

    private static void persistManifest(File manifestFile, FileManifest manifest)
            throws IOException {
        if (manifestFile == null) {
            return;
        }
        Path path = manifestFile.toPath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                path,
                PERSIST_GSON.toJson(manifest),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
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

    /** File manifest containing all file information for a directory */
    public static class FileManifest {
        /**
         * Persisted-manifest schema version. Bumped whenever the on-disk format or hash semantics
         * change (e.g. line-ending normalization). Manifests with a different (or missing) version
         * are discarded by {@link #loadPersistedManifest(File)} to avoid reusing stale,
         * incompatible hashes as a cache.
         */
        public static final int CURRENT_VERSION = 2;

        private final Map<String, FileInfo> files;
        private final java.util.Set<String> emptyDirectories;
        private final int schemaVersion;

        public FileManifest() {
            this.files = new HashMap<>();
            this.emptyDirectories = new java.util.HashSet<>();
            this.schemaVersion = CURRENT_VERSION;
        }

        public FileManifest(Map<String, FileInfo> files) {
            this.files = files;
            this.emptyDirectories = new java.util.HashSet<>();
            this.schemaVersion = CURRENT_VERSION;
        }

        public FileManifest(Map<String, FileInfo> files, java.util.Set<String> emptyDirectories) {
            this.files = files;
            this.emptyDirectories =
                    emptyDirectories != null ? emptyDirectories : new java.util.HashSet<>();
            this.schemaVersion = CURRENT_VERSION;
        }

        public int getSchemaVersion() {
            return schemaVersion;
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

    /** Information about a single file */
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
            return "FileInfo{"
                    + "path='"
                    + path
                    + '\''
                    + ", size="
                    + size
                    + ", md5='"
                    + md5
                    + '\''
                    + '}';
        }
    }
}
