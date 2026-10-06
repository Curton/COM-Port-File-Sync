package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictInfoTest {

    @Test
    void constructorStoresValuesAndBinaryFlag() {
        FileChangeDetector.FileInfo localInfo =
                new FileChangeDetector.FileInfo("test.txt", 100L, 0L, "md5-a");
        FileChangeDetector.FileInfo remoteInfo =
                new FileChangeDetector.FileInfo("test.txt", 200L, 0L, "md5-b");
        byte[] localContent = "local content".getBytes(StandardCharsets.UTF_8);

        ConflictInfo info =
                new ConflictInfo("test.txt", localInfo, remoteInfo, false, localContent);

        assertEquals("test.txt", info.getPath());
        assertEquals(localInfo, info.getLocalInfo());
        assertEquals(remoteInfo, info.getRemoteInfo());
        assertFalse(info.isBinary());
        assertArrayEquals(localContent, info.getLocalContent());

        ConflictInfo binaryInfo =
                new ConflictInfo(
                        "test.bin",
                        new FileChangeDetector.FileInfo("test.bin", 100L, 0L, "md5-a"),
                        new FileChangeDetector.FileInfo("test.bin", 200L, 0L, "md5-b"),
                        true,
                        new byte[] {0x01});
        assertTrue(binaryInfo.isBinary());
    }

    @Test
    void remoteContentLifecycle() {
        ConflictInfo info = createDefaultConflictInfo();
        assertNull(info.getRemoteContent());
        assertEquals("", info.getRemoteContentAsString());

        byte[] remoteContent = "remote content".getBytes(StandardCharsets.UTF_8);
        info.setRemoteContent(remoteContent);
        assertArrayEquals(remoteContent, info.getRemoteContent());
        assertEquals("remote content", info.getRemoteContentAsString());
    }

    @Test
    void mergedContentLifecycle() {
        ConflictInfo info = createDefaultConflictInfo();
        assertNull(info.getMergedContentAsBytes());

        info.setMergedContent("merged content");
        assertEquals("merged content", info.getMergedContent());
        byte[] expected = "merged content".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, info.getMergedContentAsBytes());
    }

    @Test
    void resolutionLifecycle() {
        ConflictInfo info = createDefaultConflictInfo();

        assertEquals(ConflictInfo.Resolution.UNRESOLVED, info.getResolution());
        assertFalse(info.isResolved());

        info.setResolution(ConflictInfo.Resolution.KEEP_LOCAL);
        assertEquals(ConflictInfo.Resolution.KEEP_LOCAL, info.getResolution());
        assertTrue(info.isResolved());
    }

    @Test
    void applyTargetDefaultsToBothAndStores() {
        ConflictInfo info = createDefaultConflictInfo();
        assertEquals(ConflictInfo.ApplyTarget.BOTH, info.getApplyTarget());

        info.setApplyTarget(ConflictInfo.ApplyTarget.REMOTE_ONLY);
        assertEquals(ConflictInfo.ApplyTarget.REMOTE_ONLY, info.getApplyTarget());
    }

    @Test
    void getLocalContentAsString_decodesUtf8AndHandlesNull() {
        byte[] localContent = "local content".getBytes(StandardCharsets.UTF_8);
        ConflictInfo info = new ConflictInfo("test.txt", null, null, false, localContent);
        assertEquals("local content", info.getLocalContentAsString());

        ConflictInfo withoutContent = new ConflictInfo("test.txt", null, null, false, null);
        assertEquals("", withoutContent.getLocalContentAsString());
    }

    @Test
    void toStringContainsPathAndResolution() {
        ConflictInfo info = createDefaultConflictInfo();
        info.setResolution(ConflictInfo.Resolution.KEEP_LOCAL);

        String result = info.toString();

        assertTrue(result.contains("test.txt"));
        assertTrue(result.contains("KEEP_LOCAL"));
        assertTrue(result.contains("binary=false"));
    }

    @Test
    void enumsHaveExpectedValues() {
        ConflictInfo.Resolution[] resolutions = ConflictInfo.Resolution.values();
        assertEquals(5, resolutions.length);
        assertEquals(
                ConflictInfo.Resolution.KEEP_LOCAL, ConflictInfo.Resolution.valueOf("KEEP_LOCAL"));
        assertEquals(
                ConflictInfo.Resolution.KEEP_REMOTE,
                ConflictInfo.Resolution.valueOf("KEEP_REMOTE"));
        assertEquals(ConflictInfo.Resolution.SKIP, ConflictInfo.Resolution.valueOf("SKIP"));
        assertEquals(ConflictInfo.Resolution.MERGE, ConflictInfo.Resolution.valueOf("MERGE"));
        assertEquals(
                ConflictInfo.Resolution.UNRESOLVED, ConflictInfo.Resolution.valueOf("UNRESOLVED"));

        ConflictInfo.ApplyTarget[] targets = ConflictInfo.ApplyTarget.values();
        assertEquals(2, targets.length);
        assertEquals(
                ConflictInfo.ApplyTarget.REMOTE_ONLY,
                ConflictInfo.ApplyTarget.valueOf("REMOTE_ONLY"));
        assertEquals(ConflictInfo.ApplyTarget.BOTH, ConflictInfo.ApplyTarget.valueOf("BOTH"));
    }

    private ConflictInfo createDefaultConflictInfo() {
        FileChangeDetector.FileInfo localInfo =
                new FileChangeDetector.FileInfo("test.txt", 100L, 0L, "md5-a");
        FileChangeDetector.FileInfo remoteInfo =
                new FileChangeDetector.FileInfo("test.txt", 200L, 0L, "md5-b");
        return new ConflictInfo(
                "test.txt", localInfo, remoteInfo, false, "local".getBytes(StandardCharsets.UTF_8));
    }

    // ========== Lazy loading tests ==========

    @Test
    void lazyLoading_readsFromFileOnFirstAccess(@TempDir Path tempDir) throws IOException {
        Path localFile = tempDir.resolve("test.txt");
        Files.writeString(localFile, "lazy loaded content");

        FileChangeDetector.FileInfo localInfo =
                new FileChangeDetector.FileInfo("test.txt", 18L, 0L, null);
        FileChangeDetector.FileInfo remoteInfo =
                new FileChangeDetector.FileInfo("test.txt", 0L, 0L, null);
        ConflictInfo info =
                new ConflictInfo("test.txt", localInfo, remoteInfo, false, (byte[]) null);
        info.setLazyLocalFile(localFile.toFile());

        assertEquals("lazy loaded content", info.getLocalContentAsString());
    }

    @Test
    void lazyLoading_cachesContentAfterFirstRead(@TempDir Path tempDir) throws IOException {
        Path localFile = tempDir.resolve("test.txt");
        Files.writeString(localFile, "original content");

        ConflictInfo info = new ConflictInfo("test.txt", null, null, false, (byte[]) null);
        info.setLazyLocalFile(localFile.toFile());

        // First access reads from file
        assertArrayEquals(
                "original content".getBytes(StandardCharsets.UTF_8), info.getLocalContent());

        // Modify file on disk after first access
        Files.writeString(localFile, "modified content");

        // Second access should return cached (original) content
        assertArrayEquals(
                "original content".getBytes(StandardCharsets.UTF_8), info.getLocalContent());
    }

    @Test
    void lazyLoading_returnsNullForTooLargeFile(@TempDir Path tempDir) throws IOException {
        // Write a file that exceeds MAX_FULL_READ_BYTES (1MB)
        Path localFile = tempDir.resolve("large.bin");
        long targetSize = 2 * 1024 * 1024L;
        try (java.io.RandomAccessFile raf =
                new java.io.RandomAccessFile(localFile.toFile(), "rw")) {
            raf.setLength(targetSize);
        }

        FileChangeDetector.FileInfo localInfo =
                new FileChangeDetector.FileInfo("large.bin", targetSize, 0L, null);
        FileChangeDetector.FileInfo remoteInfo =
                new FileChangeDetector.FileInfo("large.bin", 0L, 0L, null);
        ConflictInfo info =
                new ConflictInfo("large.bin", localInfo, remoteInfo, false, (byte[]) null);
        info.setLazyLocalFile(localFile.toFile());

        assertNull(info.getLocalContent());
        assertEquals("", info.getLocalContentAsString());
    }

    @Test
    void lazyLoading_returnsNullForNonexistentFile() {
        ConflictInfo info = new ConflictInfo("nope.txt", null, null, false, (byte[]) null);
        info.setLazyLocalFile(new File("nonexistent.txt"));

        assertNull(info.getLocalContent());
    }

    @Test
    void lazyLoading_setLazyLocalFileOverridesPreloadedContent(@TempDir Path tempDir)
            throws IOException {
        Path localFile = tempDir.resolve("test.txt");
        Files.writeString(localFile, "file content");

        ConflictInfo info = new ConflictInfo("test.txt", null, null, false, "preloaded".getBytes());
        assertArrayEquals("preloaded".getBytes(), info.getLocalContent());

        // Override with lazy loading — next access reads from file
        info.setLazyLocalFile(localFile.toFile());
        assertEquals("file content", info.getLocalContentAsString());
    }
}
