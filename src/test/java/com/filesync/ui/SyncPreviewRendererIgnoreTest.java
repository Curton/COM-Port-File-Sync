package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.FontMetrics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.border.LineBorder;
import javax.swing.plaf.basic.BasicMenuItemUI;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The right-click "Ignore" action on a preview row: the anchored patterns it derives per row type,
 * the append semantics of the root .filesyncignore it writes, the rows it removes from the open
 * dialog (which is also what exempts them from the current session — Start Sync executes only
 * checked rows), and the popup item that ties it all together.
 */
class SyncPreviewRendererIgnoreTest {

    @TempDir Path tempDir;

    private static SyncPreviewRow fileRow(String path) {
        return new SyncPreviewRow(SyncPreviewOperationType.NEW, path, "1 B", 1L);
    }

    private static SyncPreviewRow dirRow(String path, SyncPreviewOperationType type) {
        return new SyncPreviewRow(type, path, "-", 0L);
    }

    // --- Pattern derivation ------------------------------------------------------------------

    @Test
    void fileRowsProduceAnAnchoredExactPath() {
        assertEquals(
                List.of("/docs/readme.md"),
                SyncPreviewRenderer.ignorePatternsFor(fileRow("docs/readme.md")));
    }

    @Test
    void directoryRowsProduceATrailingSlashPattern() {
        assertEquals(
                List.of("/build/"),
                SyncPreviewRenderer.ignorePatternsFor(
                        dirRow("build", SyncPreviewOperationType.CREATE_DIR)));
        assertEquals(
                List.of("/stale/"),
                SyncPreviewRenderer.ignorePatternsFor(
                        dirRow("stale", SyncPreviewOperationType.DELETE_DIR)));
    }

    @Test
    void renameRowsCoverBothEndsOfTheMove() {
        // Ignoring only the new path would leave the receiver's old copy a deletion candidate;
        // ignoring both keeps each side's copy where it is.
        SyncPreviewRow rename =
                new SyncPreviewRow(
                        SyncPreviewOperationType.RENAME, "b.txt", "a.txt", "1 B", 1L, null);
        assertEquals(List.of("/a.txt", "/b.txt"), SyncPreviewRenderer.ignorePatternsFor(rename));
    }

    // --- .filesyncignore append semantics -----------------------------------------------------

