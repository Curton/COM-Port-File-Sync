package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileChangeDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void reusesCachedHashWhenMetadataUnchanged() throws IOException {
        Path filePath = tempDir.resolve("sample.txt");
        Files.writeString(filePath, "hello world");

        CountingHasher countingHasher = new CountingHasher();
        FileChangeDetector.ManifestGenerationOptions options = FileChangeDetector.ManifestGenerationOptions.builder()
                .withUseQuickHash(false)
                .withHasher(countingHasher)
                .build();

        FileChangeDetector.FileManifest first = FileChangeDetector.generateManifest(tempDir.toFile(), options);
        assertEquals(1, countingHasher.invocations.get(), "Initial hash should run once");
        String firstHash = first.getFiles().get("sample.txt").getMd5();
        assertNotNull(firstHash, "MD5 should be populated");

        FileChangeDetector.ManifestGenerationOptions cachedOptions = FileChangeDetector.ManifestGenerationOptions.builder()
                .withUseQuickHash(false)
                .withHasher(countingHasher)
                .withPreviousManifest(first)
                .build();

        FileChangeDetector.FileManifest second = FileChangeDetector.generateManifest(tempDir.toFile(), cachedOptions);
        assertEquals(1, countingHasher.invocations.get(), "Hash should be reused when metadata is unchanged");
        assertEquals(firstHash, second.getFiles().get("sample.txt").getMd5(), "Cached hash should be preserved");
    }

    @Test
    void writesPersistedManifestAndReportsProgress() throws IOException {
        Path nestedDir = tempDir.resolve("nested");
        Files.createDirectories(nestedDir);
        Files.writeString(nestedDir.resolve("a.txt"), "a");
        Files.writeString(tempDir.resolve("b.txt"), "b");

        Path manifestFile = tempDir.resolve("manifest-cache.json");
        RecordingProgressCallback callback = new RecordingProgressCallback();

        FileChangeDetector.ManifestGenerationOptions options = FileChangeDetector.ManifestGenerationOptions.builder()
                .withProgressCallback(callback)
                .withPersistedManifestFile(manifestFile.toFile())
                .withUseQuickHash(true)
                .build();

        FileChangeDetector.FileManifest manifest = FileChangeDetector.generateManifest(tempDir.toFile(), options);

        assertTrue(Files.exists(manifestFile), "Manifest should be persisted to disk");
        String persistedJson = Files.readString(manifestFile);
        FileChangeDetector.FileManifest fromDisk = FileChangeDetector.manifestFromJson(persistedJson);
        assertEquals(manifest.getFileCount(), fromDisk.getFileCount());
        assertEquals(2, callback.fileProcessedCount.get(), "Progress callback should track processed files");
        assertEquals(callback.totalFiles, callback.progressTotal, "Progress totals should match start callback");
    }

    @Test
    void hashesRunOnThreadPoolWhenEnabled() throws IOException {
        Files.writeString(tempDir.resolve("file1.txt"), "one");
        Files.writeString(tempDir.resolve("file2.txt"), "two");
        Files.writeString(tempDir.resolve("file3.txt"), "three");

        Set<String> hashingThreads = ConcurrentHashMap.newKeySet();
        FileChangeDetector.FileHasher trackingHasher = file -> {
            hashingThreads.add(Thread.currentThread().getName());
            return FileChangeDetector.calculateMD5(file);
        };

        FileChangeDetector.ManifestGenerationOptions options = FileChangeDetector.ManifestGenerationOptions.builder()
                .withHasher(trackingHasher)
                .withHashThreadPoolSize(2)
                .withUseQuickHash(false)
                .build();

        FileChangeDetector.FileManifest manifest = FileChangeDetector.generateManifest(tempDir.toFile(), options);
        assertNotNull(manifest);
        assertTrue(hashingThreads.stream().anyMatch(name -> name.contains("pool")), "Hashing should occur on worker threads");
    }

    private static final class CountingHasher implements FileChangeDetector.FileHasher {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String hash(File file) throws IOException {
            invocations.incrementAndGet();
            return FileChangeDetector.calculateMD5(file);
        }
    }

    private static final class RecordingProgressCallback implements FileChangeDetector.ManifestProgressCallback {
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
}

