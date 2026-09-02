package com.filesync.serial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

    @Test
    void receiveIntoStreamsVerifiedBlocksAndCapsPaddingAtExpectedLength() throws IOException {
        // Crosses block formats: one 4096-byte block plus a 104-byte tail inside a 1K block.
        byte[] payload = new byte[4200];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        TestSerialPortManager serialPort = new TestSerialPortManager(buildMultiBlockFrame(payload));
        XModemTransfer transfer = new XModemTransfer(serialPort);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        long written = transfer.receiveInto(payload.length, sink);

        assertEquals(payload.length, written, "A clean transfer reports the expected length");
        assertArrayEquals(payload, sink.toByteArray(), "Streaming must preserve the payload");
    }

    @Test
    void receiveIntoCapsWritesAtExpectedLength() throws IOException {
        byte[] payload = new byte[300];
        Arrays.fill(payload, (byte) 'x');
        TestSerialPortManager serialPort = new TestSerialPortManager(buildMultiBlockFrame(payload));
        XModemTransfer transfer = new XModemTransfer(serialPort);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        long written = transfer.receiveInto(100, sink);

        assertEquals(100, written, "Trailing block padding must not reach the sink");
        assertArrayEquals(Arrays.copyOf(payload, 100), sink.toByteArray());
    }

    @Test
    void receiveIntoReturnsMinusOneWhenSenderCancels() throws IOException {
        TestSerialPortManager serialPort =
                new TestSerialPortManager(new byte[] {XModemTransfer.CAN});
        XModemTransfer transfer = new XModemTransfer(serialPort);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        assertEquals(-1, transfer.receiveInto(100, sink), "A sender cancel must report failure");
        assertEquals(0, sink.size());
    }

    @Test
    void receiveIntoPropagatesMidBlockStreamFailure() {
        // Handshake + block header only: the block data read hits end-of-stream and must throw.
        byte[] partial = new byte[] {XModemTransfer.SOH, 1, (byte) 254, 'a', 'b', 'c'};
        TestSerialPortManager serialPort = new TestSerialPortManager(partial);
        XModemTransfer transfer = new XModemTransfer(serialPort);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        assertThrows(IOException.class, () -> transfer.receiveInto(128, sink));
        assertEquals(0, sink.size());
    }

    @Test
    void receiveReturnsNullWhenCleanTransferIsShorterThanExpected() throws IOException {
        byte[] payload = {'A'};
        TestSerialPortManager serialPort = new TestSerialPortManager(buildSohFrame(payload));
        XModemTransfer transfer = new XModemTransfer(serialPort);

        assertNull(
                transfer.receive(200),
                "A clean-but-short transfer must fail the known-length contract");
    }

    @Test
    @Timeout(20)
    void sendSucceedsWhenHandshakeCharArrivesBeforeSendStarts() throws IOException {
        // The receiver ACKs the transfer command and sends its 'C' immediately; by the time the
        // sender enters the XMODEM phase the 'C' is usually already buffered. Purging the input
        // here discarded it and stalled the session for a full receiver re-send cycle.
        byte[] input = {
            XModemTransfer.C,
            XModemTransfer.ACK, // consumed by drainExtraHandshakeChars
            XModemTransfer.ACK, // consumed by sendBlock's stale-char drain
            XModemTransfer.ACK, // acknowledges the data block
            XModemTransfer.ACK // acknowledges the EOT
        };
        PurgingTestSerialPortManager serialPort = new PurgingTestSerialPortManager(input);
        XModemTransfer transfer = new XModemTransfer(serialPort);

        assertTrue(transfer.send(new byte[10]), "an early 'C' must complete the handshake");
    }

    @Test
    @Timeout(20)
    void receiveToleratesSenderThatAnswersAfterSeveralResendCycles() throws IOException {
        // The receiver re-sends 'C' every 200ms; a sender that only answers after ~450ms —
        // past one resend cycle — must still connect, because the receive window spans
        // many cycles.
        byte[] payload = {'A'};
        DelayedTestSerialPortManager serialPort =
                new DelayedTestSerialPortManager(buildSohFrame(payload), 450);
        XModemTransfer transfer = new XModemTransfer(serialPort);

        byte[] result = transfer.receive(payload.length);

        assertNotNull(result);
        assertArrayEquals(payload, result);
    }

    private static byte[] buildMultiBlockFrame(byte[] payload) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        int offset = 0;
        int blockNumber = 1;
        while (offset < payload.length) {
            int remaining = payload.length - offset;
            int blockSize;
            byte header;
            if (remaining >= 4096) {
                blockSize = 4096;
                header = XModemTransfer.STX4K;
            } else if (remaining >= 1024 || remaining > 128) {
                blockSize = 1024;
                header = XModemTransfer.STX;
            } else {
                blockSize = 128;
                header = XModemTransfer.SOH;
            }
            byte[] block = new byte[blockSize];
            int toCopy = Math.min(remaining, blockSize);
            System.arraycopy(payload, offset, block, 0, toCopy);
            Arrays.fill(block, toCopy, blockSize, (byte) 0x1A);

            int crc = XModemTransfer.calculateCRC16(block);
            stream.write(header);
            stream.write(blockNumber & 0xFF);
            stream.write(255 - (blockNumber & 0xFF));
            stream.writeBytes(block);
            stream.write((crc >> 8) & 0xFF);
            stream.write(crc & 0xFF);

            offset += toCopy;
            blockNumber++;
        }
        stream.write(XModemTransfer.EOT);
        return stream.toByteArray();
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

    /** Port manager whose clearInputBuffer purges buffered input, like the real serial driver. */
    private static final class PurgingTestSerialPortManager extends SerialPortManager {
        private final ByteArrayInputStream inputStream;

        private PurgingTestSerialPortManager(byte[] input) {
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
            while (inputStream.available() > 0) {
                inputStream.read();
            }
        }
    }

    /** Port manager whose input only becomes visible after a delay, simulating a slow sender. */
    private static final class DelayedTestSerialPortManager extends SerialPortManager {
        private final ByteArrayInputStream inputStream;
        private final long deliverAtMillis;

        private DelayedTestSerialPortManager(byte[] input, long delayMillis) {
            this.inputStream = new ByteArrayInputStream(input);
            this.deliverAtMillis = System.currentTimeMillis() + delayMillis;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public int available() {
            return System.currentTimeMillis() >= deliverAtMillis ? inputStream.available() : 0;
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
