package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.protocol.SyncProtocol.Message;
import com.filesync.serial.SerialPortManager;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SyncProtocol#waitForWriteFailures()} distinguishes an arrived report (empty
 * set = arrived, named none) from a lost one (null = outcome of every write unknown) — the
 * distinction the sender's base recording depends on.
 */
class SyncProtocolWriteFailuresReportTest {

    @Test
    void timedOutWaitReturnsNullAndRestoresTheTimeout() throws IOException {
        ScriptedProtocol protocol = new ScriptedProtocol();
        protocol.setTimeout(60);

        assertNull(protocol.waitForWriteFailures(), "a lost report is null, not an empty set");
        assertEquals(60, protocol.getTimeout(), "the caller's timeout is restored after the wait");
    }

    @Test
    void clampedTimeoutIsRestoredAfterAnArrivedReport() throws IOException {
        // An ambient timeout above the 5s bound is clamped for the wait; the report arrives
        // immediately, so the test pays no real delay and only observes the restore.
        ScriptedProtocol protocol = new ScriptedProtocol();
        protocol.setTimeout(60_000);
        protocol.feed(new Message(SyncProtocol.CMD_WRITE_FAILURES, new String[] {"0"}));

        assertTrue(protocol.waitForWriteFailures().isEmpty());
        assertEquals(
                60_000, protocol.getTimeout(), "a clamped timeout is still restored afterwards");
    }

    @Test
    void arrivedReportParsesItsPaths() throws IOException {
        ScriptedProtocol protocol = new ScriptedProtocol();
        protocol.feed(
                new Message(
                        SyncProtocol.CMD_WRITE_FAILURES,
                        new String[] {"2", "locked.txt", "sub/also locked.txt"}));

        assertEquals(
                java.util.Set.of("locked.txt", "sub/also locked.txt"),
                protocol.waitForWriteFailures());
    }

    @Test
    void peerCancelDuringTheWaitPropagates() {
        ScriptedProtocol protocol = new ScriptedProtocol();
        protocol.setTimeout(10_000);
        protocol.feed(new Message(SyncProtocol.CMD_CANCEL, new String[0]));

        assertThrows(TransferCancelledException.class, () -> protocol.waitForWriteFailures());
    }

    /** Feeds a scripted sequence of messages to receiveCommand without touching a serial port. */
    private static final class ScriptedProtocol extends SyncProtocol {
        private final Queue<Message> script = new ArrayDeque<>();

        ScriptedProtocol() {
            super(new SerialPortManager());
        }

        void feed(Message... messages) {
            script.addAll(Arrays.asList(messages));
        }

        @Override
        public Message receiveCommand() {
            return script.poll();
        }
    }
}
