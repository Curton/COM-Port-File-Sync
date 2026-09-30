package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.sync.FileChangeDetector.FileRename;
import com.filesync.sync.SyncPreviewPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import org.junit.jupiter.api.Test;

/**
 * Rename rows in the preview table: their own type label and colour, the "old → new" path the table
 * shows, and the selection that filters them back into the plan.
 */
class SyncPreviewRendererRenameRowTest {

    private static SyncPreviewRow renameRow(String from, String to, long sizeBytes) {
        return new SyncPreviewRow(
                SyncPreviewOperationType.RENAME,
                to,
                from,
                UiFormatting.formatBytes(sizeBytes),
                sizeBytes,
                null);
    }

    private static DefaultTableModel modelOf(List<SyncPreviewRow> rows) {
        return new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
    }

    @Test
    void renameRowShowsBothPathsAndItsOwnType() {
        DefaultTableModel model = modelOf(List.of(renameRow("old/big.bin", "new/big.bin", 2048L)));

        assertEquals("Rename", model.getValueAt(0, 1));
        assertEquals(
                "old/big.bin → new/big.bin", model.getValueAt(0, SyncPreviewRenderer.PATH_COLUMN));
        assertEquals(2048L, model.getValueAt(0, 2));
    }

    @Test
    void nonRenameRowsKeepTheirPlainPath() {
        DefaultTableModel model =
                modelOf(
                        List.of(
                                new SyncPreviewRow(
                                        SyncPreviewOperationType.NEW, "a.txt", "1 B", 1L)));

        assertEquals("a.txt", model.getValueAt(0, SyncPreviewRenderer.PATH_COLUMN));
    }

    @Test
    void selectedRenameRowFiltersBackIntoThePlan() {
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);
        List<SyncPreviewRow> rows =
                List.of(
                        renameRow("a-old.bin", "a-new.bin", 10L),
                        renameRow("b-old.bin", "b-new.bin", 20L));
        DefaultTableModel model = modelOf(rows);
        // Check only the first rename row.
        model.setValueAt(Boolean.TRUE, 0, 0);

        SyncPreviewPlan plan =
                new SyncPreviewPlan(
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
                        Map.of(),
                        Map.of(),
                        List.of(
                                new FileRename("a-old.bin", "a-new.bin", 10L, 1L, "md5-a"),
                                new FileRename("b-old.bin", "b-new.bin", 20L, 1L, "md5-b")));

        SyncPreviewPlan filtered = renderer.createFilteredSyncPlan(plan, model, rows);

        assertEquals(1, filtered.getRenames().size());
        assertEquals("a-new.bin", filtered.getRenames().get(0).getToPath());
        assertEquals("a-old.bin", filtered.getRenames().get(0).getFromPath());
        assertEquals(1, filtered.getTotalOperations());
    }

    @Test
    void renameRowHasNoPreviewableContent() {
        // A rename is md5-verified identical on both sides, so its Preview cell shows the dash
        // instead of a button that would open an empty diff.
        DefaultTableModel model = modelOf(List.of(renameRow("old.bin", "new.bin", 1L)));
        List<SyncPreviewRow> rows = List.of(renameRow("old.bin", "new.bin", 1L));
        JTable table =
                new SyncPreviewRenderer(null)
                        .createPreviewTable(
                                model, rows, SyncPreviewRenderer.createPreviewSorter(model));

        TableCellRenderer previewRenderer =
                table.getColumnModel()
                        .getColumn(SyncPreviewRenderer.PREVIEW_COLUMN)
                        .getCellRenderer();
        java.awt.Component cell =
                previewRenderer.getTableCellRendererComponent(
                        table,
                        model.getValueAt(0, SyncPreviewRenderer.PREVIEW_COLUMN),
                        false,
                        false,
                        0,
                        SyncPreviewRenderer.PREVIEW_COLUMN);

        assertFalse(
                cell instanceof javax.swing.JButton, "a rename row shows the dash, not a button");
        assertTrue(((javax.swing.JLabel) cell).getText().contains("\u2014"));
    }

    @Test
    void renameRowLabelAndAltPathExposeTheMove() {
        SyncPreviewRow row = renameRow("from.txt", "to.txt", 5L);

        assertEquals("Rename", row.getTypeLabel());
        assertEquals("to.txt", row.getPath());
        assertEquals("from.txt", row.getAltPath());
        assertEquals("from.txt → to.txt", row.getDisplayPath());
        // No previous version worth diffing against, so no remote fetch is attempted for it.
        assertFalse(row.hasBaseVersion());
    }
}
