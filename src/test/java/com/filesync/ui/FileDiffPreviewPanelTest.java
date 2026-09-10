package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import javax.swing.JScrollPane;
import org.junit.jupiter.api.Test;

/** Verifies the preview panel renders text diffs, placeholders, and missing-side explanations. */
class FileDiffPreviewPanelTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static FileDiffPreviewModel textModel(String base, String source) {
        return FileDiffPreviewModel.of(
                "src/App.java",
                SyncPreviewOperationType.MODIFIED,
                utf8(source),
                null,
                utf8(base),
                true,
                null,
                false);
    }

    @Test
    void textDiffHighlightsAddedAndRemovedLines() {
        FileDiffPreviewPanel panel =
                new FileDiffPreviewPanel(
                        textModel("keep\nold line\ntail\n", "keep\nnew line\ntail\n"));

        assertFalse(panel.isBinaryPlaceholderShown());
        assertEquals(1, panel.getHunkCount());

        String base = panel.getBasePaneText();
        String source = panel.getSourcePaneText();
        // The removed line appears only on the previous-version side, marked with "- ".
        assertTrue(base.contains("- old line"), base);
        assertTrue(source.contains("+ new line"), source);
        assertFalse(base.contains("new line"), base);
        assertFalse(source.contains("old line"), source);
        // Context lines appear on both sides so the change stays readable in place.
        assertTrue(base.contains("keep"), base);
        assertTrue(source.contains("keep"), source);
    }

    @Test
    void identicalTextShowsFullContentWithoutHunks() {
        FileDiffPreviewPanel panel =
                new FileDiffPreviewPanel(textModel("same\nlines\n", "same\nlines\n"));

        assertEquals(0, panel.getHunkCount());
        assertEquals("same\nlines\n", panel.getBasePaneText());
        assertEquals("same\nlines\n", panel.getSourcePaneText());
    }

    @Test
    void binaryFileShowsPlaceholderInsteadOfDiff() {
        byte[] binary = new byte[] {0x00, 0x01, 0x02, 0x03, (byte) 0xFF};
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "data.bin",
                        SyncPreviewOperationType.MODIFIED,
                        binary,
                        null,
                        binary,
                        true,
                        null,
                        false);

        FileDiffPreviewPanel panel = new FileDiffPreviewPanel(model);
        assertTrue(panel.isBinaryPlaceholderShown());
        assertEquals(0, panel.getHunkCount());
    }

    @Test
    void newFileExplainsThatNoPreviousVersionExists() {
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "brand-new.txt",
                        SyncPreviewOperationType.NEW,
                        utf8("first content\n"),
                        null,
                        null,
                        false,
                        null,
                        false);

        FileDiffPreviewPanel panel = new FileDiffPreviewPanel(model);

        // The base pane carries the explanation, not a silently empty buffer.
        String base = panel.getBasePaneText();
        assertTrue(base.contains("No previous version"), base);
        // The new content is still shown even though there is nothing to diff against.
        assertTrue(panel.getSourcePaneText().contains("first content"));
    }

    @Test
    void failedFetchIsExplainedRatherThanShownAsEmpty() {
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "notes.txt",
                        SyncPreviewOperationType.MODIFIED,
                        utf8("local version\n"),
                        null,
                        null,
                        true,
                        "The peer's version could not be retrieved.",
                        false);

        FileDiffPreviewPanel panel = new FileDiffPreviewPanel(model);

        String base = panel.getBasePaneText();
        assertTrue(base.contains("could not be retrieved"), base);
    }

    @Test
    void panelIsScrollableForBothSides() {
        FileDiffPreviewPanel panel = new FileDiffPreviewPanel(textModel("a\n", "b\n"));

        // Find the scroll panes that wrap the two content areas.
        long scrollPanes = countScrollPanes(panel);
        assertTrue(scrollPanes >= 2, "expected both panes to be scrollable, found " + scrollPanes);
    }

    private static int countScrollPanes(java.awt.Container container) {
        int count = 0;
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof JScrollPane) {
                count++;
            }
            if (child instanceof java.awt.Container nested) {
                count += countScrollPanes(nested);
            }
        }
        return count;
    }
}
