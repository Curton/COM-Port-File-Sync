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
}