    @Test
    void firstWriteCreatesTheFileWithAHeaderComment() throws IOException {
        SyncPreviewRenderer.appendFileSyncIgnoreEntries(tempDir.toFile(), List.of("/skip.log"));

        List<String> lines = ignoreFileLines();
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("#"), "A fresh file must explain itself");
        assertEquals("/skip.log", lines.get(1));
    }

    @Test
    void appendingRepairsAMissingFinalNewlineBeforeTheNewEntry() throws IOException {
        Files.writeString(tempDir.resolve(".filesyncignore"), "/first.log");

        SyncPreviewRenderer.appendFileSyncIgnoreEntries(tempDir.toFile(), List.of("/second.log"));

        assertEquals(Arrays.asList("/first.log", "/second.log"), ignoreFileLines());
    }

    @Test
    void exactDuplicatesAreNotAppendedTwice() throws IOException {
        SyncPreviewRenderer.appendFileSyncIgnoreEntries(
                tempDir.toFile(), List.of("/skip.log", "/other.log"));
        SyncPreviewRenderer.appendFileSyncIgnoreEntries(tempDir.toFile(), List.of("/skip.log"));

        // Header + the two entries, and the repeated /skip.log added none of them again.
        assertEquals(
                3,
                ignoreFileLines().size(),
                "A repeated right-click must not pile up the same line");
        assertTrue(ignoreFileLines().contains("/skip.log"));
    }

    // --- Row removal --------------------------------------------------------------------------

    @Test
    void directoryPatternRemovesTheRowsBeneathItToo() {
        List<SyncPreviewRow> rows =
                new ArrayList<>(
                        List.of(
                                fileRow("keep.txt"),
                                fileRow("build/a.txt"),
                                fileRow("build/sub/b.txt"),
                                fileRow("buildx/c.txt"),
                                new SyncPreviewRow(
                                        SyncPreviewOperationType.RENAME,
                                        "elsewhere.txt",
                                        "build/old.txt",
                                        "1 B",
                                        1L,
                                        null)));
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);

        SyncPreviewRenderer.removeRowsCoveredByIgnorePatterns(model, rows, List.of("/build/"));

        assertEquals(2, rows.size(), "The directory's rows and the rename from inside it go");
        assertEquals("keep.txt", rows.get(0).getPath());
        assertEquals("buildx/c.txt", rows.get(1).getPath(), "A longer prefix is not a match");
        assertEquals(model.getRowCount(), rows.size(), "Model and row list stay aligned");
    }

    @Test
    void filePatternRemovesOnlyTheExactPath() {
        List<SyncPreviewRow> rows =
                new ArrayList<>(List.of(fileRow("docs/a.txt"), fileRow("docs/b.txt")));
        DefaultTableModel model = new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);

        SyncPreviewRenderer.removeRowsCoveredByIgnorePatterns(model, rows, List.of("/docs/a.txt"));

        assertEquals(1, rows.size());
        assertEquals("docs/b.txt", rows.get(0).getPath());
    }

    // --- Popup wiring -------------------------------------------------------------------------

    @Test
    void popupItemWritesTheEntryAndRemovesTheRow() throws IOException {
        List<String> log = new ArrayList<>();
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null, null, log::add);
        renderer.setPreviewSyncFolder(tempDir.toFile());
        List<SyncPreviewRow> rows =
                new ArrayList<>(List.of(fileRow("keep.txt"), fileRow("skip.log")));
        DefaultTableModel model = renderer.createSyncPreviewTableModel(rows);

        JPopupMenu popup = renderer.buildIgnorePopup(rows.get(1), model, rows);
        JMenuItem item = (JMenuItem) popup.getComponent(0);
        assertEquals(
                "Ignore (add to .filesyncignore)", item.getText(), "The action must be labelled");

        item.doClick();

        assertTrue(ignoreFileLines().contains("/skip.log"), "The entry must be on disk");
        assertEquals(1, rows.size(), "The ignored row must leave the dialog (this session too)");
        assertEquals("keep.txt", rows.get(0).getPath());
        assertEquals(model.getRowCount(), rows.size());
        assertFalse(log.isEmpty(), "The action must be logged");
        assertTrue(log.get(0).contains("/skip.log"));
    }

    @Test
    void popupEntryHugsItsTextAndCentersTheLabel() {
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null, null, message -> {});
        List<SyncPreviewRow> rows = new ArrayList<>(List.of(fileRow("skip.log")));
        DefaultTableModel model = renderer.createSyncPreviewTableModel(rows);

        JPopupMenu popup = renderer.buildIgnorePopup(rows.get(0), model, rows);
        JMenuItem item = (JMenuItem) popup.getComponent(0);

        // Without this the Windows L&F leaves the text off-center and the entry far wider and
        // taller
        // than the label, which is exactly what the entry is meant to avoid.
        assertTrue(
                item.getUI() instanceof BasicMenuItemUI,
                "The entry must use the paintText-overriding UI that centers the text");
        FontMetrics fm = item.getFontMetrics(item.getFont());
        assertEquals(
                fm.stringWidth(item.getText()) + 12,
                item.getPreferredSize().width,
                "The entry width must be the text width plus a tight padding");
        assertEquals(
                fm.getHeight() + 4,
                item.getPreferredSize().height,
                "The entry height must be the text height plus a tight padding");
        assertTrue(
                popup.getBorder() instanceof LineBorder,
                "The thick system popup border must be replaced by a thin line border");
    }

    private List<String> ignoreFileLines() throws IOException {
        return Files.readAllLines(tempDir.resolve(".filesyncignore"), StandardCharsets.UTF_8);
    }
}
