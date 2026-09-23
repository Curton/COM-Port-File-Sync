package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.*;

import com.filesync.sync.TextDiffUtil.DiffHunk;
import com.filesync.sync.TextDiffUtil.DiffLine;
import com.filesync.sync.TextDiffUtil.DiffLineType;
import com.filesync.sync.TextDiffUtil.DiffResult;
import org.junit.jupiter.api.Test;

/** Unit tests for TextDiffUtil. */
class TextDiffUtilTest {

    @Test
    void testIdenticalTexts() {
        String text = "line1\nline2\nline3";
        DiffResult result = TextDiffUtil.computeDiff(text, text);
        assertFalse(result.hasChanges());
        assertEquals(0, result.getAddedCount());
        assertEquals(0, result.getRemovedCount());
        assertEquals(3, result.getUnchangedCount());
        assertTrue(result.getHunks().isEmpty());
    }

    @Test
    void testEmptyTexts() {
        DiffResult result = TextDiffUtil.computeDiff("", "");
        assertFalse(result.hasChanges());
        assertTrue(result.getHunks().isEmpty());
    }

    @Test
    void testNullTexts() {
        DiffResult result = TextDiffUtil.computeDiff(null, null);
        assertFalse(result.hasChanges());
        assertTrue(result.getHunks().isEmpty());
    }

    @Test
    void testAddedLines() {
        DiffResult result = TextDiffUtil.computeDiff("line1\nline2", "line1\nline2\nline3");
        assertTrue(result.hasChanges());
        assertEquals(1, result.getAddedCount());
        assertEquals(0, result.getRemovedCount());

        result = TextDiffUtil.computeDiff("", "new line");
        assertTrue(result.hasChanges());
        assertEquals(1, result.getAddedCount());
    }

    @Test
    void testRemovedLines() {
        DiffResult result = TextDiffUtil.computeDiff("line1\nline2\nline3", "line1\nline3");
        assertTrue(result.hasChanges());
        assertEquals(0, result.getAddedCount());
        assertEquals(1, result.getRemovedCount());

        result = TextDiffUtil.computeDiff("old line", "");
        assertTrue(result.hasChanges());
        assertEquals(1, result.getRemovedCount());
    }

    @Test
    void testModifiedLines() {
        String local = "line1\nold\nline3";
        String remote = "line1\nnew\nline3";
        DiffResult result = TextDiffUtil.computeDiff(local, remote);
        assertTrue(result.hasChanges());
        assertEquals(1, result.getAddedCount());
        assertEquals(1, result.getRemovedCount());
    }

    @Test
    void testMultipleChanges() {
        String local = "a\nb\nc\nd\ne";
        String remote = "a\nx\nc\ny\ne";
        DiffResult result = TextDiffUtil.computeDiff(local, remote);
        assertEquals(2, result.getAddedCount());
        assertEquals(2, result.getRemovedCount());
    }

    @Test
    void testHunkCreation() {
        String local = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj";
        String remote = "a\nB\nc\nd\ne\nf\ng\nH\ni\nj";
        DiffResult result = TextDiffUtil.computeDiff(local, remote, 2);
        // Changes at lines 2 and 8 should be in separate hunks with context of 2
        assertTrue(result.getHunks().size() >= 1);
    }

    @Test
    void testHunkMerging() {
        String local = "a\nb\nc\nd\ne";
        String remote = "A\nB\nC\nD\nE";
        DiffResult result = TextDiffUtil.computeDiff(local, remote, 1);
        // All changes are close together, should be in one hunk
        assertEquals(1, result.getHunks().size());
    }

