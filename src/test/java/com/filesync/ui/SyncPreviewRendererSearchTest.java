package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Stream;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    @ParameterizedTest
    @MethodSource("searchRankSamples")
    void searchRankBucketsMatches(String path, String query, int expectedRank) {
        assertEquals(expectedRank, SyncPreviewRenderer.searchRank(path, query));
    }

    private static Stream<Arguments> searchRankSamples() {
        return Stream.of(
                // "java" is the extension -> rank 0; the stem contains "java" -> rank 1; neither
                // -> rank 2.
                arguments("src/Main.java", "java", 0),
                arguments("java/Main.txt", "java", 1),
                arguments("src/Main.py", "java", 2),
                // The extension is everything after the last dot: "gz" here. "tar" is part of the
                // stem, so it ranks as a name match, not an extension match.
                arguments("archive.tar.gz", "gz", 0),
                arguments("archive.tar.gz", "tar", 1),
                arguments("archive.tar.gz", "rar", 2),
                // ".gitignore" is a hidden file, not an extension-carrying one: "gitignore" is its
                // stem.
                arguments(".gitignore", "gitignore", 1),
                arguments(".gitignore", "ignore.x", 2),
                // Ranking is case-insensitive on either side of the comparison.
                arguments("a.JAVA", "java", 0),
                arguments("a.java", "JAVA", 0),
                arguments("SRC/README.MD", "java", 2),
                arguments("SRC/README.MD", "md", 0),
                // An empty or blank query matches nothing, so the ordering is left untouched.
                arguments("a.java", "", 2),
                arguments("a.java", "   ", 2),
                arguments("a.java", null, 2),
                // A path with no extension, matched via its directory segment, must not be buried
                // with the non-matching rows.
                arguments("java/notes", "java", 1),
                arguments("python/notes", "java", 2));
    }

    @ParameterizedTest
    @MethodSource("matchesSearchSamples")
    void matchesSearchMatchesItsInput(String path, String query, boolean expected) {
        assertEquals(expected, SyncPreviewRenderer.matchesSearch(path, query));
    }

    private static Stream<Arguments> matchesSearchSamples() {
        return Stream.of(
                // Matching is case-insensitive; an empty query matches nothing.
                arguments("README.MD", "readme", true),
                arguments("README.MD", "java", false),
                arguments("a.java", "", false));
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

    @ParameterizedTest
    @MethodSource("highlightSubstringSamples")
    void highlightRangeMarksTheExpectedSubstring(
            String display, String query, String expectedSubstring) {
        int[] range = SyncPreviewRenderer.searchHighlightRange(display, query);
        assertNotNull(range);
        assertEquals(expectedSubstring, display.substring(range[0], range[1]));
    }

    private static Stream<Arguments> highlightSubstringSamples() {
        return Stream.of(
                // The stem is used when the query does not match the extension.
                arguments("src/Main.java", "Main", "Main"),
                // Partial stem matches highlight just the matched part.
                arguments("src/Application.java", "lic", "lic"),
                // Case-insensitive: the marked characters come from the display text.
                arguments("src/Main.JAVA", "java", "JAVA"),
                // "java/notes" has no extension at all; the separator dot must not be treated as
                // an extension boundary.
                arguments("java/notes", "notes", "notes"),
                // The cell renders a truncated tail prefixed with "...": the match is located in
                // the rendered text itself, so the highlight lands on the right characters even
                // though the synthetic ellipsis means the display is not a byte-exact suffix of
                // the path.
                arguments("...to/Main.java", "java", "java"));
    }

    @ParameterizedTest
    @MethodSource("nullHighlightSamples")
    void highlightRangeIsNullWhenThereIsNoMatchOrQuery(String display, String query) {
        assertNull(SyncPreviewRenderer.searchHighlightRange(display, query));
    }

    private static Stream<Arguments> nullHighlightSamples() {
        return Stream.of(
                arguments("a.txt", ""),
                arguments("a.txt", "zzz"),
                arguments("", "txt"),
                arguments(null, "txt"),
                // The tail kept only the file name, and it carries no occurrence of the query, so
                // there is nothing on screen to mark.
                arguments("...file.txt", "matched"));
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
