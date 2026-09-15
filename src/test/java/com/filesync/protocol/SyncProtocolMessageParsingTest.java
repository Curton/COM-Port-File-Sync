package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.serial.SerialPortManager;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class SyncProtocolMessageParsingTest {

    @Test
    void sendCommandEscapesColonInPathParameters() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);

        protocol.sendCommand(SyncProtocol.CMD_FILE_REQ, "folder:with:colons", "123");

        String sentLine = serialPort.getLastSentLine();
        assertNotNull(sentLine);
        assertTrue(sentLine.contains("folder\\:with\\:colons"));

        SyncProtocol.Message parsed = SyncProtocol.parseMessage(sentLine);
        assertNotNull(parsed);
        assertEquals("folder:with:colons", parsed.getParam(0));
        assertEquals(123, parsed.getParamAsInt(1));
    }

    @Test
    void parseMessageUnescapesBackslashAndColonInPayload() {
        SyncProtocol.Message message = SyncProtocol.parseMessage("[[SYNC:FILE_REQ:dir\\:name]]");
        assertNotNull(message);
        assertEquals(SyncProtocol.CMD_FILE_REQ, message.getCommand());
        assertEquals("dir:name", message.getParam(0));

        SyncProtocol.Message backslashMessage =
                SyncProtocol.parseMessage("[[SYNC:FILE_REQ:dir\\\\name]]");
        assertNotNull(backslashMessage);
        assertEquals("dir\\name", backslashMessage.getParam(0));
    }

    @Test
    void getParamAsIntThrowsProtocolFieldParseExceptionForInvalidValue() {
        SyncProtocol.Message message = new SyncProtocol.Message("FILE_DATA", new String[] {"abc"});
        assertThrows(
                SyncProtocol.Message.ProtocolFieldParseException.class,
                () -> message.getParamAsInt(0));
    }

    @Test
    void getParamAsLongThrowsProtocolFieldParseExceptionForInvalidValue() {
        SyncProtocol.Message message = new SyncProtocol.Message("DROP_FILE", new String[] {"abc"});
        assertThrows(
                SyncProtocol.Message.ProtocolFieldParseException.class,
                () -> message.getParamAsLong(0));
    }

    private static class RecordingSerialPortManager extends SerialPortManager {
        private String lastSentLine;

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void writeLine(String line) {
            this.lastSentLine = line;
        }

        String getLastSentLine() {
            return lastSentLine;
        }
    }

    // ========== parseMessage edge cases ==========

    @Test
    void parseMessage_returnsNullForNullInput() {
        assertNull(SyncProtocol.parseMessage(null));
    }

    @Test
    void parseMessage_returnsNullForMissingStartMarker() {
        assertNull(SyncProtocol.parseMessage("SYNC:ACK]]"));
    }

    @Test
    void parseMessage_resyncsPastLeadingCanGarbage() {
        // sendTransferCancel's leading CAN bytes carry no newline, so newline-delimited reads
        // merge them with the frame that follows; the frame must still parse, not be dropped.
        SyncProtocol.Message cancel = SyncProtocol.parseMessage("\u0018\u0018[[SYNC:CANCEL]]");
        assertNotNull(cancel);
        assertEquals(SyncProtocol.CMD_CANCEL, cancel.getCommand());

        SyncProtocol.Message ack = SyncProtocol.parseMessage("\u0018[[SYNC:ACK]]");
        assertNotNull(ack);
        assertEquals(SyncProtocol.CMD_ACK, ack.getCommand());
    }

    @Test
    void parseMessage_stillDropsLinesWithoutACompleteFrame() {
        assertNull(SyncProtocol.parseMessage("\u0018\u0018"), "bare CAN bytes are not a frame");
        assertNull(SyncProtocol.parseMessage("noise [[SYNC:CANCEL]] trailing"));
        assertNull(SyncProtocol.parseMessage("[[SYNC:CANCEL]\u0018]"));
    }

    @Test
    void parseMessage_recoversLastCompleteFrameWhenTornPrefixMergesIntoLine() {
        // A torn frame prefix (its newline lost mid-read) merges into the same line as the
        // complete frame that follows. Parsing from the FIRST start marker would treat the
        // merged garbage as one frame and swallow the real command; the last complete frame is
        // the only recoverable one.
        SyncProtocol.Message msg = SyncProtocol.parseMessage("[[SYNC:HEARTBEAT[[SYNC:ACK]]");
        assertNotNull(msg);
        assertEquals(SyncProtocol.CMD_ACK, msg.getCommand());
        assertEquals(0, msg.getParams().length);
    }

    @Test
    void parseMessage_returnsNullForMissingEndMarker() {
        assertNull(SyncProtocol.parseMessage("[[SYNC:ACK"));
    }

    @Test
    void parseMessage_parsesCommandOnlyNoParams() {
        SyncProtocol.Message msg = SyncProtocol.parseMessage("[[SYNC:ACK]]");
        assertNotNull(msg);
        assertEquals("ACK", msg.getCommand());
        assertEquals(0, msg.getParams().length);
    }

    @Test
    void parseMessage_parsesMultipleParams() {
        SyncProtocol.Message msg = SyncProtocol.parseMessage("[[SYNC:ROLE_NEGOTIATE:100:200]]");
        assertNotNull(msg);
        assertEquals("ROLE_NEGOTIATE", msg.getCommand());
        assertEquals(2, msg.getParams().length);
        assertEquals("100", msg.getParam(0));
        assertEquals("200", msg.getParam(1));
    }

    // ========== Message getter edge cases ==========

    @Test
    void messageGetParam_returnsNullForOutOfBounds() {
        SyncProtocol.Message msg = new SyncProtocol.Message("CMD", new String[] {"a"});
        assertEquals("a", msg.getParam(0));
        assertNull(msg.getParam(1));
        assertNull(msg.getParam(-1));
    }

    @Test
    void messageGetParamAsBoolean_parsesTrueFalse() {
        SyncProtocol.Message msg =
                new SyncProtocol.Message("CMD", new String[] {"true", "false", "TRUE", null});
        assertTrue(msg.getParamAsBoolean(0));
        assertFalse(msg.getParamAsBoolean(1));
        assertTrue(msg.getParamAsBoolean(2));
        assertFalse(msg.getParamAsBoolean(3), "null param should parse as false");
    }

    @Test
    void messageGetParamAsInt_parsesValidInteger() {
        SyncProtocol.Message msg = new SyncProtocol.Message("CMD", new String[] {"42", "-7"});
        assertEquals(42, msg.getParamAsInt(0));
        assertEquals(-7, msg.getParamAsInt(1));
    }

    @Test
    void messageGetParamAsLong_parsesValidLong() {
        SyncProtocol.Message msg = new SyncProtocol.Message("CMD", new String[] {"9999999999"});
        assertEquals(9999999999L, msg.getParamAsLong(0));
    }

    @Test
    void messageGetParamAsInt_throwsForMissingParam() {
        SyncProtocol.Message msg = new SyncProtocol.Message("CMD", new String[] {});
        assertThrows(
                SyncProtocol.Message.ProtocolFieldParseException.class, () -> msg.getParamAsInt(0));
    }

    @Test
    void messageGetParamAsLong_throwsForMissingParam() {
        SyncProtocol.Message msg = new SyncProtocol.Message("CMD", new String[] {});
        assertThrows(
                SyncProtocol.Message.ProtocolFieldParseException.class,
                () -> msg.getParamAsLong(0));
    }

    @Test
    void message_toStringIncludesCommandAndParams() {
        SyncProtocol.Message msg = new SyncProtocol.Message("ACK", new String[] {});
        assertTrue(msg.toString().contains("ACK"));

        SyncProtocol.Message msgWithParams =
                new SyncProtocol.Message("FILE_REQ", new String[] {"path", "123"});
        String str = msgWithParams.toString();
        assertTrue(str.contains("FILE_REQ"));
        assertTrue(str.contains("path"));
        assertTrue(str.contains("123"));
    }

    // ========== Send convenience methods ==========

    @FunctionalInterface
    private interface Sender {
        void send(SyncProtocol protocol) throws IOException;
    }

    /**
     * Every send* convenience delegates to {@code sendCommand}. One pass over their framed output:
     * the line must be a complete frame containing the expected marker and parameters (for the
     * parameterless commands the expected fragment is the whole frame, so the marker checks make
     * that an exact-match).
     */
    private static void assertFramed(Sender sender, String... expectedFragments)
            throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        sender.send(protocol);
        String line = serialPort.getLastSentLine();
        assertNotNull(line);
        assertTrue(line.startsWith("[[SYNC:"), "line must start a frame: " + line);
        assertTrue(line.endsWith("]]"), "line must end the frame: " + line);
        for (String fragment : expectedFragments) {
            assertTrue(line.contains(fragment), line + " must contain: " + fragment);
        }
    }

    @Test
    void sendConvenienceMethods_frameCommandsCorrectly() throws IOException {
        assertFramed(SyncProtocol::sendAck, "[[SYNC:ACK]]");
        assertFramed(SyncProtocol::sendHeartbeat, "[[SYNC:HEARTBEAT]]");
        assertFramed(SyncProtocol::sendHeartbeatAck, "[[SYNC:HEARTBEAT_ACK]]");
        assertFramed(SyncProtocol::sendDisconnect, "[[SYNC:DISCONNECT]]");
        assertFramed(SyncProtocol::sendCancelCommand, "[[SYNC:CANCEL]]");
        assertFramed(p -> p.sendError("something failed"), "ERROR", "something failed");
        assertFramed(p -> p.sendDirectionChange(true), "DIRECTION_CHANGE", "true");
        assertFramed(p -> p.sendRoleNegotiate(100L, 200L), "ROLE_NEGOTIATE", "100", "200");
        assertFramed(p -> p.sendFileDelete("sub/file.txt"), "FILE_DELETE", "sub/file.txt");
        assertFramed(p -> p.sendMkdir("newdir"), "MKDIR", "newdir");
        assertFramed(p -> p.sendRmdir("olddir"), "RMDIR", "olddir");
    }
}
