package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-side .filesyncignore: the scan excludes its paths and the file itself from the manifest
 * and reports what it skipped, so the peer can stop transferring into ignored trees; the diff
 * guards exempt ignored paths from mirror-mode deletion and keep them out of rename pairing.
 */
class FileSyncIgnoreTest {

    @TempDir Path tempDir;

    // --- Parsing -----------------------------------------------------------------------------

    @Test
    void rootFileOnlyIgnoresNestedCopiesOfTheSameFileName() throws IOException {
        writeFile(tempDir.resolve(".filesyncignore"), "/secret.key", "cache/");
        Files.createDirectories(tempDir.resolve("sub"));
        // A nested copy is ordinary content with no special meaning, so it must not be loaded.
        writeFile(tempDir.resolve("sub/.filesyncignore"), "everything");

        GitignoreParser parser =
                new GitignoreParser(tempDir.toFile(), GitignoreParser.FILESYNC_IGNORE_FILENAME);
        parser.loadRootFileOnly();

        assertTrue(parser.isIgnored("secret.key", false), "Root patterns must apply");
        assertFalse(
                parser.isIgnored("sub/everything", false),
                "A nested .filesyncignore must be ignored as a file, not honored as rules");
    }

    @Test
    void ancestorCheckMakesDirectoryPatternsCoverFilesBeneathThem() throws IOException {
        writeFile(tempDir.resolve(".filesyncignore"), "/build/", "/exact.log");

        GitignoreParser parser =
                new GitignoreParser(tempDir.toFile(), GitignoreParser.FILESYNC_IGNORE_FILENAME);
        parser.loadRootFileOnly();

        // A directory-only pattern never matches the file path directly (that is gitignore
        // semantics), yet the file under it must count as ignored or the deletion guard would
        // let strict sync remove it on the receiver.
        assertFalse(
                parser.isIgnored("build/out.bin", false),
                "Directory-only pattern must not match a file path directly");
        assertTrue(
                parser.isIgnoredWithAncestors("build/out.bin", false),
                "File beneath an ignored directory must count as ignored");
        assertTrue(
                parser.isIgnoredWithAncestors("build/deep/out.bin", false),
                "The ancestor check must descend more than one level");
        assertTrue(
                parser.isIgnoredWithAncestors("exact.log", false),
                "A matching file pattern must still be honored");
        assertFalse(
                parser.isIgnoredWithAncestors("buildx/out.bin", false),
                "A prefix that is not a path segment must not count");
    }

    // --- Scanning ----------------------------------------------------------------------------

    @Test
    void manifestExcludesIgnoredPathsAndTheIgnoreFileItself() throws IOException {
        writeFile(tempDir.resolve(".filesyncignore"), "/skip.log", "/build/", "/ignoredir/");
        writeFile(tempDir.resolve("keep.txt"), "keep");
        writeFile(tempDir.resolve("skip.log"), "skip");
        Files.createDirectories(tempDir.resolve("build"));
        writeFile(tempDir.resolve("build/out.bin"), "bin");
        Files.createDirectories(tempDir.resolve("ignoredir"));
        Files.createDirectories(tempDir.resolve("emptydir"));

        FileChangeDetector.FileManifest manifest =
                FileChangeDetector.generateManifest(tempDir.toFile(), false, false);

        assertTrue(manifest.getFiles().containsKey("keep.txt"), "Normal file must be scanned");
        assertFalse(
                manifest.getFiles().containsKey("skip.log"),
                "Ignored file must be excluded from the manifest");
        assertFalse(
                manifest.getFiles().containsKey("build/out.bin"),
                "File beneath an ignored directory must be excluded");
        assertFalse(
                manifest.getFiles().containsKey(".filesyncignore"),
                "The ignore file itself never travels");
        assertTrue(
                manifest.getEmptyDirectories().contains("emptydir"),
                "A normal empty directory must be reported");
        assertFalse(
                manifest.getEmptyDirectories().contains("ignoredir"),
                "An ignored empty directory must be excluded");
        assertFalse(
                manifest.getEmptyDirectories().contains("build"),
                "An ignored non-empty directory must be excluded");

        // The skipped roots are reported so the peer can stop transferring into them.
        assertEquals(Set.of("skip.log"), manifest.getIgnoredFiles());
        assertEquals(Set.of("build", "ignoredir"), manifest.getIgnoredDirectories());
    }

