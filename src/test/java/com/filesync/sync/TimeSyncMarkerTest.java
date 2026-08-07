package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for the time-sync marker format and parsing used by the combined-log merge. */
class TimeSyncMarkerTest {

    @Test
    void markerMessage_embedsCurrentEpoch_parseable() {
        long before = System.currentTimeMillis();
        String message = TimeSyncMarker.markerMessage();
        long after = System.currentTimeMillis();

        Long epochMs = TimeSyncMarker.parseEpochMs(message);
        assertNotNull(epochMs, "A marker message must carry a parseable epoch");
        assertTrue(epochMs >= before && epochMs <= after, "Epoch must match the current time");
    }

    @Test
    void markerMessage_withGivenEpoch_formatsTimeAndEpoch() {
        long epochMs = 1764995400123L; // 2026-12-06-ish, deterministic value
        String message = TimeSyncMarker.markerMessage(epochMs);

        assertTrue(
                message.startsWith(TimeSyncMarker.PREFIX + " "), "Marker must start with prefix");
        assertTrue(message.endsWith(" " + epochMs), "Marker must end with the epoch");
        assertEquals(epochMs, TimeSyncMarker.parseEpochMs(message).longValue());
    }

    @Test
    void parseEpochMs_returnsNull_forNullMessage() {
        assertNull(TimeSyncMarker.parseEpochMs(null));
    }

    @Test
    void parseEpochMs_returnsNull_forNonMarkerMessage() {
        assertNull(TimeSyncMarker.parseEpochMs("Manifest sent (3 files)"));
        assertNull(TimeSyncMarker.parseEpochMs("TIME-SYNC"));
    }

    @Test
    void parseEpochMs_returnsNull_whenEpochMissingOrMalformed() {
        assertNull(TimeSyncMarker.parseEpochMs("TIME-SYNC 2026-08-06 15:30:00.123"));
        assertNull(TimeSyncMarker.parseEpochMs("TIME-SYNC 2026-08-06 15:30:00.123 abc"));
        assertNull(TimeSyncMarker.parseEpochMs("TIME-SYNC 2026-08-06 15:30:00.123 123"));
    }

    @Test
    void parseEpochMs_extractsEpochFromFullMarker() {
        long epochMs = 1764995400123L;
        String message = TimeSyncMarker.markerMessage(epochMs);
        assertEquals(epochMs, TimeSyncMarker.parseEpochMs(message).longValue());
        assertTrue(
                TimeSyncMarker.parseEpochMs(message) > 0,
                "Parsed epoch must be a positive millisecond value");
    }
}
