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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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

    @Test
    void findConflicts_contentLoadedOnDemand_lazyLoading(@TempDir Path tempDir) throws IOException {
        // Verify that content is loaded lazily from disk (not eagerly in findConflicts)
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);

        Path localFile = localDir.resolve("file.txt");
        Path remoteFile = remoteDir.resolve("file.txt");
        Files.writeString(localFile, "local version");
        Files.writeString(remoteFile, "remote version");
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(remoteFile, FileTime.fromMillis(5000L));

        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());
        assertEquals(1, conflicts.size());

        // Content loaded on first access
        assertEquals("local version", conflicts.get(0).getLocalContentAsString());
    }

    // ========== exemptPrefixShapedConflicts tests ==========

    /**
     * Receiver holds the first 64 bytes of the sender's 96-byte binary file, with a newer mtime.
     */
    private List<ConflictInfo> createBinaryPrefixConflict(
            Path localDir, Path remoteDir, byte[] fullContent, int prefixLength)
            throws IOException {
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);

        byte[] prefix = Arrays.copyOf(fullContent, prefixLength);
        Path localFile = localDir.resolve("archive.gz");
        Path remoteFile = remoteDir.resolve("archive.gz");
        Files.write(localFile, fullContent);
        Files.write(remoteFile, prefix);
        // Receiver's copy is newer (e.g. an outside-the-sync copy made after the sender's file)
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(remoteFile, FileTime.fromMillis(6000L));

        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        return ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());
    }

    @Test
    void exemptPrefixShapedConflicts_exemptsVerifiedPrefix(@TempDir Path tempDir)
            throws IOException {
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        // Binary-looking content so md5 is raw bytes on both sides
        byte[] fullContent = new byte[96];
        for (int i = 0; i < fullContent.length; i++) {
            fullContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x41 + (i % 26));
        }
        List<ConflictInfo> conflicts =
                createBinaryPrefixConflict(localDir, remoteDir, fullContent, 64);
        assertEquals(1, conflicts.size(), "prefix copy must be classified as a conflict first");
        assertTrue(conflicts.get(0).isBinary());

        Set<String> exempted =
                ConflictAnalyzer.exemptPrefixShapedConflicts(conflicts, localDir.toFile());

        assertTrue(conflicts.isEmpty(), "verified prefix must be exempted from the conflict path");
        assertEquals(1, exempted.size());
        assertTrue(exempted.contains("archive.gz"));
    }

    @Test
    void exemptPrefixShapedConflicts_keepsNonPrefixConflict(@TempDir Path tempDir)
            throws IOException {
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        // Same sizes, but the receiver's bytes differ inside the prefix: a genuine modification
        byte[] localContent = new byte[96];
        byte[] remoteContent = new byte[96];
        for (int i = 0; i < localContent.length; i++) {
            localContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x41 + (i % 26));
            remoteContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x61 + (i % 26));
        }
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        Path localFile = localDir.resolve("archive.gz");
        Path remoteFile = remoteDir.resolve("archive.gz");
        Files.write(localFile, localContent);
        Files.write(remoteFile, remoteContent);
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(remoteFile, FileTime.fromMillis(6000L));
        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());
        assertEquals(1, conflicts.size());

        Set<String> exempted =
                ConflictAnalyzer.exemptPrefixShapedConflicts(conflicts, localDir.toFile());

        assertEquals(1, conflicts.size(), "non-prefix conflict must be kept");
        assertTrue(exempted.isEmpty());
    }

    @Test
    void exemptPrefixShapedConflicts_keepsConflictWithoutReceiverMd5(@TempDir Path tempDir)
            throws IOException {
        // Fast mode leaves binary files unhashed: without a receiver md5 the prefix cannot be
        // verified, so the conflict must stay.
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        byte[] fullContent = new byte[96];
        byte[] prefix = new byte[64];
        for (int i = 0; i < fullContent.length; i++) {
            fullContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x41 + (i % 26));
        }
        System.arraycopy(fullContent, 0, prefix, 0, prefix.length);
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        Path localFile = localDir.resolve("archive.gz");
        Path remoteFile = remoteDir.resolve("archive.gz");
        Files.write(localFile, fullContent);
        Files.write(remoteFile, prefix);
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(remoteFile, FileTime.fromMillis(6000L));
        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, true);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, true);
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());
        assertEquals(1, conflicts.size());
        assertNull(conflicts.get(0).getRemoteInfo().getMd5(), "fast mode: no binary md5");

        Set<String> exempted =
                ConflictAnalyzer.exemptPrefixShapedConflicts(conflicts, localDir.toFile());

        assertEquals(1, conflicts.size(), "unverifiable conflict must be kept");
        assertTrue(exempted.isEmpty());
    }

    @Test
    void exemptPrefixShapedConflicts_keepsTextConflicts(@TempDir Path tempDir) throws IOException {
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        // Receiver holds a textual prefix, but text conflicts keep the merge flow
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        Path localFile = localDir.resolve("notes.txt");
        Path remoteFile = remoteDir.resolve("notes.txt");
        Files.writeString(localFile, "line1\nline2\nline3\n");
        Files.writeString(remoteFile, "line1\nline2\n");
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(remoteFile, FileTime.fromMillis(6000L));
        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());
        assertEquals(1, conflicts.size());

        Set<String> exempted =
                ConflictAnalyzer.exemptPrefixShapedConflicts(conflicts, localDir.toFile());

        assertEquals(1, conflicts.size(), "text conflicts are never exempted");
        assertTrue(exempted.isEmpty());
    }

    @Test
    void exemptPrefixShapedConflicts_keepsEqualOrEmptyReceiverFiles(@TempDir Path tempDir)
            throws IOException {
        // Receiver file same size as sender's: not an append shape
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        byte[] localContent = new byte[96];
        byte[] remoteContent = new byte[96];
        for (int i = 0; i < localContent.length; i++) {
            localContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x41 + (i % 26));
            remoteContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x61 + (i % 26));
        }
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        Path localFile = localDir.resolve("archive.gz");
        Path remoteFile = remoteDir.resolve("archive.gz");
        Files.write(localFile, localContent);
        Files.write(remoteFile, remoteContent);
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(remoteFile, FileTime.fromMillis(6000L));
        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());
        assertEquals(1, conflicts.size());

        Set<String> exempted =
                ConflictAnalyzer.exemptPrefixShapedConflicts(conflicts, localDir.toFile());

        assertEquals(1, conflicts.size(), "equal-size receiver file is not a prefix shape");
        assertTrue(exempted.isEmpty());
    }

    // ========== findPrefixShapedDeltaCandidates tests ==========

    @Test
    void findPrefixShapedDeltaCandidates_reportsVerifiedAppend(@TempDir Path tempDir)
            throws IOException {
        // The receiver's copy is the first 64 bytes of the sender's 96-byte file: a pure append
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        byte[] fullContent = new byte[96];
        for (int i = 0; i < fullContent.length; i++) {
            fullContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x41 + (i % 26));
        }
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        Files.write(localDir.resolve("archive.gz"), fullContent);
        Files.write(remoteDir.resolve("archive.gz"), Arrays.copyOf(fullContent, 64));
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        Set<String> appendShaped =
                ConflictAnalyzer.findPrefixShapedDeltaCandidates(
                        Set.of("archive.gz", "absent.bin"), remoteManifest, localDir.toFile());

        assertEquals(Set.of("archive.gz"), appendShaped);
    }

    @Test
    void findPrefixShapedDeltaCandidates_rejectsMidFileEdit(@TempDir Path tempDir)
            throws IOException {
        // The receiver's bytes differ inside the prefix: a genuine modification, not a tail
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        byte[] localContent = new byte[96];
        byte[] remoteContent = new byte[64];
        for (int i = 0; i < localContent.length; i++) {
            localContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x41 + (i % 26));
            if (i < remoteContent.length) {
                remoteContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x61 + (i % 26));
            }
        }
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        Files.write(localDir.resolve("archive.gz"), localContent);
        Files.write(remoteDir.resolve("archive.gz"), remoteContent);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        Set<String> appendShaped =
                ConflictAnalyzer.findPrefixShapedDeltaCandidates(
                        Set.of("archive.gz"), remoteManifest, localDir.toFile());

        assertTrue(appendShaped.isEmpty(), "mid-file difference is not an append shape");
    }

    @Test
    void findPrefixShapedDeltaCandidates_skipsFastModeWithoutReceiverMd5(@TempDir Path tempDir)
            throws IOException {
        // Fast mode leaves binary files unhashed: without a receiver md5 the shape is unverifiable
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        byte[] fullContent = new byte[96];
        for (int i = 0; i < fullContent.length; i++) {
            fullContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x41 + (i % 26));
        }
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        Files.write(localDir.resolve("archive.gz"), fullContent);
        Files.write(remoteDir.resolve("archive.gz"), Arrays.copyOf(fullContent, 64));
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, true);

        Set<String> appendShaped =
                ConflictAnalyzer.findPrefixShapedDeltaCandidates(
                        Set.of("archive.gz"), remoteManifest, localDir.toFile());

        assertTrue(appendShaped.isEmpty());
    }

    @Test
    void findPrefixShapedDeltaCandidates_skipsEqualSizeReceiverFile(@TempDir Path tempDir)
            throws IOException {
        // Same size on both sides: nothing to append
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        byte[] localContent = new byte[96];
        byte[] remoteContent = new byte[96];
        for (int i = 0; i < localContent.length; i++) {
            localContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x41 + (i % 26));
            remoteContent[i] = (byte) (i % 2 == 0 ? 0x00 : 0x61 + (i % 26));
        }
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        Files.write(localDir.resolve("archive.gz"), localContent);
        Files.write(remoteDir.resolve("archive.gz"), remoteContent);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        Set<String> appendShaped =
                ConflictAnalyzer.findPrefixShapedDeltaCandidates(
                        Set.of("archive.gz"), remoteManifest, localDir.toFile());

        assertTrue(appendShaped.isEmpty());
    }
}
