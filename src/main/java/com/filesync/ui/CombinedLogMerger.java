package com.filesync.ui;

import com.filesync.sync.TimeSyncMarker;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges the local and remote log texts into one chronologically ordered view for the "save
 * combined log" feature. Lines are tagged with [LOCAL]/[REMOTE]. When both sides logged a TIME-SYNC
 * marker (written when a sync starts), the remote timestamps are shifted by the measured clock
 * offset so the two machines' logs interleave correctly despite clock differences; without markers
 * the merge falls back to raw time-of-day ordering.
 */
public final class CombinedLogMerger {
    public static final String LOCAL_TAG = "[LOCAL] ";
    public static final String REMOTE_TAG = "[REMOTE] ";

    private static final int SOURCE_LOCAL = 0;
    private static final int SOURCE_REMOTE = 1;

    private static final Pattern TIMESTAMP_PREFIX =
            Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})\\]\\s?(.*)$");

    private CombinedLogMerger() {}

    /**
     * Merges the two log texts, tagged and ordered chronologically. A line that does not start with
     * the "[HH:mm:ss] " prefix is treated as a continuation of the previous line.
     *
     * @return the merged text with a trailing newline
     */
    public static String merge(String localText, String remoteText) {
        List<Entry> localEntries = parseEntries(localText, SOURCE_LOCAL);
        List<Entry> remoteEntries = parseEntries(remoteText, SOURCE_REMOTE);
        MarkerInfo localMarker = findMarkerInfo(localText);
        MarkerInfo remoteMarker = findMarkerInfo(remoteText);

        List<Entry> all = new ArrayList<>(localEntries.size() + remoteEntries.size());
        all.addAll(localEntries);
        all.addAll(remoteEntries);

        if (localMarker != null && remoteMarker != null) {
            // The local marker epoch anchors the merged timeline; each entry's offset from its own
            // side's marker time-of-day is measured in seconds, which is skew-free. Using the
            // remote marker epoch directly would shift remote entries by the full clock offset.
            for (Entry entry : all) {
                MarkerInfo anchor = entry.source == SOURCE_LOCAL ? localMarker : remoteMarker;
                entry.sortKeyMs =
                        localMarker.epochMs + (entry.secondsOfDay - anchor.secondsOfDay) * 1000L;
            }
        } else {
            for (Entry entry : all) {
                entry.sortKeyMs = entry.secondsOfDay * 1000L;
            }
        }

        all.sort(
                Comparator.comparingLong((Entry entry) -> entry.sortKeyMs)
                        .thenComparingInt(entry -> entry.source));

        StringBuilder merged = new StringBuilder();
        for (Entry entry : all) {
            merged.append(entry.source == SOURCE_LOCAL ? LOCAL_TAG : REMOTE_TAG)
                    .append(entry.line)
                    .append('\n');
        }
        return merged.toString();
    }

    /**
     * Clock offset between the two machines (local minus remote) in milliseconds, derived from the
     * last TIME-SYNC marker of each log. Returns null when either side has no marker.
     */
    public static Long computeClockOffsetMs(String localText, String remoteText) {
        MarkerInfo localMarker = findMarkerInfo(localText);
        MarkerInfo remoteMarker = findMarkerInfo(remoteText);
        if (localMarker == null || remoteMarker == null) {
            return null;
        }
        return localMarker.epochMs - remoteMarker.epochMs;
    }

    /**
     * Builds the timestamped file name for a combined log, e.g. combined_log_20260806_153000.txt.
     */
    public static String buildFileName(Date now) {
        return "combined_log_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(now) + ".txt";
    }

    private static List<Entry> parseEntries(String text, int source) {
        List<Entry> entries = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return entries;
        }
        for (String line : text.split("\r?\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = TIMESTAMP_PREFIX.matcher(line);
            if (matcher.matches()) {
                int hours = Integer.parseInt(matcher.group(1));
                int minutes = Integer.parseInt(matcher.group(2));
                int seconds = Integer.parseInt(matcher.group(3));
                entries.add(new Entry(source, line, hours * 3600 + minutes * 60 + seconds));
            } else if (!entries.isEmpty()) {
                Entry last = entries.get(entries.size() - 1);
                last.line = last.line + "\n" + line;
            } else {
                entries.add(new Entry(source, line, 0));
            }
        }
        return entries;
    }

    /** Finds the last time-sync marker in a log text (epoch ms + its time-of-day seconds). */
    private static MarkerInfo findMarkerInfo(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        MarkerInfo lastMarker = null;
        for (String line : text.split("\r?\n", -1)) {
            Matcher matcher = TIMESTAMP_PREFIX.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            Long epochMs = TimeSyncMarker.parseEpochMs(matcher.group(4));
            if (epochMs == null) {
                continue;
            }
            int hours = Integer.parseInt(matcher.group(1));
            int minutes = Integer.parseInt(matcher.group(2));
            int seconds = Integer.parseInt(matcher.group(3));
            lastMarker = new MarkerInfo(epochMs, hours * 3600 + minutes * 60 + seconds);
        }
        return lastMarker;
    }

    private static final class Entry {
        final int source;
        String line;
        final int secondsOfDay;
        long sortKeyMs;

        Entry(int source, String line, int secondsOfDay) {
            this.source = source;
            this.line = line;
            this.secondsOfDay = secondsOfDay;
        }
    }

    private static final class MarkerInfo {
        final long epochMs;
        final int secondsOfDay;

        MarkerInfo(long epochMs, int secondsOfDay) {
            this.epochMs = epochMs;
            this.secondsOfDay = secondsOfDay;
        }
    }
}
