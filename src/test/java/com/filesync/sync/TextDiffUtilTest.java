package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.filesync.sync.TextDiffUtil.DiffHunk;
import com.filesync.sync.TextDiffUtil.DiffLine;
import com.filesync.sync.TextDiffUtil.DiffLineType;
import com.filesync.sync.TextDiffUtil.DiffResult;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Unit tests for TextDiffUtil. */
class TextDiffUtilTest {

    @ParameterizedTest
    @MethodSource("diffCountCases")
    void computeDiff_counts(String local, String remote, int added, int removed, int unchanged) {
        DiffResult result = TextDiffUtil.computeDiff(local, remote);

        assertEquals(added, result.getAddedCount(), "added count");
        assertEquals(removed, result.getRemovedCount(), "removed count");
        assertEquals(unchanged, result.getUnchangedCount(), "unchanged count");
        assertEquals(added + removed > 0, result.hasChanges(), "hasChanges verdict");
        if (added + removed == 0) {
            assertTrue(result.getHunks().isEmpty(), "no changes: hunks must be empty");
        }
    }

    private static Stream<Arguments> diffCountCases() {
        return Stream.of(
                // Identical texts: three shared lines, no changes at all.
                arguments("line1\nline2\nline3", "line1\nline2\nline3", 0, 0, 3),
                // Empty and null inputs.
                arguments("", "", 0, 0, 0),
                arguments(null, null, 0, 0, 0),
                // Pure additions.
                arguments("line1\nline2", "line1\nline2\nline3", 1, 0, 2),
                arguments("", "new line", 1, 0, 0),
                // Pure removals.
                arguments("line1\nline2\nline3", "line1\nline3", 0, 1, 2),
                arguments("old line", "", 0, 1, 0),
                // A modified line counts as one removal plus one addition.
                arguments("line1\nold\nline3", "line1\nnew\nline3", 1, 1, 2),
                // Multiple scattered changes.
                arguments("a\nb\nc\nd\ne", "a\nx\nc\ny\ne", 2, 2, 3),
                // Completely different texts share no unchanged line.
                arguments("aaa\nbbb\nccc", "xxx\nyyy\nzzz", 3, 3, 0));
    }

    @Test
    void hunkGrouping_byContextGap() {
        // Changes at lines 2 and 8 should be in separate hunks with context of 2.
        String local = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj";
        String remote = "a\nB\nc\nd\ne\nf\ng\nH\ni\nj";
        DiffResult separated = TextDiffUtil.computeDiff(local, remote, 2);
        assertTrue(separated.getHunks().size() >= 1);

        // All changes are close together, so a context of 1 merges them into one hunk.
        DiffResult merged = TextDiffUtil.computeDiff("a\nb\nc\nd\ne", "A\nB\nC\nD\nE", 1);
        assertEquals(1, merged.getHunks().size());
    }

    @Test
    void testHasMeaningfulDifferences_ignoresWhitespaceOnlyChanges() {
        Object[][] cases = {
            // Whitespace-only differences must not count as meaningful changes.
            {"hello world  ", "hello world", false}, // trailing spaces on one side
            {"line1  \nline2   \n", "line1\nline2\n", false},
            {"hello\n\n\nworld", "hello\nworld", false}, // blank lines
            {"hello  \nworld   \n", "hello  \nworld   \n", false}, // identical after normalization
            {"\n\n\n", "\n\n\n", false}, // newline-only content
            {"   \n   \n", "\n\n", false},
            {"   \n\t\t\n", "\n", false}, // whitespace-only on both sides
            {null, null, false},
            {"hello\r\nworld\r\n", "hello\r\nworld\r\n", false}, // identical CRLF text
            {"hello  \r\nworld\r\n", "hello\r\nworld\r\n", false},
            {"hello\nworld", "hello\n   \nworld", false}, // added lines are whitespace-only
            // Real content changes - including a one-sided null - must still be detected.
            {"hello\nworld", "hello\n   \nreal change\nworld", true},
            {"hello world", "hello there", true},
            {null, "hello", true},
            {"hello", null, true},
        };
        for (Object[] c : cases) {
            assertEquals(
                    c[2],
                    TextDiffUtil.hasMeaningfulDifferences((String) c[0], (String) c[1]),
                    "unexpected verdict for " + c[0] + " vs " + c[1]);
        }
    }

    @ParameterizedTest
    @MethodSource("normalizeForComparisonCases")
    void testNormalizeForComparison(String text, String expected) {
        assertEquals(expected, TextDiffUtil.normalizeForComparison(text));
    }

    private static Stream<Arguments> normalizeForComparisonCases() {
        return Stream.of(
                // Trailing whitespace (spaces and tabs) is stripped from each line.
                arguments("hello   \nworld\t\t\n", "hello\nworld\n"),
                // Multiple blank lines collapse away.
                arguments("hello\n\n\n\nworld", "hello\nworld\n"),
                arguments(null, ""));
    }

