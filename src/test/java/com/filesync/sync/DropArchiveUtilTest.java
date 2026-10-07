package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Round-trip and safety coverage for the drag-and-drop archive packing/unpacking. */
class DropArchiveUtilTest {

    @TempDir Path tempDir;

    @Test
    void loneFolderRoundTripsAsTheFolderItselfWithEmptyDirectoriesKept() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("photos"));
        Files.createDirectories(folder.resolve("nested/deep"));
        Files.writeString(folder.resolve("pic.bin"), "picture bytes");
        Files.writeString(folder.resolve("nested/notes.txt"), "notes");
        Files.createDirectories(folder.resolve("empty-dir"));
        File archive = tempDir.resolve("photos.zip").toFile();

        DropArchiveUtil.writeDropArchive(List.of(folder.toFile()), archive);
        File extractedParent = tempDir.resolve("downloads").toFile();
        File extracted = DropArchiveUtil.extractArchive(archive, extractedParent);

        assertEquals(tempDir.resolve("downloads/photos").toFile(), extracted);
        assertEquals("picture bytes", Files.readString(extracted.toPath().resolve("pic.bin")));
        assertEquals("notes", Files.readString(extracted.toPath().resolve("nested/notes.txt")));
        assertTrue(
                Files.isDirectory(extracted.toPath().resolve("nested/deep")),
                "nested directories survive the round trip");
        assertTrue(
                Files.isDirectory(extracted.toPath().resolve("empty-dir")),
                "empty directories survive the round trip");
    }

    @Test
    void multiItemSelectionKeepsEachItemNameAsRootSegment() throws Exception {
        Path loose = tempDir.resolve("loose.txt");
        Files.writeString(loose, "loose");
        Path folder = Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(folder.resolve("a.txt"), "a");

        File archive = tempDir.resolve("dropped-files-test.zip").toFile();
        DropArchiveUtil.writeDropArchive(List.of(loose.toFile(), folder.toFile()), archive);

        File extracted = DropArchiveUtil.extractArchive(archive, tempDir.resolve("out").toFile());
        assertEquals(
                "dropped-files-test",
                extracted.getName(),
                "the archive unpacks into a folder named after the zip");
        assertEquals("loose", Files.readString(extracted.toPath().resolve("loose.txt")));
        assertEquals("a", Files.readString(extracted.toPath().resolve("docs/a.txt")));
    }

    @Test
    void duplicateItemNamesAreDisambiguatedInsteadOfFailingTheSend() throws Exception {
        Path first = Files.createDirectories(tempDir.resolve("one")).resolve("a.txt");
        Files.writeString(first, "first");
        Path second = Files.createDirectories(tempDir.resolve("two")).resolve("a.txt");
        Files.writeString(second, "second");

        File archive = tempDir.resolve("dup.zip").toFile();
        DropArchiveUtil.writeDropArchive(List.of(first.toFile(), second.toFile()), archive);

        File extracted = DropArchiveUtil.extractArchive(archive, tempDir.resolve("out").toFile());
        assertEquals("first", Files.readString(extracted.toPath().resolve("a.txt")));
        assertEquals("second", Files.readString(extracted.toPath().resolve("a (2).txt")));
    }

    @Test
    void traversingEntryNamesAreRejectedAndThePartialTreeRemoved() throws Exception {
        File evilZip = tempDir.resolve("evil.zip").toFile();
        writeZipEntries(evilZip, "../outside.txt", "payload");

        IOException e =
                assertThrows(
                        IOException.class,
                        () ->
                                DropArchiveUtil.extractArchive(
                                        evilZip, tempDir.resolve("out").toFile()));
        assertTrue(
                e.getMessage().contains("escapes the target folder"),
                "the rejection names the entry, got: " + e.getMessage());
        assertFalse(
                Files.exists(tempDir.resolve("out/outside.txt")),
                "nothing may be written outside the target folder");
        assertFalse(
                Files.exists(tempDir.resolve("out/evil")),
                "the partially extracted tree is removed on failure");
    }

    @Test
    void absoluteAndDriveLetterEntryNamesAreRejected() throws Exception {
        File absoluteZip = tempDir.resolve("absolute.zip").toFile();
        writeZipEntries(absoluteZip, "/etc/evil", "payload");
        assertThrows(
                IOException.class,
                () ->
                        DropArchiveUtil.extractArchive(
                                absoluteZip, tempDir.resolve("out1").toFile()));

        File driveZip = tempDir.resolve("drive.zip").toFile();
        writeZipEntries(driveZip, "C:/evil", "payload");
        assertThrows(
                IOException.class,
                () -> DropArchiveUtil.extractArchive(driveZip, tempDir.resolve("out2").toFile()));
    }

    @Test
    void takenTargetFolderNamesGetTheCounterSuffix() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("photos"));
        Files.writeString(folder.resolve("a.txt"), "a");
        File archive = tempDir.resolve("photos.zip").toFile();
        DropArchiveUtil.writeDropArchive(List.of(folder.toFile()), archive);
        File downloads = tempDir.resolve("downloads").toFile();
        Files.createDirectories(downloads.toPath().resolve("photos"));

        File extracted = DropArchiveUtil.extractArchive(archive, downloads);

        assertEquals("photos (1)", extracted.getName());
        assertEquals("a", Files.readString(extracted.toPath().resolve("a.txt")));
    }

    @Test
    void deleteRecursivelyRemovesWholeTrees() throws Exception {
        Path tree = Files.createDirectories(tempDir.resolve("tree/nested"));
        Files.writeString(tree.resolve("f.txt"), "x");

        DropArchiveUtil.deleteRecursively(tempDir.resolve("tree").toFile());

        assertFalse(Files.exists(tree), "the tree including nested content is gone");
    }

    private static void writeZipEntries(File zipFile, String... entryNamesAndContents)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (int i = 0; i < entryNamesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entryNamesAndContents[i]));
                zip.write(
                        entryNamesAndContents[i + 1].getBytes(
                                java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
