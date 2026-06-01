package com.filesync.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

        List<DiffLine> allDiffLines = myersDiff(localLines, remoteLines);

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
        // Quick equality check on normalized forms
        String localNormalized = normalizeForComparison(local);
        String remoteNormalized = normalizeForComparison(remote);
        if (localNormalized.equals(remoteNormalized)) {
            return false;
        }

        // Streaming scan: compare normalized lines without building a full diff
        String[] localLines = splitAndNormalizeLines(local);
        String[] remoteLines = splitAndNormalizeLines(remote);

        int i = 0, j = 0;
        while (i < localLines.length && j < remoteLines.length) {
            if (localLines[i].equals(remoteLines[j])) {
                i++;
                j++;
            } else {
                // Lines differ — check if the difference is non-whitespace
                if (!localLines[i].trim().isEmpty() || !remoteLines[j].trim().isEmpty()) {
                    return true;
                }
                // Skip whitespace-only lines on both sides
                if (localLines[i].trim().isEmpty()) i++;
                if (remoteLines[j].trim().isEmpty()) j++;
            }
        }
        // Check remaining lines
        while (i < localLines.length) {
            if (!localLines[i].trim().isEmpty()) return true;
            i++;
        }
        while (j < remoteLines.length) {
            if (!remoteLines[j].trim().isEmpty()) return true;
            j++;
        }
        return false;
    }

    /** Split text into lines and normalize each (strip trailing whitespace, skip blank). */
    private static String[] splitAndNormalizeLines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        String raw = text.replace("\r", "");
        String[] rawLines = raw.split("\n", -1);
        List<String> result = new ArrayList<>();
        for (String line : rawLines) {
            String normalized = line.stripTrailing();
            result.add(normalized);
        }
        return result.toArray(new String[0]);
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
     * Myers' O(ND) diff algorithm. Returns diff lines directly, avoiding the quadratic-space LCS
     * table. Time: O((N+M)*D), Space: O(D*(N+M)) where D is the edit distance.
     */
    private static List<DiffLine> myersDiff(String[] a, String[] b) {
        int n = a.length;
        int m = b.length;

        if (n == 0 && m == 0) {
            return List.of();
        }
        if (n == 0) {
            List<DiffLine> result = new ArrayList<>();
            for (int j = 0; j < m; j++) {
                result.add(new DiffLine(DiffLineType.ADDED, b[j], -1, j + 1));
            }
            return result;
        }
        if (m == 0) {
            List<DiffLine> result = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                result.add(new DiffLine(DiffLineType.REMOVED, a[i], i + 1, -1));
            }
            return result;
        }

        int max = n + m;
        int offset = max;
        int[] v = new int[2 * max + 1];
        Arrays.fill(v, -1);
        v[offset + 1] = 0;

        List<int[]> trace = new ArrayList<>();
        int foundD = -1;

        forward:
        for (int d = 0; d <= max; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) {
                    x = v[k + 1 + offset];
                } else {
                    x = v[k - 1 + offset] + 1;
                }
                int y = x - k;

                while (x < n && y < m && a[x].equals(b[y])) {
                    x++;
                    y++;
                }

                v[k + offset] = x;

                if (x >= n && y >= m) {
                    foundD = d;
                    break forward;
                }
            }
        }

        if (foundD == -1) {
            return List.of();
        }

        // Backtrack through the trace to produce diff lines
        List<DiffLine> result = new ArrayList<>();
        int cx = n, cy = m;

        for (int d = foundD; d > 0; d--) {
            int[] vPrev = trace.get(d);
            int k = cx - cy;

            boolean fromAbove;
            if (k == -d) {
                fromAbove = true;
            } else if (k == d) {
                fromAbove = false;
            } else {
                fromAbove = vPrev[k - 1 + offset] < vPrev[k + 1 + offset];
            }

            int prevK = fromAbove ? k + 1 : k - 1;
            int prevX = vPrev[prevK + offset];
            int prevY = prevX - prevK;

            int midX = fromAbove ? prevX : prevX + 1;
            int midY = fromAbove ? prevY + 1 : prevY;

            // Snake from (midX, midY) to (cx, cy) — all UNCHANGED
            int sx = cx, sy = cy;
            while (sx > midX && sy > midY) {
                sx--;
                sy--;
                result.add(new DiffLine(DiffLineType.UNCHANGED, a[sx], sx + 1, sy + 1));
            }

            // The edit step
            if (fromAbove) {
                result.add(new DiffLine(DiffLineType.ADDED, b[prevY], -1, prevY + 1));
            } else {
                result.add(new DiffLine(DiffLineType.REMOVED, a[prevX], prevX + 1, -1));
            }

            cx = prevX;
            cy = prevY;
        }

        // Initial snake from (0, 0) to (cx, cy)
        while (cx > 0 && cy > 0) {
            cx--;
            cy--;
            result.add(new DiffLine(DiffLineType.UNCHANGED, a[cx], cx + 1, cy + 1));
        }

        Collections.reverse(result);
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
