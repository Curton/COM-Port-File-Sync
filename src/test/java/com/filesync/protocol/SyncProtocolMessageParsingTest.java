package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.filesync.serial.SerialPortManager;

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

        SyncProtocol.Message backslashMessage = SyncProtocol.parseMessage("[[SYNC:FILE_REQ:dir\\\\name]]");
        assertNotNull(backslashMessage);
        assertEquals("dir\\name", backslashMessage.getParam(0));
    }

    @Test
    void getParamAsIntThrowsProtocolFieldParseExceptionForInvalidValue() {
        SyncProtocol.Message message = new SyncProtocol.Message("FILE_DATA", new String[]{"abc"});
        assertThrows(SyncProtocol.Message.ProtocolFieldParseException.class, () -> message.getParamAsInt(0));
    }

    @Test
    void getParamAsLongThrowsProtocolFieldParseExceptionForInvalidValue() {
        SyncProtocol.Message message = new SyncProtocol.Message("DROP_FILE", new String[]{"abc"});
        assertThrows(SyncProtocol.Message.ProtocolFieldParseException.class, () -> message.getParamAsLong(0));
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
}

