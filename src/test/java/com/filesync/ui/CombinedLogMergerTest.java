package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for the combined-log merge, clock-offset alignment and file name building. */
class CombinedLogMergerTest {

    private static final long LOCAL_EPOCH = 1760000000000L; // local marker epoch (10:00:00)
    private static final long REMOTE_EPOCH = LOCAL_EPOCH + 120_000L; // remote clock 2 min ahead

    @Test
    void merge_alignsRemoteClockAndInterleavesByCorrectedTime() {
        String localText =
                "[10:00:00] TIME-SYNC 2026-08-06 10:00:00.000 "
                        + LOCAL_EPOCH
                        + "\n"
                        + "[10:00:05] Local event A\n"
                        + "[10:00:10] Local event B\n";
        // Remote clock is 2 minutes ahead: an event the local side saw at 10:00:07 is logged at
        // 10:02:07 by the remote; one the local side saw at 09:58:30 is logged at 10:00:30.
        String remoteText =
                "[10:02:00] TIME-SYNC 2026-08-06 10:02:00.000 "
                        + REMOTE_EPOCH
                        + "\n"
                        + "[10:02:07] Remote event X\n"
                        + "[10:00:30] Remote event Z\n";

        String merged = CombinedLogMerger.merge(localText, remoteText);
        List<String> lines = nonEmptyLines(merged);

        assertEquals(6, lines.size(), "All marker and event lines must be present");
        assertEquals(
                "[REMOTE] [10:00:30] Remote event Z",
                lines.get(0),
                "Z happened first (09:58:30 local)");
        assertEquals(
                "[LOCAL] [10:00:00] TIME-SYNC 2026-08-06 10:00:00.000 " + LOCAL_EPOCH,
                lines.get(1));
        assertEquals(
                "[REMOTE] [10:02:00] TIME-SYNC 2026-08-06 10:02:00.000 " + REMOTE_EPOCH,
                lines.get(2),
                "The remote marker anchors to local 10:00:00 (tie: LOCAL first)");
        assertEquals("[LOCAL] [10:00:05] Local event A", lines.get(3));
        assertEquals(
                "[REMOTE] [10:02:07] Remote event X",
                lines.get(4),
                "X happened at 10:00:07 local, before B");
        assertEquals("[LOCAL] [10:00:10] Local event B", lines.get(5));
    }

    @Test
    void computeClockOffsetMs_returnsLocalMinusRemote() {
        Long offset =
                CombinedLogMerger.computeClockOffsetMs(
                        "[10:00:00] TIME-SYNC 2026-08-06 10:00:00.000 " + LOCAL_EPOCH + "\n",
                        "[10:02:00] TIME-SYNC 2026-08-06 10:02:00.000 " + REMOTE_EPOCH + "\n");
        assertEquals(Long.valueOf(-120_000L), offset, "Offset must be local minus remote epoch");
    }

    @Test
    void merge_usesLastMarkerWhenMultipleExist() {
        // The first marker (epoch 999000000000) must be ignored in favor of the last one, so the
        // local event still sorts just before the remote event (which is 1s ahead on the remote
        // clock: 10:00:05 remote = 10:00:06 local).
        String localText =
                "[09:00:00] TIME-SYNC 2026-08-06 09:00:00.000 999000000000\n"
                        + "[10:00:00] TIME-SYNC 2026-08-06 10:00:00.000 1000000000000\n"
                        + "[10:00:05] Local event\n";
        String remoteText =
                "[10:00:00] TIME-SYNC 2026-08-06 10:00:00.000 1000000001000\n"
                        + "[10:00:05] Remote event\n";

        List<String> lines = nonEmptyLines(CombinedLogMerger.merge(localText, remoteText));

        // Order on the local timeline: old local marker (09:00), local marker (10:00:00), remote
        // marker (10:00:00 remote = 10:00:01 local), local event (10:00:05), remote event
        // (10:00:05 remote = 10:00:06 local).
        assertEquals(
                "[LOCAL] [09:00:00] TIME-SYNC 2026-08-06 09:00:00.000 999000000000", lines.get(0));
        assertEquals(
                "[LOCAL] [10:00:00] TIME-SYNC 2026-08-06 10:00:00.000 1000000000000", lines.get(1));
        assertEquals(
                "[REMOTE] [10:00:00] TIME-SYNC 2026-08-06 10:00:00.000 1000000001000",
                lines.get(2));
        assertEquals("[LOCAL] [10:00:05] Local event", lines.get(3));
        assertEquals("[REMOTE] [10:00:05] Remote event", lines.get(4));
        assertEquals(
                Long.valueOf(1000000000000L - 1000000001000L),
                CombinedLogMerger.computeClockOffsetMs(localText, remoteText));
    }

    @Test
    void merge_withoutMarkers_fallsBackToRawTimeOfDayOrdering() {
        String localText = "[10:00:02] Local two\n[10:00:01] Local one\n";
        String remoteText = "[10:00:03] Remote three\n[10:00:02] Remote two\n";

        List<String> lines = nonEmptyLines(CombinedLogMerger.merge(localText, remoteText));

        assertEquals("[LOCAL] [10:00:01] Local one", lines.get(0));
        assertEquals("[LOCAL] [10:00:02] Local two", lines.get(1), "Tie at 10:00:02: LOCAL first");
        assertEquals("[REMOTE] [10:00:02] Remote two", lines.get(2));
        assertEquals("[REMOTE] [10:00:03] Remote three", lines.get(3));
        assertNull(CombinedLogMerger.computeClockOffsetMs(localText, remoteText));
    }

