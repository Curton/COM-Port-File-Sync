package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ConflictAnalyzer}: the base-state arbitration matrix (R == B is the only
 * non-conflict divergence), the conservative first-sync default without a base, the fast-mode
 * timestamp fallback, and the prefix-shape exemptions.
 */
class ConflictAnalyzerTest {

    @TempDir Path tempDir;

    // ========== arbitration matrix (base + content hashes) ==========

    /**
     * Write {@code localContent} and {@code remoteContent} for the same path and generate both
     * manifests. Timestamps are irrelevant to the outcome — the arbitration is hash-based unless
     * fast mode is on.
     */
    private ConflictSetup setup(String localContent, String remoteContent) throws IOException {
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        Files.writeString(localDir.resolve("file.txt"), localContent);
        Files.writeString(remoteDir.resolve("file.txt"), remoteContent);
        ConflictSetup setup = new ConflictSetup();
        setup.localManifest = FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        setup.remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);
        setup.localDir = localDir;
        return setup;
    }

    /** Fluent helper: find conflicts with a store pre-seeded with the given base md5. */
    private List<ConflictInfo> findConflicts(ConflictSetup setup, String baseMd5)
            throws IOException {
        SyncStateStore store = new SyncStateStore(setup.localDir.resolve("state.json").toFile());
        if (baseMd5 != null) {
            store.confirm(
                    "file.txt", baseMd5, setup.remoteManifest.getFiles().get("file.txt").getSize());
        }
        return ConflictAnalyzer.findConflicts(
                setup.localManifest, setup.remoteManifest, setup.localDir.toFile(), store);
    }

    private static final class ConflictSetup {
        FileChangeDetector.FileManifest localManifest;
        FileChangeDetector.FileManifest remoteManifest;
        Path localDir;

        String localMd5() {
            return localManifest.getFiles().get("file.txt").getMd5();
        }

        String remoteMd5() {
            return remoteManifest.getFiles().get("file.txt").getMd5();
        }
    }

    @Test
    void bothSidesModifiedWithBase_conflicts() throws IOException {
        ConflictSetup setup = setup("local version", "remote version");
        String base =
                FileChangeDetector.manifestMd5("agreed version".getBytes(StandardCharsets.UTF_8));
        // L != B, R != B, L != R: both sides moved away from the agreement.
        List<ConflictInfo> conflicts = findConflicts(setup, base);

        assertEquals(1, conflicts.size(), "both sides modified: conflict");
        ConflictInfo conflict = conflicts.get(0);
        assertEquals("file.txt", conflict.getPath());
        assertFalse(conflict.isBinary(), "Text file should not be marked as binary");
        assertNotNull(conflict.getLocalContent());
    }

    @Test
    void onlySenderModified_normalTransfer_noConflict() throws IOException {
        ConflictSetup setup = setup("sender modified content", "old content");
        // The receiver still holds the agreed-on version: only the sender modified the file.
        List<ConflictInfo> conflicts = findConflicts(setup, setup.remoteMd5());

        assertTrue(
                conflicts.isEmpty(), "only sender modified (R == B): normal transfer, no conflict");
    }

    @Test
    void onlyReceiverModified_conflicts() throws IOException {
        ConflictSetup setup = setup("old content", "receiver modified content");
        // The sender still holds the agreed-on version; pushing it would lose receiver changes.
        List<ConflictInfo> conflicts = findConflicts(setup, setup.localMd5());

        assertEquals(1, conflicts.size(), "only receiver modified (R != B): conflict");
    }

    @Test
    void noBase_firstSync_everyDifferenceConflicts() throws IOException {
        ConflictSetup setup = setup("local version", "remote version");
        List<ConflictInfo> conflicts = findConflicts(setup, null);

        assertEquals(
                1,
                conflicts.size(),
                "without a recorded base the history is unknown: conservatively a conflict");
    }

    @Test
    void identicalContent_neverConflicts() throws IOException {
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        Files.writeString(localDir.resolve("file.txt"), "same content");
        Files.writeString(remoteDir.resolve("file.txt"), "same content");

        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        // Even a missing base cannot manufacture a conflict: content must differ first.
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(
                        localManifest,
                        remoteManifest,
                        localDir.toFile(),
                        new SyncStateStore(localDir.resolve("state.json").toFile()));

        assertTrue(conflicts.isEmpty(), "No conflicts when files are identical");
    }

    @Test
    void onlySenderHasFile_notAConflict() throws IOException {
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        Files.writeString(localDir.resolve("senderOnly.txt"), "content");

        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(
                        localManifest,
                        remoteManifest,
                        localDir.toFile(),
                        new SyncStateStore(localDir.resolve("state.json").toFile()));

        assertTrue(conflicts.isEmpty(), "No conflict when file exists on only sender");
    }

    @Test
    void binaryFile_bothSidesModified_conflicts() throws IOException {
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        byte[] localBinary = new byte[] {0x00, 0x01, 0x02};
        byte[] remoteBinary = new byte[] {0x00, 0x03, 0x04};
        Files.write(localDir.resolve("image.bin"), localBinary);
        Files.write(remoteDir.resolve("image.bin"), remoteBinary);
        Files.setLastModifiedTime(localDir.resolve("image.bin"), FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(remoteDir.resolve("image.bin"), FileTime.fromMillis(5000L));

        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(
                        localManifest,
                        remoteManifest,
                        localDir.toFile(),
                        new SyncStateStore(localDir.resolve("state.json").toFile()));

        assertEquals(1, conflicts.size(), "both sides modified without a base: conflict");
        assertTrue(conflicts.get(0).isBinary(), "File should be marked as binary");
    }

    // ========== fast-mode timestamp fallback ==========

    @Test
    void fastMode_noHashes_fallsBackToTimestampArbitration() throws IOException {
        Path localDir = tempDir.resolve("local");
        Path remoteDir = tempDir.resolve("remote");
        Files.createDirectories(localDir);
        Files.createDirectories(remoteDir);
        // A binary path: quick mode hashes only text files, so both sides lack an md5 and the
        // arbitration has to fall back to the timestamp rule it always had.
        Path localFile = localDir.resolve("image.bin");
        Path remoteFile = remoteDir.resolve("image.bin");
        Files.write(localFile, new byte[] {0x00, 0x01, 0x02});
        Files.write(remoteFile, new byte[] {0x00, 0x03, 0x04});
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(remoteFile, FileTime.fromMillis(5000L));

        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, true);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, true);
        assertNull(localManifest.getFiles().get("image.bin").getMd5(), "fast mode: no hash");

        // Receiver newer than the window: conflict, exactly as before the base arbitration.
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(
                        localManifest,
                        remoteManifest,
                        localDir.toFile(),
                        new SyncStateStore(localDir.resolve("state.json").toFile()));
        assertEquals(
                1,
                conflicts.size(),
                "fast mode with a newer receiver must keep reporting the conflict");

        // Receiver older than the window: no conflict, exactly as before.
        Files.setLastModifiedTime(remoteFile, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(5000L));
        FileChangeDetector.FileManifest localManifest2 =
                FileChangeDetector.generateManifest(localDir.toFile(), false, true);
        FileChangeDetector.FileManifest remoteManifest2 =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, true);
        List<ConflictInfo> conflicts2 =
                ConflictAnalyzer.findConflicts(
                        localManifest2,
                        remoteManifest2,
                        localDir.toFile(),
                        new SyncStateStore(localDir.resolve("state2.json").toFile()));
        assertTrue(
                conflicts2.isEmpty(),
                "fast mode with an older receiver must keep transferring without a dialog");
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

    // ========== exemptPrefixShapedConflicts tests ==========

    /**
     * Receiver holds the first {@code prefixLength} bytes of the sender's binary file; with no base
     * recorded this classifies as a conflict (first-sync semantics) before the exemption runs.
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
        Files.setLastModifiedTime(localFile, FileTime.fromMillis(1000L));
        Files.setLastModifiedTime(remoteFile, FileTime.fromMillis(6000L));

        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(localDir.toFile(), false, false);
        FileChangeDetector.FileManifest remoteManifest =
                FileChangeDetector.generateManifest(remoteDir.toFile(), false, false);

        return ConflictAnalyzer.findConflicts(
                localManifest,
                remoteManifest,
                localDir.toFile(),
                new SyncStateStore(localDir.resolve("state.json").toFile()));
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
        // Same sizes, but the receiver's bytes differ inside the prefix: a genuine modification.
        // An equal-size receiver file is not an append shape either, so the conflict must stay.
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
                ConflictAnalyzer.findConflicts(
                        localManifest,
                        remoteManifest,
                        localDir.toFile(),
                        new SyncStateStore(localDir.resolve("state.json").toFile()));
        assertEquals(1, conflicts.size());

        Set<String> exempted =
                ConflictAnalyzer.exemptPrefixShapedConflicts(conflicts, localDir.toFile());

        assertEquals(
                1,
                conflicts.size(),
                "non-prefix conflict must be kept (an equal-size receiver file is not a prefix"
                        + " shape)");
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
                ConflictAnalyzer.findConflicts(
                        localManifest,
                        remoteManifest,
                        localDir.toFile(),
                        new SyncStateStore(localDir.resolve("state.json").toFile()));
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
                ConflictAnalyzer.findConflicts(
                        localManifest,
                        remoteManifest,
                        localDir.toFile(),
                        new SyncStateStore(localDir.resolve("state.json").toFile()));
        assertEquals(1, conflicts.size());

        Set<String> exempted =
                ConflictAnalyzer.exemptPrefixShapedConflicts(conflicts, localDir.toFile());

        assertEquals(1, conflicts.size(), "text conflicts are never exempted");
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
