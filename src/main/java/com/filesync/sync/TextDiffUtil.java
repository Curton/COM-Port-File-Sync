package com.filesync.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for computing line-by-line differences between two text files, similar to git diff. Uses
 * LCS (Longest Common Subsequence) algorithm for diff computation.
 */
public final class TextDiffUtil {

    /** Type of change for a single line. */
    public enum DiffLineType {
        /** Line is unchanged (present in both versions) */
        UNCHANGED,
        /** Line was removed (present only in local/version A) */
        REMOVED,
        /** Line was added (present only in remote/version B) */
        ADDED
    }

    /** A single line in the diff result. */
    public static final class DiffLine {
        private final DiffLineType type;
        private final String content;

        /** Line number in the original (local) text, 1-based. -1 if added. */
        private final int localLineNumber;

        /** Line number in the new (remote) text, 1-based. -1 if removed. */
        private final int remoteLineNumber;

        public DiffLine(
                DiffLineType type, String content, int localLineNumber, int remoteLineNumber) {
            this.type = type;
            this.content = content;
            this.localLineNumber = localLineNumber;
            this.remoteLineNumber = remoteLineNumber;
        }

        public DiffLineType getType() {
            return type;
        }

        public String getContent() {
            return content;
        }

        public int getLocalLineNumber() {
            return localLineNumber;
        }

        public int getRemoteLineNumber() {
            return remoteLineNumber;
        }

        @Override
        public String toString() {
            String prefix =
                    switch (type) {
                        case UNCHANGED -> " ";
                        case REMOVED -> "-";
                        case ADDED -> "+";
                    };
            return prefix + " " + content;
        }
    }

    /**
     * A contiguous block of changes with surrounding context lines. Similar to a "hunk" in unified
     * diff format.
     */
    public static final class DiffHunk {
        private final List<DiffLine> lines;

        /** Starting line number in local (1-based) */
        private final int localStartLine;

        /** Starting line number in remote (1-based) */
        private final int remoteStartLine;

        public DiffHunk(List<DiffLine> lines, int localStartLine, int remoteStartLine) {
            this.lines = List.copyOf(lines);
            this.localStartLine = localStartLine;
            this.remoteStartLine = remoteStartLine;
        }

        public List<DiffLine> getLines() {
            return lines;
        }

        public int getLocalStartLine() {
            return localStartLine;
        }

        public int getRemoteStartLine() {
            return remoteStartLine;
        }

        /** Number of lines in local version covered by this hunk. */
        public int getLocalLineCount() {
            return (int)
                    lines.stream()
                            .filter(
                                    l ->
                                            l.getType() == DiffLineType.UNCHANGED
                                                    || l.getType() == DiffLineType.REMOVED)
                            .count();
        }

