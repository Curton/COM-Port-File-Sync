package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import org.junit.jupiter.api.Test;

/**
 * Verifies the sync preview's search bar: relevance ranking (extension matches first, then file
 * name matches, then everything else), the re-sort that follows a query change, and the
 * highlighting of the matched text.
 */
class SyncPreviewRendererSearchTest {

    private static SyncPreviewRow row(String path) {
        return new SyncPreviewRow(SyncPreviewOperationType.NEW, path, "1 B", 1L);
    }

    private static DefaultTableModel modelOf(List<SyncPreviewRow> rows) {
        return new SyncPreviewRenderer(null).createSyncPreviewTableModel(rows);
    }

    // --- Ranking ------------------------------------------------------------------------------

    @Test
    void extensionMatchOutranksFileNameMatchWhichOutranksNoMatch() {
        // "java" is the extension -> rank 0; the stem contains "java" -> rank 1; neither -> rank 2.
        assertEquals(0, SyncPreviewRenderer.searchRank("src/Main.java", "java"));
        assertEquals(1, SyncPreviewRenderer.searchRank("java/Main.txt", "java"));
        assertEquals(2, SyncPreviewRenderer.searchRank("src/Main.py", "java"));
    }

    @Test
    void extensionMatchIsFoundOnAFileNameWithMultipleDots() {
        // The extension is everything after the last dot: "gz" here. "tar" is part of the stem, so
        // it ranks as a name match, not an extension match.
        assertEquals(0, SyncPreviewRenderer.searchRank("archive.tar.gz", "gz"));
        assertEquals(1, SyncPreviewRenderer.searchRank("archive.tar.gz", "tar"));
        assertEquals(2, SyncPreviewRenderer.searchRank("archive.tar.gz", "rar"));
    }

    @Test
    void dotFilesHaveNoExtensionSoTheyCannotRankAsAnExtensionMatch() {
        // ".gitignore" is a hidden file, not an extension-carrying one: "gitignore" is its stem.
        assertEquals(1, SyncPreviewRenderer.searchRank(".gitignore", "gitignore"));
        assertEquals(2, SyncPreviewRenderer.searchRank(".gitignore", "ignore.x"));
    }

    @Test
    void rankingIsCaseInsensitive() {
        assertEquals(
                SyncPreviewRenderer.searchRank("a.JAVA", "java"),
                SyncPreviewRenderer.searchRank("a.java", "JAVA"));
        assertEquals(0, SyncPreviewRenderer.searchRank("a.JAVA", "java"));
    }

    @Test
    void matchingIsCaseInsensitiveAndRanksTheUnmatchedPathLast() {
        assertEquals(2, SyncPreviewRenderer.searchRank("SRC/README.MD", "java"));
        assertEquals(0, SyncPreviewRenderer.searchRank("SRC/README.MD", "md"));
        assertTrue(SyncPreviewRenderer.matchesSearch("README.MD", "readme"));
        assertFalse(SyncPreviewRenderer.matchesSearch("README.MD", "java"));
    }

    @Test
    void emptyOrNullQueryMatchesNothingSoOrderingIsUnaffected() {
        assertEquals(2, SyncPreviewRenderer.searchRank("a.java", ""));
        assertEquals(2, SyncPreviewRenderer.searchRank("a.java", "   "));
        assertEquals(2, SyncPreviewRenderer.searchRank("a.java", null));
        assertFalse(SyncPreviewRenderer.matchesSearch("a.java", ""));
    }

    @Test
    void directorySegmentMatchIsTreatedAsANameMatchRatherThanDropped() {
        // A path with no extension, matched via its directory segment, must not be buried with the
        // non-matching rows.
        assertEquals(1, SyncPreviewRenderer.searchRank("java/notes", "java"));
        assertEquals(2, SyncPreviewRenderer.searchRank("python/notes", "java"));
    }

    // --- Re-sorting ---------------------------------------------------------------------------

    @Test
    void searchSortsExtensionMatchesFirstThenNameMatchesThenTheRest() {
        List<SyncPreviewRow> rows =
                List.of(
                        row("notes.txt"),
                        row("Main.java"),
                        row("java-notes.txt"),
                        row("image.png"));
        DefaultTableModel model = modelOf(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);

        SyncPreviewRenderer.applySearch(sorter, "java");

        // View order: extension match (Main.java), name match (java-notes.txt), then the
        // non-matching rows in their normal directory order (image.png, notes.txt).
        assertEquals("Main.java", model.getValueAt(sorter.convertRowIndexToModel(0), 3));
        assertEquals("java-notes.txt", model.getValueAt(sorter.convertRowIndexToModel(1), 3));
        assertEquals("image.png", model.getValueAt(sorter.convertRowIndexToModel(2), 3));
        assertEquals("notes.txt", model.getValueAt(sorter.convertRowIndexToModel(3), 3));
    }

