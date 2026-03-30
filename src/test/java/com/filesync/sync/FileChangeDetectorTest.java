package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileChangeDetectorTest {

    @TempDir Path tempDir;

    @Test
    void reusesCachedHashWhenMetadataUnchanged() throws IOException {
        Path filePath = tempDir.resolve("sample.txt");
        Files.writeString(filePath, "hello world");

        CountingHasher countingHasher = new CountingHasher();
        FileChangeDetector.ManifestGenerationOptions options =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withUseQuickHash(false)
                        .withHasher(countingHasher)
                        .build();

        FileChangeDetector.FileManifest first =
                FileChangeDetector.generateManifest(tempDir.toFile(), options);
        assertEquals(1, countingHasher.invocations.get(), "Initial hash should run once");
        String firstHash = first.getFiles().get("sample.txt").getMd5();
        assertNotNull(firstHash, "MD5 should be populated");

        FileChangeDetector.ManifestGenerationOptions cachedOptions =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withUseQuickHash(false)
                        .withHasher(countingHasher)
                        .withPreviousManifest(first)
                        .build();

        FileChangeDetector.FileManifest second =
                FileChangeDetector.generateManifest(tempDir.toFile(), cachedOptions);
        assertEquals(
                1,
                countingHasher.invocations.get(),
                "Hash should be reused when metadata is unchanged");
        assertEquals(
                firstHash,
                second.getFiles().get("sample.txt").getMd5(),
                "Cached hash should be preserved");
    }

    @Test
    void writesPersistedManifestAndReportsProgress() throws IOException {
        Path nestedDir = tempDir.resolve("nested");
        Files.createDirectories(nestedDir);
        Files.writeString(nestedDir.resolve("a.txt"), "a");
        Files.writeString(tempDir.resolve("b.txt"), "b");

        Path manifestFile = tempDir.resolve("manifest-cache.json");
        RecordingProgressCallback callback = new RecordingProgressCallback();

        FileChangeDetector.ManifestGenerationOptions options =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withProgressCallback(callback)
                        .withPersistedManifestFile(manifestFile.toFile())
                        .withUseQuickHash(true)
                        .build();

        FileChangeDetector.FileManifest manifest =
                FileChangeDetector.generateManifest(tempDir.toFile(), options);

        assertTrue(Files.exists(manifestFile), "Manifest should be persisted to disk");
        String persistedJson = Files.readString(manifestFile);
        FileChangeDetector.FileManifest fromDisk =
                FileChangeDetector.manifestFromJson(persistedJson);
        assertEquals(manifest.getFileCount(), fromDisk.getFileCount());
        assertEquals(
                2,
                callback.fileProcessedCount.get(),
                "Progress callback should track processed files");
        assertEquals(
                callback.totalFiles,
                callback.progressTotal,
                "Progress totals should match start callback");
    }

    @Test
    void hashesRunOnThreadPoolWhenEnabled() throws IOException {
        Files.writeString(tempDir.resolve("file1.txt"), "one");
        Files.writeString(tempDir.resolve("file2.txt"), "two");
        Files.writeString(tempDir.resolve("file3.txt"), "three");

        Set<String> hashingThreads = ConcurrentHashMap.newKeySet();
        FileChangeDetector.FileHasher trackingHasher =
                file -> {
                    hashingThreads.add(Thread.currentThread().getName());
                    return FileChangeDetector.calculateMD5(file);
                };

        FileChangeDetector.ManifestGenerationOptions options =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withHasher(trackingHasher)
                        .withHashThreadPoolSize(2)
                        .withUseQuickHash(false)
                        .build();

        FileChangeDetector.FileManifest manifest =
                FileChangeDetector.generateManifest(tempDir.toFile(), options);
        assertNotNull(manifest);
        assertTrue(
                hashingThreads.stream().anyMatch(name -> name.contains("pool")),
                "Hashing should occur on worker threads");
    }

    private static final class CountingHasher implements FileChangeDetector.FileHasher {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String hash(File file) throws IOException {
            invocations.incrementAndGet();
            return FileChangeDetector.calculateMD5(file);
        }
    }

    private static final class RecordingProgressCallback
            implements FileChangeDetector.ManifestProgressCallback {
        private final AtomicInteger fileProcessedCount = new AtomicInteger();
        private int totalFiles;
        private int progressTotal;

        @Override
        public void onStart(int totalFiles) {
            this.totalFiles = totalFiles;
        }

        @Override
        public void onProgress(int processedFiles, int totalFiles) {
            this.progressTotal = totalFiles;
        }

        @Override
        public void onFileProcessed(String fileName) {
            fileProcessedCount.incrementAndGet();
        }
    }

    // ========== Delta method tests ==========

    @Test
    void getChangedFiles_returnsFileOnlyInSource() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("new.txt", new FileChangeDetector.FileInfo("new.txt", 100, 1000L, "abc"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertEquals(1, changed.size());
        assertEquals("new.txt", changed.get(0).getPath());
    }

    @Test
    void getChangedFiles_skipsFileWithSameMd5() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put(
                "file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, "abc123"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put(
                "file.txt", new FileChangeDetector.FileInfo("file.txt", 200, 5000L, "abc123"));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertTrue(changed.isEmpty(), "Same MD5 should mean no change");
    }

    @Test
    void getChangedFiles_detectsDifferentMd5() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, "abc"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 200, 5000L, "def"));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertEquals(1, changed.size());
    }

    @Test
    void getChangedFiles_skipsSameMetadataInWindow() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, null));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 2000L, null));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertTrue(
                changed.isEmpty(),
                "Within MODIFY_WINDOW_MS (3000) and same size should be unchanged");
    }

    @Test
    void getChangedFiles_detectsMetadataBeyondWindow() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, null));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 5000L, null));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertEquals(1, changed.size(), "Beyond MODIFY_WINDOW_MS should detect change");
    }

    @Test
    void getChangedFiles_detectsDifferentSizeEvenWithinWindow() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, null));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 200, 1500L, null));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertEquals(1, changed.size(), "Different size should always detect change");
    }

    @Test
    void getFilesToDelete_returnsPathsOnlyInTarget() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("keep.txt", new FileChangeDetector.FileInfo("keep.txt", 50, 0, "x"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("keep.txt", new FileChangeDetector.FileInfo("keep.txt", 50, 0, "x"));
        targetFiles.put(
                "obsolete.txt", new FileChangeDetector.FileInfo("obsolete.txt", 30, 0, "y"));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<String> toDelete = FileChangeDetector.getFilesToDelete(source, target);
        assertEquals(1, toDelete.size());
        assertEquals("obsolete.txt", toDelete.get(0));
    }

    @Test
    void getFilesToDelete_returnsEmptyWhenTargetIsSubset() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("a.txt", new FileChangeDetector.FileInfo("a.txt", 10, 0, "x"));
        sourceFiles.put("b.txt", new FileChangeDetector.FileInfo("b.txt", 20, 0, "y"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("a.txt", new FileChangeDetector.FileInfo("a.txt", 10, 0, "x"));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        assertTrue(FileChangeDetector.getFilesToDelete(source, target).isEmpty());
    }

    @Test
    void getEmptyDirectoriesToCreate_returnsDirsOnlyInSource() {
        Set<String> sourceDirs = new HashSet<>(List.of("newdir", "shared"));
        Set<String> targetDirs = new HashSet<>(List.of("shared"));

        FileChangeDetector.FileManifest source =
                new FileChangeDetector.FileManifest(new HashMap<>(), sourceDirs);
        FileChangeDetector.FileManifest target =
                new FileChangeDetector.FileManifest(new HashMap<>(), targetDirs);

        List<String> toCreate = FileChangeDetector.getEmptyDirectoriesToCreate(source, target);
        assertEquals(1, toCreate.size());
        assertEquals("newdir", toCreate.get(0));
    }

    @Test
    void getEmptyDirectoriesToDelete_returnsDirsOnlyInTargetSortedDeepestFirst() {
        Set<String> sourceDirs = new HashSet<>(List.of("a"));
        Set<String> targetDirs = new HashSet<>(List.of("a", "b/c/d", "b/c", "b"));

        FileChangeDetector.FileManifest source =
                new FileChangeDetector.FileManifest(new HashMap<>(), sourceDirs);
        FileChangeDetector.FileManifest target =
                new FileChangeDetector.FileManifest(new HashMap<>(), targetDirs);

        List<String> toDelete = FileChangeDetector.getEmptyDirectoriesToDelete(source, target);
        assertEquals(3, toDelete.size());
        assertEquals("b/c/d", toDelete.get(0), "Deepest directory should come first");
    }

    @Test
    void fileManifest_noArgConstructorCreatesEmptyManifest() {
        FileChangeDetector.FileManifest manifest = new FileChangeDetector.FileManifest();
        assertTrue(manifest.getFiles().isEmpty());
        assertTrue(manifest.getEmptyDirectories().isEmpty());
        assertEquals(0, manifest.getFileCount());
        assertEquals(0, manifest.getEmptyDirectoryCount());
    }

    @Test
    void fileManifest_singleArgConstructorUsesProvidedMap() {
        Map<String, FileChangeDetector.FileInfo> files = new HashMap<>();
        files.put("a.txt", new FileChangeDetector.FileInfo("a.txt", 10, 0, "hash"));
        FileChangeDetector.FileManifest manifest = new FileChangeDetector.FileManifest(files);
        assertEquals(1, manifest.getFileCount());
        assertTrue(manifest.getEmptyDirectories().isEmpty());
    }

    @Test
    void fileInfo_toStringIncludesPathAndSize() {
        FileChangeDetector.FileInfo info =
                new FileChangeDetector.FileInfo("test.txt", 42, 0, "abc");
        String str = info.toString();
        assertTrue(str.contains("test.txt"));
        assertTrue(str.contains("42"));
    }
}
