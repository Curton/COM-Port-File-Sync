package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.serial.SerialPortManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The FILE_RENAME exchange: a confirmed request to move a file the receiver already holds, with ACK
 * for a performed rename, RENAME_REJECTED for a refused one (the sender's fallback signal), and
 * CMD_ERROR still meaning the session is over.
 */
class SyncProtocolFileRenameTest {

    /** Serves scripted frames for readLine and records everything sent. */
    private static final class FakeSerialPort extends SerialPortManager {
        private final Queue<String> inbox = new ConcurrentLinkedQueue<>();
        private final List<String> written = new ArrayList<>();

        void feedLine(String frame) {
            inbox.add(frame);
        }

        List<String> getWrittenLines() {
            return written;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public String readLine(int timeoutMs) {
            return inbox.poll();
        }

        @Override
        public int available() {
            return inbox.isEmpty() ? 0 : 1;
        }

        @Override
        public void writeLine(String line) {
            written.add(line);
        }
    }

    private static final String RENAME_FRAME_PREFIX = "[[SYNC:FILE_RENAME:old.bin:new.bin:";

    @Test
    void announcesPathPairSizeTimestampAndMd5ThenWaitsForAck() throws IOException {
        FakeSerialPort serial = new FakeSerialPort();
        serial.feedLine("[[SYNC:ACK]]");
        SyncProtocol protocol = new SyncProtocol(serial);

        boolean renamed = protocol.sendFileRename("old.bin", "new.bin", 2048L, 5555L, "md5-abc");

        assertTrue(renamed);
        assertEquals(1, serial.getWrittenLines().size());
        assertEquals(RENAME_FRAME_PREFIX + "2048:5555:md5-abc]]", serial.getWrittenLines().get(0));
    }

    @Test
    void rejectionIsAFallbackSignalNotAFailure() throws IOException {
        FakeSerialPort serial = new FakeSerialPort();
        serial.feedLine("[[SYNC:RENAME_REJECTED:old.bin:new.bin:content drifted on the receiver]]");
        SyncProtocol protocol = new SyncProtocol(serial);

        assertFalse(protocol.sendFileRename("old.bin", "new.bin", 10L, 1L, "md5-abc"));
    }

    @Test
    void remoteErrorStillAbortsTheExchange() {
        FakeSerialPort serial = new FakeSerialPort();
        serial.feedLine("[[SYNC:ERROR:boom]]");
        SyncProtocol protocol = new SyncProtocol(serial);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> protocol.sendFileRename("old.bin", "new.bin", 10L, 1L, "md5-abc"));
        assertTrue(thrown.getMessage().contains("boom"));
    }

    @Test
    @Timeout(20)
    void silenceTimesOut() {
        FakeSerialPort serial = new FakeSerialPort();
        SyncProtocol protocol = new SyncProtocol(serial);
        protocol.setTimeout(150);

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> protocol.sendFileRename("old.bin", "new.bin", 10L, 1L, "md5-abc"));
        assertTrue(thrown.getMessage().contains("Timeout"), thrown.getMessage());
    }

    @Test
    void unrelatedFramesAreStashedForTheListenerLoopAndHeartbeatsAnswered() throws IOException {
        FakeSerialPort serial = new FakeSerialPort();
        serial.feedLine("[[SYNC:HEARTBEAT]]");
        serial.feedLine("[[SYNC:SHARED_TEXT:1:aGk=]]");
        serial.feedLine("[[SYNC:ACK]]");
        SyncProtocol protocol = new SyncProtocol(serial);

        assertTrue(protocol.sendFileRename("old.bin", "new.bin", 10L, 1L, "md5-abc"));

        // The heartbeat was answered inline; the SHARED_TEXT must reach the listener loop intact
        // rather than being consumed by the rename wait. The stash holds the wire form (Base64
        // payload); SharedTextService decodes it when the listener loop dispatches the message.
        assertTrue(
                serial.getWrittenLines().stream()
                        .anyMatch(l -> l.equals("[[SYNC:HEARTBEAT_ACK]]")));
        SyncProtocol.Message stashed = protocol.pollStashedMessage();
        assertEquals(SyncProtocol.CMD_SHARED_TEXT, stashed.getCommand());
        assertEquals("aGk=", stashed.getParam(1));
    }

    @Test
    void escapesColonAndMarkerCharactersInPaths() throws IOException {
        FakeSerialPort serial = new FakeSerialPort();
        serial.feedLine("[[SYNC:ACK]]");
        SyncProtocol protocol = new SyncProtocol(serial);

        assertTrue(protocol.sendFileRename("dir:a/b.bin", "dir:c/b.bin", 10L, 1L, "md5-abc"));

        String frame = serial.getWrittenLines().get(0);
        assertTrue(frame.contains("dir\\:a/b.bin"), frame);
        SyncProtocol.Message parsed = SyncProtocol.parseMessage(frame);
        assertEquals(5, parsed.getParams().length);
        assertEquals("dir:a/b.bin", parsed.getParam(0));
        assertEquals("dir:c/b.bin", parsed.getParam(1));
    }
}
