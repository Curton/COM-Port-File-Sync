package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.sync.FileChangeDetector.FileInfo;
import com.filesync.sync.FileChangeDetector.FileRename;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** How the preview plan carries and filters the renames the detector paired up. */
class SyncPreviewPlanRenameTest {

    private static FileInfo file(String path, long size) {
        return new FileInfo(path, size, 0L, "md5-" + path);
    }

    private static FileRename rename(String from, String to) {
        return new FileRename(from, to, 42L, 1000L, "md5-" + to);
    }

    private static SyncPreviewPlan planWith(List<FileRename> renames) {
        return new SyncPreviewPlan(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0L,
                true,
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                java.util.Map.of(),
                java.util.Map.of(),
                renames);
    }

    @Test
    void planWithoutRenamesReportsNoRenames() {
        // The constructors every older caller uses stay rename-free.
        SyncPreviewPlan plan =
                new SyncPreviewPlan(List.of(), List.of(), List.of(), List.of(), 0L, true);

        assertTrue(plan.getRenames().isEmpty());
        assertEquals(0, plan.getTotalOperations());
    }

    @Test
    void renamesCountTowardsTotalOperationsExactlyOnce() {
        SyncPreviewPlan plan =
                planWith(List.of(rename("old.txt", "new.txt"), rename("a.bin", "b.bin")));

        assertEquals(2, plan.getTotalOperations());
        assertEquals(2, plan.getRenames().size());
        // A rename is its own operation: it appears in no other list of the plan.
        assertTrue(plan.getFilesToTransfer().isEmpty());
        assertTrue(plan.getFilesToDelete().isEmpty());
    }

    @Test
    void selectedRenamesSurviveTheFilterAndDeselectedOnesDoNot() {
        SyncPreviewPlan plan =
                planWith(List.of(rename("old.txt", "new.txt"), rename("a.bin", "b.bin")));

        SyncPreviewPlan filtered =
                plan.createFilteredPlan(
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        // Only the New/Modified checkbox rows (new paths) were checked.
                        Set.of("b.bin"));

        assertEquals(1, filtered.getRenames().size());
        assertEquals("b.bin", filtered.getRenames().get(0).getToPath());
        assertEquals("a.bin", filtered.getRenames().get(0).getFromPath());
        assertEquals(1, filtered.getTotalOperations());
    }

    @Test
    void filterWithoutRenameSelectionKeepsNoRenames() {
        SyncPreviewPlan plan = planWith(List.of(rename("old.txt", "new.txt")));

        SyncPreviewPlan filtered = plan.createFilteredPlan(Set.of(), Set.of(), Set.of(), Set.of());

        assertTrue(filtered.getRenames().isEmpty());
        assertEquals(0, filtered.getTotalOperations());
    }

    @Test
    void filteredOutRenameKeepsItsTransferAndDeleteInTheirPhases() {
        // The transfer and the delete the rename replaced are planned independently: dropping the
        // rename from the plan must not resurrect them, and selecting it must not duplicate them.
        SyncPreviewPlan plan =
                new SyncPreviewPlan(
                        List.of(file("keep.txt", 10)),
                        List.of(),
                        List.of("obsolete.txt"),
                        List.of(),
                        10L,
                        true,
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        java.util.Map.of(),
                        java.util.Map.of(),
                        List.of(rename("old.txt", "new.txt")));

        SyncPreviewPlan everythingSelected =
                plan.createFilteredPlan(
                        Set.of("keep.txt"),
                        Set.of(),
                        Set.of("obsolete.txt"),
                        Set.of(),
                        Set.of("new.txt"));

        assertEquals(
                List.of("keep.txt"),
                everythingSelected.getFilesToTransfer().stream().map(FileInfo::getPath).toList());
        assertEquals(List.of("obsolete.txt"), everythingSelected.getFilesToDelete());
        assertEquals(1, everythingSelected.getRenames().size());
        assertEquals(3, everythingSelected.getTotalOperations());
    }
}
