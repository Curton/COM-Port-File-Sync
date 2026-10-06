package com.filesync.serial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class XModemTransferTest {

    @Test
    void receiveTrailingCtrlZHandlingDependsOnExpectedLength() throws IOException {
        byte[] payload = {'A', 0x1A};
        byte[] frame = buildSohFrame(payload);

        TestSerialPortManager serialPort = new TestSerialPortManager(frame);
        XModemTransfer transfer = new XModemTransfer(serialPort);
        byte[] result = transfer.receive(payload.length);

        assertNotNull(result);
        assertArrayEquals(payload, result);

        serialPort = new TestSerialPortManager(frame);
        transfer = new XModemTransfer(serialPort);
        result = transfer.receive(-1);

        assertArrayEquals(new byte[] {'A'}, result);
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
    @Timeout(20)
    void receiveAbortsAfterRepeatedBlockNumberMismatches() throws IOException {
        // Minimal input that keeps the receive loop inside the block-number-mismatch branch: each
        // round is SOH + a block number + a complement that does not sum to 255, followed by
        // exactly the blockSize + 2 bytes that path drains, so the next round starts on a fresh
        // header byte. A peer resending a corrupt block at a fixed stride produces this shape, and
        // it is the one shape in which the mismatch path can be re-entered indefinitely.
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (int i = 0; i < 500; i++) {
            stream.write(XModemTransfer.SOH);
            stream.write(1);
            stream.write(0); // 1 + 0 != 255
            stream.write(new byte[130], 0, 130);
        }
        RecordingTestSerialPortManager serialPort =
                new RecordingTestSerialPortManager(stream.toByteArray());
        XModemTransfer transfer = new XModemTransfer(serialPort);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        long written = transfer.receiveInto(128, sink);

        assertEquals(-1, written, "Repeated block number mismatches must abort the transfer");
        assertEquals(0, sink.size(), "Nothing may be written for a rejected block");
        assertTrue(
                transfer.getLastErrorMessage().contains("block number"),
                "The mismatch path must be the one that gives up, got: "
                        + transfer.getLastErrorMessage());
        assertTrue(
                countWrites(serialPort.getWrites(), new byte[] {XModemTransfer.NAK}) <= 20,
                "A handful of NAKs must be enough to abort, not one per resent block");
    }

    @Test
    @Timeout(20)
    void receiveFailureResendsCancelSoOneLostCanDoesNotOrphanTheSender() {
        // The block data read hitting end-of-stream escapes receiveInto as an IOException.
        // That exit must tell the sender to stop with several spaced-out abort signals: a
        // single lost CAN would leave the sender streaming into a session the command
        // listener has already taken back.
        byte[] partial = {XModemTransfer.SOH, 1, (byte) 254, 'a', 'b', 'c'};
        RecordingTestSerialPortManager serialPort = new RecordingTestSerialPortManager(partial);
        XModemTransfer transfer = new XModemTransfer(serialPort);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        assertThrows(IOException.class, () -> transfer.receiveInto(128, sink));
        assertEquals(0, sink.size());

        assertEquals(
                6,
                countWrites(serialPort.getWrites(), new byte[] {XModemTransfer.CAN}),
                "sendCancel writes 2 CAN bytes per call; the finally must run it 3 times");
    }

    @Test
    @Timeout(20)
    void receiveCompletionSendsNoCancelSignal() throws IOException {
        // The cancel safety net in the finally must not fire after a clean EOT: extra CAN
        // bytes on a healthy session would appear as stray data to the command listener.
        byte[] payload = {'A'};
        RecordingTestSerialPortManager serialPort =
                new RecordingTestSerialPortManager(buildSohFrame(payload));
        XModemTransfer transfer = new XModemTransfer(serialPort);

        byte[] result = transfer.receive(payload.length);

        assertArrayEquals(payload, result);
        assertEquals(
                0,
                countWrites(serialPort.getWrites(), new byte[] {XModemTransfer.CAN}),
                "a clean EOT must not be followed by any CAN byte");
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
    void sendDrainsInFlightHandshakeCharWithoutResendingBlock() throws IOException {
        // A straggler 'C' still in flight when the handshake completes lands ~10ms later, inside
        // the drain's quiet window, so it must be cleared there: the first block is written
        // exactly once.
        StagedTestSerialPortManager serialPort =
                new StagedTestSerialPortManager(
                        new byte[] {XModemTransfer.C},
                        new byte[] {
                            XModemTransfer.C, // straggler handshake char, drained
                            XModemTransfer.ACK, // consumed by drainExtraHandshakeChars
                            XModemTransfer.ACK, // consumed by sendBlock's stale-char drain
                            XModemTransfer.ACK, // acknowledges the data block
                            XModemTransfer.ACK // acknowledges the EOT
                        },
                        10);
        XModemTransfer transfer = new XModemTransfer(serialPort);

        assertTrue(transfer.send(new byte[10]), "an in-flight straggler 'C' must be drained");

        assertEquals(1, countDataPackets(serialPort.getWrites()), "the block must not be re-sent");
    }

    @Test
    @Timeout(20)
    void sendRecoversViaBlockResendWhenHandshakeCharOutrunsDrainWindow() throws IOException {
        // A straggler 'C' that lands after the drain window (~100ms here) is read at the block's
        // ACK position instead; that must cost exactly one block re-send, not the session — the
        // receiver ACKs duplicate blocks.
        StagedTestSerialPortManager serialPort =
                new StagedTestSerialPortManager(
                        new byte[] {XModemTransfer.C},
                        new byte[] {
                            XModemTransfer.C, // straggler read at the ACK position, triggers retry
                            XModemTransfer.ACK, // consumed by the retry's stale-char drain
                            XModemTransfer.ACK, // acknowledges the re-sent block
                            XModemTransfer.ACK // acknowledges the EOT
                        },
                        100);
        XModemTransfer transfer = new XModemTransfer(serialPort);

        assertTrue(transfer.send(new byte[10]), "a late straggler 'C' must not fail the session");

        assertEquals(2, countDataPackets(serialPort.getWrites()), "the miss costs one re-send");
    }

    @Test
    @Timeout(20)
    void sendSplitsShortTailInto128ByteBlocksInsteadOfOnePadded1KBlock() throws IOException {
        // 4300 bytes = one 4K block + a 204-byte tail. The tail used to ride in a single 1K
        // block padded with 820 CTRL-Z bytes; it must now walk out as two 128-byte blocks so
        // the padding overhead on the line is capped at 127 bytes.
        byte[] payload = new byte[4300];
        // ACK budget: drainExtraHandshakeChars, then per block a stale-char drain + a block
        // ACK (3 blocks), then the EOT ACK.
        RecordingTestSerialPortManager serialPort =
                new RecordingTestSerialPortManager(buildInput(XModemTransfer.C, 8));
        XModemTransfer transfer = new XModemTransfer(serialPort);

        assertTrue(transfer.send(payload));

        List<Byte> dataHeaders = new ArrayList<>();
        for (byte[] write : serialPort.getWrites()) {
            if (write.length > 4) { // data packet: header + blockNum + complement + data + CRC
                dataHeaders.add(write[0]);
            }
        }
        assertEquals(
                List.of(XModemTransfer.STX4K, XModemTransfer.SOH, XModemTransfer.SOH),
                dataHeaders,
                "the 204-byte tail must split into two 128-byte blocks, not one padded 1K block");
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

    // ========== between-blocks interleave (shared text) ==========

    private static final byte[] INTERLEAVE_FRAME =
            "[[SYNC:SHARED_TEXT:123:QUJD]]\n".getBytes(StandardCharsets.UTF_8);

    @Test
    @Timeout(20)
    void sendRunsBlockBoundaryHookAndDeliversFrameBetweenBlocks() throws IOException {
        // 4200 bytes = one 4K block + one 128-byte block, so the hook runs at two boundaries.
        // Scripted reads: 'C' handshake, ACK drained by drainExtraHandshakeChars, ACK drained
        // by block 1's stale-char drain, ACK for block 1, ACK for the interleave frame, ACK
        // drained by block 2's stale-char drain, ACK for block 2, ACK for the EOT.
        byte[] payload = new byte[4200];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        byte[] input = buildInput(XModemTransfer.C, 7);
        RecordingTestSerialPortManager serialPort = new RecordingTestSerialPortManager(input);
        XModemTransfer transfer = new XModemTransfer(serialPort);
        AtomicInteger hookCalls = new AtomicInteger();
        transfer.setBlockBoundaryHook(
                () -> {
                    if (hookCalls.incrementAndGet() == 1) {
                        return transfer.sendInterleavedFrame(INTERLEAVE_FRAME)
                                ? XModemTransfer.InterleaveResult.SENT
                                : XModemTransfer.InterleaveResult.FAILED;
                    }
                    return XModemTransfer.InterleaveResult.NOTHING_PENDING;
                });

        assertTrue(transfer.send(payload), "the session must complete after the interleave");

        assertEquals(2, hookCalls.get(), "hook runs at every block boundary");
        assertEquals(
                1,
                countWrites(serialPort.getWrites(), INTERLEAVE_FRAME),
                "interleave frame written exactly once, between the two blocks");
        assertEquals(
                1,
                countWrites(serialPort.getWrites(), new byte[] {XModemTransfer.EOT}),
                "EOT must still be sent after the interleaved frame");
    }

    @Test
    @Timeout(20)
    void sendKeepsAskingHookWhenNothingIsPending() throws IOException {
        byte[] payload = new byte[4200];
        byte[] input = buildInput(XModemTransfer.C, 6);
        RecordingTestSerialPortManager serialPort = new RecordingTestSerialPortManager(input);
        XModemTransfer transfer = new XModemTransfer(serialPort);
        AtomicInteger hookCalls = new AtomicInteger();
        transfer.setBlockBoundaryHook(
                () -> {
                    hookCalls.incrementAndGet();
                    return XModemTransfer.InterleaveResult.NOTHING_PENDING;
                });

        assertTrue(transfer.send(payload));

        assertEquals(2, hookCalls.get(), "an empty boundary must not disable the hook");
    }

    @Test
    @Timeout(20)
    void sendLatchesHookOffAfterFailedInterleaveAttempt() throws IOException {
        // A FAILED result must not be re-tried at every remaining boundary, or an
        // unacknowledged interleave would stall each one for the ACK timeout.
        byte[] payload = new byte[4200];
        byte[] input = buildInput(XModemTransfer.C, 6);
        RecordingTestSerialPortManager serialPort = new RecordingTestSerialPortManager(input);
        XModemTransfer transfer = new XModemTransfer(serialPort);
        AtomicInteger hookCalls = new AtomicInteger();
        transfer.setBlockBoundaryHook(
                () -> {
                    hookCalls.incrementAndGet();
                    return XModemTransfer.InterleaveResult.FAILED;
                });

        assertTrue(transfer.send(payload), "a failed interleave must not fail the session");

        assertEquals(1, hookCalls.get(), "FAILED result latches the hook off for the session");
    }

    @Test
    void sendInterleavedFrameResendsAfterNakUntilAcknowledged() throws IOException {
        RecordingTestSerialPortManager serialPort =
                new RecordingTestSerialPortManager(
                        new byte[] {XModemTransfer.NAK, XModemTransfer.ACK});
        XModemTransfer transfer = new XModemTransfer(serialPort);

        assertTrue(transfer.sendInterleavedFrame(INTERLEAVE_FRAME));
        assertEquals(2, countWrites(serialPort.getWrites(), INTERLEAVE_FRAME));
    }

    @Test
    void sendInterleavedFrameGivesUpAfterRepeatedUnacknowledgedAttempts() throws IOException {
        // The port always answers with an immediate garbage byte (EOF read masked to 0xFF),
        // so all three attempts fail without any real-time waiting.
        GarbageResponseTestSerialPortManager serialPort =
                new GarbageResponseTestSerialPortManager();
        XModemTransfer transfer = new XModemTransfer(serialPort);

        assertFalse(transfer.sendInterleavedFrame(INTERLEAVE_FRAME));
        assertEquals(
                3,
                countWrites(serialPort.getWrites(), INTERLEAVE_FRAME),
                "the frame is resent once per attempt, then the hook gives up");
    }

    @Test
    void sendInterleavedFrameAbortsSessionOnCancel() {
        RecordingTestSerialPortManager serialPort =
                new RecordingTestSerialPortManager(new byte[] {XModemTransfer.CAN});
        XModemTransfer transfer = new XModemTransfer(serialPort);

        assertThrows(IOException.class, () -> transfer.sendInterleavedFrame(INTERLEAVE_FRAME));
        assertTrue(transfer.wasCancelSignalled(), "a CAN during the interleave wait is a cancel");
    }

    @Test
    @Timeout(20)
    void receiveIntoConsumesInterleavedFrameBetweenBlocksAndKeepsSession() throws IOException {
        byte[] payload = new byte[4200];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        RecordingTestSerialPortManager serialPort =
                new RecordingTestSerialPortManager(
                        buildMultiBlockFrameWithInterleave(payload, INTERLEAVE_FRAME));
        XModemTransfer transfer = new XModemTransfer(serialPort);
        List<String> receivedLines = new ArrayList<>();
        transfer.setInterleavedFrameHandler(receivedLines::add);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        long written = transfer.receiveInto(payload.length, sink);

        assertEquals(payload.length, written, "the interleaved frame must not truncate payload");
        assertArrayEquals(payload, sink.toByteArray(), "block data must survive the interleave");
        assertEquals(
                List.of("[[SYNC:SHARED_TEXT:123:QUJD]]"),
                receivedLines,
                "the handler gets the complete framed line");
        assertEquals(
                4,
                countWrites(serialPort.getWrites(), new byte[] {XModemTransfer.ACK}),
                "ACKs: block 1, interleave frame, block 2, EOT");
    }

    @Test
    @Timeout(20)
    void receiveIntoAcksInterleavedFrameWithoutHandlerAndWhenHandlerThrows() throws IOException {
        byte[] payload = {'A'};
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.writeBytes(INTERLEAVE_FRAME);
        stream.writeBytes(buildSohFrame(payload));

        // No handler installed: the frame must not stall the session.
        RecordingTestSerialPortManager serialPort =
                new RecordingTestSerialPortManager(stream.toByteArray());
        XModemTransfer transfer = new XModemTransfer(serialPort);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        long written = transfer.receiveInto(payload.length, sink);

        assertEquals(payload.length, written, "no handler installed must not stall the session");
        assertArrayEquals(payload, sink.toByteArray());
        assertTrue(
                countWrites(serialPort.getWrites(), new byte[] {XModemTransfer.ACK}) >= 2,
                "frame and blocks are acknowledged even with no handler");

        // A handler failure must not escape the receive loop: the frame is still ACKed so the
        // sender does not retry it, and the file session has to complete untouched.
        serialPort = new RecordingTestSerialPortManager(stream.toByteArray());
        transfer = new XModemTransfer(serialPort);
        transfer.setInterleavedFrameHandler(
                line -> {
                    throw new IllegalStateException("handler blew up");
                });
        sink = new ByteArrayOutputStream();

        written = transfer.receiveInto(payload.length, sink);

        assertEquals(payload.length, written, "a throwing handler must not abort the session");
        assertArrayEquals(payload, sink.toByteArray());
        assertTrue(
                countWrites(serialPort.getWrites(), new byte[] {XModemTransfer.ACK}) >= 2,
                "the frame is acknowledged even when the handler throws");
    }

    /** Build an input of one leading byte followed by {@code ackCount} ACK bytes. */
    private static byte[] buildInput(byte first, int ackCount) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(first);
        for (int i = 0; i < ackCount; i++) {
            stream.write(XModemTransfer.ACK);
        }
        return stream.toByteArray();
    }

    private static int countWrites(List<byte[]> writes, byte[] expected) {
        int count = 0;
        for (byte[] write : writes) {
            if (Arrays.equals(write, expected)) {
                count++;
            }
        }
        return count;
    }

    /** Count block packet writes (header + blockNum + complement + data + CRC), i.e. re-sends. */
    private static int countDataPackets(List<byte[]> writes) {
        int count = 0;
        for (byte[] write : writes) {
            if (write.length > 4) {
                count++;
            }
        }
        return count;
    }

    /** Like {@link #buildMultiBlockFrame(byte[])} but inserts a raw frame after the first block. */
    private static byte[] buildMultiBlockFrameWithInterleave(byte[] payload, byte[] frame) {
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
            } else if (remaining >= 1024) {
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
            stream.write((crc & 0xFF));

            offset += toCopy;
            blockNumber++;
            if (blockNumber == 2) {
                stream.writeBytes(frame);
            }
        }
        stream.write(XModemTransfer.EOT);
        return stream.toByteArray();
    }

    /**
     * Port manager that records every outbound write so frames and control bytes can be asserted.
     */
    private static final class RecordingTestSerialPortManager extends SerialPortManager {
        private final ByteArrayInputStream inputStream;
        private final List<byte[]> writes = new CopyOnWriteArrayList<>();

        private RecordingTestSerialPortManager(byte[] input) {
            this.inputStream = new ByteArrayInputStream(input);
        }

        List<byte[]> getWrites() {
            return writes;
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
        public void write(int b) {
            writes.add(new byte[] {(byte) b});
        }

        @Override
        public void write(byte[] data) {
            writes.add(data.clone());
        }

        @Override
        public void clearInputBuffer() {
            // Intentionally ignored in test.
        }
    }

    /**
     * Port manager whose reads always yield an immediate garbage byte (available() reports data,
     * read() returns EOF which readByteWithTimeout masks to 0xFF), so ACK waits fail instantly
     * instead of after the full 10 s timeout.
     */
    private static final class GarbageResponseTestSerialPortManager extends SerialPortManager {
        private final List<byte[]> writes = new CopyOnWriteArrayList<>();

        List<byte[]> getWrites() {
            return writes;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public int available() {
            return 1;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void write(int b) {
            writes.add(new byte[] {(byte) b});
        }

        @Override
        public void write(byte[] data) {
            writes.add(data.clone());
        }
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
            } else if (remaining >= 1024) {
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

    /**
     * Port manager that records writes and serves input in two stages: the leading bytes are
     * visible immediately, the rest only after a delay, so a straggler handshake char can be
     * scripted to land mid-drain (inside the quiet window) or after the drain window.
     */
    private static final class StagedTestSerialPortManager extends SerialPortManager {
        private final ByteArrayInputStream immediate;
        private final ByteArrayInputStream delayed;
        private final long delayedAtMillis;
        private final List<byte[]> writes = new CopyOnWriteArrayList<>();

        private StagedTestSerialPortManager(byte[] immediate, byte[] delayed, long delayMillis) {
            this.immediate = new ByteArrayInputStream(immediate);
            this.delayed = new ByteArrayInputStream(delayed);
            this.delayedAtMillis = System.currentTimeMillis() + delayMillis;
        }

        List<byte[]> getWrites() {
            return writes;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public int available() {
            int count = immediate.available();
            if (System.currentTimeMillis() >= delayedAtMillis) {
                count += delayed.available();
            }
            return count;
        }

        @Override
        public int read() {
            if (immediate.available() > 0) {
                return immediate.read();
            }
            return delayed.read();
        }

        @Override
        public void write(int b) {
            writes.add(new byte[] {(byte) b});
        }

        @Override
        public void write(byte[] data) {
            writes.add(data.clone());
        }
    }
}
