package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.sync.FileChangeDetector.FileInfo;
import com.filesync.sync.FileChangeDetector.FileManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mirror-mode behaviour for paths that differ between the two sides only by letter case.
 *
 * <p>On a case-insensitive filesystem (Windows, macOS default volumes, most vfat mounts) {@code
 * Foo.txt} and {@code foo.txt} name the same file. A pure case rename must therefore never be
 * planned as "transfer the new spelling, delete the old one": the delete resolves to the very file
 * the transfer has just written, so the receiver loses the file while the sender keeps it and the
 * next sync repeats the whole thing forever.
 *
 * <p>On a case-sensitive filesystem the two spellings are two different entries, which is why the
 * receiver's filesystem semantics decide the plan, and why a genuine rename there must still be
 * mirrored as transfer + delete.
 */
class FileChangeDetectorCaseRenameTest {

    @TempDir Path tempDir;

    // --- fixture helpers ---------------------------------------------------

    private static FileInfo file(String path, long size, String md5) {
        return new FileInfo(path, size, 0L, md5);
    }

    private static Map<String, FileInfo> files(FileInfo... entries) {
        Map<String, FileInfo> map = new HashMap<>();
        for (FileInfo entry : entries) {
            map.put(entry.getPath(), entry);
        }
        return map;
    }

    private static FileManifest manifest(boolean caseSensitive, Map<String, FileInfo> files) {
        return new FileManifest(files, new HashSet<>(), caseSensitive);
    }

    private static FileManifest emptyDirs(boolean caseSensitive, String... dirs) {
        return new FileManifest(new HashMap<>(), new HashSet<>(Set.of(dirs)), caseSensitive);
    }

    private static List<String> paths(List<FileInfo> infos) {
        return infos.stream().map(FileInfo::getPath).toList();
    }

    // --- the bug -----------------------------------------------------------

    @Test
    void caseOnlyRenameNeverPairsATransferWithTheDeleteOfTheSameFile() {
        // Sender renamed Foo.txt to foo.txt and edited it on the way.
        FileManifest sender = manifest(false, files(file("foo.txt", 11, "new")));
        FileManifest receiver = manifest(false, files(file("Foo.txt", 10, "old")));

        List<String> toSync = paths(FileChangeDetector.getChangedFiles(sender, receiver));
        List<String> toDelete = FileChangeDetector.getFilesToDelete(sender, receiver);

        assertEquals(List.of("foo.txt"), toSync, "The new spelling still has to be transferred");
        assertTrue(
                toDelete.isEmpty(),
                "Deleting Foo.txt on a case-insensitive receiver deletes the file the transfer"
                        + " just wrote; planned deletes were "
                        + toDelete);
    }

    @Test
    void caseOnlyRenameConvergesWhenTheContentAlreadyMatches() {
        FileManifest sender = manifest(false, files(file("foo.txt", 10, "same")));
        FileManifest receiver = manifest(false, files(file("Foo.txt", 10, "same")));

        assertTrue(
                FileChangeDetector.getChangedFiles(sender, receiver).isEmpty(),
                "The receiver's Foo.txt is the sender's foo.txt, so nothing is missing there");
        assertTrue(
                FileChangeDetector.getFilesToDelete(sender, receiver).isEmpty(),
                "The sender still has the file, so it is not an obsolete receiver-only path");

        // A path nested below the root must converge the same way as a root-level one.
        sender = manifest(false, files(file("docs/Readme.md", 10, "same")));
        receiver = manifest(false, files(file("docs/README.md", 10, "same")));

        assertTrue(FileChangeDetector.getChangedFiles(sender, receiver).isEmpty());
        assertTrue(FileChangeDetector.getFilesToDelete(sender, receiver).isEmpty());
    }

    // --- guards: the case-sensitive filesystem is unaffected ----------------

    @Test
    void caseOnlyRenameIsStillMirroredOnCaseSensitiveFilesystem() {
        FileManifest sender = manifest(true, files(file("foo.txt", 10, "same")));
        FileManifest receiver = manifest(true, files(file("Foo.txt", 10, "same")));

        assertEquals(
                List.of("foo.txt"),
                paths(FileChangeDetector.getChangedFiles(sender, receiver)),
                "Two entries on a case-sensitive receiver: the new spelling is a new file");
        assertEquals(
                List.of("Foo.txt"),
                FileChangeDetector.getFilesToDelete(sender, receiver),
                "The old spelling is genuinely gone from the sender and must be mirrored out");
    }

    @Test
    void genuineDeletionIsStillPlannedOnCaseInsensitiveFilesystem() {
        FileManifest sender = manifest(false, files(file("kept.txt", 10, "same")));
        FileManifest receiver =
                manifest(false, files(file("kept.txt", 10, "same"), file("gone.txt", 4, "old")));

        assertEquals(List.of("gone.txt"), FileChangeDetector.getFilesToDelete(sender, receiver));
    }

    // --- directories: rmdir is recursive, so the same rule has to hold ------