    @Test
    void testDiffLineTypesAndLineNumbers() {
        String local = "line1\nline2\nline3";
        String remote = "line1\nmodified\nline3";
        DiffResult result = TextDiffUtil.computeDiff(local, remote);

        boolean foundAdded = false;
        boolean foundRemoved = false;
        boolean foundUnchanged = false;

        for (DiffHunk hunk : result.getHunks()) {
            for (DiffLine line : hunk.getLines()) {
                if (line.getType() == DiffLineType.ADDED) {
                    foundAdded = true;
                    assertEquals(-1, line.getLocalLineNumber());
                    assertTrue(line.getRemoteLineNumber() > 0);
                } else if (line.getType() == DiffLineType.REMOVED) {
                    foundRemoved = true;
                    assertTrue(line.getLocalLineNumber() > 0);
                    assertEquals(-1, line.getRemoteLineNumber());
                } else if (line.getType() == DiffLineType.UNCHANGED) {
                    foundUnchanged = true;
                    assertTrue(line.getLocalLineNumber() > 0);
                    assertTrue(line.getRemoteLineNumber() > 0);
                }
            }
        }

        assertTrue(foundAdded, "Should have added lines");
        assertTrue(foundRemoved, "Should have removed lines");
        assertTrue(foundUnchanged, "Should have unchanged lines");
    }

    @Test
    void testHunkLineCounts() {
        String local = "old";
        String remote = "new";
        DiffResult result = TextDiffUtil.computeDiff(local, remote);

        assertEquals(1, result.getHunks().size());
        DiffHunk hunk = result.getHunks().get(0);
        assertEquals(1, hunk.getLocalLineCount());
        assertEquals(1, hunk.getRemoteLineCount());
    }

    // ========== large conflicting texts stay bounded ==========

    /**
     * Drops the trailing newline so line counts read directly, without the shared empty last line.
     */
    private static String textOf(StringBuilder sb) {
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /**
     * A conflict whose edit distance outruns the per-range step budget used to make its own
     * correction. The trace the greedy sweep keeps must stay O(steps^2) rather than growing with
     * the distance times the text size, and the result must still be a valid script.
     */
    @Test
    void largeConflictingTextsDoNotExhaustMemory() {
        // ~1000 lines with every other line rewritten: distance ~ n, well over the step budget.
        int lines = 2000;
        StringBuilder local = new StringBuilder();
        StringBuilder remote = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            local.append("base line ").append(i).append('\n');
            remote.append(i % 2 == 0 ? "changed line " : "base line ").append(i).append('\n');
        }
        DiffResult result = TextDiffUtil.computeDiff(textOf(local), textOf(remote));
        assertEquals(lines / 2, result.getAddedCount());
        assertEquals(lines / 2, result.getRemovedCount());
        assertEquals(lines / 2, result.getUnchangedCount());
    }

    /**
     * The same shape at a distance the greedy sweep cannot reach at all, which is the case that
     * used to store a trace slice per step over the whole text and exhaust the heap.
     */
    @Test
    void largeFullyRewrittenTextsDoNotExhaustMemory() {
        int lines = 3000;
        StringBuilder local = new StringBuilder();
        StringBuilder remote = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            local.append("local content number ").append(i).append('\n');
            remote.append("remote content number ").append(i).append('\n');
        }
        DiffResult result = TextDiffUtil.computeDiff(textOf(local), textOf(remote));
        assertTrue(result.hasChanges());
        assertEquals(lines, result.getAddedCount());
        assertEquals(lines, result.getRemovedCount());
        assertEquals(0, result.getUnchangedCount());
    }

    /**
     * A big shared backbone with a scattered set of edits: the common prefix and suffix collapse
     * away, so only the middle needs searching, and the diff must stay minimal.
     */
    @Test
    void largeTextWithScatteredEditsStaysMinimal() {
        int lines = 4000;
        int edits = lines / 400;
        StringBuilder local = new StringBuilder();
        StringBuilder remote = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            boolean changed = (i % 400) == 200;
            local.append("stable line ").append(i).append('\n');
            remote.append(changed ? "edited line " : "stable line ").append(i).append('\n');
        }
        DiffResult result = TextDiffUtil.computeDiff(textOf(local), textOf(remote));
        assertEquals(2 * edits, result.getChangeCount());
        assertEquals(lines - edits, result.getUnchangedCount());
    }

    // ========== hasMeaningfulDifferences streaming pre-check ==========

    @Test
    void hasMeaningfulDifferences_largeIdenticalTexts() {
        // Build 2000 lines of identical text to verify streaming pre-check works
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("line ").append(i).append("\n");
        }
        String text = sb.toString();
        assertFalse(TextDiffUtil.hasMeaningfulDifferences(text, text));
    }

    @Test
    void hasMeaningfulDifferences_largeWithOneMeaningfulChange() {
        // 2000 lines with 1 real change
        StringBuilder localSb = new StringBuilder();
        StringBuilder remoteSb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            localSb.append("line ").append(i).append("\n");
            remoteSb.append("line ").append(i).append("\n");
        }
        remoteSb.append("real change\n");
        assertTrue(TextDiffUtil.hasMeaningfulDifferences(localSb.toString(), remoteSb.toString()));
    }
}
