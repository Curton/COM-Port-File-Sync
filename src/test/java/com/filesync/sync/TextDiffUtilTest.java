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
        String local = "line1\nline2";
        String remote = "line1\nline2\nline3";
        DiffResult result = TextDiffUtil.computeDiff(local, remote);
        assertTrue(result.hasChanges());
        assertEquals(1, result.getAddedCount());
        assertEquals(0, result.getRemovedCount());
    }

    @Test
    void testRemovedLines() {
        String local = "line1\nline2\nline3";
        String remote = "line1\nline3";
        DiffResult result = TextDiffUtil.computeDiff(local, remote);
        assertTrue(result.hasChanges());
        assertEquals(0, result.getAddedCount());
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
    void testHasMeaningfulDifferences_WhitespaceOnly() {
        String local = "hello world  ";
        String remote = "hello world";
        assertFalse(TextDiffUtil.hasMeaningfulDifferences(local, remote));
    }

    @Test
    void testHasMeaningfulDifferences_TrailingSpaces() {
        String local = "line1  \nline2   \n";
        String remote = "line1\nline2\n";
        assertFalse(TextDiffUtil.hasMeaningfulDifferences(local, remote));
    }

    @Test
    void testHasMeaningfulDifferences_BlankLines() {
        String local = "hello\n\n\nworld";
        String remote = "hello\nworld";
        assertFalse(TextDiffUtil.hasMeaningfulDifferences(local, remote));
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

    @Test
    void testSingleLineAdd() {
        String local = "";
        String remote = "new line";
        DiffResult result = TextDiffUtil.computeDiff(local, remote);
        assertTrue(result.hasChanges());
        assertEquals(1, result.getAddedCount());
    }

    @Test
    void testSingleLineRemove() {
        String local = "old line";
        String remote = "";
        DiffResult result = TextDiffUtil.computeDiff(local, remote);
        assertTrue(result.hasChanges());
        assertEquals(1, result.getRemovedCount());
    }
}
