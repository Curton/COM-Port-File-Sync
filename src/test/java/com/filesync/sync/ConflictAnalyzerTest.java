package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictAnalyzerTest {

    @TempDir
    Path tempDir;

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
        FileChangeDetector.FileManifest localManifest = FileChangeDetector.generateManifest(localDir.toFile(), false, true);
        FileChangeDetector.FileManifest remoteManifest = FileChangeDetector.generateManifest(remoteDir.toFile(), false, true);

        // Find conflicts
        List<ConflictInfo> conflicts = ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());

        assertTrue(conflicts.isEmpty(), "No conflicts when files are identical");
    }

    @Test
    void findConflicts_detectsConflict_whenBothSidesModified() throws IOException {
        // Create test files
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);

        // Same file but different content on each side
        Files.writeString(localDir.resolve("file.txt"), "local version");
        Files.writeString(remoteDir.resolve("file.txt"), "remote version");

        // Generate manifests (fast mode to use MD5)
        FileChangeDetector.FileManifest localManifest = FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest = FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        // Find conflicts
        List<ConflictInfo> conflicts = ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());

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
        FileChangeDetector.FileManifest localManifest = FileChangeDetector.generateManifest(localDir.toFile(), false, true);
        FileChangeDetector.FileManifest remoteManifest = FileChangeDetector.generateManifest(remoteDir.toFile(), false, true);

        // Find conflicts
        List<ConflictInfo> conflicts = ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());

        assertTrue(conflicts.isEmpty(), "No conflict when file exists on only one side");
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
        FileChangeDetector.FileManifest localManifest = FileChangeDetector.generateManifest(localDir.toFile(), false, true);
        FileChangeDetector.FileManifest remoteManifest = FileChangeDetector.generateManifest(remoteDir.toFile(), false, true);

        // Find conflicts
        List<ConflictInfo> conflicts = ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());

        assertTrue(conflicts.isEmpty(), "No conflict when file exists on only sender");
    }

    @Test
    void findConflicts_detectsConflict_forBinaryFiles() throws IOException {
        // Create test files
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);

        // Create binary-like files (with null bytes)
        byte[] localBinary = new byte[]{0x00, 0x01, 0x02};
        byte[] remoteBinary = new byte[]{0x00, 0x03, 0x04};
        Files.write(localDir.resolve("image.bin"), localBinary);
        Files.write(remoteDir.resolve("image.bin"), remoteBinary);

        // Generate manifests
        FileChangeDetector.FileManifest localManifest = FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest = FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        // Find conflicts
        List<ConflictInfo> conflicts = ConflictAnalyzer.findConflicts(localManifest, remoteManifest, localDir.toFile());

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
        FileChangeDetector.FileInfo local = new FileChangeDetector.FileInfo("path", 100, System.currentTimeMillis(), "abc123");
        FileChangeDetector.FileInfo remote = new FileChangeDetector.FileInfo("path", 100, System.currentTimeMillis(), "def456");

        assertTrue(ConflictAnalyzer.contentDiffers(local, remote));
    }

    @Test
    void contentDiffers_detectsSameMd5() {
        FileChangeDetector.FileInfo local = new FileChangeDetector.FileInfo("path", 100, System.currentTimeMillis(), "abc123");
        FileChangeDetector.FileInfo remote = new FileChangeDetector.FileInfo("path", 100, System.currentTimeMillis(), "abc123");

        assertFalse(ConflictAnalyzer.contentDiffers(local, remote));
    }

    @Test
    void contentDiffers_fallsBackToSizeAndTime() {
        // When MD5 is null (fast mode)
        FileChangeDetector.FileInfo local = new FileChangeDetector.FileInfo("path", 100, 1000L, null);
        FileChangeDetector.FileInfo remoteSame = new FileChangeDetector.FileInfo("path", 100, 1000L, null);
        FileChangeDetector.FileInfo remoteDiffSize = new FileChangeDetector.FileInfo("path", 200, 1000L, null);
        // MODIFY_WINDOW_MS is 3000ms, so need > 3000ms difference
        FileChangeDetector.FileInfo remoteDiffTime = new FileChangeDetector.FileInfo("path", 100, 5000L, null);

        assertFalse(ConflictAnalyzer.contentDiffers(local, remoteSame), "Same size and time should be equal");
        assertTrue(ConflictAnalyzer.contentDiffers(local, remoteDiffSize), "Different size should differ");
        assertTrue(ConflictAnalyzer.contentDiffers(local, remoteDiffTime), "Different time beyond window should differ");
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
}