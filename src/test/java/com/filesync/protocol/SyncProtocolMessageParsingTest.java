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

    @Test
    void sendAck_framedCorrectly() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        protocol.sendAck();
        assertEquals("[[SYNC:ACK]]", serialPort.getLastSentLine());
    }

    @Test
    void sendHeartbeat_framedCorrectly() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        protocol.sendHeartbeat();
        assertEquals("[[SYNC:HEARTBEAT]]", serialPort.getLastSentLine());
    }

    @Test
    void sendHeartbeatAck_framedCorrectly() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        protocol.sendHeartbeatAck();
        assertEquals("[[SYNC:HEARTBEAT_ACK]]", serialPort.getLastSentLine());
    }

    @Test
    void sendDisconnect_framedCorrectly() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        protocol.sendDisconnect();
        assertEquals("[[SYNC:DISCONNECT]]", serialPort.getLastSentLine());
    }

    @Test
    void sendError_framedWithMessage() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        protocol.sendError("something failed");
        String line = serialPort.getLastSentLine();
        assertNotNull(line);
        assertTrue(line.contains("ERROR"));
        assertTrue(line.contains("something failed"));
    }

    @Test
    void sendDirectionChange_framedWithBoolean() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        protocol.sendDirectionChange(true);
        String line = serialPort.getLastSentLine();
        assertNotNull(line);
        assertTrue(line.contains("DIRECTION_CHANGE"));
        assertTrue(line.contains("true"));
    }

    @Test
    void sendRoleNegotiate_framedWithPriorityAndTieBreaker() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        protocol.sendRoleNegotiate(100L, 200L);
        String line = serialPort.getLastSentLine();
        assertNotNull(line);
        assertTrue(line.contains("ROLE_NEGOTIATE"));
        assertTrue(line.contains("100"));
        assertTrue(line.contains("200"));
    }

    @Test
    void sendFileDelete_framedWithPath() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        protocol.sendFileDelete("sub/file.txt");
        String line = serialPort.getLastSentLine();
        assertNotNull(line);
        assertTrue(line.contains("FILE_DELETE"));
        assertTrue(line.contains("sub/file.txt"));
    }

    @Test
    void sendMkdir_framedWithPath() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        protocol.sendMkdir("newdir");
        String line = serialPort.getLastSentLine();
        assertNotNull(line);
        assertTrue(line.contains("MKDIR"));
        assertTrue(line.contains("newdir"));
    }

    @Test
    void sendRmdir_framedWithPath() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        protocol.sendRmdir("olddir");
        String line = serialPort.getLastSentLine();
        assertNotNull(line);
        assertTrue(line.contains("RMDIR"));
        assertTrue(line.contains("olddir"));
    }

    @Test
    void sendCancelCommand_framedCorrectly() throws IOException {
        RecordingSerialPortManager serialPort = new RecordingSerialPortManager();
        SyncProtocol protocol = new SyncProtocol(serialPort);
        protocol.sendCancelCommand();
        assertEquals("[[SYNC:CANCEL]]", serialPort.getLastSentLine());
    }
}
