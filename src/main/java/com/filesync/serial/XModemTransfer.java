package com.filesync.serial;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Implements XMODEM protocol for reliable file transfer over serial port. Uses 4096-byte blocks for
 * large payloads, 1024-byte blocks (STX) with CRC-16 checksum, and falls back to 128-byte (SOH) for
 * small data.
 */
public class XModemTransfer {

    // XMODEM control characters
    public static final byte SOH = 0x01; // Start of Header (128 byte block)
    public static final byte STX = 0x02; // Start of Header (1024 byte block) - XMODEM-1K
    public static final byte STX4K = 0x05; // Start of Header (4096 byte block) - XMODEM-4K (custom)
    public static final byte EOT = 0x04; // End of Transmission
    public static final byte ACK = 0x06; // Acknowledge
    public static final byte NAK = 0x15; // Negative Acknowledge
    public static final byte CAN = 0x18; // Cancel
    public static final byte C = 0x43; // 'C' for CRC mode

    private static final int BLOCK_SIZE_1K = 1024; // XMODEM-1K block size
    private static final int BLOCK_SIZE_4K = 4096; // XMODEM-4K block size
    private static final int BLOCK_SIZE_128 = 128; // Standard XMODEM block size
    private static final int MAX_RETRIES = 10;
    private static final int TIMEOUT_MS = 10000;
    private static final int HANDSHAKE_TIMEOUT_MS = 60000;
    private static final int MAX_INTERLEAVE_RETRIES = 3;
    // The interleave frame is short and the receiver answers as soon as it has consumed the
    // line, so it gets its own ACK timeout instead of the 10 s block timeout: three full block
    // timeouts would stall a single block boundary for 30 s on an unresponsive peer.
    private static final int INTERLEAVE_ACK_TIMEOUT_MS = 2000;
    // Sanity bound for a frame consumed at the block-header position; real frames stay far
    // below this (the sender only interleaves inline-budget text).
    private static final int MAX_INTERLEAVED_FRAME_BYTES = 2 * 1024 * 1024;
    private static final byte FRAME_START_BYTE = '[';
    private static final byte PADDING = 0x1A; // CTRL-Z for padding
    private static final int POLL_INTERVAL_MS = 1; // Reduced from 10ms for better throughput
    private static final int HANDSHAKE_RESEND_INTERVAL_MS = 200;
    // Pause before the post-handshake drain. It only has to outlast a 'C' straggler that is
    // already in flight (one byte-time at any supported baud, plus driver latency) so the drain
    // clears it before the first block goes out. The pause is a fast path, not the safety net:
    // how long a straggler could still be coming was never knowable here, so a longer pause only
    // buys sleep for every transfer. A straggler that arrives after it is skipped where it lands
    // — at a block's ACK position — regardless of its offset (see
    // readTransferResponseConsumingFrames).
    private static final int HANDSHAKE_DRAIN_PAUSE_MS = 5;
    // How many stray 'C's a single response read may skip before giving up and returning the byte
    // (the caller's retry then drains the backlog with its stale-char drain). The receiver emits
    // at most one live straggler per handshake; the bound only keeps a pathological 'C' stream
    // from burning a whole block timeout on skips.
    private static final int MAX_HANDSHAKE_STRAGGLERS = 8;
    private static final long RECEIVE_HANDSHAKE_WINDOW_MS = (long) MAX_RETRIES * 1000;
    // A receiver aborting mid-block re-sends CAN a few times, spaced out, because one lost CAN
    // makes the sender keep streaming into a session that has already given up.
    private static final int CAN_RESEND_ATTEMPTS = 3;
    private static final int CAN_RESEND_INTERVAL_MS = 50;

    private final SerialPortManager serialPort;
    private TransferProgressListener progressListener;
    private BlockBoundaryHook blockBoundaryHook;
    private ReceiveBoundaryHook receiveBoundaryHook;
    private Consumer<String> interleavedFrameHandler;

    /**
     * One byte read past a lone '[' at the header position and pushed back for the next
     * header-position read (see the FRAME_START_BYTE branch of the receive loop). -1 = empty.
     */
    private int pushedBackByte = -1;

    /**
     * The port-session epoch ({@link SerialPortManager#currentEpoch()}) captured when the current
     * transfer started. Volatile because the port can be closed and reopened by another thread
     * mid-transfer. Loop heads compare it with the live epoch and abort the transfer when it moves,
     * so a thread from a torn-down session cannot keep reading from and writing to the port after
     * the user reconnects: those bytes belong to the new session.
     */
    private volatile long transferSessionEpoch;

    /**
     * Stores the last human-readable error message for diagnostics. Higher level code (e.g.
     * SyncProtocol) can use this to provide more detailed context when reporting failures.
     */
    private String lastErrorMessage;

