package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SyncPreviewPlanTest {

    @Test
    void createFilteredPlanRetainsOnlySelectedOperations() {
        FileChangeDetector.FileInfo transferOne = new FileChangeDetector.FileInfo("docs/readme.txt", 120L, 0L, "md5-a");
        FileChangeDetector.FileInfo transferTwo = new FileChangeDetector.FileInfo("src/main.java", 80L, 0L, "md5-b");

        SyncPreviewPlan basePlan = new SyncPreviewPlan(
                List.of(transferOne, transferTwo),
                List.of("create/only-dir"),
                List.of("delete/obsolete.txt"),
                List.of("delete/old-dir"),
                200L,
                true);

        Set<String> selectedFilesToTransfer = new LinkedHashSet<>(List.of("docs/readme.txt"));
        Set<String> selectedEmptyDirsToCreate = new LinkedHashSet<>(List.of("create/only-dir"));
        Set<String> selectedFilesToDelete = new LinkedHashSet<>(List.of("delete/obsolete.txt"));
        Set<String> selectedEmptyDirsToDelete = new LinkedHashSet<>();

        SyncPreviewPlan filteredPlan = basePlan.createFilteredPlan(
                selectedFilesToTransfer,
                selectedEmptyDirsToCreate,
                selectedFilesToDelete,
                selectedEmptyDirsToDelete);

        assertEquals(1, filteredPlan.getFilesToTransfer().size());
        assertEquals("docs/readme.txt", filteredPlan.getFilesToTransfer().get(0).getPath());
        assertEquals(List.of("create/only-dir"), filteredPlan.getEmptyDirectoriesToCreate());
        assertEquals(List.of("delete/obsolete.txt"), filteredPlan.getFilesToDelete());
        assertTrue(filteredPlan.getEmptyDirectoriesToDelete().isEmpty());
        assertEquals(120L, filteredPlan.getTotalBytesToTransfer());
        assertEquals(3, filteredPlan.getTotalOperations());
        assertEquals(basePlan.isStrictSyncMode(), filteredPlan.isStrictSyncMode());
    }

    @Test
    void createFilteredPlanKeepsEverythingWhenSelectionSetsAreNull() {
        List<FileChangeDetector.FileInfo> transferList = new ArrayList<>();
        transferList.add(new FileChangeDetector.FileInfo("a.txt", 10L, 0L, "md5-a"));
        transferList.add(new FileChangeDetector.FileInfo("b.txt", 15L, 0L, "md5-b"));

        List<String> createDirList = List.of("dir1", "dir2");
        List<String> deleteFileList = List.of("delete/a.bin", "delete/b.bin");
        List<String> deleteDirList = List.of("empty/old");

        SyncPreviewPlan basePlan = new SyncPreviewPlan(
                transferList,
                createDirList,
                deleteFileList,
                deleteDirList,
                25L,
                false);

        SyncPreviewPlan filteredPlan = basePlan.createFilteredPlan(null, null, null, null);

        assertEquals(basePlan.getFilesToTransfer().size(), filteredPlan.getFilesToTransfer().size());
        assertEquals(basePlan.getEmptyDirectoriesToCreate().size(), filteredPlan.getEmptyDirectoriesToCreate().size());
        assertEquals(basePlan.getFilesToDelete().size(), filteredPlan.getFilesToDelete().size());
        assertEquals(basePlan.getEmptyDirectoriesToDelete().size(), filteredPlan.getEmptyDirectoriesToDelete().size());
        assertEquals(basePlan.getTotalBytesToTransfer(), filteredPlan.getTotalBytesToTransfer());
        assertEquals(basePlan.getTotalOperations(), filteredPlan.getTotalOperations());
    }

    @Test
    void createFilteredPlan_excludesSkippedConflicts() {
        FileChangeDetector.FileInfo transferOne = new FileChangeDetector.FileInfo("keep.txt", 100L, 0L, "md5-a");
        FileChangeDetector.FileInfo conflictFile = new FileChangeDetector.FileInfo("conflict.txt", 200L, 0L, "md5-b");

        ConflictInfo skipConflict = new ConflictInfo("conflict.txt",
                new FileChangeDetector.FileInfo("conflict.txt", 200L, 0L, "md5-b"),
                new FileChangeDetector.FileInfo("conflict.txt", 200L, 0L, "md5-c"),
                false, "local".getBytes());
        skipConflict.setResolution(ConflictInfo.Resolution.SKIP);

        SyncPreviewPlan basePlan = new SyncPreviewPlan(
                List.of(transferOne, conflictFile),
                List.of(),
                List.of(),
                List.of(),
                300L,
                false,
                List.of(skipConflict));

        SyncPreviewPlan filteredPlan = basePlan.createFilteredPlan(
                Set.of("keep.txt", "conflict.txt"),
                Set.of(),
                Set.of(),
                Set.of());

        assertEquals(1, filteredPlan.getFilesToTransfer().size());
        assertEquals("keep.txt", filteredPlan.getFilesToTransfer().get(0).getPath());
        assertEquals(1, filteredPlan.getConflicts().size());
    }

    @Test
    void createFilteredPlan_excludesKeepRemoteConflicts() {
        FileChangeDetector.FileInfo transferOne = new FileChangeDetector.FileInfo("keep.txt", 100L, 0L, "md5-a");
        FileChangeDetector.FileInfo conflictFile = new FileChangeDetector.FileInfo("remote.txt", 200L, 0L, "md5-b");

        ConflictInfo keepRemoteConflict = new ConflictInfo("remote.txt",
                new FileChangeDetector.FileInfo("remote.txt", 200L, 0L, "md5-b"),
                new FileChangeDetector.FileInfo("remote.txt", 200L, 0L, "md5-c"),
                false, "local".getBytes());
        keepRemoteConflict.setResolution(ConflictInfo.Resolution.KEEP_REMOTE);

        SyncPreviewPlan basePlan = new SyncPreviewPlan(
                List.of(transferOne, conflictFile),
                List.of(),
                List.of(),
                List.of(),
                300L,
                false,
                List.of(keepRemoteConflict));

        SyncPreviewPlan filteredPlan = basePlan.createFilteredPlan(
                Set.of("keep.txt", "remote.txt"),
                Set.of(),
                Set.of(),
                Set.of());

        assertEquals(1, filteredPlan.getFilesToTransfer().size());
        assertEquals("keep.txt", filteredPlan.getFilesToTransfer().get(0).getPath());
        assertEquals(1, filteredPlan.getConflicts().size());
    }

    @Test
    void createFilteredPlan_includesKeepLocalConflicts() {
        FileChangeDetector.FileInfo conflictFile = new FileChangeDetector.FileInfo("local.txt", 200L, 0L, "md5-a");

        ConflictInfo keepLocalConflict = new ConflictInfo("local.txt",
                new FileChangeDetector.FileInfo("local.txt", 200L, 0L, "md5-a"),
                new FileChangeDetector.FileInfo("local.txt", 200L, 0L, "md5-b"),
                false, "local content".getBytes());
        keepLocalConflict.setResolution(ConflictInfo.Resolution.KEEP_LOCAL);

        SyncPreviewPlan basePlan = new SyncPreviewPlan(
                List.of(conflictFile),
                List.of(),
                List.of(),
                List.of(),
                200L,
                false,
                List.of(keepLocalConflict));

        SyncPreviewPlan filteredPlan = basePlan.createFilteredPlan(
                Set.of("local.txt"),
                Set.of(),
                Set.of(),
                Set.of());

        assertEquals(1, filteredPlan.getFilesToTransfer().size());
        assertEquals("local.txt", filteredPlan.getFilesToTransfer().get(0).getPath());
        assertEquals(1, filteredPlan.getConflicts().size());
    }

    @Test
    void createFilteredPlan_includesMergeConflicts() {
        FileChangeDetector.FileInfo conflictFile = new FileChangeDetector.FileInfo("merge.txt", 200L, 0L, "md5-a");

        ConflictInfo mergeConflict = new ConflictInfo("merge.txt",
                new FileChangeDetector.FileInfo("merge.txt", 200L, 0L, "md5-a"),
                new FileChangeDetector.FileInfo("merge.txt", 200L, 0L, "md5-b"),
                false, "local content".getBytes());
        mergeConflict.setRemoteContent("remote content".getBytes());
        mergeConflict.setMergedContent("merged content");
        mergeConflict.setResolution(ConflictInfo.Resolution.MERGE);

        SyncPreviewPlan basePlan = new SyncPreviewPlan(
                List.of(conflictFile),
                List.of(),
                List.of(),
                List.of(),
                200L,
                false,
                List.of(mergeConflict));

        SyncPreviewPlan filteredPlan = basePlan.createFilteredPlan(
                Set.of("merge.txt"),
                Set.of(),
                Set.of(),
                Set.of());

        assertEquals(1, filteredPlan.getFilesToTransfer().size());
        assertEquals("merge.txt", filteredPlan.getFilesToTransfer().get(0).getPath());
        assertEquals(1, filteredPlan.getConflicts().size());
        assertEquals(ConflictInfo.Resolution.MERGE, filteredPlan.getConflicts().get(0).getResolution());
    }

    @Test
    void createFilteredPlan_preservesConflictsInFilteredPlan() {
        FileChangeDetector.FileInfo fileOne = new FileChangeDetector.FileInfo("file1.txt", 100L, 0L, "md5-a");
        FileChangeDetector.FileInfo fileTwo = new FileChangeDetector.FileInfo("file2.txt", 150L, 0L, "md5-b");

        ConflictInfo conflict1 = new ConflictInfo("file1.txt",
                new FileChangeDetector.FileInfo("file1.txt", 100L, 0L, "md5-a"),
                new FileChangeDetector.FileInfo("file1.txt", 100L, 0L, "md5-x"),
                false, "content1".getBytes());
        conflict1.setResolution(ConflictInfo.Resolution.KEEP_LOCAL);

        ConflictInfo conflict2 = new ConflictInfo("file2.txt",
                new FileChangeDetector.FileInfo("file2.txt", 150L, 0L, "md5-b"),
                new FileChangeDetector.FileInfo("file2.txt", 150L, 0L, "md5-y"),
                false, "content2".getBytes());
        conflict2.setResolution(ConflictInfo.Resolution.SKIP);

        SyncPreviewPlan basePlan = new SyncPreviewPlan(
                List.of(fileOne, fileTwo),
                List.of(),
                List.of(),
                List.of(),
                250L,
                true,
                List.of(conflict1, conflict2));

        // Select only file1 - only file1 should be in transfer list
        // but conflicts list is preserved as-is (not filtered)
        SyncPreviewPlan filteredPlan = basePlan.createFilteredPlan(
                Set.of("file1.txt"),
                Set.of(),
                Set.of(),
                Set.of());

        assertEquals(1, filteredPlan.getFilesToTransfer().size());
        assertEquals("file1.txt", filteredPlan.getFilesToTransfer().get(0).getPath());
        // Conflicts list is preserved unchanged - both conflicts remain
        assertEquals(2, filteredPlan.getConflicts().size());
        assertEquals("file1.txt", filteredPlan.getConflicts().get(0).getPath());
        assertEquals("file2.txt", filteredPlan.getConflicts().get(1).getPath());
    }
}
