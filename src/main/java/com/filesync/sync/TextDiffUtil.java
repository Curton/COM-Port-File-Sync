package com.filesync.sync;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Utility for computing line-by-line differences between two text files, similar to git diff. Uses
 * Myers' O(ND) diff algorithm, bounded so that huge files cannot exhaust the heap.
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

    /**
     * Maximum number of greedy search steps run against a single diff range. The trace kept for the
     * backtrack stores only the diagonals in scope at each step, so this bounds that trace at
     * O(steps^2) ints (about 4 MiB at the default). Beyond it, the range is split rather than
     * traced.
     */
    private static final int MAX_SEARCH_STEPS = 1024;

    /**
     * Maximum recursion depth when splitting a diff range. Splits normally balance well below this;
     * the limit exists so pathological input cannot nest unboundedly.
     */
    private static final int MAX_SPLIT_DEPTH = 64;

    /**
     * Upper bound on greedy work (line comparisons and diagonal steps) for a single diff. Spending
     * it degrades the remaining ranges to "everything changed", which keeps worst-case time bounded
     * for files that share almost nothing.
     */
    private static final long MAX_DIFF_WORK = 400_000_000L;

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
     * Myers' O(ND) diff algorithm, bounded variant. The greedy sweep runs per diff range and the
     * trace keeps only the diagonals in scope at each step, so memory stays at O((N+M) + steps^2)
     * instead of O(D*(N+M)); a range whose distance outruns the step budget is split at the greedy
     * frontier instead of being traced. Time is O((N+M)*D) with a hard work budget.
     */
    private static List<DiffLine> myersDiff(String[] a, String[] b) {
        int n = a.length;
        int m = b.length;

        if (n == 0 && m == 0) {
            return List.of();
        }
        List<DiffLine> result = new ArrayList<>(Math.min(n + m, 1024));
        if (n == 0) {
            appendRange(result, DiffLineType.ADDED, b, 0, m);
            return result;
        }
        if (m == 0) {
            appendRange(result, DiffLineType.REMOVED, a, 0, n);
            return result;
        }
        new MyersRangeDiff(a, b, result).diffRange(0, n, 0, m, 0);
        return result;
    }

    /** Appends the lines of {@code [from, to)} as pure additions or removals. */
    private static void appendRange(
            List<DiffLine> out, DiffLineType type, String[] lines, int from, int to) {
        if (type == DiffLineType.ADDED) {
            for (int j = from; j < to; j++) {
                out.add(new DiffLine(DiffLineType.ADDED, lines[j], -1, j + 1));
            }
        } else {
            for (int i = from; i < to; i++) {
                out.add(new DiffLine(DiffLineType.REMOVED, lines[i], i + 1, -1));
            }
        }
    }

    /** Copies the diagonals in scope at step {@code d} (k in [-d, d]) into a compact slice. */
    private static int[] copyDiagonals(int[] v, int d, int offset) {
        int[] slice = new int[2 * d + 1];
        for (int k = -d; k <= d; k++) {
            slice[k + d] = v[k + offset];
        }
        return slice;
    }

    /**
     * Emits diff lines for a[a0, aEnd) against b[b0, bEnd). Trims the common prefix and suffix
     * before searching, since a localized edit otherwise walks the full file through the greedy.
     */
    private static final class MyersRangeDiff {
        private final String[] a;
        private final String[] b;
        private final List<DiffLine> out;
        private long workLeft;

        MyersRangeDiff(String[] a, String[] b, List<DiffLine> out) {
            this.a = a;
            this.b = b;
            this.out = out;
            this.workLeft = MAX_DIFF_WORK;
        }

        void diffRange(int a0, int aEnd, int b0, int bEnd, int depth) {
            int n = aEnd - a0;
            int m = bEnd - b0;
            if (n == 0 && m == 0) {
                return;
            }
            if (n == 0) {
                appendRange(out, DiffLineType.ADDED, b, b0, bEnd);
                return;
            }
            if (m == 0) {
                appendRange(out, DiffLineType.REMOVED, a, a0, aEnd);
                return;
            }

            int prefix = 0;
            while (prefix < n && prefix < m && a[a0 + prefix].equals(b[b0 + prefix])) {
                prefix++;
                workLeft--;
            }
            for (int i = 0; i < prefix; i++) {
                out.add(
                        new DiffLine(
                                DiffLineType.UNCHANGED, a[a0 + i], a0 + i + 1, b0 + i + 1));
            }
            int suffix = 0;
            while (suffix < n - prefix
                    && suffix < m - prefix
                    && a[aEnd - 1 - suffix].equals(b[bEnd - 1 - suffix])) {
                suffix++;
                workLeft--;
            }

            int midA0 = a0 + prefix;
            int midA1 = aEnd - suffix;
            int midB0 = b0 + prefix;
            int midB1 = bEnd - suffix;
            if (midA1 == midA0) {
                appendRange(out, DiffLineType.ADDED, b, midB0, midB1);
            } else if (midB1 == midB0) {
                appendRange(out, DiffLineType.REMOVED, a, midA0, midA1);
            } else {
                diffMiddle(midA0, midA1, midB0, midB1, depth);
            }

            // The suffix lines match and follow the middle, so they are all unchanged.
            for (int i = 0; i < suffix; i++) {
                int ai = midA1 + i;
                int bi = midB1 + i;
                out.add(new DiffLine(DiffLineType.UNCHANGED, a[ai], ai + 1, bi + 1));
            }
        }

        /**
         * Runs the greedy sweep for one middle range. If the sweep reaches both ends within the step
         * budget, the trace is walked back into the full diff. If the distance outruns the budget,
         * the range is split at the middle snake instead — a snake on an optimal path, so the two
         * halves' optimal distances add back up to this range's and no slack accumulates down the
         * recursion. Spending the work budget or the split depth reports the range as fully changed
         * instead, which bounds worst-case time.
         */
        private void diffMiddle(int a0, int aEnd, int b0, int bEnd, int depth) {
            int n = aEnd - a0;
            int m = bEnd - b0;
            int[] v = new int[2 * MAX_SEARCH_STEPS + 5];
            int offset = MAX_SEARCH_STEPS + 2;
            Arrays.fill(v, -1);
            v[offset + 1] = 0;

            List<int[]> trace = null;
            int foundD = -1;
            int limit = Math.min(n + m, MAX_SEARCH_STEPS);
            if (workLeft > 0) {
                trace = new ArrayList<>(limit + 1);
                search:
                for (int d = 0; d <= limit; d++) {
                    trace.add(copyDiagonals(v, d, offset));
                    for (int k = -d; k <= d; k += 2) {
                        int x;
                        if (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) {
                            x = v[k + 1 + offset];
                        } else {
                            x = v[k - 1 + offset] + 1;
                        }
                        int y = x - k;

                        while (x < n && y < m && a[a0 + x].equals(b[b0 + y])) {
                            x++;
                            y++;
                            workLeft--;
                        }
                        workLeft--;
                        if (workLeft <= 0) {
                            break search;
                        }
                        v[k + offset] = x;

                        if (x >= n && y >= m) {
                            foundD = d;
                            break search;
                        }
                    }
                }
            }

            if (foundD >= 0) {
                List<DiffLine> reversed = backtrack(trace, foundD, a0, aEnd, b0, bEnd);
                Collections.reverse(reversed);
                out.addAll(reversed);
                return;
            }
            if (workLeft <= 0 || depth >= MAX_SPLIT_DEPTH) {
                appendRange(out, DiffLineType.REMOVED, a, a0, aEnd);
                appendRange(out, DiffLineType.ADDED, b, b0, bEnd);
                return;
            }

            // Distance outruns the trace budget, so tracing the whole range as one piece is out.
            // Split at the middle snake instead. Its search is bounded by half the range's rows —
            // the step at which the two frontiers are guaranteed to have crossed — so a range whose
            // rows outrun that bound is halved first rather than burning the budget on a search that
            // cannot succeed.
            int snakeBudget = Math.min(MAX_SEARCH_STEPS, (n + m + 1) / 2);
            int[] snake = findMiddleSnake(a0, aEnd, b0, bEnd, snakeBudget);
            if (snake == null
                    || snake[0] < 0
                    || snake[1] < 0
                    || snake[0] > n
                    || snake[1] > m
                    || snake[2] > n
                    || snake[3] > m
                    || snake[2] < snake[0]
                    || snake[3] < snake[1]
                    || (snake[2] == n && snake[3] == m)
                    || (snake[0] == 0 && snake[1] == 0)) {
                // No usable snake inside this range: halve the rows and recurse.
                int midA = a0 + (n + 1) / 2;
                int midB = b0 + (m + 1) / 2;
                diffRange(a0, midA, b0, midB, depth + 1);
                diffRange(midA, aEnd, midB, bEnd, depth + 1);
                return;
            }
            diffRange(a0, a0 + snake[0], b0, b0 + snake[1], depth + 1);
            for (int i = snake[0]; i < snake[2]; i++) {
                out.add(
                        new DiffLine(
                                DiffLineType.UNCHANGED,
                                a[a0 + i],
                                a0 + i + 1,
                                b0 + (i - snake[0] + snake[1]) + 1));
            }
            diffRange(a0 + snake[2], aEnd, b0 + snake[3], bEnd, depth + 1);
        }

        /**
         * Finds a snake on an optimal path for a[a0, aEnd) vs b[b0, bEnd) by running the greedy
         * edit-graph search forward from (0, 0) and, in lockstep, forward over the reversed strings
         * — which is the same search run backward from (n, m). The first diagonal where the forward
         * frontier has reached the backward frontier's point is where the two paths cross, and the
         * common run there lies on an optimal path. Returns {startX, startY, endX, endY} in
         * range-local coordinates, or null when the frontiers do not cross within the budget.
         */
        private int[] findMiddleSnake(int a0, int aEnd, int b0, int bEnd, int budget) {
            int n = aEnd - a0;
            int m = bEnd - b0;
            int delta = n - m;
            int off = budget + 2;
            // vf[k]: furthest x on diagonal k (x - y) reached from (0, 0).
            // vb[kr]: furthest x reached from (n, m), held in the reversed strings' own diagonal
            // space, so a reversed diagonal kr is original diagonal (delta - kr).
            int[] vf = new int[2 * budget + 5];
            int[] vb = new int[2 * budget + 5];
            Arrays.fill(vf, -1);
            Arrays.fill(vb, -1);
            vf[off + 1] = 0;
            vb[off + 1] = 0;

            for (int d = 0; d <= budget; d++) {
                for (int k = -d; k <= d; k += 2) {
                    int x = greedyBest(vf, off, k, d);
                    int y = x - k;
                    while (x < n && y < m && a[a0 + x].equals(b[b0 + y])) {
                        x++;
                        y++;
                    }
                    vf[k + off] = x;
                }
                for (int k = -d; k <= d; k += 2) {
                    int x = greedyBest(vb, off, k, d);
                    int y = x - k;
                    while (x < n && y < m && a[aEnd - 1 - x].equals(b[bEnd - 1 - y])) {
                        x++;
                        y++;
                    }
                    vb[k + off] = x;
                }
                // A diagonal both frontiers cover now holds the forward frontier at or past the
                // backward frontier's point: the paths have crossed on it.
                for (int k = -d; k <= d; k += 2) {
                    int kr = delta - k;
                    if (kr < -d || kr > d) {
                        continue;
                    }
                    int xf = vf[k + off];
                    int xr = vb[kr + off];
                    if (xf < 0 || xr < 0) {
                        continue;
                    }
                    int bx = n - xr;
                    int by = m - (xr - kr);
                    if (xf < bx) {
                        continue;
                    }
                    // Walk the actual common run through the crossing, so the emitted snake is
                    // equal lines by construction whatever the frontier arithmetic says.
                    int sx = bx;
                    int sy = by;
                    while (sx > 0 && sy > 0 && a[a0 + sx - 1].equals(b[b0 + sy - 1])) {
                        sx--;
                        sy--;
                    }
                    int ex = bx;
                    int ey = by;
                    while (ex < n && ey < m && a[a0 + ex].equals(b[b0 + ey])) {
                        ex++;
                        ey++;
                    }
                    if (ex == sx && ey == sy) {
                        continue;
                    }
                    return new int[] {sx, sy, ex, ey};
                }
            }
            return null;
        }

        /** Greedy best x for diagonal k at step d — the standard Myers table step. */
        private static int greedyBest(int[] v, int off, int k, int d) {
            if (k == -d || (k != d && v[k - 1 + off] < v[k + 1 + off])) {
                return v[k + 1 + off];
            }
            return v[k - 1 + off] + 1;
        }

        /**
         * Walks the trace backwards from (n, m) to (0, 0), emitting lines in reverse order. Reads
         * only the diagonals recorded at each step's own offset, since the slices are compact.
         */
        private List<DiffLine> backtrack(
                List<int[]> trace, int foundD, int a0, int aEnd, int b0, int bEnd) {
            List<DiffLine> reversed = new ArrayList<>();
            int cx = aEnd - a0;
            int cy = bEnd - b0;

            for (int d = foundD; d > 0; d--) {
                int[] vPrev = trace.get(d);
                int k = cx - cy;
                int traceOffset = d;

                boolean fromAbove;
                if (k == -d) {
                    fromAbove = true;
                } else if (k == d) {
                    fromAbove = false;
                } else {
                    fromAbove = vPrev[k - 1 + traceOffset] < vPrev[k + 1 + traceOffset];
                }

                int prevK = fromAbove ? k + 1 : k - 1;
                int prevX = vPrev[prevK + traceOffset];
                int prevY = prevX - prevK;

                int midX = fromAbove ? prevX : prevX + 1;
                int midY = fromAbove ? prevY + 1 : prevY;

                // Snake from (midX, midY) to (cx, cy) — all UNCHANGED
                int sx = cx;
                int sy = cy;
                while (sx > midX && sy > midY) {
                    sx--;
                    sy--;
                    reversed.add(
                            new DiffLine(
                                    DiffLineType.UNCHANGED,
                                    a[a0 + sx],
                                    a0 + sx + 1,
                                    b0 + sy + 1));
                }

                // The edit step
                if (fromAbove) {
                    reversed.add(
                            new DiffLine(
                                    DiffLineType.ADDED, b[b0 + prevY], -1, b0 + prevY + 1));
                } else {
                    reversed.add(
                            new DiffLine(
                                    DiffLineType.REMOVED, a[a0 + prevX], a0 + prevX + 1, -1));
                }

                cx = prevX;
                cy = prevY;
            }

            // Initial snake from (0, 0) to (cx, cy)
            while (cx > 0 && cy > 0) {
                cx--;
                cy--;
                reversed.add(
                        new DiffLine(
                                DiffLineType.UNCHANGED, a[a0 + cx], a0 + cx + 1, b0 + cy + 1));
            }
            return reversed;
        }
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
