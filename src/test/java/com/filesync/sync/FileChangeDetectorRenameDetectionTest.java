package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.sync.FileChangeDetector.FileInfo;
import com.filesync.sync.FileChangeDetector.FileManifest;
import com.filesync.sync.FileChangeDetector.FileRename;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rename/move detection: a path the sender added whose content matches a path the sender no longer
 * has must be servable as one server-side move instead of a full transfer plus a delete.
 */
class FileChangeDetectorRenameDetectionTest {

    private static FileInfo file(String path, long size, String md5) {
        return new FileInfo(path, size, 1234L, md5);
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

    @Test
    void pairsNewPathWithDeletedOldPathOfSameContent() {
        // The sender renamed big.bin to archive.bin; the receiver still holds big.bin.
        FileManifest sender = manifest(true, files(file("archive.bin", 100, "md5-x")));
        FileManifest receiver = manifest(true, files(file("big.bin", 100, "md5-x")));

        List<FileRename> renames = FileChangeDetector.findRenames(sender, receiver);

        assertEquals(1, renames.size());
        FileRename rename = renames.get(0);
        assertEquals("big.bin", rename.getFromPath());
        assertEquals("archive.bin", rename.getToPath());
        assertEquals(100L, rename.getSize());
        assertEquals(1234L, rename.getLastModified());
        assertEquals("md5-x", rename.getMd5());
    }

    @Test
    void noPairingWhenContentDiffers() {
        FileManifest sender = manifest(true, files(file("archive.bin", 100, "md5-new")));
        FileManifest receiver = manifest(true, files(file("big.bin", 100, "md5-old")));

        assertTrue(FileChangeDetector.findRenames(sender, receiver).isEmpty());
    }

    @Test
    void noPairingWhenOldPathStillExistsOnSender() {
        // The sender copied the file rather than moving it: the old path must stay on the receiver.
        FileManifest sender =
                manifest(
                        true, files(file("big.bin", 100, "md5-x"), file("copy.bin", 100, "md5-x")));
        FileManifest receiver = manifest(true, files(file("big.bin", 100, "md5-x")));

        assertTrue(FileChangeDetector.findRenames(sender, receiver).isEmpty());
    }

    @Test
    void noPairingWhenNewPathAlreadyExistsOnReceiver() {
        // The new path exists there: this is a modification, not a rename.
        FileManifest sender = manifest(true, files(file("archive.bin", 110, "md5-x")));
        FileManifest receiver =
                manifest(
                        true,
                        files(file("big.bin", 100, "md5-x"), file("archive.bin", 100, "md5-old")));

        assertTrue(FileChangeDetector.findRenames(sender, receiver).isEmpty());
    }

    @Test
    void noPairingWithoutHashesOnBothSides() {
        // Fast mode leaves binaries unhashed: nothing reliable to match on, transfer as usual.
        FileManifest sender = manifest(true, files(file("archive.bin", 100, null)));
        FileManifest receiver = manifest(true, files(file("big.bin", 100, null)));

        assertTrue(FileChangeDetector.findRenames(sender, receiver).isEmpty());
    }

    @Test
    void matchingIsOneToOneAndDeterministic() {
        // Two sender files with identical content and two deleted old paths with the same hash:
        // each old path is claimed exactly once, in a stable order.
        FileManifest sender =
                manifest(true, files(file("b-new.bin", 10, "same"), file("a-new.bin", 10, "same")));
        FileManifest receiver =
                manifest(true, files(file("z-old.bin", 10, "same"), file("m-old.bin", 10, "same")));

        List<FileRename> renames = FileChangeDetector.findRenames(sender, receiver);

        assertEquals(2, renames.size());
        // New paths are matched in sorted order, each claiming the oldest surviving old path.
        assertEquals("a-new.bin", renames.get(0).getToPath());
        assertEquals("m-old.bin", renames.get(0).getFromPath());
        assertEquals("b-new.bin", renames.get(1).getToPath());
        assertEquals("z-old.bin", renames.get(1).getFromPath());
    }

    @Test
    void moreNewPathsThanOldPathsPairsEachOldPathOnce() {
        // Three copies, one deleted original: only one new path may ride the rename.
        FileManifest sender =
                manifest(
                        true,
                        files(
                                file("a.bin", 10, "same"),
                                file("b.bin", 10, "same"),
                                file("c.bin", 10, "same")));
        FileManifest receiver = manifest(true, files(file("old.bin", 10, "same")));

        List<FileRename> renames = FileChangeDetector.findRenames(sender, receiver);

        assertEquals(1, renames.size());
        assertEquals("old.bin", renames.get(0).getFromPath());
        assertEquals("a.bin", renames.get(0).getToPath());
    }

    @Test
    void caseOnlyRenameIsNeverPaired() {
        // On a case-insensitive receiver Foo.txt and foo.txt are one file: the "rename" would move
        // the file onto itself. The regular transfer handles the new spelling instead.
        FileManifest sender = manifest(false, files(file("foo.txt", 11, "new")));
        FileManifest receiver = manifest(false, files(file("Foo.txt", 10, "new")));

        assertTrue(FileChangeDetector.findRenames(sender, receiver).isEmpty());
    }

    @Test
    void caseOnlyRenameIsNeverPairedEvenOnCaseSensitiveTarget() {
        // The two spellings are two entries there, so the regular transfer-plus-delete mirrors the
        // rename faithfully; pairing it as a move would only risk the move clobbering the target.
        FileManifest sender = manifest(true, files(file("foo.txt", 11, "new")));
        FileManifest receiver = manifest(true, files(file("Foo.txt", 11, "new")));

        assertTrue(FileChangeDetector.findRenames(sender, receiver).isEmpty());
    }

    @Test
    void caseInsensitiveReceiverDoesNotPairPathThatResolvesToExistingFile() {
        // The sender renamed b.txt to A.txt, but the receiver's filesystem already holds that
        // spelling: the new path is a modification there, not a rename target.
        FileManifest sender = manifest(false, files(file("A.txt", 11, "new")));
        FileManifest receiver =
                manifest(false, files(file("a.txt", 10, "old"), file("b.txt", 10, "new")));

        assertTrue(FileChangeDetector.findRenames(sender, receiver).isEmpty());
    }

    @Test
    void caseInsensitiveReceiverStillPairsGenuinelyDifferentPaths() {
        FileManifest sender = manifest(false, files(file("dir/moved.txt", 12, "md5-y")));
        FileManifest receiver = manifest(false, files(file("dir/original.txt", 12, "md5-y")));

        List<FileRename> renames = FileChangeDetector.findRenames(sender, receiver);

        assertEquals(1, renames.size());
        assertEquals("dir/original.txt", renames.get(0).getFromPath());
        assertEquals("dir/moved.txt", renames.get(0).getToPath());
    }

    @Test
    void repeatedlyComparingTheSameManifestsPlansTheSameRenames() {
        // The preview runs on every sync; the pairing must be stable or the plan flickers.
        FileManifest sender = manifest(true, files(file("a.txt", 5, "h1"), file("b.txt", 5, "h2")));
        FileManifest receiver =
                manifest(true, files(file("x.txt", 5, "h1"), file("y.txt", 5, "h2")));

        List<String> first =
                FileChangeDetector.findRenames(sender, receiver).stream()
                        .map(r -> r.getFromPath() + "->" + r.getToPath())
                        .toList();
        List<String> second =
                FileChangeDetector.findRenames(sender, receiver).stream()
                        .map(r -> r.getFromPath() + "->" + r.getToPath())
                        .toList();

        assertEquals(List.of("x.txt->a.txt", "y.txt->b.txt"), first);
        assertEquals(first, second);
    }

    @Test
    void unrelatedDeletionsAndAdditionsAreLeftAlone() {
        // A deletion whose content no longer exists anywhere is not renameable, and a genuinely
        // new file (no matching old content) still transfers.
        FileManifest sender =
                manifest(
                        true,
                        files(
                                file("new-only.bin", 10, "md5-1"),
                                file("brand-new.bin", 7, "md5-3")));
        FileManifest receiver =
                manifest(
                        true,
                        files(file("gone.bin", 10, "md5-1"), file("other-gone.bin", 9, "md5-2")));

        List<FileRename> renames = FileChangeDetector.findRenames(sender, receiver);

        assertEquals(1, renames.size());
        assertEquals("gone.bin", renames.get(0).getFromPath());
        assertEquals("new-only.bin", renames.get(0).getToPath());
    }
}
