package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileChangeDetectorManifestOptionsTest {

    @Test
    void builderBuildsWithDefaultValues() {
        FileChangeDetector.ManifestGenerationOptions options =
                FileChangeDetector.ManifestGenerationOptions.builder().build();

        assertFalse(options.isRespectGitignore());
        assertFalse(options.isUseQuickHash());
        assertNull(options.getProgressCallback());
        assertTrue(options.getHashThreadPoolSize() >= 2);
        assertNull(options.getPersistedManifestFile());
        assertTrue(options.isPersistResult());
        assertNull(options.getPreviousManifest());
        assertNotNull(options.getHasher());
    }

    @Test
    void builderSetsAllValues() {
        FileChangeDetector.ManifestProgressCallback callback =
                new FileChangeDetector.ManifestProgressCallback() {};
        File manifestFile = new File("/tmp/manifest.json");
        FileChangeDetector.FileManifest previousManifest = new FileChangeDetector.FileManifest();
        FileChangeDetector.FileHasher hasher = file -> "test-hash";

        FileChangeDetector.ManifestGenerationOptions options =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withRespectGitignore(true)
                        .withUseQuickHash(true)
                        .withProgressCallback(callback)
                        .withHashThreadPoolSize(4)
                        .withPersistedManifestFile(manifestFile)
                        .withPersistResult(false)
                        .withPreviousManifest(previousManifest)
                        .withHasher(hasher)
                        .build();

        assertTrue(options.isRespectGitignore());
        assertTrue(options.isUseQuickHash());
        assertEquals(callback, options.getProgressCallback());
        assertEquals(4, options.getHashThreadPoolSize());
        assertEquals(manifestFile, options.getPersistedManifestFile());
        assertFalse(options.isPersistResult());
        assertEquals(previousManifest, options.getPreviousManifest());
        assertEquals(hasher, options.getHasher());
    }

    @Test
    void builderThreadPoolSizeMinimumIsOne() {
        FileChangeDetector.ManifestGenerationOptions options =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withHashThreadPoolSize(0)
                        .build();

        assertEquals(1, options.getHashThreadPoolSize());
    }

    @Test
    void builderThreadPoolSizeNegativeBecomesOne() {
        FileChangeDetector.ManifestGenerationOptions options =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withHashThreadPoolSize(-5)
                        .build();

        assertEquals(1, options.getHashThreadPoolSize());
    }

    @Test
    void builderBuilderMethodReturnsNewBuilder() {
        FileChangeDetector.ManifestGenerationOptions.Builder builder =
                FileChangeDetector.ManifestGenerationOptions.builder();
        assertNotNull(builder);
    }

    @Test
    void manifestProgressCallbackDefaultMethodsDoNothing() {
        FileChangeDetector.ManifestProgressCallback callback =
                new FileChangeDetector.ManifestProgressCallback() {};

        // These should not throw
        callback.onStart(100);
        callback.onProgress(10, 100);
        callback.onFileProcessed("test.txt");
        callback.onFileProcessed("test.txt", 10, 100);
        callback.onComplete(new FileChangeDetector.FileManifest());
    }

    @Test
    void manifestProgressCallbackOnFileProcessedDelegatesToSingleArgVersion() {
        List<String> processedFiles = new ArrayList<>();

        FileChangeDetector.ManifestProgressCallback callback =
                new FileChangeDetector.ManifestProgressCallback() {
                    @Override
                    public void onFileProcessed(String fileName) {
                        processedFiles.add(fileName);
                    }
                };

        callback.onFileProcessed("file1.txt", 1, 10);

        assertEquals(1, processedFiles.size());
        assertEquals("file1.txt", processedFiles.get(0));
    }

    @Test
    void fileInfoGettersReturnValues() {
        FileChangeDetector.FileInfo info =
                new FileChangeDetector.FileInfo("test.txt", 1024L, 12345L, "md5hash");

        assertEquals("test.txt", info.getPath());
        assertEquals(1024L, info.getSize());
        assertEquals(12345L, info.getLastModified());
        assertEquals("md5hash", info.getMd5());
    }

    @Test
    void fileInfoToStringContainsPath() {
        FileChangeDetector.FileInfo info =
                new FileChangeDetector.FileInfo("test.txt", 1024L, 12345L, "md5hash");

        String str = info.toString();
        assertTrue(str.contains("test.txt"));
        assertTrue(str.contains("1024"));
        assertTrue(str.contains("md5hash"));
    }

    @Test
    void fileManifestDefaultConstructorCreatesEmptyManifest() {
        FileChangeDetector.FileManifest manifest = new FileChangeDetector.FileManifest();

        assertTrue(manifest.getFiles().isEmpty());
        assertTrue(manifest.getEmptyDirectories().isEmpty());
        assertEquals(0, manifest.getFileCount());
        assertEquals(0, manifest.getEmptyDirectoryCount());
    }

    @Test
    void fileManifestConstructorWithFiles() {
        java.util.Map<String, FileChangeDetector.FileInfo> files = new java.util.HashMap<>();
        files.put("test.txt", new FileChangeDetector.FileInfo("test.txt", 100L, 0L, "md5"));

        FileChangeDetector.FileManifest manifest = new FileChangeDetector.FileManifest(files);

        assertEquals(1, manifest.getFileCount());
        assertTrue(manifest.getEmptyDirectories().isEmpty());
    }

    @Test
    void fileManifestConstructorWithFilesAndDirectories() {
        java.util.Map<String, FileChangeDetector.FileInfo> files = new java.util.HashMap<>();
        java.util.Set<String> dirs = new java.util.HashSet<>();
        dirs.add("empty-dir");

        FileChangeDetector.FileManifest manifest = new FileChangeDetector.FileManifest(files, dirs);

        assertEquals(0, manifest.getFileCount());
        assertEquals(1, manifest.getEmptyDirectoryCount());
        assertTrue(manifest.getEmptyDirectories().contains("empty-dir"));
    }

    @Test
    void fileManifestConstructorWithNullDirectories() {
        FileChangeDetector.FileManifest manifest =
                new FileChangeDetector.FileManifest(new java.util.HashMap<>(), null);

        assertNotNull(manifest.getEmptyDirectories());
        assertTrue(manifest.getEmptyDirectories().isEmpty());
    }
}