    @Test
    void testHasMeaningfulDifferences_ContentChange() {
        String local = "hello world";
        String remote = "hello there";
        assertTrue(TextDiffUtil.hasMeaningfulDifferences(local, remote));
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
            // A real content change must still be detected.
            {"hello\nworld", "hello\n   \nreal change\nworld", true},
        };
        for (Object[] c : cases) {
            assertEquals(
                    c[2],
                    TextDiffUtil.hasMeaningfulDifferences((String) c[0], (String) c[1]),
                    "unexpected verdict for " + c[0] + " vs " + c[1]);
        }
    }

    @Test
    void testNormalizeForComparison_TrailingWhitespace() {
        String text = "hello   \nworld\t\t\n";
        String normalized = TextDiffUtil.normalizeForComparison(text);
        assertEquals("hello\nworld\n", normalized);
    }

    @Test
    void testNormalizeForComparison_MultipleBlankLines() {
        String text = "hello\n\n\n\nworld";
        String normalized = TextDiffUtil.normalizeForComparison(text);
        assertEquals("hello\nworld\n", normalized);
    }

    @Test
    void testNormalizeForComparison_Null() {
        String normalized = TextDiffUtil.normalizeForComparison(null);
        assertEquals("", normalized);
    }

    @Test
    void testDiffLineTypes() {
        String local = "removed\nunchanged";
        String remote = "added\nunchanged";
        DiffResult result = TextDiffUtil.computeDiff(local, remote);

        boolean foundAdded = false;
        boolean foundRemoved = false;
        boolean foundUnchanged = false;

        for (DiffHunk hunk : result.getHunks()) {
            for (DiffLine line : hunk.getLines()) {
                switch (line.getType()) {
                    case ADDED -> foundAdded = true;
                    case REMOVED -> foundRemoved = true;
                    case UNCHANGED -> foundUnchanged = true;
                }
            }
        }

        assertTrue(foundAdded, "Should have added lines");
        assertTrue(foundRemoved, "Should have removed lines");
        assertTrue(foundUnchanged, "Should have unchanged lines");
    }

    @Test
    void testLineNumbers() {
        String local = "line1\nline2\nline3";
        String remote = "line1\nmodified\nline3";
        DiffResult result = TextDiffUtil.computeDiff(local, remote);

        for (DiffHunk hunk : result.getHunks()) {
            for (DiffLine line : hunk.getLines()) {
                if (line.getType() == DiffLineType.UNCHANGED) {
                    assertTrue(line.getLocalLineNumber() > 0);
                    assertTrue(line.getRemoteLineNumber() > 0);
                } else if (line.getType() == DiffLineType.REMOVED) {
                    assertTrue(line.getLocalLineNumber() > 0);
                    assertEquals(-1, line.getRemoteLineNumber());
                } else if (line.getType() == DiffLineType.ADDED) {
                    assertEquals(-1, line.getLocalLineNumber());
                    assertTrue(line.getRemoteLineNumber() > 0);
                }
            }
        }
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

    @Test
    void testCompletelyDifferentTexts() {
        String local = "aaa\nbbb\nccc";
        String remote = "xxx\nyyy\nzzz";
        DiffResult result = TextDiffUtil.computeDiff(local, remote);
        assertTrue(result.hasChanges());
        assertEquals(3, result.getAddedCount());
        assertEquals(3, result.getRemovedCount());
    }

    // ========== large conflicting texts stay bounded ==========

    /** Drops the trailing newline so line counts read directly, without the shared empty last line. */
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
        DiffResult result =
                TextDiffUtil.computeDiff(textOf(local), textOf(remote));
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
        DiffResult result =
                TextDiffUtil.computeDiff(textOf(local), textOf(remote));
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
        DiffResult result =
                TextDiffUtil.computeDiff(textOf(local), textOf(remote));
        assertEquals(2 * edits, result.getChangeCount());
        assertEquals(lines - edits, result.getUnchangedCount());
    }

    // ========== hasMeaningfulDifferences streaming pre-check ==========

    @Test
    void hasMeaningfulDifferences_oneNull() {
        assertTrue(TextDiffUtil.hasMeaningfulDifferences(null, "hello"));
        assertTrue(TextDiffUtil.hasMeaningfulDifferences("hello", null));
    }

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