    @Test
    void caseOnlyDirectoryRenameIsMirroredOnlyOnCaseSensitiveFilesystem() {
        // Case-insensitive receiver: Bin and bin are one and the same directory.
        FileManifest sender = emptyDirs(false, "bin");
        FileManifest receiver = emptyDirs(false, "Bin");

        assertTrue(
                FileChangeDetector.getEmptyDirectoriesToDelete(sender, receiver).isEmpty(),
                "Bin and bin are the same directory on the receiver");
        assertTrue(
                FileChangeDetector.getEmptyDirectoriesToCreate(sender, receiver).isEmpty(),
                "The receiver already has that directory, under the other spelling");

        // Case-sensitive receiver: two entries, so the rename is mirrored both ways.
        sender = emptyDirs(true, "bin");
        receiver = emptyDirs(true, "Bin");

        assertEquals(
                List.of("Bin"), FileChangeDetector.getEmptyDirectoriesToDelete(sender, receiver));
        assertEquals(
                List.of("bin"), FileChangeDetector.getEmptyDirectoriesToCreate(sender, receiver));
    }

    @Test
    void directorySpelledDifferentlyIsNotDeletedWhileTheSenderFillsIt() {
        // Receiver still has an empty Bin/ while the sender has bin/x.txt. On a case-insensitive
        // receiver those are one directory, and rmdir is recursive: the delete would wipe the file
        // the transfer has just written into it.
        FileManifest sender = manifest(false, files(file("bin/x.txt", 5, "hash")));
        FileManifest receiver = emptyDirs(false, "Bin");

        assertTrue(FileChangeDetector.getEmptyDirectoriesToDelete(sender, receiver).isEmpty());
    }

    // --- the filesystem semantics have to travel with the manifest ---------

    @Test
    void manifestJsonCarriesFilesystemCaseSensitivity() {
        Map<String, FileInfo> entries = files(file("a.txt", 1, "hash"));
        String caseSensitiveJson =
                FileChangeDetector.manifestToJson(new FileManifest(entries, new HashSet<>(), true));
        String caseInsensitiveJson =
                FileChangeDetector.manifestToJson(
                        new FileManifest(entries, new HashSet<>(), false));

        assertTrue(FileChangeDetector.manifestFromJson(caseSensitiveJson).isCaseSensitive());
        assertFalse(FileChangeDetector.manifestFromJson(caseInsensitiveJson).isCaseSensitive());
    }

    @Test
    void manifestWithoutCaseSensitivityFallsBackToTheSafeAnswer() {
        // A manifest that does not say how its filesystem treats letter case must not be assumed
        // case-sensitive: that is the assumption that deletes data. Unknown means "the two
        // spellings may be one file".
        FileManifest parsed =
                FileChangeDetector.manifestFromJson(
                        "{\"files\":{},\"emptyDirectories\":[],\"schemaVersion\":2}");

        assertFalse(parsed.isCaseSensitive());
    }

    @Test
    void generatedManifestReportsTheCaseSensitivityOfItsFolder() throws IOException {
        Files.writeString(tempDir.resolve("Sample.txt"), "content");
        boolean folderIsCaseSensitive = probeCaseSensitivityIndependently(tempDir);

        FileManifest manifest = FileChangeDetector.generateManifest(tempDir.toFile());

        assertEquals(folderIsCaseSensitive, manifest.isCaseSensitive());
    }

    @Test
    void caseSensitivityProbeLeavesNoFileBehind() throws IOException {
        Files.writeString(tempDir.resolve("Sample.txt"), "content");

        FileChangeDetector.isCaseSensitiveFileSystem(tempDir.toFile());
        FileChangeDetector.generateManifest(tempDir.toFile());

        try (Stream<Path> entries = Files.list(tempDir)) {
            assertEquals(
                    List.of("Sample.txt"),
                    entries.map(path -> path.getFileName().toString()).sorted().toList(),
                    "The probe must clean up after itself, or it would sync as user content");
        }
    }

    @Test
    void leftoverCaseProbeFileIsNeverSyncedAsUserContent() throws IOException {
        // A folder being scanned by an indexer or antivirus can refuse the probe's delete. A
        // leftover probe is this class's own bookkeeping, not something to publish to the peer.
        Files.writeString(
                tempDir.resolve(FileChangeDetector.CASE_PROBE_PREFIX + "1234abcdAb"), "probe");
        Files.writeString(tempDir.resolve("real.txt"), "real");

        FileManifest manifest = FileChangeDetector.generateManifest(tempDir.toFile());

        assertEquals(List.of("real.txt"), manifest.getFiles().keySet().stream().sorted().toList());
    }

    // --- diagnostics -------------------------------------------------------

    @Test
    void caseOnlyRenamesAreReportedForTheSender() {
        FileManifest sender =
                manifest(false, files(file("foo.txt", 10, "same"), file("clean.txt", 1, "hash")));
        FileManifest receiver =
                manifest(false, files(file("Foo.txt", 10, "same"), file("clean.txt", 1, "hash")));

        assertEquals(
                List.of("foo.txt"), FileChangeDetector.findCaseOnlyRenamePaths(sender, receiver));
    }

    @Test
    void caseOnlyRenamesAreNotReportedOnCaseSensitiveFilesystem() {
        FileManifest sender = manifest(true, files(file("foo.txt", 10, "same")));
        FileManifest receiver = manifest(true, files(file("Foo.txt", 10, "same")));

        assertTrue(FileChangeDetector.findCaseOnlyRenamePaths(sender, receiver).isEmpty());
    }

    /**
     * Whether {@code directory} distinguishes paths by letter case, measured without going through
     * the production probe, so the assertions above compare two independent answers.
     */
    private static boolean probeCaseSensitivityIndependently(Path directory) throws IOException {
        Path probe = directory.resolve("CaseProbeFile.txt");
        Files.writeString(probe, "probe");
        try {
            return !Files.exists(directory.resolve("caseprobefile.txt"));
        } finally {
            Files.deleteIfExists(probe);
        }
    }
}
