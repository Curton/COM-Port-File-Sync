package com.filesync.serial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class XModemTransferTest {

    @Test
    void receivePreservesTrailingCtrlZWhenLengthIsKnown() throws IOException {
        byte[] payload = {'A', 0x1A};
        byte[] frame = buildSohFrame(payload);
        TestSerialPortManager serialPort = new TestSerialPortManager(frame);
        XModemTransfer transfer = new XModemTransfer(serialPort);

        byte[] result = transfer.receive(payload.length);

        assertNotNull(result);
        assertArrayEquals(payload, result);
    }

    @Test
    void receiveWithoutExpectedLengthStillTrimsTrailingCtrlZPadding() throws IOException {
        byte[] payload = {'A', 0x1A};
        byte[] frame = buildSohFrame(payload);
        TestSerialPortManager serialPort = new TestSerialPortManager(frame);
        XModemTransfer transfer = new XModemTransfer(serialPort);

        byte[] result = transfer.receive(-1);

        assertArrayEquals(new byte[] {'A'}, result);
    }

    private static byte[] buildSohFrame(byte[] payload) {
        byte[] block = new byte[128];
        Arrays.fill(block, (byte) 0x1A);
        System.arraycopy(payload, 0, block, 0, payload.length);

        int crc = XModemTransfer.calculateCRC16(block);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(XModemTransfer.SOH);
        stream.write(1);
        stream.write(254);
        stream.writeBytes(block);
        stream.write((crc >> 8) & 0xFF);
        stream.write(crc & 0xFF);
        stream.write(XModemTransfer.EOT);
        return stream.toByteArray();
    }

    private static final class TestSerialPortManager extends SerialPortManager {
        private final ByteArrayInputStream inputStream;

        private TestSerialPortManager(byte[] input) {
            this.inputStream = new ByteArrayInputStream(input);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public int available() {
            return inputStream.available();
        }

        @Override
        public int read() throws IOException {
            return inputStream.read();
        }

        @Override
        public byte[] readExact(int length, int timeoutMs) throws IOException {
            byte[] data = new byte[length];
            int bytesRead = 0;
            while (bytesRead < length) {
                int read = inputStream.read(data, bytesRead, length - bytesRead);
                if (read < 0) {
                    throw new IOException(
                            "Unexpected end of stream while reading " + length + " bytes");
                }
                bytesRead += read;
            }
            return data;
        }

        @Override
        public void write(int b) throws IOException {
            // Intentionally ignored in test.
        }

        @Override
        public void write(byte[] data) throws IOException {
            // Intentionally ignored in test.
        }

        @Override
        public void clearInputBuffer() throws IOException {
            // Intentionally ignored in test.
        }
    }
}