    @Test
    void withinARelevanceGroupRowsKeepDirectoryOrder() {
        List<SyncPreviewRow> rows =
                List.of(row("b.txt"), row("a.txt"), row("z/a.txt"), row("nomatch.md"));
        DefaultTableModel model = modelOf(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);

        SyncPreviewRenderer.applySearch(sorter, "txt");

        // All three .txt rows are extension matches, so they sort by directory order: "a.txt" <
        // "b.txt" < "z/a.txt" (directories interleave with files at the same segment level).
        assertEquals("a.txt", model.getValueAt(sorter.convertRowIndexToModel(0), 3));
        assertEquals("b.txt", model.getValueAt(sorter.convertRowIndexToModel(1), 3));
        assertEquals("z/a.txt", model.getValueAt(sorter.convertRowIndexToModel(2), 3));
        assertEquals("nomatch.md", model.getValueAt(sorter.convertRowIndexToModel(3), 3));
    }

    @Test
    void clearingTheQueryRestoresTheSyncDescendingDefaultOrder() {
        List<SyncPreviewRow> rows = List.of(row("a.txt"), row("b.java"));
        DefaultTableModel model = modelOf(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);

        SyncPreviewRenderer.applySearch(sorter, "java");
        assertEquals("b.java", model.getValueAt(sorter.convertRowIndexToModel(0), 3));

        // An empty query restores the default sort key: Sync descending.
        SyncPreviewRenderer.applySearch(sorter, "   ");
        assertEquals(1, sorter.getSortKeys().size());
        assertEquals(0, sorter.getSortKeys().get(0).getColumn());
        assertEquals(javax.swing.SortOrder.DESCENDING, sorter.getSortKeys().get(0).getSortOrder());
    }

    @Test
    void searchingSortsThePathColumnAscending() {
        List<SyncPreviewRow> rows = List.of(row("a.txt"), row("b.java"));
        DefaultTableModel model = modelOf(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);

        SyncPreviewRenderer.applySearch(sorter, "java");

        assertEquals(SyncPreviewRenderer.PATH_COLUMN, sorter.getSortKeys().get(0).getColumn());
        assertEquals(javax.swing.SortOrder.ASCENDING, sorter.getSortKeys().get(0).getSortOrder());
    }

    @Test
    void searchSurvivesATableSinceTheSorterDrivesTheView() {
        // A JTable is required for the sorter's view indices to be meaningful in the UI path.
        List<SyncPreviewRow> rows = List.of(row("notes.txt"), row("Main.java"));
        DefaultTableModel model = modelOf(rows);
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);
        JTable table = new JTable(model);
        table.setRowSorter(sorter);

        SyncPreviewRenderer.applySearch(sorter, "java");