        /** Number of lines in remote version covered by this hunk. */
        public int getRemoteLineCount() {
            return (int)
                    lines.stream()
                            .filter(
                                    l ->
                                            l.getType() == DiffLineType.UNCHANGED
                                                    || l.getType() == DiffLineType.ADDED)
                            .count();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("@@ -")
                    .append(localStartLine)
                    .append(",")
                    .append(getLocalLineCount())
                    .append(" +")
                    .append(remoteStartLine)
                    .append(",")
                    .append(getRemoteLineCount())
                    .append(" @@\n");
            for (DiffLine line : lines) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    /** Complete diff result between two texts. */
    public static final class DiffResult {
        private final List<DiffHunk> hunks;
        private final int addedCount;
        private final int removedCount;
        private final int unchangedCount;

        public DiffResult(
                List<DiffHunk> hunks, int addedCount, int removedCount, int unchangedCount) {
            this.hunks = List.copyOf(hunks);
            this.addedCount = addedCount;
            this.removedCount = removedCount;
            this.unchangedCount = unchangedCount;
        }

        public List<DiffHunk> getHunks() {
            return hunks;
        }

        public int getAddedCount() {
            return addedCount;
        }

        public int getRemovedCount() {
            return removedCount;
        }

        public int getUnchangedCount() {
            return unchangedCount;
        }

        /** Total number of changed lines (added + removed). */
        public int getChangeCount() {
            return addedCount + removedCount;
        }

        /** True if there are any differences at all. */
        public boolean hasChanges() {
            return addedCount > 0 || removedCount > 0;
        }

        /** True if there are meaningful (non-whitespace) differences. */
        public boolean hasMeaningfulChanges() {
            for (DiffHunk hunk : hunks) {
                for (DiffLine line : hunk.getLines()) {
                    if (line.getType() == DiffLineType.ADDED
                            || line.getType() == DiffLineType.REMOVED) {
                        String trimmed = line.getContent().trim();
                        if (!trimmed.isEmpty()) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    /** Number of context lines to include around each change. */
    private static final int DEFAULT_CONTEXT_LINES = 3;

    private TextDiffUtil() {
        // Utility class
    }

    /**
     * Compute the diff between two text strings.
     *
     * @param local the original/local text (may be null, treated as empty)
     * @param remote the new/remote text (may be null, treated as empty)
     * @return diff result with hunks
     */
    public static DiffResult computeDiff(String local, String remote) {
        return computeDiff(local, remote, DEFAULT_CONTEXT_LINES);
    }

    /**
     * Compute the diff between two text strings with configurable context lines.
     *
     * @param local the original/local text (may be null, treated as empty)
     * @param remote the new/remote text (may be null, treated as empty)
     * @param contextLines number of unchanged lines to include around each change
     * @return diff result with hunks
     */
    public static DiffResult computeDiff(String local, String remote, int contextLines) {
        String[] localLines = splitLines(local);
        String[] remoteLines = splitLines(remote);

        // Compute LCS
        int[][] lcs = computeLCS(localLines, remoteLines);

        // Backtrack to find the diff operations
        List<DiffLine> allDiffLines = backtrackDiff(lcs, localLines, remoteLines);

        // Count changes
        int addedCount = 0;
        int removedCount = 0;
        int unchangedCount = 0;
        for (DiffLine line : allDiffLines) {
            switch (line.getType()) {
                case ADDED -> addedCount++;
                case REMOVED -> removedCount++;
                case UNCHANGED -> unchangedCount++;
            }
        }

        // Group into hunks with context
        List<DiffHunk> hunks = createHunks(allDiffLines, contextLines);

        return new DiffResult(hunks, addedCount, removedCount, unchangedCount);
    }

    /**
     * Check if two texts have meaningful differences (ignoring whitespace-only changes).
     *
     * @param local the original/local text
     * @param remote the new/remote text
     * @return true if there are content changes beyond whitespace
     */
    public static boolean hasMeaningfulDifferences(String local, String remote) {
        String localNormalized = normalizeForComparison(local);
        String remoteNormalized = normalizeForComparison(remote);
        if (localNormalized.equals(remoteNormalized)) {
            return false;
        }
        DiffResult diff = computeDiff(local, remote);
        return diff.hasMeaningfulChanges();
    }

    /**
     * Normalize text for comparison by stripping trailing whitespace from each line and removing
     * blank lines entirely.
     */
    public static String normalizeForComparison(String text) {
        if (text == null) {
            return "";
        }
        // Use split without limit to drop trailing empty strings
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.replaceAll("[\\s]+$", "");
            if (!trimmed.isEmpty()) {
                sb.append(trimmed).append("\n");
            }
            // Skip blank lines entirely to ignore extra blank line differences
        }
        return sb.toString();
    }

    /** Split text into lines, stripping carriage returns for cross-platform compatibility. */
    private static String[] splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        // Strip \r before splitting so Windows and Unix line endings produce identical diffs
        return text.replace("\r", "").split("\n", -1);
    }

    /**
     * Compute the LCS (Longest Common Subsequence) table. Returns a 2D array where lcs[i][j] is the
     * length of LCS of localLines[0..i-1] and remoteLines[0..j-1].
     */
    private static int[][] computeLCS(String[] localLines, String[] remoteLines) {
        int m = localLines.length;
        int n = remoteLines.length;
        int[][] lcs = new int[m + 1][n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (localLines[i - 1].equals(remoteLines[j - 1])) {
                    lcs[i][j] = lcs[i - 1][j - 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i - 1][j], lcs[i][j - 1]);
                }
            }
        }

        return lcs;
    }

    /** Backtrack through the LCS table to produce the list of diff operations. */
    private static List<DiffLine> backtrackDiff(
            int[][] lcs, String[] localLines, String[] remoteLines) {
        List<DiffLine> result = new ArrayList<>();
        int i = localLines.length;
        int j = remoteLines.length;

        // We need to backtrack from (m,n) to (0,0), collecting operations in reverse order
        List<DiffLine> reversed = new ArrayList<>();

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && localLines[i - 1].equals(remoteLines[j - 1])) {
                // Lines match
                reversed.add(new DiffLine(DiffLineType.UNCHANGED, localLines[i - 1], i, j));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
                // Line added in remote (or we came from the left)
                reversed.add(new DiffLine(DiffLineType.ADDED, remoteLines[j - 1], -1, j));
                j--;
            } else if (i > 0) {
                // Line removed from local (or we came from above)
                reversed.add(new DiffLine(DiffLineType.REMOVED, localLines[i - 1], i, -1));
                i--;
            }
        }

        // Reverse to get correct order
        for (int k = reversed.size() - 1; k >= 0; k--) {
            result.add(reversed.get(k));
        }

        return result;
    }

