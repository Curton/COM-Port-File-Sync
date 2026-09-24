package com.filesync.lab.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The pacing queue: bytes leave at exactly the configured wire rate, and never sooner. */
class ByteScheduleTest {

    @Test
    void releaseTimesAreSpacedByTheBytePeriod() {
        WireModel model = new WireModel().baud(1_000_000).latencyMillis(0); // 100000 B/s
        ByteSchedule schedule = new ByteSchedule(model, 1 << 20);
        byte[] ten = new byte[10];
        assertEquals(10, schedule.offer(ten, 0, ten.length, 0));

        long before = System.nanoTime();
        byte[] one = new byte[1];
        long previousDue = before;
        for (int i = 0; i < 10; i++) {
            assertEquals(1, schedule.takeDue(one, 0, 1, Long.MAX_VALUE));
            if (i == 9) {
                break;
            }
            long due = schedule.nextDueNanos();
            if (i > 0) {
                double spacing = due - previousDue;
                assertEquals(10_000.0, spacing, 2_000.0, "byte " + i + " spacing");
            }
            previousDue = due;
        }
        // All ten bytes fit inside a 200 us window at 100 kB/s.
        assertTrue(previousDue - before < 400_000, previousDue - before + " ns total");
    }

    @Test
    @Timeout(30)
    void offerNeverRunsAheadOfTheRate() throws Exception {
        WireModel model = new WireModel().baud(4_000_000).latencyMillis(0); // 400000 B/s
        ByteSchedule schedule = new ByteSchedule(model, 1 << 17);
        // Kept under the queue capacity: the point of this test is the rate, not backpressure.
        byte[] payload = new byte[100_000];
        long started = System.nanoTime();
        int offered = schedule.offer(payload, 0, payload.length, 0);
        assertEquals(payload.length, offered);

        byte[] buffer = new byte[8192];
        long taken = 0;
        while (taken < payload.length) {
            taken += schedule.takeDue(buffer, 0, buffer.length, System.nanoTime());
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        // 100000 bytes at 400000 B/s needs 250 ms; allow generous slack for slow CI machines.
        assertTrue(elapsedMs >= 220, "drained too fast: " + elapsedMs + " ms");
        assertTrue(elapsedMs < 4_000, "drained too slowly: " + elapsedMs + " ms");
    }

    @Test
    @Timeout(30)
    void offerBlocksWhenFullAndResumesWhenDrained() throws Exception {
        WireModel model = new WireModel().baud(4_000_000).latencyMillis(0); // 400000 B/s
        ByteSchedule schedule = new ByteSchedule(model, 1 << 17);
        int total = 400_000;
        Thread offerer = new Thread(() -> schedule.offer(new byte[total], 0, total, 0));
        offerer.setDaemon(true);
        offerer.start();

        // The schedule holds 128 KiB; the offer of 400 KiB must stall until we drain.
        offerer.join(150);
        assertTrue(offerer.isAlive(), "offer should block on a full schedule");

        byte[] buffer = new byte[8192];
        long taken = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (taken < total && System.nanoTime() < deadline) {
            taken += schedule.takeDue(buffer, 0, buffer.length, System.nanoTime());
            if (taken % 65536 < buffer.length) {
                Thread.sleep(1);
            }
        }
        offerer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(offerer.isAlive(), "offer should have finished once drained");
        assertEquals(total, taken);
    }

    @Test
    void clearDropsEverythingPending() {
        WireModel model = new WireModel().baud(1_000_000).latencyMillis(0);
        ByteSchedule schedule = new ByteSchedule(model, 1 << 20);
        schedule.offer(new byte[64], 0, 64, 0);
        schedule.clear();
        byte[] buffer = new byte[64];
        assertEquals(0, schedule.takeDue(buffer, 0, buffer.length, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, schedule.nextDueNanos());
    }

    @Test
    void extraDelayHoldsBytesBack() {
        WireModel model = new WireModel().baud(1_000_000).latencyMillis(0);
        ByteSchedule schedule = new ByteSchedule(model, 1 << 20);
        byte[] data = "[[SYNC:ACK]]\n".getBytes(StandardCharsets.ISO_8859_1);
        long before = System.nanoTime();
        schedule.offer(data, 0, data.length, TimeUnit.MILLISECONDS.toNanos(200));
        byte[] buffer = new byte[data.length];
        // Nothing is due yet.
        assertEquals(0, schedule.takeDue(buffer, 0, buffer.length, System.nanoTime()));
        long dueAt = schedule.nextDueNanos();
        assertTrue(dueAt - before >= TimeUnit.MILLISECONDS.toNanos(190), dueAt - before + " ns");
        // The last byte leaves one byte period (10 us at 100 kB/s) after the first one.
        long allDue = dueAt + TimeUnit.MICROSECONDS.toNanos(10 * data.length);
        assertEquals(data.length, schedule.takeDue(buffer, 0, buffer.length, allDue));
    }
}