    @Test
    void filesyncIgnoreAppliesIndependentlyOfTheGitignoreToggle() throws IOException {
        writeFile(tempDir.resolve(".filesyncignore"), "/syncside.log");
        writeFile(tempDir.resolve(".gitignore"), "gitside.log");
        writeFile(tempDir.resolve("syncside.log"), "x");
        writeFile(tempDir.resolve("gitside.log"), "x");
        writeFile(tempDir.resolve("keep.txt"), "x");

        FileChangeDetector.FileManifest off =
                FileChangeDetector.generateManifest(tempDir.toFile(), false, false);
        assertFalse(
                off.getFiles().containsKey("syncside.log"), ".filesyncignore must always apply");
        assertTrue(
                off.getFiles().containsKey("gitside.log"),
                ".gitignore must stay off while its toggle is off");

        FileChangeDetector.FileManifest on =
                FileChangeDetector.generateManifest(tempDir.toFile(), true, false);
        assertFalse(on.getFiles().containsKey("syncside.log"), ".filesyncignore must still apply");
        assertFalse(on.getFiles().containsKey("gitside.log"), ".gitignore must apply when on");
        assertTrue(on.getFiles().containsKey("keep.txt"), "Unmatched files must survive both");
    }

    // --- Wire/cache format -------------------------------------------------------------------

    @Test
    void ignoredSetsSurviveTheJsonRoundTripAndDefaultToEmpty() {
        FileChangeDetector.FileManifest manifest =
                new FileChangeDetector.FileManifest(
                        new HashMap<>(),
                        new HashSet<>(),
                        true,
                        new HashSet<>(List.of("a.log")),
                        new HashSet<>(List.of("build")));

        FileChangeDetector.FileManifest back =
                FileChangeDetector.manifestFromJson(FileChangeDetector.manifestToJson(manifest));

        assertEquals(Set.of("a.log"), back.getIgnoredFiles());
        assertEquals(Set.of("build"), back.getIgnoredDirectories());

        FileChangeDetector.FileManifest legacy =
                FileChangeDetector.manifestFromJson("{\"files\":{},\"schemaVersion\":2}");
        assertTrue(legacy.getIgnoredFiles().isEmpty(), "A payload without the fields reads empty");
        assertTrue(legacy.getIgnoredDirectories().isEmpty());
    }

    // --- Deletion exemption ------------------------------------------------------------------

    @Test
    void senderIgnoredPathsAreExemptFromMirrorDeletion() throws IOException {
        writeFile(tempDir.resolve(".filesyncignore"), "/keep-me.log", "/build/");
        GitignoreParser rules =
                new GitignoreParser(tempDir.toFile(), GitignoreParser.FILESYNC_IGNORE_FILENAME);
        rules.loadRootFileOnly();
        Predicate<String> guard = path -> rules.isIgnoredWithAncestors(path, false);

        // The receiver holds everything; the sender's manifest is empty, so strict mode would
        // delete all three paths without the guard.
        Map<String, FileChangeDetector.FileInfo> receiverFiles = new HashMap<>();
        receiverFiles.put("keep-me.log", info("keep-me.log"));
        receiverFiles.put("build/out.bin", info("build/out.bin"));
        receiverFiles.put("ordinary.log", info("ordinary.log"));
        FileChangeDetector.FileManifest sender =
                new FileChangeDetector.FileManifest(new HashMap<>(), new HashSet<>(), true);
        FileChangeDetector.FileManifest receiver =
                new FileChangeDetector.FileManifest(receiverFiles, new HashSet<>(), true);

        List<String> toDelete = FileChangeDetector.getFilesToDelete(sender, receiver, guard);

        assertEquals(List.of("ordinary.log"), toDelete, "Only the unignored path is deleted");

        // The unguarded overload is the old behavior: everything goes.
        assertEquals(
                3,
                FileChangeDetector.getFilesToDelete(sender, receiver).size(),
                "Without the guard strict mode deletes everything the sender lacks");
    }

