package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.protocol.SyncProtocol.Message;
import com.filesync.serial.SerialPortManager;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

/**
 * Verifies that synchronous command waits do not silently drop unrelated async messages (e.g.
 * SHARED_TEXT, DIRECTION_CHANGE) but stash them for the listener loop to dispatch later.
 */
class SyncProtocolWaitForCommandTest {

    @Test
    void waitForCommandStashesAsyncMessagesAndReturnsExpected() throws IOException {
        ScriptedProtocol protocol = new ScriptedProtocol();
        Message sharedText =
                new Message(SyncProtocol.CMD_SHARED_TEXT, new String[] {"1", "aGVsbG8="});
        Message direction = new Message(SyncProtocol.CMD_DIRECTION_CHANGE, new String[] {"true"});
        Message ack = new Message(SyncProtocol.CMD_ACK, new String[0]);
        protocol.feed(sharedText, direction, ack);

        Message result = protocol.waitForCommand(SyncProtocol.CMD_ACK);

        assertSame(ack, result);
        assertSame(sharedText, protocol.pollStashedMessage());
        assertSame(direction, protocol.pollStashedMessage());
        assertNull(protocol.pollStashedMessage());
    }

    @Test
    void waitForCommandHandlesHeartbeatsWithoutStashing() throws IOException {
        ScriptedProtocol protocol = new ScriptedProtocol();
        Message ack = new Message(SyncProtocol.CMD_ACK, new String[0]);
        protocol.feed(
                new Message(SyncProtocol.CMD_HEARTBEAT, new String[0]),
                new Message(SyncProtocol.CMD_HEARTBEAT_ACK, new String[0]),
                ack);

        Message result = protocol.waitForCommand(SyncProtocol.CMD_ACK);

        assertSame(ack, result);
        assertEquals(1, protocol.heartbeatAcksSent, "Heartbeat should be answered inline");
        assertNull(protocol.pollStashedMessage(), "Heartbeats must not be stashed");
    }

    @Test
    void waitForCommandThrowsOnErrorButKeepsStashedMessages() {
        ScriptedProtocol protocol = new ScriptedProtocol();
        Message sharedText =
                new Message(SyncProtocol.CMD_SHARED_TEXT, new String[] {"1", "aGVsbG8="});
        protocol.feed(sharedText, new Message(SyncProtocol.CMD_ERROR, new String[] {"boom"}));

        IOException ex =
                assertThrows(
                        IOException.class, () -> protocol.waitForCommand(SyncProtocol.CMD_ACK));

        assertTrue(ex.getMessage().contains("boom"));
        assertSame(sharedText, protocol.pollStashedMessage());
        assertNull(protocol.pollStashedMessage());
    }

    @Test
    void waitForCommandFiresBaseStaleHandlerAndThrows() {
        ScriptedProtocol protocol = new ScriptedProtocol();
        Message sharedText =
                new Message(SyncProtocol.CMD_SHARED_TEXT, new String[] {"1", "aGVsbG8="});
        Message stale =
                new Message(
                        SyncProtocol.CMD_BASE_STALE,
                        new String[] {"app.log", "100", "42", "md5-value"});
        List<Message> handled = new ArrayList<>();
        protocol.setBaseStaleHandler(handled::add);
        protocol.feed(sharedText, stale);

        IOException ex =
                assertThrows(
                        IOException.class, () -> protocol.waitForCommand(SyncProtocol.CMD_ACK));

        // The notification aborts the in-flight operation after the handler has recorded it.
        assertTrue(ex.getMessage().contains("rejected the transfer base"));
        assertTrue(ex.getMessage().contains("app.log"));
        assertEquals(List.of(stale), handled, "handler must receive the notification");
        // Earlier async messages stay stashed; BASE_STALE itself is delivered, not stashed.
        assertSame(sharedText, protocol.pollStashedMessage());
        assertNull(protocol.pollStashedMessage());
    }

    @Test
    void waitForCommandBaseStaleWithoutHandlerOrPathStillThrows() {
        ScriptedProtocol protocol = new ScriptedProtocol();
        protocol.feed(new Message(SyncProtocol.CMD_BASE_STALE, new String[0]));

        IOException ex =
                assertThrows(
                        IOException.class, () -> protocol.waitForCommand(SyncProtocol.CMD_ACK));

        assertTrue(
                ex.getMessage().contains("unknown"), "missing path falls back: " + ex.getMessage());
    }

    @Test
    void clearStashedMessagesDiscardsBacklog() throws IOException {
        ScriptedProtocol protocol = new ScriptedProtocol();
        Message sharedText =
                new Message(SyncProtocol.CMD_SHARED_TEXT, new String[] {"1", "aGVsbG8="});
        protocol.feed(sharedText, new Message(SyncProtocol.CMD_ACK, new String[0]));

        protocol.waitForCommand(SyncProtocol.CMD_ACK);
        protocol.clearStashedMessages();

        assertNull(protocol.pollStashedMessage());
    }

    /** Feeds a scripted sequence of messages to receiveCommand without touching a serial port. */
    private static final class ScriptedProtocol extends SyncProtocol {
        private final Queue<Message> script = new ArrayDeque<>();
        private int heartbeatAcksSent;

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

        @Override
        public void sendHeartbeatAck() {
            heartbeatAcksSent++;
        }
    }
}
