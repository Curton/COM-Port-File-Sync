package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import org.junit.jupiter.api.Test;

class SyncPreviewRendererSortTest {

    private static SyncPreviewRow row(String path, long sizeBytes) {
        return new SyncPreviewRow(
                SyncPreviewOperationType.NEW, path, UiFormatting.formatBytes(sizeBytes), sizeBytes);
    }

    @Test
    void pathCompareOrdersSegmentsBeforeFileNames() {
        // Raw string order would put "a.txt" first ('.' < '/'); directory order groups the
        // "a" directory before "a.txt".
        assertTrue(SyncPreviewRenderer.comparePathsDirectoryOrder("a/b.txt", "a.txt") < 0);
        // Raw string order would put "a-x/c" first ('-' < '/'); directory order compares the
        // first segment, where "a" < "a-x".
        assertTrue(SyncPreviewRenderer.comparePathsDirectoryOrder("a/b", "a-x/c") < 0);
        assertTrue(SyncPreviewRenderer.comparePathsDirectoryOrder("a-x/c", "a/b") > 0);
    }

    @Test
    void pathCompareOrdersAncestorBeforeDescendant() {
        assertTrue(SyncPreviewRenderer.comparePathsDirectoryOrder("dir", "dir/file.txt") < 0);
        assertTrue(SyncPreviewRenderer.comparePathsDirectoryOrder("dir/file.txt", "dir") > 0);
        assertEquals(
                0, SyncPreviewRenderer.comparePathsDirectoryOrder("dir/file.txt", "dir/file.txt"));
    }

    @Test
    void pathCompareHandlesSeparatorsAndCase() {
        // Windows-style separators compare the same as forward slashes.
        assertEquals(
                0, SyncPreviewRenderer.comparePathsDirectoryOrder("dir/file.txt", "dir\\file.txt"));
        assertTrue(
                SyncPreviewRenderer.comparePathsDirectoryOrder("dir/file.txt", "dir\\zzz.txt") < 0);
        // Case is ignored first, so "B" and "b" group together before "c".
        assertTrue(SyncPreviewRenderer.comparePathsDirectoryOrder("b/file.txt", "C/file.txt") < 0);
        assertTrue(SyncPreviewRenderer.comparePathsDirectoryOrder("C/file.txt", "b/file.txt") > 0);
    }

    @Test
    void sizeColumnStoresRawByteCountsForNumericSorting() {
        List<SyncPreviewRow> rows = List.of(row("big.bin", 2048L), row("small.txt", 100L));
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
        assertEquals(Boolean.class, model.getColumnClass(0));
        assertEquals(String.class, model.getColumnClass(1));
        assertEquals(Long.class, model.getColumnClass(2));
        assertEquals(2048L, model.getValueAt(0, 2));
        assertEquals(100L, model.getValueAt(1, 2));
    }

    @Test
    void defaultSortPutsSelectedRowsOnTop() {
        // Model rows stay fixed; only the view ordering changes. A JTable is required here:
        // model events reach the sorter through JTable.tableChanged(), a bare sorter does not
        // listen to the model.
        List<SyncPreviewRow> rows =
                List.of(row("a.txt", 100L), row("b.txt", 200L), row("c.txt", 300L));
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);
        assertEquals(0, sorter.getSortKeys().get(0).getColumn());
        assertEquals(SortOrder.DESCENDING, sorter.getSortKeys().get(0).getSortOrder());

        javax.swing.JTable table = new javax.swing.JTable(model);
        table.setRowSorter(sorter);

        // With all rows unchecked the stable sort keeps the original order.
        assertEquals(0, table.convertRowIndexToView(0));
        assertEquals(1, table.convertRowIndexToView(1));
        assertEquals(2, table.convertRowIndexToView(2));

        // Checking a row re-sorts automatically and lifts it to the top.
        model.setValueAt(Boolean.TRUE, 2, 0);
        assertEquals(0, table.convertRowIndexToView(2));
        assertEquals(1, table.convertRowIndexToView(0));
        assertEquals(2, table.convertRowIndexToView(1));

        // A second checked row joins the checked group; among equal keys rows keep model order
        // (the sorter rebuilds the view from model order, so ordering stays deterministic).
        model.setValueAt(Boolean.TRUE, 1, 0);
        assertEquals(0, table.convertRowIndexToView(1));
        assertEquals(1, table.convertRowIndexToView(2));
        assertEquals(2, table.convertRowIndexToView(0));

        // Unchecking drops the row back into the unchecked group, in model order.
        model.setValueAt(Boolean.FALSE, 1, 0);
        assertEquals(0, table.convertRowIndexToView(2));
        assertEquals(1, table.convertRowIndexToView(0));
        assertEquals(2, table.convertRowIndexToView(1));
    }

    @Test
    void sizeSortIsNumericNotLexicographic() {
        List<SyncPreviewRow> rows =
                List.of(row("a.bin", 2048L), row("b.bin", 100L), row("c.bin", 900L));
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);

        sorter.setSortKeys(List.of(new RowSorter.SortKey(2, SortOrder.ASCENDING)));
        sorter.sort();
        // 100 < 900 < 2048; the "900 B" label would sort after "2048" text lexicographically.
        assertEquals(0, sorter.convertRowIndexToView(1));
        assertEquals(1, sorter.convertRowIndexToView(2));
        assertEquals(2, sorter.convertRowIndexToView(0));

        sorter.setSortKeys(List.of(new RowSorter.SortKey(2, SortOrder.DESCENDING)));
        sorter.sort();
        assertEquals(0, sorter.convertRowIndexToView(0));
        assertEquals(1, sorter.convertRowIndexToView(2));
        assertEquals(2, sorter.convertRowIndexToView(1));
    }

    @Test
    void pathSortUsesDirectoryOrder() {
        List<SyncPreviewRow> rows =
                List.of(
                        row("b.txt", 1L),
                        row("a/c.txt", 2L),
                        row("a.txt", 3L),
                        row("a-x/d.txt", 4L));
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);

        sorter.setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.ASCENDING)));
        sorter.sort();
        // Directory order compares segments: "a" < "a-x" < "a.txt" < "b", so a/c.txt, a-x/d.txt,
        // a.txt, b.txt. Raw string order would put "a-x/d.txt" first ('-' < '.').
        assertEquals(0, sorter.convertRowIndexToView(1));
        assertEquals(1, sorter.convertRowIndexToView(3));
        assertEquals(2, sorter.convertRowIndexToView(2));
        assertEquals(3, sorter.convertRowIndexToView(0));
    }

    @Test
    void typeSortIsCaseInsensitive() {
        List<SyncPreviewRow> rows =
                List.of(
                        new SyncPreviewRow(SyncPreviewOperationType.DELETE_FILE, "z.txt", "-", 0L),
                        new SyncPreviewRow(SyncPreviewOperationType.MODIFIED, "y.txt", "1 B", 1L),
                        new SyncPreviewRow(SyncPreviewOperationType.APPEND, "x.txt", "2 B", 2L));
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);

        sorter.setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.ASCENDING)));
        sorter.sort();
        // "Append" < "Delete File" < "Modified" ignoring case.
        assertEquals(0, sorter.convertRowIndexToView(2));
        assertEquals(1, sorter.convertRowIndexToView(0));
        assertEquals(2, sorter.convertRowIndexToView(1));
    }
}