    @Test
    void senderIgnoredDirectoriesAreExemptFromEmptyDirDeletion() {
        FileChangeDetector.FileManifest sender =
                new FileChangeDetector.FileManifest(new HashMap<>(), new HashSet<>(), true);
        Set<String> receiverEmptyDirs = new HashSet<>(List.of("ignoredir", "normaldir"));
        FileChangeDetector.FileManifest receiver =
                new FileChangeDetector.FileManifest(new HashMap<>(), receiverEmptyDirs, true);

        List<String> dirsToDelete =
                FileChangeDetector.getEmptyDirectoriesToDelete(
                        sender, receiver, dir -> dir.equals("ignoredir"));

        assertEquals(List.of("normaldir"), dirsToDelete);
    }

    // --- Rename pairing ----------------------------------------------------------------------

    @Test
    void renamesNeverConsumeOrProduceIgnoredPaths() {
        String md5 = "same-content";
        Map<String, FileChangeDetector.FileInfo> senderFiles = new HashMap<>();
        senderFiles.put("new.txt", new FileChangeDetector.FileInfo("new.txt", 1L, 1L, md5));
        Map<String, FileChangeDetector.FileInfo> receiverFiles = new HashMap<>();
        receiverFiles.put("old.txt", new FileChangeDetector.FileInfo("old.txt", 1L, 1L, md5));
        FileChangeDetector.FileManifest sender =
                new FileChangeDetector.FileManifest(senderFiles, new HashSet<>(), true);
        FileChangeDetector.FileManifest receiver =
                new FileChangeDetector.FileManifest(receiverFiles, new HashSet<>(), true);

        assertFalse(
                FileChangeDetector.findRenames(sender, receiver).isEmpty(),
                "The scenario must be a rename without any ignore rules");

        assertTrue(
                FileChangeDetector.findRenames(
                                sender, receiver, path -> path.equals("old.txt"), path -> false)
                        .isEmpty(),
                "A sender-ignored old path must stay untouched instead of being moved");

        assertTrue(
                FileChangeDetector.findRenames(
                                sender, receiver, path -> false, path -> path.equals("new.txt"))
                        .isEmpty(),
                "A receiver-ignored new path must not receive the moved file");
    }

    // --- Receiver-side transfer suppression ---------------------------------------------------

    @Test
    void receiverIgnoredPathsAreMatchedExactlyOrAsDirectoryPrefixes() {
        FileChangeDetector.FileManifest receiver =
                new FileChangeDetector.FileManifest(
                        new HashMap<>(),
                        new HashSet<>(),
                        true,
                        new HashSet<>(List.of("skipped.log")),
                        new HashSet<>(List.of("build")));

        assertTrue(SyncCoordinator.isReceiverIgnored("skipped.log", receiver));
        assertTrue(SyncCoordinator.isReceiverIgnored("build", receiver));
        assertTrue(SyncCoordinator.isReceiverIgnored("build/nested/file.txt", receiver));
        assertFalse(SyncCoordinator.isReceiverIgnored("buildx/file.txt", receiver));
        assertFalse(SyncCoordinator.isReceiverIgnored("ordinary.log", receiver));
    }

    // --- Helpers -----------------------------------------------------------------------------

    private static FileChangeDetector.FileInfo info(String path) {
        return new FileChangeDetector.FileInfo(path, 1L, 1L, "md5-" + path);
    }

    private static void writeFile(Path path, String... lines) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }
}
