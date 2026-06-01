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
    void constructorStoresValues() {
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
    }

    @Test
    void constructorStoresBinaryFlag() {
        FileChangeDetector.FileInfo localInfo =
                new FileChangeDetector.FileInfo("test.bin", 100L, 0L, "md5-a");
        FileChangeDetector.FileInfo remoteInfo =
                new FileChangeDetector.FileInfo("test.bin", 200L, 0L, "md5-b");

        ConflictInfo info =
                new ConflictInfo("test.bin", localInfo, remoteInfo, true, new byte[] {0x01});

        assertTrue(info.isBinary());
    }

    @Test
    void setRemoteContentStoresRemoteContent() {
        ConflictInfo info = createDefaultConflictInfo();
        byte[] remoteContent = "remote content".getBytes(StandardCharsets.UTF_8);

        info.setRemoteContent(remoteContent);

        assertArrayEquals(remoteContent, info.getRemoteContent());
    }

    @Test
    void getRemoteContentReturnsNullWhenNotSet() {
        ConflictInfo info = createDefaultConflictInfo();

        assertNull(info.getRemoteContent());
    }

    @Test
    void setMergedContentStoresMergedContent() {
        ConflictInfo info = createDefaultConflictInfo();

        info.setMergedContent("merged content");

        assertEquals("merged content", info.getMergedContent());
    }

    @Test
    void getMergedContentAsBytesReturnsUtf8Bytes() {
        ConflictInfo info = createDefaultConflictInfo();
        info.setMergedContent("merged content");

        byte[] expected = "merged content".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, info.getMergedContentAsBytes());
    }

    @Test
    void getMergedContentAsBytesReturnsNullWhenNotSet() {
        ConflictInfo info = createDefaultConflictInfo();

        assertNull(info.getMergedContentAsBytes());
    }

    @Test
    void setResolutionStoresResolution() {
        ConflictInfo info = createDefaultConflictInfo();

        info.setResolution(ConflictInfo.Resolution.KEEP_LOCAL);

        assertEquals(ConflictInfo.Resolution.KEEP_LOCAL, info.getResolution());
    }

    @Test
    void getResolutionReturnsUnresolvedByDefault() {
        ConflictInfo info = createDefaultConflictInfo();

        assertEquals(ConflictInfo.Resolution.UNRESOLVED, info.getResolution());
    }

    @Test
    void setApplyTargetStoresApplyTarget() {
        ConflictInfo info = createDefaultConflictInfo();

        info.setApplyTarget(ConflictInfo.ApplyTarget.REMOTE_ONLY);

        assertEquals(ConflictInfo.ApplyTarget.REMOTE_ONLY, info.getApplyTarget());
    }

    @Test
    void getApplyTargetReturnsBothByDefault() {
        ConflictInfo info = createDefaultConflictInfo();

        assertEquals(ConflictInfo.ApplyTarget.BOTH, info.getApplyTarget());
    }

    @Test
    void isResolvedReturnsTrueWhenResolved() {
        ConflictInfo info = createDefaultConflictInfo();
        info.setResolution(ConflictInfo.Resolution.SKIP);

        assertTrue(info.isResolved());
    }

    @Test
    void isResolvedReturnsFalseWhenUnresolved() {
        ConflictInfo info = createDefaultConflictInfo();

        assertFalse(info.isResolved());
    }

    @Test
    void getLocalContentAsStringReturnsUtf8String() {
        byte[] localContent = "local content".getBytes(StandardCharsets.UTF_8);
        ConflictInfo info = new ConflictInfo("test.txt", null, null, false, localContent);

        assertEquals("local content", info.getLocalContentAsString());
    }

    @Test
    void getLocalContentAsStringReturnsEmptyStringWhenNull() {
        ConflictInfo info = new ConflictInfo("test.txt", null, null, false, null);

        assertEquals("", info.getLocalContentAsString());
    }

    @Test
    void getRemoteContentAsStringReturnsUtf8String() {
        ConflictInfo info = createDefaultConflictInfo();
        info.setRemoteContent("remote content".getBytes(StandardCharsets.UTF_8));

        assertEquals("remote content", info.getRemoteContentAsString());
    }

    @Test
    void getRemoteContentAsStringReturnsEmptyStringWhenNull() {
        ConflictInfo info = createDefaultConflictInfo();

        assertEquals("", info.getRemoteContentAsString());
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
    void resolutionEnumHasAllExpectedValues() {
        ConflictInfo.Resolution[] values = ConflictInfo.Resolution.values();
        assertEquals(5, values.length);
        assertEquals(
                ConflictInfo.Resolution.KEEP_LOCAL, ConflictInfo.Resolution.valueOf("KEEP_LOCAL"));
        assertEquals(
                ConflictInfo.Resolution.KEEP_REMOTE,
                ConflictInfo.Resolution.valueOf("KEEP_REMOTE"));
        assertEquals(ConflictInfo.Resolution.SKIP, ConflictInfo.Resolution.valueOf("SKIP"));
        assertEquals(ConflictInfo.Resolution.MERGE, ConflictInfo.Resolution.valueOf("MERGE"));
        assertEquals(
                ConflictInfo.Resolution.UNRESOLVED, ConflictInfo.Resolution.valueOf("UNRESOLVED"));
    }

    @Test
    void applyTargetEnumHasAllExpectedValues() {
        ConflictInfo.ApplyTarget[] values = ConflictInfo.ApplyTarget.values();
        assertEquals(2, values.length);
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