        assertEquals("Main.java", table.getValueAt(0, 3));
        assertEquals("notes.txt", table.getValueAt(1, 3));
    }

    @Test
    void applySearchIsNullSafe() {
        SyncPreviewRenderer.applySearch(null, "java");
    }

    // --- Highlighting -------------------------------------------------------------------------

    @Test
    void highlightRangePrefersTheExtensionOverTheName() {
        // "txt" appears in both the stem and the extension; the extension is the one the user
        // typed to filter file types, so it is the range that gets marked.
        int[] range = SyncPreviewRenderer.searchHighlightRange("txt-notes.txt", "txt");
        assertNotNull(range);
        assertEquals(10, range[0]);
        assertEquals(13, range[1]);
        assertEquals("txt", "txt-notes.txt".substring(range[0], range[1]));
    }

    @Test
    void highlightRangeFallsBackToTheFileNameStem() {
        int[] range = SyncPreviewRenderer.searchHighlightRange("src/Main.java", "Main");
        assertNotNull(range);
        assertEquals("Main", "src/Main.java".substring(range[0], range[1]));
    }

    @Test
    void highlightRangeHandlesPartialMatches() {
        int[] range = SyncPreviewRenderer.searchHighlightRange("src/Application.java", "lic");
        assertNotNull(range);
        assertEquals("lic", "src/Application.java".substring(range[0], range[1]));
    }

    @Test
    void highlightRangeIsCaseInsensitive() {
        int[] range = SyncPreviewRenderer.searchHighlightRange("src/Main.JAVA", "java");
        assertNotNull(range);
        assertEquals("JAVA", "src/Main.JAVA".substring(range[0], range[1]));
    }

    @Test
    void highlightRangeIsNullWhenThereIsNoMatchOrQuery() {
        assertNull(SyncPreviewRenderer.searchHighlightRange("a.txt", ""));
        assertNull(SyncPreviewRenderer.searchHighlightRange("a.txt", "zzz"));
        assertNull(SyncPreviewRenderer.searchHighlightRange("", "txt"));
        assertNull(SyncPreviewRenderer.searchHighlightRange(null, "txt"));
    }

    @Test
    void highlightRangeDoesNotMarkADotThatIsNotAnExtension() {
        // "java/notes" has no extension at all; a query for "notes" must highlight the stem, and
        // the separator dot must not be treated as an extension boundary.
        int[] range = SyncPreviewRenderer.searchHighlightRange("java/notes", "notes");
        assertNotNull(range);
        assertEquals("notes", "java/notes".substring(range[0], range[1]));
    }

    @Test
    void highlightHtmlWrapsOnlyTheMatchedRangeInAYellowSpan() {
        String html = SyncPreviewRenderer.highlightHtml("Main.java", new int[] {5, 9});
        assertTrue(html.startsWith("<html>"), html);
        assertTrue(html.contains("background:#FFEB3B"), html);
        assertTrue(html.contains(">java<"), html);
        assertTrue(html.contains("Main."), html);
        // The match is the only span; the surrounding text is plain.
        assertEquals(1, html.split("<span", -1).length - 1, html);
    }

    @Test
    void highlightHtmlKeepsSpacesLiteral() {
        // Swing's HTML renderer collapses runs of whitespace unless spaces are escaped, which would
        // shift the highlight off the matched text in a path containing spaces.
        String html = SyncPreviewRenderer.highlightHtml("my notes.txt", new int[] {9, 12});
        assertTrue(html.contains("&#32;"), html);
        assertTrue(html.contains(">txt<"), html);
    }

    @Test
    void highlightHtmlEscapesMarkupInPathSegments() {
        // A path segment containing HTML must not break out of the rendered fragment.
        String html = SyncPreviewRenderer.highlightHtml("<b>x</b>.java", new int[] {9, 13});
        assertFalse(html.contains("<b>x</b>"), html);
        assertTrue(html.contains("&lt;b&gt;"), html);
    }

    @Test
    void highlightIsLocatedWithinATruncatedTailDisplay() {
        // The cell renders a truncated tail prefixed with "...". The match is located in the
        // rendered text itself, so the highlight lands on the right characters even though the
        // synthetic ellipsis means the display is not a byte-exact suffix of the path.
        String display = "...to/Main.java";
        int[] range = SyncPreviewRenderer.searchHighlightRange(display, "java");
        assertNotNull(range);
        assertEquals("java", display.substring(range[0], range[1]));
    }

    @Test
    void aMatchDroppedByTruncationIsNotHighlighted() {
        // The tail kept only the file name, and it carries no occurrence of the query, so there is
        // nothing on screen to mark.
        String display = "...file.txt";
        assertNull(SyncPreviewRenderer.searchHighlightRange(display, "matched"));
    }

    @Test
    void highlightIsNullForAnEmptyDisplayOrQuery() {
        assertNull(SyncPreviewRenderer.searchHighlightRange("", "txt"));
        assertNull(SyncPreviewRenderer.searchHighlightRange("a.txt", ""));
    }

    // --- Search field -------------------------------------------------------------------------

    @Test
    void searchFieldExposesAQueryPropertyAndAPlaceholder() {
        DefaultTableModel model = modelOf(List.of(row("a.txt")));
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);

        javax.swing.JTextField field = new SyncPreviewRenderer(null).createSearchField(sorter);

        assertNotNull(field.getToolTipText());
        assertEquals(
                SyncPreviewRenderer.SEARCH_FIELD_PLACEHOLDER,
                field.getClientProperty("JTextField.placeholderText"));
    }

    @Test
    void applySearchQueryTrimsAndPublishesTheQuery() {
        DefaultTableModel model = modelOf(List.of(row("a.txt"), row("b.java")));
        TableRowSorter<DefaultTableModel> sorter = SyncPreviewRenderer.createPreviewSorter(model);
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);

        renderer.applySearchQuery(sorter, "  java  ");

        assertEquals("java", renderer.getPreviewSearchQuery());
        assertEquals("b.java", model.getValueAt(sorter.convertRowIndexToModel(0), 3));
    }
}
