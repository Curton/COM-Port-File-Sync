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
    private static final long RECEIVE_HANDSHAKE_WINDOW_MS = (long) MAX_RETRIES * 1000;

    private final SerialPortManager serialPort;
    private TransferProgressListener progressListener;
    private BlockBoundaryHook blockBoundaryHook;
    private Consumer<String> interleavedFrameHandler;

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

    public void setInterleavedFrameHandler(Consumer<String> handler) {
        this.interleavedFrameHandler = handler;
    }

    /** Send data using XMODEM protocol (supports 4096/1024/128-byte blocks) */
    public boolean send(byte[] data) throws IOException {
        cancelSignalled = false;

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
                sendCancel();
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
                sendCancel();
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

        while (true) {
            int header = readByteWithTimeout(TIMEOUT_MS);

            if (header == EOT) {
                // End of transmission
                serialPort.write(ACK);
                break;
            }

            if (header == CAN) {
                // The sender aborted deliberately: an expected outcome, not a link failure.
                cancelSignalled = true;
                reportCancelled("Transfer cancelled by sender");
                return -1;
            }

            if (header == FRAME_START_BYTE) {
                // A framed control line (e.g. shared text) interleaved by the sender in the
                // gap between two data blocks: consume it, hand it to the handler, ACK it,
                // and keep waiting for the next block header without burning a retry.
                if (consumeInterleavedFrame()) {
                    serialPort.write(ACK);
                }
                continue;
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
                        sendCancel();
                        return -1;
                    }
                    serialPort.write(NAK);
                    continue;
                }
            }

            // Read block number and its complement
            int blockNum = readByteWithTimeout(TIMEOUT_MS);
            int blockNumComplement = readByteWithTimeout(TIMEOUT_MS);

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
                serialPort.write(NAK);
                continue;
            }

            // Read data block
            byte[] block = serialPort.readExact(blockSize, TIMEOUT_MS);

            // Read CRC (2 bytes, high byte first)
            int crcHigh = readByteWithTimeout(TIMEOUT_MS);
            int crcLow = readByteWithTimeout(TIMEOUT_MS);
            int receivedCrc = ((crcHigh & 0xFF) << 8) | (crcLow & 0xFF);

            // Verify CRC
            int calculatedCrc = calculateCRC16(block);
            if (receivedCrc != calculatedCrc) {
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    reportError("Too many CRC errors, aborting transfer");
                    sendCancel();
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
                reportProgress(expectedBlockNumber - 1, expectedTotalBlocks, totalBytesTransferred);
            } else if (blockNum == ((expectedBlockNumber - 1) & 0xFF)) {
                // Duplicate block, ACK but don't save
                serialPort.write(ACK);
            } else {
                // Out of sequence
                serialPort.write(NAK);
            }
        }

        return written;
    }

    private boolean waitForHandshake() throws IOException {
        long startTime = System.currentTimeMillis();
        // The receiver sends its 'C' immediately after the command ACK, so the 'C' has usually
        // already arrived by now; discarding it would stall the session until the receiver's
        // next re-send cycle. Drain stale bytes but keep an early 'C'.
        while (serialPort.available() > 0) {
            if ((serialPort.read() & 0xFF) == C) {
                return true;
            }
        }

        while (System.currentTimeMillis() - startTime < HANDSHAKE_TIMEOUT_MS) {
            int b = readByteWithTimeout(1000);
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
     */
    private void drainExtraHandshakeChars() throws IOException {
        // Small delay to let any in-flight 'C' chars arrive
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Drain any 'C' or NAK chars
        while (serialPort.available() > 0) {
            int b = serialPort.read() & 0xFF;
            if (b != C && b != NAK) {
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
            serialPort.write(C);

            long waitStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - waitStart < HANDSHAKE_RESEND_INTERVAL_MS) {
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
            // Clear any stale 'C' chars before sending (especially on retry)
            while (serialPort.available() > 0) {
                int stale = serialPort.read() & 0xFF;
                if (stale != C && stale != NAK) {
                    break; // Unexpected byte, stop draining
                }
            }

            serialPort.write(packet);

            int response = readByteWithTimeout(TIMEOUT_MS);
            if (response == ACK) {
                return true;
            }
            if (response == CAN) {
                cancelSignalled = true;
                return false;
            }
            // NAK, 'C' (stale handshake char), or timeout - retry
        }
        return false;
    }

    private boolean sendEOT() throws IOException {
        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            serialPort.write(EOT);
            int response = readByteWithTimeout(TIMEOUT_MS);
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
            int response = readByteWithTimeout(INTERLEAVE_ACK_TIMEOUT_MS);
            if (response == ACK) {
                return true;
            }
            if (response == CAN) {
                cancelSignalled = true;
                throw new IOException("Transfer cancelled by receiver");
            }
            // NAK, stale handshake char, timeout, or a frame the receiver could not parse.
        }
        return false;
    }

    /**
     * Read the rest of an interleaved frame line — the leading {@code '['} was already consumed as
     * the block-header byte — and hand the complete line to the handler.
     *
     * <p>A handler failure is swallowed: this runs inside the receive loop, so letting it escape
     * would skip the frame's ACK (making the sender retry or abandon the interleave) and abort the
     * file session. A bad interleaved frame must only ever cost the frame itself.
     *
     * @return false when the line is truncated or implausibly long, leaving the frame
     *     unacknowledged so the sender resends or gives up
     */
    private boolean consumeInterleavedFrame() throws IOException {
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
            String line =
                    ((char) (FRAME_START_BYTE & 0xFF))
                            + lineBytes.toString(StandardCharsets.UTF_8.name());
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

    private int readByteWithTimeout(int timeoutMs) throws IOException {
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
        if (remainingBytes > BLOCK_SIZE_128) {
            return new BlockFormat(BLOCK_SIZE_1K, STX);
        }
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

    /** Progress listener interface for transfer status updates */
    public interface TransferProgressListener {
        void onProgress(
                int currentBlock, int totalBlocks, long bytesTransferred, double speedBytesPerSec);

        void onError(String message);

        /** A deliberate cancel (CAN) ended the transfer; an expected, benign outcome. */
        default void onCancelled(String message) {}
    }
}
