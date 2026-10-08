package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import org.junit.jupiter.api.Test;

/**
 * The preview table's Pre button as a single-flight entry: a repeat click while a row's preview is
 * being fetched or shown must not start a second fetch or open a second File Change Preview window,
 * and the button must report what it is doing (the transfer's percent) so the wait reads as work
 * instead of a frozen UI.
 */
class SyncPreviewPreviewSingleFlightTest {

    private static SyncPreviewRow modifiedRow(String path) {
        return new SyncPreviewRow(SyncPreviewOperationType.MODIFIED, path, "1 B", 1L);
    }

    // --- Single-flight ------------------------------------------------------------------------

    @Test
    void repeatClickWhileAPreviewInProgressIsIgnored() {
        SyncPreviewRow row = modifiedRow("m.txt");
        AtomicBoolean fetched = new AtomicBoolean();
        SyncPreviewRenderer renderer =
                new SyncPreviewRenderer(
                        null,
                        path -> {
                            fetched.set(true);
                            return "peer".getBytes();
                        });
        // A fetch is in flight for this row, as it would be during the round trip.
        row.setPreviewInProgress(true);

        renderer.openChangePreview(row);

        assertFalse(fetched.get(), "A repeat click must not start a second fetch");
        assertTrue(row.isPreviewInProgress(), "The in-flight preview keeps its claim");
    }

    @Test
    void nullRowIsIgnored() {
        new SyncPreviewRenderer(null).openChangePreview(null);
    }

    @Test
    void fetchForwardsTheProgressSinkToTheResolver() {
        SyncPreviewRow row = modifiedRow("m.txt");
        List<Integer> reported = new CopyOnWriteArrayList<>();
        SyncPreviewRenderer renderer =
                new SyncPreviewRenderer(
                        null,
                        new SyncPreviewRenderer.ConflictResolver() {
                            @Override
                            public byte[] fetchRemoteContent(String path) {
                                throw new AssertionError(
                                        "The progress-aware overload must be used");
                            }

                            @Override
                            public byte[] fetchRemoteContent(
                                    String path, java.util.function.IntConsumer progress) {
                                progress.accept(0);
                                progress.accept(33);
                                progress.accept(99);
                                return "peer".getBytes();
                            }
                        });

        byte[] fetched = renderer.fetchBaseContent(row, reported::add);

        assertTrue(fetched != null && fetched.length > 0);
        assertEquals(List.of(0, 33, 99), reported, "The transfer's percent reaches the caller");
    }

    @Test
    void resolverWithoutProgressSupportStillFetches() {
        SyncPreviewRow row = modifiedRow("m.txt");
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null, path -> "peer".getBytes());

        byte[] fetched = renderer.fetchBaseContent(row, percent -> {});

        assertTrue(fetched != null && fetched.length > 0);
    }

    // --- Button feedback ----------------------------------------------------------------------

    @Test
    void idleRowKeepsItsPlainPreviewButton() {
        DefaultTableModel model =
                new SyncPreviewRenderer(null)
                        .createSyncPreviewTableModel(List.of(modifiedRow("m.txt")));
        List<SyncPreviewRow> rows = List.of(modifiedRow("m.txt"));
        JTable table = previewTableFor(model, rows);

        javax.swing.JButton cell = previewCell(table, 0);

        assertEquals("Pre", cell.getText());
        assertTrue(cell.isEnabled(), "An idle row's button stays clickable");
    }

    @Test
    void rowBeingFetchedShowsThePercentAndStaysUnclickable() {
        SyncPreviewRow row = modifiedRow("m.txt");
        row.setPreviewInProgress(true);
        DefaultTableModel model =
                new SyncPreviewRenderer(null).createSyncPreviewTableModel(List.of(row));
        JTable table = previewTableFor(model, List.of(row));

        assertEquals(
                "...",
                SyncPreviewRenderer.previewProgressLabel(row),
                "An inline fetch that cannot report percent shows the busy label");

        row.setPreviewProgressPercent(0);
        javax.swing.JButton cell = previewCell(table, 0);
        assertEquals("0%", cell.getText());
        assertFalse(cell.isEnabled(), "A row being fetched must not accept another click");
        assertEquals("Loading the change preview - please wait", cell.getToolTipText());

        row.setPreviewProgressPercent(99);
        assertEquals("99%", previewCell(table, 0).getText());
    }

    @Test
    void percentLabelStaysThreeCharactersWide() {
        SyncPreviewRow row = modifiedRow("m.txt");

        assertEquals("...", SyncPreviewRenderer.previewProgressLabel(row));
        row.setPreviewProgressPercent(5);
        assertEquals("5%", SyncPreviewRenderer.previewProgressLabel(row));
        row.setPreviewProgressPercent(100);
        assertEquals(
                "99%",
                SyncPreviewRenderer.previewProgressLabel(row),
                "The column is pinned to the Pre label's width, so 100% would overflow it");
    }

    @Test
    void editorButtonOfARowBeingFetchedReportsThePercent() {
        SyncPreviewRow row = modifiedRow("m.txt");
        row.setPreviewInProgress(true);
        row.setPreviewProgressPercent(42);
        DefaultTableModel model =
                new SyncPreviewRenderer(null).createSyncPreviewTableModel(List.of(row));
        JTable table = previewTableFor(model, List.of(row));

        TableCellEditor editor =
                table.getColumnModel()
                        .getColumn(SyncPreviewRenderer.PREVIEW_COLUMN)
                        .getCellEditor();
        javax.swing.JButton cell =
                (javax.swing.JButton)
                        editor.getTableCellEditorComponent(
                                table, null, false, 0, SyncPreviewRenderer.PREVIEW_COLUMN);

        assertEquals("42%", cell.getText());
        // Enabled so the click still cancels the cell edit; openChangePreview refuses the repeat.
        assertTrue(cell.isEnabled());
        assertEquals("Loading the change preview - please wait", cell.getToolTipText());
    }

    private static JTable previewTableFor(DefaultTableModel model, List<SyncPreviewRow> rows) {
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);
        return renderer.createPreviewTable(
                model, rows, SyncPreviewRenderer.createPreviewSorter(model));
    }

    private static javax.swing.JButton previewCell(JTable table, int row) {
        TableCellRenderer renderer =
                table.getColumnModel()
                        .getColumn(SyncPreviewRenderer.PREVIEW_COLUMN)
                        .getCellRenderer();
        return (javax.swing.JButton)
                renderer.getTableCellRendererComponent(
                        table,
                        table.getModel().getValueAt(row, SyncPreviewRenderer.PREVIEW_COLUMN),
                        false,
                        false,
                        row,
                        SyncPreviewRenderer.PREVIEW_COLUMN);
    }
}
