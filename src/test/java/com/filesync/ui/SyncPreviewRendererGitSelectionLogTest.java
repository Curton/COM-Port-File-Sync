package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies git-selection outcomes are routed to the log sink instead of failing silently. */
class SyncPreviewRendererGitSelectionLogTest {

    private static SyncPreviewRow row(String path) {
        return new SyncPreviewRow(SyncPreviewOperationType.NEW, path, "1 B", 1L);
    }

    @Test
    void outcomeLogsMatchCounts() {
        List<String> logs = new ArrayList<>();
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null, null, logs::add);

        renderer.logGitSelectionOutcome(
                new LinkedHashSet<>(List.of("a.txt")),
                1,
                List.of(row("a.txt"), row("b.txt")),
                "git");

        assertEquals(1, logs.size());
        assertEquals(
                "git: matched 1 of 2 preview row(s); git reported 1 changed path(s) via git",
                logs.get(0));
    }

    @Test
    void outcomeLogsWhichGitExecutableWasUsed() {
        List<String> logs = new ArrayList<>();
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null, null, logs::add);

        // Install-location fallback: the resolved path must appear in the log.
        renderer.logGitSelectionOutcome(
                new LinkedHashSet<>(List.of("a.txt")),
                1,
                List.of(row("a.txt")),
                "D:\\appl\\git\\cmd\\git.exe");

        assertEquals(1, logs.size());
        assertEquals(
                "git: matched 1 of 1 preview row(s); git reported 1 changed path(s) via "
                        + "D:\\appl\\git\\cmd\\git.exe",
                logs.get(0));
    }

    @Test
    void zeroMatchOutcomeLogsSamplesFromBothSides() {
        List<String> logs = new ArrayList<>();
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null, null, logs::add);
        LinkedHashSet<String> changed =
                new LinkedHashSet<>(List.of("x.txt", "y.txt", "z.txt", "p.txt", "q.txt", "r.txt"));

        renderer.logGitSelectionOutcome(changed, 0, List.of(row("a.txt"), row("b.txt")), "git");

        assertEquals(2, logs.size());
        String diagnostic = logs.get(1);
        assertTrue(diagnostic.contains("no preview row matched a git path"), diagnostic);
        assertTrue(diagnostic.contains("x.txt"), diagnostic);
        // Only the first 5 git paths are sampled; the 6th appears only in the total count.
        assertFalse(diagnostic.contains("r.txt"), diagnostic);
        assertTrue(diagnostic.contains("(6 total)"), diagnostic);
        assertTrue(diagnostic.contains("preview paths: a.txt, b.txt"), diagnostic);
    }

    @Test
    void zeroMatchWithoutRowsOrChangesLogsCountsOnly() {
        List<String> logs = new ArrayList<>();
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null, null, logs::add);

        renderer.logGitSelectionOutcome(new LinkedHashSet<>(), 0, List.of(), "git");

        assertEquals(1, logs.size());
        assertEquals(
                "git: matched 0 of 0 preview row(s); git reported 0 changed path(s) via git",
                logs.get(0));
    }

    @Test
    void nullLogSinkFallsBackToNoOp() {
        // The legacy constructors must keep working without a sink.
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null, null);
        renderer.logGitSelectionOutcome(
                new LinkedHashSet<>(List.of("a.txt")), 0, List.of(row("b.txt")), "git");
    }
}
