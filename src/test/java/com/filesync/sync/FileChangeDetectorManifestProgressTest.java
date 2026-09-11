package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the progress reporting of manifest generation: the pre-count walk is skipped when a
 * persisted cache provides the denominator (its size, grown monotonically for new files), and the
 * no-cache path still reports an exact total.
 */
class FileChangeDetectorManifestProgressTest {

    @TempDir Path syncFolder;

    @TempDir Path cacheDir;

    /** Records onStart/onProgress totals through generation. */
    private static final class RecordingCallback
            implements FileChangeDetector.ManifestProgressCallback {
        // onProgress fires on the walk thread and the hash pool concurrently: the collection must
        // be safe for concurrent adds.
        final List<int[]> progressPairs = Collections.synchronizedList(new ArrayList<>());
        Integer startTotal;
        Integer completeProcessed;
        Integer completeTotal;

        @Override
        public void onStart(int totalFiles) {
            startTotal = totalFiles;
        }

        @Override
        public void onProgress(int processedFiles, int totalFiles) {
            progressPairs.add(new int[] {processedFiles, totalFiles});
        }

        @Override
        public void onComplete(FileChangeDetector.FileManifest manifest) {
            completeProcessed = manifest.getFileCount();
            completeTotal = manifest.getFileCount();
        }
    }

    private FileChangeDetector.FileManifest generate(boolean useCache, RecordingCallback callback)
            throws IOException {
        File cacheFile = useCache ? cacheDir.resolve("manifest-cache.json").toFile() : null;
        return FileChangeDetector.generateManifest(
                syncFolder.toFile(),
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withProgressCallback(callback)
                        .withPersistedManifestFile(cacheFile)
                        .build());
    }

    @Test
    void noCache_preCountReportsExactConstantTotal() throws IOException {
        for (int i = 0; i < 4; i++) {
            Files.writeString(syncFolder.resolve("file" + i + ".txt"), "content-" + i);
        }
        RecordingCallback callback = new RecordingCallback();

        FileChangeDetector.FileManifest manifest = generate(false, callback);

        assertEquals(4, callback.startTotal, "No cache: the pre-count walk gives the exact total");
        assertEquals(4, manifest.getFileCount());
        for (int[] pair : callback.progressPairs) {
            assertEquals(4, pair[1], "Total stays constant without a cache");
            assertTrue(pair[0] <= pair[1], "processed must never exceed total");
        }
    }

    @Test
    void withCache_startIsCacheSizeAndTotalGrowsMonotonically() throws IOException {
        Files.writeString(syncFolder.resolve("old.txt"), "old");
        RecordingCallback first = new RecordingCallback();
        generate(true, first);
        assertEquals(1, first.startTotal, "First generation counts the folder exactly");

        // Three new files the cache does not know about.
        for (int i = 0; i < 3; i++) {
            Files.writeString(syncFolder.resolve("new" + i + ".txt"), "new-" + i);
        }
        RecordingCallback second = new RecordingCallback();

        FileChangeDetector.FileManifest manifest = generate(true, second);

        // The distinguishing assertion: with a cache the tree is NOT pre-counted, so onStart is
        // the cache size (1), not the real file count (1 + 3 new = 4).
        assertEquals(1, second.startTotal, "With a cache, onStart is the cache-size estimate");
        assertEquals(4, manifest.getFileCount());
        int lastTotal = 0;
        for (int[] pair : second.progressPairs) {
            // processed values may report slightly out of order across the hash pool, but the
            // fraction must stay valid and the total may only grow.
            assertTrue(pair[0] <= pair[1], "processed must never exceed total");
            assertTrue(pair[1] >= lastTotal, "total must grow monotonically");
            lastTotal = pair[1];
        }
        assertEquals(4, lastTotal, "The total grows to the real file count during the walk");
    }

    @Test
    void withCacheAndNoNewFiles_totalStaysAtCacheSize() throws IOException {
        for (int i = 0; i < 3; i++) {
            Files.writeString(syncFolder.resolve("file" + i + ".txt"), "content-" + i);
        }
        generate(true, new RecordingCallback());
        RecordingCallback second = new RecordingCallback();

        FileChangeDetector.FileManifest manifest = generate(true, second);

        assertEquals(3, second.startTotal, "Unchanged folder: the cache size is the exact total");
        for (int[] pair : second.progressPairs) {
            assertEquals(3, pair[1], "No growth needed when nothing new appeared");
        }
        assertEquals(3, manifest.getFileCount());
    }
}