    /** Group diff lines into hunks with context lines around changes. */
    private static List<DiffHunk> createHunks(List<DiffLine> allLines, int contextLines) {
        List<DiffHunk> hunks = new ArrayList<>();
        if (allLines.isEmpty()) {
            return hunks;
        }

        // Find indices of changed lines
        List<Integer> changeIndices = new ArrayList<>();
        for (int i = 0; i < allLines.size(); i++) {
            DiffLine line = allLines.get(i);
            if (line.getType() == DiffLineType.ADDED || line.getType() == DiffLineType.REMOVED) {
                changeIndices.add(i);
            }
        }

        if (changeIndices.isEmpty()) {
            // No changes - return empty hunks
            return hunks;
        }

        // Group changes that are close enough to share context
        List<List<Integer>> groups = new ArrayList<>();
        List<Integer> currentGroup = new ArrayList<>();
        currentGroup.add(changeIndices.get(0));

        for (int g = 1; g < changeIndices.size(); g++) {
            int prevIdx = changeIndices.get(g - 1);
            int currIdx = changeIndices.get(g);
            // If gap between changes is small enough to share context
            if (currIdx - prevIdx <= contextLines * 2 + 1) {
                currentGroup.add(currIdx);
            } else {
                groups.add(new ArrayList<>(currentGroup));
                currentGroup.clear();
                currentGroup.add(currIdx);
            }
        }
        groups.add(currentGroup);

        // Create hunks from groups
        for (List<Integer> group : groups) {
            int firstChange = group.get(0);
            int lastChange = group.get(group.size() - 1);

            // Include context lines before and after
            int startIdx = Math.max(0, firstChange - contextLines);
            int endIdx = Math.min(allLines.size() - 1, lastChange + contextLines);

            List<DiffLine> hunkLines = new ArrayList<>();
            for (int k = startIdx; k <= endIdx; k++) {
                hunkLines.add(allLines.get(k));
            }

            // Calculate start line numbers
            int localStart = findLocalStartLine(allLines, startIdx);
            int remoteStart = findRemoteStartLine(allLines, startIdx);

            hunks.add(new DiffHunk(hunkLines, localStart, remoteStart));
        }

        return hunks;
    }

    /** Find the local line number where the hunk at the given index starts. */
    private static int findLocalStartLine(List<DiffLine> allLines, int idx) {
        for (int i = idx; i < allLines.size(); i++) {
            DiffLine line = allLines.get(i);
            if (line.getLocalLineNumber() > 0) {
                return line.getLocalLineNumber();
            }
        }
        // No subsequent line with a local line number — scan backward from idx
        // to find the last context/removed line's local position
        for (int i = idx - 1; i >= 0; i--) {
            DiffLine line = allLines.get(i);
            if (line.getLocalLineNumber() > 0) {
                return line.getLocalLineNumber() + 1;
            }
        }
        return 1;
    }

    /** Find the remote line number where the hunk at the given index starts. */
    private static int findRemoteStartLine(List<DiffLine> allLines, int idx) {
        for (int i = idx; i < allLines.size(); i++) {
            DiffLine line = allLines.get(i);
            if (line.getRemoteLineNumber() > 0) {
                return line.getRemoteLineNumber();
            }
        }
        // No subsequent line with a remote line number — scan backward from idx
        // to find the last context/added line's remote position
        for (int i = idx - 1; i >= 0; i--) {
            DiffLine line = allLines.get(i);
            if (line.getRemoteLineNumber() > 0) {
                return line.getRemoteLineNumber() + 1;
            }
        }
        return 1;
    }
}