    /**
     * Set when the last transfer failed because the peer sent a CAN signal. A peer cancel is an
     * expected outcome, not a communication failure; SyncProtocol consults this to surface the
     * failure as {@code TransferCancelledException} instead of a plain IOException.
     */
    private boolean cancelSignalled;

    private long transferStartTime;
    private long totalBytesTransferred;

    public XModemTransfer(SerialPortManager serialPort) {
        this.serialPort = serialPort;
    }

    public void setProgressListener(TransferProgressListener listener) {
        this.progressListener = listener;
    }

    public void setBlockBoundaryHook(BlockBoundaryHook hook) {
        this.blockBoundaryHook = hook;
    }

    public void setReceiveBoundaryHook(ReceiveBoundaryHook hook) {
        this.receiveBoundaryHook = hook;
    }

    public void setInterleavedFrameHandler(Consumer<String> handler) {
        this.interleavedFrameHandler = handler;
    }

    /** Send data using XMODEM protocol (supports 4096/1024/128-byte blocks) */
    public boolean send(byte[] data) throws IOException {
        cancelSignalled = false;
        // A transfer aborted right after a lone-'[' pushback must not leak its pushed-back byte
        // into the next transfer: this instance is reused across reconnects.
        pushedBackByte = -1;
        // Capture the port session this transfer runs on; loop heads abort when it changes.
        transferSessionEpoch = serialPort.currentEpoch();

        // Wait for receiver to send 'C' to initiate CRC mode
        if (!waitForHandshake()) {
            reportError("Handshake failed: receiver not responding");
            return false;
        }

        // Clear any extra 'C' characters that may have been sent by receiver during handshake
        // This prevents reading stale 'C' when waiting for ACK after first block
        drainExtraHandshakeChars();

        // Initialize transfer tracking
        transferStartTime = System.currentTimeMillis();
        totalBytesTransferred = 0;

        int dataOffset = 0;
        int blockNumber = 1;
        int totalBlocks = estimateTotalBlocks(data.length);
        boolean interleaveDisabled = false;

        while (dataOffset < data.length) {
            assertSessionCurrent();
            int remaining = data.length - dataOffset;

            // Choose block size: prefer 4K, then 1K, fall back to 128-byte for tiny tails
            BlockFormat format = selectBlockFormat(remaining);
            int blockSize = format.size();
            byte headerByte = format.header();

            byte[] block = new byte[blockSize];
            int bytesToCopy = Math.min(remaining, blockSize);
            System.arraycopy(data, dataOffset, block, 0, bytesToCopy);

            // Pad the block if necessary
            for (int i = bytesToCopy; i < blockSize; i++) {
                block[i] = PADDING;
            }

            // Send block with retries
            if (!sendBlock(block, blockNumber, headerByte)) {
                if (cancelSignalled) {
                    // The receiver aborted deliberately: an expected outcome, not a failure.
                    reportCancelled("Transfer cancelled by receiver");
                } else {
                    reportError(
                            "Failed to send block "
                                    + blockNumber
                                    + " after "
                                    + MAX_RETRIES
                                    + " retries");
                }
                sendCancelIfSessionCurrent();
                return false;
            }

            dataOffset += bytesToCopy;
            totalBytesTransferred += bytesToCopy;
            reportProgress(blockNumber, totalBlocks, totalBytesTransferred);
            blockNumber++;

            // Block boundary: the receiver has ACKed and is idle waiting for the next header,
            // so the line is free. Let higher-priority traffic (e.g. queued shared text)
            // interleave a short frame here. Once a hook attempt fails, stop trying for the
            // rest of the session so a rejected interleave cannot stall every remaining block.
            if (blockBoundaryHook != null && !interleaveDisabled) {
                if (blockBoundaryHook.sendBetweenBlocks() == InterleaveResult.FAILED) {
                    interleaveDisabled = true;
                }
            }
        }

        // Send EOT and wait for ACK
        if (!sendEOT()) {
            reportError("Failed to complete transfer: EOT not acknowledged");
            return false;
        }

        return true;
    }

    /** Receive data using XMODEM protocol (supports 4096, 1024 and 128-byte blocks) */
    public byte[] receive() throws IOException {
        return receive(-1);
    }

