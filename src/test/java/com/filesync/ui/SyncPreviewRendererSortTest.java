package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import javax.swing.JTable;
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
    void defaultSortKeyIsSyncDescending() {
        List<SyncPreviewRow> rows = List.of(row("a.txt", 100L));
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);
        List<? extends RowSorter.SortKey> keys = sorter.getSortKeys();
        assertEquals(1, keys.size());
        assertEquals(0, keys.get(0).getColumn());
        assertEquals(SortOrder.DESCENDING, keys.get(0).getSortOrder());
    }

    @Test
    void manualCheckboxToggleDoesNotResort() {
        // A JTable is required: model events reach the sorter through JTable.tableChanged().
        List<SyncPreviewRow> rows =
                List.of(row("a.txt", 100L), row("b.txt", 200L), row("c.txt", 300L));
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);
        JTable table = new JTable(model);
        table.setRowSorter(sorter);

        // Toggling individual checkboxes must not move any row, even mid-list.
        model.setValueAt(Boolean.TRUE, 2, 0);
        model.setValueAt(Boolean.TRUE, 1, 0);
        assertEquals(0, table.convertRowIndexToView(0));
        assertEquals(1, table.convertRowIndexToView(1));
        assertEquals(2, table.convertRowIndexToView(2));

        // Unchecking also leaves the order untouched.
        model.setValueAt(Boolean.FALSE, 1, 0);
        assertEquals(0, table.convertRowIndexToView(0));
        assertEquals(1, table.convertRowIndexToView(1));
        assertEquals(2, table.convertRowIndexToView(2));
    }

    @Test
    void gitSelectionResortsCheckedRowsToTop() {
        List<SyncPreviewRow> rows =
                List.of(row("a.txt", 100L), row("b.txt", 200L), row("c.txt", 300L));
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);

        int matches =
                new SyncPreviewRenderer(null)
                        .applyGitSelection(model, rows, Set.of("c.txt", "b.txt"), sorter);
        assertEquals(2, matches);
        // Checked rows rise to the top, keeping model order within the group.
        assertEquals(0, sorter.convertRowIndexToView(1));
        assertEquals(1, sorter.convertRowIndexToView(2));
        assertEquals(2, sorter.convertRowIndexToView(0));
        assertEquals(Boolean.TRUE, model.getValueAt(1, 0));
        assertEquals(Boolean.TRUE, model.getValueAt(2, 0));
        assertEquals(Boolean.FALSE, model.getValueAt(0, 0));
    }

    @Test
    void batchSelectionResortsOnlyUnderSyncSort() {
        List<SyncPreviewRow> rows =
                List.of(row("c.txt", 300L), row("a.txt", 100L), row("b.txt", 200L));
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);

        // Select All re-applies the Sync sort: all rows equal, so the view resets to model order.
        renderer.setPreviewSelection(model, true, sorter);
        assertEquals(0, sorter.convertRowIndexToView(0));
        assertEquals(1, sorter.convertRowIndexToView(1));
        assertEquals(2, sorter.convertRowIndexToView(2));

        // With the user sorted by Path, a git batch does not yank the order back.
        sorter.setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.ASCENDING)));
        sorter.sort();
        assertEquals(0, sorter.convertRowIndexToView(1)); // a.txt
        assertEquals(1, sorter.convertRowIndexToView(2)); // b.txt
        assertEquals(2, sorter.convertRowIndexToView(0)); // c.txt
        renderer.applyGitSelection(model, rows, Set.of("c.txt"), sorter);
        assertEquals(0, sorter.convertRowIndexToView(1));
        assertEquals(1, sorter.convertRowIndexToView(2));
        assertEquals(2, sorter.convertRowIndexToView(0));
        assertEquals(Boolean.TRUE, model.getValueAt(0, 0)); // c.txt checked, still last
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
