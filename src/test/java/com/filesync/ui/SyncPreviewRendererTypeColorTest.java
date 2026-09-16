package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Color;
import java.awt.Component;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import org.junit.jupiter.api.Test;

/** Type-column colours: a dedicated hue per operation, and no colour bleed between rows. */
class SyncPreviewRendererTypeColorTest {

    private static SyncPreviewRow row(SyncPreviewOperationType type, String path) {
        return new SyncPreviewRow(type, path, "1 B", 1L);
    }

    private static JTable previewTable(List<SyncPreviewRow> rows) {
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
        return new SyncPreviewRenderer(null)
                .createPreviewTable(model, rows, SyncPreviewRenderer.createPreviewSorter(model));
    }

    /** Render a Type-column cell through the column's real (shared) renderer instance. */
    private static Color renderedForeground(JTable table, int viewRow) {
        TableCellRenderer typeRenderer = table.getColumnModel().getColumn(1).getCellRenderer();
        Component cell =
                typeRenderer.getTableCellRendererComponent(
                        table, table.getModel().getValueAt(viewRow, 1), false, false, viewRow, 1);
        return ((JLabel) cell).getForeground();
    }

    @Test
    void eachOperationTypeMapsToItsAssignedColour() {
        assertEquals(
                new Color(200, 0, 0),
                SyncPreviewRenderer.typeColor(SyncPreviewOperationType.CONFLICT));
        assertEquals(
                new Color(216, 96, 96),
                SyncPreviewRenderer.typeColor(SyncPreviewOperationType.DELETE_FILE));
        assertEquals(
                new Color(0, 128, 0), SyncPreviewRenderer.typeColor(SyncPreviewOperationType.NEW));
        assertEquals(
                new Color(0, 0, 180),
                SyncPreviewRenderer.typeColor(SyncPreviewOperationType.MODIFIED));
        assertEquals(
                new Color(0, 128, 128),
                SyncPreviewRenderer.typeColor(SyncPreviewOperationType.APPEND));
        // Types without a dedicated colour use the table default, signalled by null.
        assertNull(SyncPreviewRenderer.typeColor(SyncPreviewOperationType.TRANSFER_FILE));
        assertNull(SyncPreviewRenderer.typeColor(SyncPreviewOperationType.DELETE_DIR));
    }

    @Test
    void deleteFileRedDiffersFromConflictRed() {
        // Both read as red, but at different saturations so they are not mistaken for each other.
        assertNotEquals(
                SyncPreviewRenderer.typeColor(SyncPreviewOperationType.CONFLICT),
                SyncPreviewRenderer.typeColor(SyncPreviewOperationType.DELETE_FILE));
    }

    @Test
    void deleteFileRendersItsOwnRedNotThePreviousRowColour() {
        List<SyncPreviewRow> rows =
                List.of(
                        row(SyncPreviewOperationType.MODIFIED, "a.txt"),
                        row(SyncPreviewOperationType.DELETE_FILE, "b.txt"));
        JTable table = previewTable(rows);
        // One shared renderer paints row 0 (blue) then row 1 (delete): row 1 must show its own
        // desaturated red, not the blue left behind by row 0.
        renderedForeground(table, 0);
        assertEquals(
                SyncPreviewRenderer.typeColor(SyncPreviewOperationType.DELETE_FILE),
                renderedForeground(table, 1));
    }

    @Test
    void uncolouredTypesFallBackToTableForegroundNotPreviousRowColour() {
        List<SyncPreviewRow> rows =
                List.of(
                        row(SyncPreviewOperationType.MODIFIED, "a.txt"),
                        row(SyncPreviewOperationType.TRANSFER_FILE, "b.txt"),
                        row(SyncPreviewOperationType.DELETE_DIR, "empty-dir"));
        JTable table = previewTable(rows);
        // The regression this locks in: the shared renderer used to remember the Modified row's
        // blue and leak it into every type rendered after it that had no dedicated colour.
        renderedForeground(table, 0);
        assertEquals(table.getForeground(), renderedForeground(table, 1));
        assertEquals(table.getForeground(), renderedForeground(table, 2));
    }
}