    /**
     * Receive data using XMODEM protocol.
     *
     * @param expectedDataLength expected compressed payload length in bytes, or -1 if unknown
     * @return received bytes, with padding removed only when expectedDataLength is unknown
     */
    public byte[] receive(int expectedDataLength) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long written = receiveInto(expectedDataLength, buffer);
        if (written < 0) {
            return null;
        }
        byte[] result = buffer.toByteArray();
        if (expectedDataLength > 0) {
            return enforceExpectedLength(result, expectedDataLength);
        }
        return removePadding(result);
    }

    /**
     * Receive data using XMODEM, streaming each verified block into {@code sink} as it arrives so
     * callers can stage large payloads on disk instead of buffering them in memory. When {@code
     * expectedDataLength} is known, at most that many bytes are written — trailing padding of the
     * final block is dropped — and a clean-but-short transfer is reported by returning fewer bytes
     * than expected rather than failing.
     *
     * @return the number of bytes written to the sink, or -1 if the transfer failed (handshake
     *     rejection, sender cancel, or retry exhaustion)
     */
    public long receiveInto(int expectedDataLength, OutputStream sink) throws IOException {
        cancelSignalled = false;
        // A transfer aborted right after a lone-'[' pushback must not leak its pushed-back byte
        // into the next transfer: this instance is reused across reconnects.
        pushedBackByte = -1;
        // Capture the port session this transfer runs on; loop heads abort when it changes.
        transferSessionEpoch = serialPort.currentEpoch();

        // Initiate transfer by sending 'C' for CRC mode
        if (!initiateReceive()) {
            // Try to collect a bit more context for diagnostics
            boolean portOpen = serialPort.isOpen();
            int availableBytes = 0;
            try {
                availableBytes = serialPort.available();
            } catch (IOException e) {
                // Ignore, we are already failing the transfer
            }

            String detailedMessage =
                    "Failed to initiate transfer: "
                            + "no response from sender within the "
                            + RECEIVE_HANDSHAKE_WINDOW_MS
                            + "ms handshake window"
                            + " (portOpen="
                            + portOpen
                            + ", bytesAvailable="
                            + availableBytes
                            + ")";
            reportError(detailedMessage);

            // Best-effort cancel to put the sender (if any) into a known state
            try {
                sendCancelIfSessionCurrent();
            } catch (IOException e) {
                // Ignore secondary failure during cancel
            }

            return -1;
        }

        // Initialize transfer tracking
        transferStartTime = System.currentTimeMillis();
        totalBytesTransferred = 0;
        int expectedTotalBlocks =
                expectedDataLength > 0 ? estimateTotalBlocks(expectedDataLength) : -1;

        int expectedBlockNumber = 1;
        int retryCount = 0;
        long written = 0;
        boolean transferCompleted = false;

        try {
            while (true) {
                assertSessionCurrent();
                int header = readByteWithTimeout(TIMEOUT_MS);

                if (header == EOT) {
                    // End of transmission
                    serialPort.write(ACK);
                    transferCompleted = true;
                    break;
                }

                if (header == CAN) {
                    // The sender aborted deliberately: an expected outcome, not a link failure.
                    cancelSignalled = true;
                    reportCancelled("Transfer cancelled by sender");
                    return -1;
                }

                if (header == FRAME_START_BYTE) {
                    // A framed control line starts "[[SYNC:" — a lone '[' that is really block
                    // data must not trigger the frame path. Only when the next byte also is '['
                    // does the interleaved-frame read run; otherwise the '[' is treated as an
                    // unexpected header byte (bounded by the retry budget, like every other
                    // garbage byte) and the peeked byte is pushed back so the block parser
                    // still sees it. Without this, one 0x5B data byte at a block boundary sent
                    // the loop into the frame reader, which swallowed the resent block as a
                    // "truncated line" without consuming a retry — wedging the session forever.
                    int second = readByteWithTimeout(INTERLEAVE_ACK_TIMEOUT_MS);
                    if (second == FRAME_START_BYTE) {
                        if (consumeInterleavedFrameAfterSecondBracket()) {
                            serialPort.write(ACK);
                        }
                        continue;
                    }
                    if (second >= 0) {
                        pushedBackByte = second;
                    }
                    // Fall through: the switch below NAKs the stray '[' and advances retryCount.
                }

                // Determine block size based on header
                int blockSize;
                switch (header) {
                    case STX4K -> blockSize = BLOCK_SIZE_4K;
                    case STX -> blockSize = BLOCK_SIZE_1K;
                    case SOH -> blockSize = BLOCK_SIZE_128;
                    default -> {
                        retryCount++;
                        if (retryCount > MAX_RETRIES) {
                            reportError("Too many errors, aborting transfer");
                            sendCancelIfSessionCurrent();
                            return -1;
                        }
                        serialPort.write(NAK);
                        continue;
                    }
                }

                // Read block number and its complement. A read timeout returns -1; masking it
                // into a data byte (turning -1 into 0xFF) fabricates corruption that never
                // happened on the wire. Treat an incomplete header as a failed block instead,
                // failing fast so one silent-sender round costs a single timeout.
                int blockNum = readByteWithTimeout(TIMEOUT_MS);
                int blockNumComplement = blockNum < 0 ? -1 : readByteWithTimeout(TIMEOUT_MS);
                if (blockNum < 0 || blockNumComplement < 0) {
                    retryCount++;
                    if (retryCount > MAX_RETRIES) {
                        reportError("Timed out reading block number, aborting transfer");
                        sendCancelIfSessionCurrent();
                        return -1;
                    }
                    serialPort.write(NAK);
                    continue;
                }

                // Verify block number
                if (blockNum + blockNumComplement != 255) {
                    // Drain stale data block + CRC from the current (failed) block so
                    // they are not misread as block headers on subsequent loop iterations.
                    // Each misread would consume retries and eventually abort the transfer.
                    try {
                        for (int i = 0; i < blockSize + 2 && serialPort.available() > 0; i++) {
                            serialPort.read();
                        }
                    } catch (IOException ignored) {
                    }
                    // The mismatch itself must consume a retry too: a sender whose resends stay
                    // aligned to a header byte re-enters this branch indefinitely otherwise, and
                    // the transfer would only end once the read timeouts exhaust another path's
                    // budget.
                    retryCount++;
                    if (retryCount > MAX_RETRIES) {
                        reportError("Too many block number errors, aborting transfer");
                        sendCancelIfSessionCurrent();
                        return -1;
                    }
                    serialPort.write(NAK);
                    continue;
                }

                // Read data block
                byte[] block = serialPort.readExact(blockSize, TIMEOUT_MS);

                // Read CRC (2 bytes, high byte first). A timeout (-1) must fail the block
                // right there: it means the trailer never arrived, not that the CRC byte was
                // 0xFF. Failing immediately also keeps one silent-sender round at a single
                // timeout instead of two back-to-back ones.
                int crcHigh = readByteWithTimeout(TIMEOUT_MS);
                if (crcHigh < 0) {
                    retryCount++;
                    if (retryCount > MAX_RETRIES) {
                        reportError("Timed out reading block CRC, aborting transfer");
                        sendCancelIfSessionCurrent();
                        return -1;
                    }
                    serialPort.write(NAK);
                    continue;
                }
                int crcLow = readByteWithTimeout(TIMEOUT_MS);
                if (crcLow < 0) {
                    retryCount++;
                    if (retryCount > MAX_RETRIES) {
                        reportError("Timed out reading block CRC, aborting transfer");
                        sendCancelIfSessionCurrent();
                        return -1;
                    }
                    serialPort.write(NAK);
                    continue;
                }
                int receivedCrc = ((crcHigh & 0xFF) << 8) | (crcLow & 0xFF);

                // Verify CRC
                int calculatedCrc = calculateCRC16(block);
                if (receivedCrc != calculatedCrc) {
                    retryCount++;
                    if (retryCount > MAX_RETRIES) {
                        reportError("Too many CRC errors, aborting transfer");
                        sendCancelIfSessionCurrent();
                        return -1;
                    }
                    serialPort.write(NAK);
                    continue;
                }

                // Check block number
                if (blockNum == (expectedBlockNumber & 0xFF)) {
                    int toWrite = blockSize;
                    if (expectedDataLength >= 0 && written + toWrite > expectedDataLength) {
                        toWrite = (int) (expectedDataLength - written);
                    }
                    if (toWrite > 0) {
                        sink.write(block, 0, toWrite);
                        written += toWrite;
                    }
                    expectedBlockNumber++;
                    retryCount = 0;
                    serialPort.write(ACK);
                    totalBytesTransferred += blockSize;
                    reportProgress(
                            expectedBlockNumber - 1, expectedTotalBlocks, totalBytesTransferred);
                    // Block boundary on the receive side: this side just ACKed, and the sender
                    // will not write the next header until it has processed that ACK, so the
                    // reverse direction is idle. Let a queued shared text jump the queue here.
                    if (receiveBoundaryHook != null) {
                        receiveBoundaryHook.sendBetweenReceivedBlocks();
                    }
                } else if (blockNum == ((expectedBlockNumber - 1) & 0xFF)) {
                    // Duplicate block, ACK but don't save
                    serialPort.write(ACK);
                } else {
                    // Out of sequence
                    serialPort.write(NAK);
                }
            }

            return written;
        } finally {
            // sessionStillCurrent(): a torn-down session must exit silently. After a reconnect
            // these streams belong to the NEW session, and a CAN written by the old session's
            // abort path would corrupt it.
            if (!transferCompleted && !cancelSignalled && sessionStillCurrent()) {
                // Every exit that is not a clean EOT must leave the sender told to stop. The
                // retry-exhaustion exits above already sent CAN on their way out and firing
                // again here is deliberate: CANs are idempotent, and a few spaced re-sends are
                // what keep a single lost CAN from being fatal anyway. The exit this finally
                // actually covers is an IOException escaping the block read (typically a read
                // timeout when the sender stalled mid-block), which otherwise sends nothing.
                // Without it the sender keeps streaming the rest of the file into a receiver
                // that has given up, and those orphaned bytes are later misread by the command
                // listener.
                sendCancelWithRetry();
            }
        }
    }

    /**
     * Re-send the CAN abort signal a few times, spaced out. A receiver that is about to hand the
     * line back to the command listener cannot afford a single lost CAN: if the sender misses it,
     * its remaining blocks become stray data on an otherwise idle session. Costs nothing on a
     * healthy link because the writer flushes immediately.
     *
     * <p>Stops early on interrupt (checked at the head of each round, not only when a sleep throws)
     * so a cancel-driven shutdown does not have to wait out the retries, and likewise on a
     * port-session change: the streams then belong to the new session, and a stale CAN would
     * corrupt it. Also skips the trailing pause so nothing is slept after the final attempt.
     */
    private void sendCancelWithRetry() {
        for (int attempt = 0; attempt < CAN_RESEND_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted() || !sessionStillCurrent()) {
                return;
            }
            try {
                sendCancel();
            } catch (IOException e) {
                // The port is already unusable; abandoning further sends loses nothing.
                return;
            }
            boolean lastAttempt = attempt == CAN_RESEND_ATTEMPTS - 1;
            if (lastAttempt) {
                break;
            }
            try {
                Thread.sleep(CAN_RESEND_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean waitForHandshake() throws IOException {
        long startTime = System.currentTimeMillis();
        // The receiver sends its 'C' immediately after the command ACK, so the 'C' has usually
        // already arrived by now; discarding it would stall the session until the receiver's
        // next re-send cycle. Drain stale bytes but keep an early 'C'. A receiver-originated
        // frame that raced the announce is consumed whole instead of being drained as noise.
        while (serialPort.available() > 0) {
            assertSessionCurrent();
            int b = readBufferedByteConsumingFrames();
            if (b == C) {
                return true;
            }
        }

        while (System.currentTimeMillis() - startTime < HANDSHAKE_TIMEOUT_MS) {
            assertSessionCurrent();
            int b = readResponseByteConsumingFrames(1000);
            if (b == C) {
                return true;
            }
            if (b == NAK) {
                // Checksum mode requested, but we only support CRC
                // Keep waiting for 'C'
            }
        }
        return false;
    }

    /**
     * Drain any extra 'C' or NAK characters from the buffer after handshake. The receiver may have
     * sent multiple 'C' chars before the sender started listening, and these stale chars could
     * interfere with ACK detection during block sending.
     *
     * <p>The pause covers only stragglers already in flight, so the drain clears them here and the
     * first block's response read stays clean. One that arrives after the pause is not lost: the
     * response read recognizes it as a straggler and skips it, so its arrival offset no longer
     * matters and the block is still written exactly once.
     */
    private void drainExtraHandshakeChars() throws IOException {
        // Short delay to let an in-flight 'C' straggler arrive
        try {
            Thread.sleep(HANDSHAKE_DRAIN_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Drain any 'C' or NAK chars that arrived during the pause. Receiver-originated
        // frames are consumed whole so their bracket prefix cannot poison the block ACK reads.
        while (serialPort.available() > 0) {
            assertSessionCurrent();
            int b = readBufferedByteConsumingFrames();
            if (b >= 0 && b != C && b != NAK) {
                // Unexpected byte, stop draining
                break;
            }
        }
    }

    private boolean initiateReceive() throws IOException {
        serialPort.clearInputBuffer();

        // Send 'C' to request CRC mode, re-sending every HANDSHAKE_RESEND_INTERVAL_MS so a 'C'
        // the sender missed costs one short cycle instead of a full second. The overall wait
        // keeps the former MAX_RETRIES x 1s budget.
        long deadline = System.currentTimeMillis() + RECEIVE_HANDSHAKE_WINDOW_MS;
        while (System.currentTimeMillis() < deadline) {
            assertSessionCurrent();
            serialPort.write(C);

            long waitStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - waitStart < HANDSHAKE_RESEND_INTERVAL_MS) {
                assertSessionCurrent();
                if (serialPort.available() > 0) {
                    return true;
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private boolean sendBlock(byte[] block, int blockNumber, byte headerByte) throws IOException {
        int blockSize = block.length;
        byte[] packet = new byte[3 + blockSize + 2]; // Header + blockNum + complement + data + CRC
        packet[0] = headerByte;
        packet[1] = (byte) (blockNumber & 0xFF);
        packet[2] = (byte) (255 - (blockNumber & 0xFF));
        System.arraycopy(block, 0, packet, 3, blockSize);

        int crc = calculateCRC16(block);
        packet[3 + blockSize] = (byte) ((crc >> 8) & 0xFF);
        packet[3 + blockSize + 1] = (byte) (crc & 0xFF);

        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            assertSessionCurrent();
            // Clear any stale 'C' chars before sending (especially on retry). A queued
            // receiver-originated frame is consumed whole: nibbling only its first byte here
            // would leave a de-bracketed line that desynchronizes every later response read.
            while (serialPort.available() > 0) {
                int stale = readBufferedByteConsumingFrames();
                if (stale >= 0 && stale != C && stale != NAK) {
                    break; // Unexpected byte, stop draining
                }
            }

            serialPort.write(packet);

            int response = readTransferResponseConsumingFrames(TIMEOUT_MS);
            if (response == ACK) {
                return true;
            }
            if (response == CAN) {
                cancelSignalled = true;
                return false;
            }
            // NAK, a 'C' past the straggler-skip bound, or timeout - retry
        }
        return false;
    }

    private boolean sendEOT() throws IOException {
        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            serialPort.write(EOT);
            int response = readTransferResponseConsumingFrames(TIMEOUT_MS);
            if (response == ACK) {
                return true;
            }
        }
        return false;
    }

    /**
     * Send one framed control line in the gap between two data blocks and wait for the receiver's
     * raw ACK. The receiver consumes the frame at the block-header position of its receive loop and
     * ACKs it after handling, so the file session continues untouched. Retries resend the whole
     * frame; duplicate delivery is harmless because interleaved payloads carry their own dedupe key
     * (e.g. the shared-text timestamp). A CAN response aborts the send session the same way a
     * cancel during a block wait does.
     *
     * @return true if the frame was acknowledged, false after {@link #MAX_INTERLEAVE_RETRIES}
     *     unacknowledged attempts (the caller should let the file session continue)
     */
    public boolean sendInterleavedFrame(byte[] frame) throws IOException {
        for (int attempt = 0; attempt < MAX_INTERLEAVE_RETRIES; attempt++) {
            serialPort.write(frame);
            int response = readTransferResponseConsumingFrames(INTERLEAVE_ACK_TIMEOUT_MS);
            if (response == ACK) {
                return true;
            }
            if (response == CAN) {
                cancelSignalled = true;
                throw new IOException("Transfer cancelled by receiver");
            }
            // NAK, a 'C' past the straggler-skip bound, timeout, or a frame the receiver could
            // not parse.
        }
        return false;
    }

    /**
     * Read the rest of an interleaved frame line — the leading {@code "[["} was already consumed at
     * the block-header position — and hand the complete line to the handler.
     *
     * <p>A handler failure is swallowed: this runs inside the receive loop, so letting it escape
     * would skip the frame's ACK (making the sender retry or abandon the interleave) and abort the
     * file session. A bad interleaved frame must only ever cost the frame itself.
     *
     * @return false when the line is truncated or implausibly long, leaving the frame
     *     unacknowledged so the sender resends or gives up
     */
    private boolean consumeInterleavedFrameAfterSecondBracket() throws IOException {
        ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
        while (true) {
            int b = readByteWithTimeout(INTERLEAVE_ACK_TIMEOUT_MS);
            if (b == -1) {
                return false;
            }
            if (b == '\n') {
                break;
            }
            if (b == '\r') {
                continue;
            }
            lineBytes.write(b);
            if (lineBytes.size() > MAX_INTERLEAVED_FRAME_BYTES) {
                return false;
            }
        }
        if (interleavedFrameHandler != null) {
            String line = "[[" + lineBytes.toString(StandardCharsets.UTF_8.name());
            try {
                interleavedFrameHandler.accept(line);
            } catch (RuntimeException e) {
                // Contained on purpose: the frame is still ACKed and the session continues.
            }
        }
        return true;
    }

    public void sendCancelSignal() throws IOException {
        sendCancel();
    }

    private void sendCancel() throws IOException {
        // Send CAN twice to ensure it's received
        serialPort.write(CAN);
        serialPort.write(CAN);
    }

    /** True while the port session this transfer started on is still the current one. */
    private boolean sessionStillCurrent() {
        return serialPort.currentEpoch() == transferSessionEpoch;
    }

    /**
     * Aborts the transfer when the port was closed/reopened underneath it (link teardown followed
     * by a user reconnect): continuing would read and write the NEW session's stream.
     */
    private void assertSessionCurrent() throws IOException {
        if (!sessionStillCurrent()) {
            throw new IOException("Serial session changed since the transfer started");
        }
    }

    /**
     * Sends the CAN abort for a failing transfer, but only while the port session the transfer
     * started on is still current. After a teardown followed by a reconnect the port's streams
     * belong to the new session, and a CAN written by an old-session abort path would corrupt it,
     * so an old-session failure must stay silent instead.
     */
    private void sendCancelIfSessionCurrent() throws IOException {
        if (sessionStillCurrent()) {
            sendCancel();
        }
    }

    private int readByteWithTimeout(int timeoutMs) throws IOException {
        if (pushedBackByte >= 0) {
            int value = pushedBackByte;
            pushedBackByte = -1;
            return value;
        }
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (serialPort.available() > 0) {
                return serialPort.read() & 0xFF;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Read interrupted");
            }
        }
        return -1;
    }

    /**
     * Read one buffered byte, consuming a whole interleaved frame when the byte begins one ("[["
     * prefix). Returns the byte the caller should process, or -1 when the buffer ran dry (the frame
     * case loops internally until a non-frame byte or an empty buffer). A lone '[' is noise: when a
     * follower byte was already readable, the '[' is dropped and the follower returned so no real
     * handshake/response byte is lost to the bracket.
     */
    private int readBufferedByteConsumingFrames() throws IOException {
        while (serialPort.available() > 0) {
            assertSessionCurrent();
            int b = serialPort.read() & 0xFF;
            if (b != (FRAME_START_BYTE & 0xFF)) {
                return b;
            }
            if (serialPort.available() <= 0) {
                return b; // Nothing behind the '[' yet; report it as the drained byte
            }
            int second = serialPort.read() & 0xFF;
            if (second != (FRAME_START_BYTE & 0xFF)) {
                return second;
            }
            consumeInterleavedFrameAfterSecondBracket();
        }
        return -1;
    }

    /**
     * Read the peer's next single-byte response (ACK/NAK/CAN/'C'), transparently consuming any
     * interleaved frame the peer's receive loop wrote between our data blocks. Receiver-originated
     * frames are fire-and-forget — consumed and dispatched via the interleaved-frame handler, never
     * acknowledged — so this keeps reading until a non-frame byte arrives and returns that. A lone
     * '[' (line noise, not a frame start) is returned as an unexpected response byte and its peeked
     * follower is pushed back, mirroring the receive loop's header-position handling.
     */
    private int readResponseByteConsumingFrames(int timeoutMs) throws IOException {
        return readResponseConsumingFrames(timeoutMs, false);
    }

    /**
     * Read the peer's next single-byte response with the same frame handling as {@link
     * #readResponseByteConsumingFrames}, but skipping stray 'C' handshake stragglers. Once blocks
     * are flowing the receiver never sends 'C' — only its handshake does — so a 'C' read here is
     * a straggler that outlasted the handshake drain. Skipping it keeps the ACK position honest;
     * reading it as a bad response instead would re-send a block the receiver already has (its
     * duplicate-block ACK absorbs that, but the round is pure waste, and at the EOT position a
     * re-sent EOT lands on the command listener as garbage). Skips are bounded by {@link
     * #MAX_HANDSHAKE_STRAGGLERS} so a flood falls back to a retry, whose stale-char drain clears
     * the backlog.
     */
    private int readTransferResponseConsumingFrames(int timeoutMs) throws IOException {
        return readResponseConsumingFrames(timeoutMs, true);
    }

    private int readResponseConsumingFrames(int timeoutMs, boolean skipHandshakeStragglers)
            throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int stragglers = 0;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return -1;
            }
            int b = readByteWithTimeout((int) remaining);
            if (b != (FRAME_START_BYTE & 0xFF)) {
                if (skipHandshakeStragglers && b == C && stragglers < MAX_HANDSHAKE_STRAGGLERS) {
                    stragglers++;
                    continue;
                }
                return b;
            }
            int second =
                    readByteWithTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
            if (second != (FRAME_START_BYTE & 0xFF)) {
                if (second >= 0) {
                    pushedBackByte = second;
                }
                return b;
            }
            consumeInterleavedFrameAfterSecondBracket();
            // Frame consumed; keep waiting for the actual response byte.
        }
    }

    /** Calculate CRC-16-CCITT */
    public static int calculateCRC16(byte[] data) {
        int crc = 0;
        for (byte b : data) {
            crc = crc ^ ((b & 0xFF) << 8);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc = crc << 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private byte[] removePadding(byte[] data) {
        if (data == null || data.length == 0) {
            return data;
        }

        // Find the last non-padding byte
        int endIndex = data.length;
        while (endIndex > 0 && data[endIndex - 1] == PADDING) {
            endIndex--;
        }

        if (endIndex == data.length) {
            return data;
        }

        byte[] result = new byte[endIndex];
        System.arraycopy(data, 0, result, 0, endIndex);
        return result;
    }

    private byte[] enforceExpectedLength(byte[] data, int expectedDataLength) {
        if (data == null) {
            return null;
        }

        if (data.length < expectedDataLength) {
            reportError(
                    "Received data length "
                            + data.length
                            + " bytes is shorter than expected "
                            + expectedDataLength
                            + " bytes");
            return null;
        }

        if (data.length == expectedDataLength) {
            return data;
        }

        return Arrays.copyOf(data, expectedDataLength);
    }

    private void reportProgress(int currentBlock, int totalBlocks, long bytesTransferred) {
        if (progressListener != null) {
            double speedBytesPerSec = calculateSpeed(bytesTransferred);
            progressListener.onProgress(
                    currentBlock, totalBlocks, bytesTransferred, speedBytesPerSec);
        }
    }

    /** Calculate transfer speed in bytes per second */
    private double calculateSpeed(long bytesTransferred) {
        long elapsed = System.currentTimeMillis() - transferStartTime;
        if (elapsed <= 0) {
            return 0;
        }
        return (bytesTransferred * 1000.0) / elapsed;
    }

    private BlockFormat selectBlockFormat(int remainingBytes) {
        if (remainingBytes >= BLOCK_SIZE_4K) {
            return new BlockFormat(BLOCK_SIZE_4K, STX4K);
        }
        if (remainingBytes >= BLOCK_SIZE_1K) {
            return new BlockFormat(BLOCK_SIZE_1K, STX);
        }
        // Tails of 129..1023 bytes walk out as several 128-byte blocks: a single padded 1K
        // block would push up to ~900 bytes of CTRL-Z padding across the line for nothing.
        return new BlockFormat(BLOCK_SIZE_128, SOH);
    }

    private int estimateTotalBlocks(int dataLength) {
        int remaining = dataLength;
        int blocks = 0;
        while (remaining > 0) {
            BlockFormat format = selectBlockFormat(remaining);
            blocks++;
            remaining -= Math.min(remaining, format.size());
        }
        return blocks;
    }

    private void reportError(String message) {
        // Remember the last error so higher-level layers can include it
        // in their own exception / log messages.
        this.lastErrorMessage = message;
        if (progressListener != null) {
            progressListener.onError(message);
        }
    }

    /**
     * Report a deliberate peer cancel: remembered for diagnostics like an error, but surfaced
     * through {@link TransferProgressListener#onCancelled} so listeners log it as a normal event
     * instead of raising an ERROR.
     */
    private void reportCancelled(String message) {
        this.lastErrorMessage = message;
        if (progressListener != null) {
            progressListener.onCancelled(message);
        }
    }

    private record BlockFormat(int size, byte header) {}

    /**
     * Get the last error message reported by this transfer instance. May return null if no error
     * has occurred yet.
     */
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    /**
     * Whether the last transfer failure was a peer cancel (CAN signal) rather than a genuine
     * communication problem. Reset at the start of every send/receive.
     */
    public boolean wasCancelSignalled() {
        return cancelSignalled;
    }

    /** Outcome of a between-blocks interleave attempt. */
    public enum InterleaveResult {
        /** No interleave-eligible payload was pending; later boundaries should keep asking. */
        NOTHING_PENDING,
        /** A frame was sent and acknowledged by the receiver. */
        SENT,
        /**
         * A pending payload existed but could not be delivered inline; the hook should not be asked
         * again until the next session, and the payload waits for the regular flush points.
         */
        FAILED
    }

    /**
     * Hook invoked between two data blocks of a send session, while the receiver is idle waiting
     * for the next block header. Lets higher layers interleave a short higher-priority frame (e.g.
     * a queued shared text) on the otherwise idle line.
     */
    public interface BlockBoundaryHook {
        InterleaveResult sendBetweenBlocks() throws IOException;
    }

    /**
     * Hook invoked between two data blocks of a receive session, right after this side ACKed a
     * block and before it waits for the next block header — the reverse direction is idle then.
     * Lets the receiving side interleave a short frame (e.g. a queued shared text) of its own.
     *
     * <p>Any frame the hook writes is fire-and-forget: the sender's response reads consume and
     * dispatch it without acknowledging, so the hook must not block waiting for a reply — the next
     * block header can arrive at any moment.
     */
    public interface ReceiveBoundaryHook {
        void sendBetweenReceivedBlocks() throws IOException;
    }

    /** Progress listener interface for transfer status updates */
    public interface TransferProgressListener {
        void onProgress(
                int currentBlock, int totalBlocks, long bytesTransferred, double speedBytesPerSec);

        void onError(String message);

        /** A deliberate cancel (CAN) ended the transfer; an expected, benign outcome. */
        default void onCancelled(String message) {}
    }
}
