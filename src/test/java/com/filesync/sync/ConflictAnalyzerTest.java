package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictAnalyzerTest {

    @TempDir Path tempDir;

    @Test
    void findConflicts_noConflicts_whenFilesIdentical() throws IOException {
        // Create test files
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);

        String content = "same content";
        Files.writeString(localDir.resolve("file.txt"), content);
        Files.writeString(remoteDir.resolve("file.txt"), content);

        // Generate manifests
        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, true);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, true);

        // Find conflicts
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());

        assertTrue(conflicts.isEmpty(), "No conflicts when files are identical");
    }

    @Test
    void findConflicts_detectsConflict_whenBothSidesModified() throws IOException {
        // Create test files
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);

        // Same file but different content on each side; remote has newer mtime (receiver modified)
        Path localFile = localDir.resolve("file.txt");
        Path remoteFile = remoteDir.resolve("file.txt");
        Files.writeString(localFile, "local version");
        Files.writeString(remoteFile, "remote version");
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(
                remoteFile,
                FileTime.fromMillis(5000L)); // remote newer than local + MODIFY_WINDOW_MS

        // Generate manifests (fast mode to use MD5)
        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        // Find conflicts
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());

        assertEquals(1, conflicts.size(), "Should detect one conflict");
        ConflictInfo conflict = conflicts.get(0);
        assertEquals("file.txt", conflict.getPath());
        assertFalse(conflict.isBinary(), "Text file should not be marked as binary");
        assertNotNull(conflict.getLocalContent());
    }

    @Test
    void findConflicts_noConflict_whenOnlyOneSideModified() throws IOException {
        // Create test files
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);

        // File exists only on local side (receiver deleted or never had it)
        Files.writeString(localDir.resolve("newfile.txt"), "new content on sender");

        // Generate manifests
        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, true);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, true);

        // Find conflicts
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());

        assertTrue(conflicts.isEmpty(), "No conflict when file exists on only one side");
    }

    @Test
    void findConflicts_noConflict_whenOnlySenderModified() throws IOException {
        // File exists on both sides, content differs, but only sender (local) modified
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);

        Path localFile = localDir.resolve("file.txt");
        Path remoteFile = remoteDir.resolve("file.txt");
        Files.writeString(localFile, "sender modified content");
        Files.writeString(remoteFile, "old content");
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(5000L)); // local newer
        Files.setLastModifiedTime(remoteFile, FileTime.fromMillis(1000L)); // remote older

        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());

        assertTrue(conflicts.isEmpty(), "No conflict when only sender modified - normal transfer");
    }

    @Test
    void findConflicts_noConflict_whenOnlySenderHasFile() throws IOException {
        // Create test files
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);

        // File exists only on sender side
        Files.writeString(localDir.resolve("senderOnly.txt"), "content");
        // remoteDir is empty

        // Generate manifests
        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, true);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, true);

        // Find conflicts
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());

        assertTrue(conflicts.isEmpty(), "No conflict when file exists on only sender");
    }

    @Test
    void findConflicts_detectsConflict_forBinaryFiles() throws IOException {
        // Create test files
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);

        // Create binary-like files (with null bytes); remote has newer mtime
        byte[] localBinary = new byte[] {0x00, 0x01, 0x02};
        byte[] remoteBinary = new byte[] {0x00, 0x03, 0x04};
        Path localPath = localDir.resolve("image.bin");
        Path remotePath = remoteDir.resolve("image.bin");
        Files.write(localPath, localBinary);
        Files.write(remotePath, remoteBinary);
        Files.setLastModifiedTime(localPath, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(remotePath, FileTime.fromMillis(5000L));

        // Generate manifests
        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        // Find conflicts
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());

        assertEquals(1, conflicts.size(), "Should detect conflict for binary file");
        assertTrue(conflicts.get(0).isBinary(), "File should be marked as binary");
    }

    @Test
    void isBinaryExtension_detectsBinaryExtensions() {
        assertTrue(ConflictAnalyzer.isBinaryExtension("image.jpg"));
        assertTrue(ConflictAnalyzer.isBinaryExtension("document.pdf"));
        assertTrue(ConflictAnalyzer.isBinaryExtension("archive.zip"));
        assertTrue(ConflictAnalyzer.isBinaryExtension("video.mp4"));
        assertTrue(ConflictAnalyzer.isBinaryExtension("audio.mp3"));
    }

    @Test
    void isBinaryExtension_detectsTextExtensions() {
        assertFalse(ConflictAnalyzer.isBinaryExtension("file.txt"));
        assertFalse(ConflictAnalyzer.isBinaryExtension("source.java"));
        assertFalse(ConflictAnalyzer.isBinaryExtension("document.json"));
        assertFalse(ConflictAnalyzer.isBinaryExtension("config.xml"));
        assertFalse(ConflictAnalyzer.isBinaryExtension("script.py"));
    }

    @Test
    void isBinaryExtension_handlesEdgeCases() {
        assertFalse(ConflictAnalyzer.isBinaryExtension(null));
        assertFalse(ConflictAnalyzer.isBinaryExtension(""));
        assertFalse(ConflictAnalyzer.isBinaryExtension("noextension"));
        assertFalse(ConflictAnalyzer.isBinaryExtension("file."));
    }

    @Test
    void contentDiffers_detectsDifferentMd5() {
        FileChangeDetector.FileInfo local =
                new FileChangeDetector.FileInfo("path", 100, System.currentTimeMillis(), "abc123");
        FileChangeDetector.FileInfo remote =
                new FileChangeDetector.FileInfo("path", 100, System.currentTimeMillis(), "def456");

        assertTrue(ConflictAnalyzer.contentDiffers(local, remote));
    }

    @Test
    void contentDiffers_detectsSameMd5() {
        FileChangeDetector.FileInfo local =
                new FileChangeDetector.FileInfo("path", 100, System.currentTimeMillis(), "abc123");
        FileChangeDetector.FileInfo remote =
                new FileChangeDetector.FileInfo("path", 100, System.currentTimeMillis(), "abc123");

        assertFalse(ConflictAnalyzer.contentDiffers(local, remote));
    }

    @Test
    void contentDiffers_fallsBackToSizeAndTime() {
        // When MD5 is null (fast mode)
        FileChangeDetector.FileInfo local =
                new FileChangeDetector.FileInfo("path", 100, 1000L, null);
        FileChangeDetector.FileInfo remoteSame =
                new FileChangeDetector.FileInfo("path", 100, 1000L, null);
        FileChangeDetector.FileInfo remoteDiffSize =
                new FileChangeDetector.FileInfo("path", 200, 1000L, null);
        // MODIFY_WINDOW_MS is 3000ms, so need > 3000ms difference
        FileChangeDetector.FileInfo remoteDiffTime =
                new FileChangeDetector.FileInfo("path", 100, 5000L, null);

        assertFalse(
                ConflictAnalyzer.contentDiffers(local, remoteSame),
                "Same size and time should be equal");
        assertTrue(
                ConflictAnalyzer.contentDiffers(local, remoteDiffSize),
                "Different size should differ");
        assertTrue(
                ConflictAnalyzer.contentDiffers(local, remoteDiffTime),
                "Different time beyond window should differ");
    }

    @Test
    void readFileContent_readsFileCorrectly() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        String content = "test content";
        Files.writeString(testFile, content);

        byte[] result = ConflictAnalyzer.readFileContent(testFile.toFile());

        assertNotNull(result);
        assertEquals(content, new String(result));
    }

    @Test
    void readFileContent_returnsNullForNonexistentFile() {
        byte[] result = ConflictAnalyzer.readFileContent(new File("nonexistent.txt"));
        assertNull(result);
    }

    // ========== isLikelyBinary tests ==========

    @Test
    void isLikelyBinary_returnsFalseForNull() {
        assertFalse(ConflictAnalyzer.isLikelyBinary(null));
    }

    @Test
    void isLikelyBinary_returnsFalseForEmpty() {
        assertFalse(ConflictAnalyzer.isLikelyBinary(new byte[0]));
    }

    @Test
    void isLikelyBinary_returnsFalseForPureText() {
        // ASCII text with tabs, newlines, carriage returns - all allowed
        String text = "Hello\t\nWorld\r\nLine3\nLine4\r\n";
        assertFalse(ConflictAnalyzer.isLikelyBinary(text.getBytes()));
    }

    @Test
    void isLikelyBinary_returnsTrueForBinaryData() {
        // Null bytes and high ratio of control chars indicate binary
        byte[] binary = new byte[100];
        for (int i = 0; i < 50; i++) {
            binary[i] = 0; // null byte - non-text
        }
        for (int i = 50; i < 100; i++) {
            binary[i] = 1; // control char - non-text
        }
        assertTrue(ConflictAnalyzer.isLikelyBinary(binary));
    }

    @Test
    void isLikelyBinary_boundaryAt10Percent() {
        // 10% non-text should be false (threshold is > 10%)
        byte[] data = new byte[1000];
        for (int i = 0; i < 100; i++) {
            data[i] = 0; // 10% null bytes
        }
        for (int i = 100; i < 1000; i++) {
            data[i] = 'a'; // text
        }
        assertFalse(ConflictAnalyzer.isLikelyBinary(data), "Exactly 10%% non-text should be false");

        // 11% non-text should be true
        for (int i = 100; i < 110; i++) {
            data[i] = 0; // 11% null bytes
        }
        assertTrue(ConflictAnalyzer.isLikelyBinary(data), "11%% non-text should be true");
    }

    // ========== readFileSample tests ==========

    @Test
    void readFileSample_readsUpTo4096Bytes(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("large.bin");
        byte[] data = new byte[5000];
        for (int i = 0; i < 5000; i++) {
            data[i] = (byte) (i % 256);
        }
        Files.write(testFile, data);

        byte[] sample = ConflictAnalyzer.readFileSample(testFile.toFile());

        assertEquals(4096, sample.length);
    }

    @Test
    void readFileSample_returnsEmptyForNonexistentFile() {
        byte[] sample = ConflictAnalyzer.readFileSample(new File("nonexistent.bin"));
        assertEquals(0, sample.length);
    }

    @Test
    void readFileSample_returnsEmptyForDirectory(@TempDir Path tempDir) {
        byte[] sample = ConflictAnalyzer.readFileSample(tempDir.toFile());
        assertEquals(0, sample.length);
    }

    @Test
    void readFileSample_handlesPartialRead(@TempDir Path tempDir) throws IOException {
        // File smaller than 4096 - should return all content
        Path testFile = tempDir.resolve("small.txt");
        Files.writeString(testFile, "abc");

        byte[] sample = ConflictAnalyzer.readFileSample(testFile.toFile());

        assertEquals(3, sample.length);
        assertEquals("abc", new String(sample));
    }

    // ========== ConflictInfo mutability and getter tests ==========

    @Test
    void conflictInfo_settersAndGetters() throws IOException {
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);

        Path localFile = localDir.resolve("file.txt");
        Path remoteFile = remoteDir.resolve("file.txt");
        Files.writeString(localFile, "local content");
        Files.writeString(remoteFile, "remote content");
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(
                remoteFile, FileTime.fromMillis(5000L)); // remote newer for conflict detection

        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());
        assertEquals(1, conflicts.size());

        ConflictInfo conflict = conflicts.get(0);
        assertEquals("file.txt", conflict.getPath());
        assertFalse(conflict.isBinary());
        assertNotNull(conflict.getLocalInfo());
        assertNotNull(conflict.getRemoteInfo());
        assertNotNull(conflict.getLocalContent());
        assertEquals("local content", conflict.getLocalContentAsString());

        // Test setters and mutable getters
        byte[] remoteContent = "remote content".getBytes();
        conflict.setRemoteContent(remoteContent);
        assertArrayEquals(remoteContent, conflict.getRemoteContent());
        assertEquals("remote content", conflict.getRemoteContentAsString());

        conflict.setMergedContent("merged content");
        assertEquals("merged content", conflict.getMergedContent());

        assertFalse(conflict.isResolved());
        conflict.setResolution(ConflictInfo.Resolution.KEEP_LOCAL);
        assertEquals(ConflictInfo.Resolution.KEEP_LOCAL, conflict.getResolution());
        assertTrue(conflict.isResolved());
    }

    @Test
    void conflictInfo_getMergedContentAsBytes() {
        FileChangeDetector.FileInfo localInfo =
                new FileChangeDetector.FileInfo("test.txt", 10, 0, "abc");
        FileChangeDetector.FileInfo remoteInfo =
                new FileChangeDetector.FileInfo("test.txt", 10, 0, "def");
        ConflictInfo conflict = new ConflictInfo("test.txt", localInfo, remoteInfo, false, null);

        conflict.setMergedContent("merged result");
        byte[] mergedBytes = conflict.getMergedContentAsBytes();

        assertNotNull(mergedBytes);
        assertEquals(
                "merged result", new String(mergedBytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void conflictInfo_toString() {
        FileChangeDetector.FileInfo localInfo =
                new FileChangeDetector.FileInfo("test.txt", 10, 0, "abc");
        FileChangeDetector.FileInfo remoteInfo =
                new FileChangeDetector.FileInfo("test.txt", 10, 0, "def");
        ConflictInfo conflict = new ConflictInfo("test.txt", localInfo, remoteInfo, true, null);

        String str = conflict.toString();
        assertTrue(str.contains("test.txt"));
        assertTrue(str.contains("binary=true"));
        assertTrue(str.contains("resolution=UNRESOLVED"));
    }

    @Test
    void conflictInfo_remoteContentNullHandling() {
        FileChangeDetector.FileInfo localInfo =
                new FileChangeDetector.FileInfo("test.txt", 10, 0, "abc");
        FileChangeDetector.FileInfo remoteInfo =
                new FileChangeDetector.FileInfo("test.txt", 10, 0, "def");
        ConflictInfo conflict = new ConflictInfo("test.txt", localInfo, remoteInfo, false, null);

        // Remote content not set yet
        assertEquals("", conflict.getRemoteContentAsString());
        assertNull(conflict.getMergedContentAsBytes());
    }
}
