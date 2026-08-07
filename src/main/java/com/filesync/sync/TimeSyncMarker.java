package com.filesync.sync;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats and parses the time-sync marker lines that both peers write to their logs when a sync
 * starts. Each marker carries the machine's wall-clock epoch, so the combined-log merge can compute
 * the clock offset between the two peers (their clocks may differ) and align their timestamps
 * before merging.
 */
public final class TimeSyncMarker {
    /** Message prefix identifying a marker line, e.g. "TIME-SYNC 2026-08-06 15:30:00.123 ...". */
    public static final String PREFIX = "TIME-SYNC";

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Pattern EPOCH_PATTERN = Pattern.compile("\\s(\\d{13})$");

    private TimeSyncMarker() {}

    /** Returns a marker message embedding the current local time and epoch milliseconds. */
    public static String markerMessage() {
        return markerMessage(System.currentTimeMillis());
    }

    /** Returns a marker message embedding the given epoch milliseconds. */
    public static String markerMessage(long epochMs) {
        LocalDateTime time =
                LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
        return PREFIX + " " + FORMATTER.format(time) + " " + epochMs;
    }

    /**
     * Parses the epoch milliseconds from a log line's message part (the text after the "[HH:mm:ss]
     * " prefix). Returns null when the message is not a time-sync marker.
     */
    public static Long parseEpochMs(String message) {
        if (message == null || !message.startsWith(PREFIX)) {
            return null;
        }
        Matcher matcher = EPOCH_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        return Long.parseLong(matcher.group(1));
    }
}