    @Test
    void merge_withMarkerOnOneSideOnly_fallsBackToRawOrdering() {
        // A marker-prefixed line without a parseable epoch must not be treated as a marker either.
        String localText = "[10:00:00] TIME-SYNC 2026-08-06 10:00:00.000\n[10:00:02] Local\n";
        String remoteText = "[10:00:01] Remote\n";

        List<String> lines = nonEmptyLines(CombinedLogMerger.merge(localText, remoteText));

        assertEquals("[LOCAL] [10:00:00] TIME-SYNC 2026-08-06 10:00:00.000", lines.get(0));
        assertEquals("[REMOTE] [10:00:01] Remote", lines.get(1));
        assertEquals("[LOCAL] [10:00:02] Local", lines.get(2));
        assertNull(CombinedLogMerger.computeClockOffsetMs(localText, remoteText));
    }

    @Test
    void merge_withMarkerOnLocalSideOnly_fallsBackToRawOrdering() {
        // Only the local side has a parseable marker: no alignment is possible, so the merge
        // falls back to raw time-of-day ordering and the offset stays null.
        String localText =
                "[10:00:00] TIME-SYNC 2026-08-06 10:00:00.000 1000000000000\n"
                        + "[10:00:02] Local\n";
        String remoteText = "[10:00:01] Remote\n";

        List<String> lines = nonEmptyLines(CombinedLogMerger.merge(localText, remoteText));

        assertEquals(
                "[LOCAL] [10:00:00] TIME-SYNC 2026-08-06 10:00:00.000 1000000000000", lines.get(0));
        assertEquals("[REMOTE] [10:00:01] Remote", lines.get(1));
        assertEquals("[LOCAL] [10:00:02] Local", lines.get(2));
        assertNull(CombinedLogMerger.computeClockOffsetMs(localText, remoteText));
    }

    @Test
    void merge_keepsContinuationLinesWithTheirTimestampedEntry() {
        String localText =
                "line without any timestamp prefix\n"
                        + "[10:00:01] First entry\n"
                        + "continuation of first entry\n"
                        + "\n"
                        + "[10:00:02] Second entry\n";
        String remoteText = "";

        String merged = CombinedLogMerger.merge(localText, remoteText);
        List<String> lines = nonEmptyLines(merged);

        // The untimestamped first line becomes an entry with time 00:00:00 and sorts first.
        assertEquals("[LOCAL] line without any timestamp prefix", lines.get(0));
        assertTrue(
                merged.contains("[LOCAL] [10:00:01] First entry\ncontinuation of first entry"),
                "The continuation line must stay attached to its timestamped entry");
        assertEquals("[LOCAL] [10:00:02] Second entry", lines.get(3));
        assertEquals(
                4, lines.size(), "Orphan + timestamped entry with continuation + second entry");
    }

    @Test
    void merge_handlesCrLfInput() {
        String localText = "[10:00:01] Local\r\n[10:00:03] Later local\r\n";
        String remoteText = "[10:00:02] Remote\r\n";

        List<String> lines = nonEmptyLines(CombinedLogMerger.merge(localText, remoteText));

        assertEquals(3, lines.size());
        assertEquals("[LOCAL] [10:00:01] Local", lines.get(0));
        assertEquals("[REMOTE] [10:00:02] Remote", lines.get(1));
        assertEquals("[LOCAL] [10:00:03] Later local", lines.get(2));
    }

    @Test
    void merge_emptyInputs_produceEmptyOutput() {
        assertEquals("", CombinedLogMerger.merge("", ""));
        assertEquals("", CombinedLogMerger.merge(null, ""));
        assertFalse(CombinedLogMerger.merge("", "").contains(CombinedLogMerger.LOCAL_TAG));
    }

    @Test
    void merge_remoteEmpty_onlyLocalLinesTagged() {
        List<String> lines = nonEmptyLines(CombinedLogMerger.merge("[10:00:01] Local only\n", ""));
        assertEquals(1, lines.size());
        assertEquals("[LOCAL] [10:00:01] Local only", lines.get(0));
    }

    @Test
    void buildFileName_usesTimestampPattern() {
        String fileName = CombinedLogMerger.buildFileName(new Date(1764995400000L));
        assertTrue(
                fileName.matches("combined_log_\\d{8}_\\d{6}\\.txt"),
                "File name must be combined_log_yyyyMMdd_HHmmss.txt but was: " + fileName);
        assertTrue(fileName.startsWith("combined_log_"), "File name must keep the prefix");
        assertTrue(fileName.endsWith(".txt"), "File name must end with .txt");
    }

    private static List<String> nonEmptyLines(String text) {
        return Arrays.stream(text.split("\n", -1)).filter(line -> !line.isEmpty()).toList();
    }
}
