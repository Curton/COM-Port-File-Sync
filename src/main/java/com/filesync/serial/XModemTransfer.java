package com.filesync.serial;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Implements XMODEM protocol variant for reliable file transfer over serial port.
 * Uses 2048-byte blocks (STX) with CRC-16 checksum, falls back to 128-byte (SOH) for small data.
 */
public class XModemTransfer {

    // XMODEM control characters
    public static final byte SOH = 0x01;  // Start of Header (128 byte block)
    public static final byte STX = 0x02;  // Start of Header (2048 byte block)
    public static final byte EOT = 0x04;  // End of Transmission
    public static final byte ACK = 0x06;  // Acknowledge
    public static final byte NAK = 0x15;  // Negative Acknowledge
    public static final byte CAN = 0x18;  // Cancel
    public static final byte C = 0x43;    // 'C' for CRC mode

    private static final int LARGE_BLOCK_SIZE = 2048;  // Large block size (2K)
    private static final int BLOCK_SIZE_128 = 128;  // Standard XMODEM block size
    private static final int MAX_RETRIES = 10;
    private static final int TIMEOUT_MS = 10000;
    private static final int HANDSHAKE_TIMEOUT_MS = 60000;
    private static final byte PADDING = 0x1A;  // CTRL-Z for padding

    private final SerialPortManager serialPort;
    private TransferProgressListener progressListener;
    /**
     * Stores the last human-readable error message for diagnostics.
     * Higher level code (e.g. SyncProtocol) can use this to provide
     * more detailed context when reporting failures.
     */
    private String lastErrorMessage;
    private long transferStartTime;
    private long totalBytesTransferred;

    public XModemTransfer(SerialPortManager serialPort) {
        this.serialPort = serialPort;
    }

    public void setProgressListener(TransferProgressListener listener) {
        this.progressListener = listener;
    }

    /**
     * Send data using large-block XMODEM protocol (2048-byte blocks)
     */
    public boolean send(byte[] data) throws IOException {
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
        int totalBlocks = (data.length + LARGE_BLOCK_SIZE - 1) / LARGE_BLOCK_SIZE;

        while (dataOffset < data.length) {
            int remaining = data.length - dataOffset;
            
            // Choose block size: use 2K blocks when possible, 128-byte for small remaining data
            int blockSize;
            byte headerByte;
            if (remaining >= LARGE_BLOCK_SIZE) {
                blockSize = LARGE_BLOCK_SIZE;
                headerByte = STX;
            } else if (remaining > BLOCK_SIZE_128) {
                // Still use 2K block but with padding
                blockSize = LARGE_BLOCK_SIZE;
                headerByte = STX;
            } else {
                // Use 128-byte block for small remaining data
                blockSize = BLOCK_SIZE_128;
                headerByte = SOH;
            }

            byte[] block = new byte[blockSize];
            int bytesToCopy = Math.min(remaining, blockSize);
            System.arraycopy(data, dataOffset, block, 0, bytesToCopy);
            
            // Pad the block if necessary
            for (int i = bytesToCopy; i < blockSize; i++) {
                block[i] = PADDING;
            }

            // Send block with retries
            if (!sendBlock(block, blockNumber, headerByte)) {
                reportError("Failed to send block " + blockNumber + " after " + MAX_RETRIES + " retries");
                sendCancel();
                return false;
            }

            dataOffset += bytesToCopy;
            totalBytesTransferred += bytesToCopy;
            reportProgress(blockNumber, totalBlocks, totalBytesTransferred);
            blockNumber++;
        }

        // Send EOT and wait for ACK
        if (!sendEOT()) {
            reportError("Failed to complete transfer: EOT not acknowledged");
            return false;
        }

        return true;
    }

    /**
     * Receive data using XMODEM-1K protocol (supports both 1024-byte and 128-byte blocks)
     */
    public byte[] receive() throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

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

            String detailedMessage = "Failed to initiate transfer: " +
                    "no response from sender after " + MAX_RETRIES + " handshake attempts" +
                    " (portOpen=" + portOpen + ", bytesAvailable=" + availableBytes + ")";
            reportError(detailedMessage);

            // Best-effort cancel to put the sender (if any) into a known state
            try {
                sendCancel();
            } catch (IOException e) {
                // Ignore secondary failure during cancel
            }

            return null;
        }

        // Initialize transfer tracking
        transferStartTime = System.currentTimeMillis();
        totalBytesTransferred = 0;

        int expectedBlockNumber = 1;
        int retryCount = 0;

        while (true) {
            // Clear any stale data before reading each block header
            performThoroughBufferClear();
            
            int header;
            try {
                header = readByteWithTimeout(TIMEOUT_MS);
            } catch (IOException e) {
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    long elapsedMs = System.currentTimeMillis() - transferStartTime;
                    boolean portOpen = serialPort.isOpen();
                    int availableBytes = 0;
                    try {
                        availableBytes = serialPort.available();
                    } catch (IOException ex) {
                        // Ignore, we are already failing the transfer
                    }
                    String detailedMessage = String.format(
                        "Too many errors, aborting transfer: read timeout/IO error while reading header " +
                        "(error=%s), retryCount=%d/%d, expectedBlock=%d, bytesTransferred=%d, elapsedMs=%d, " +
                        "portOpen=%s, bytesAvailable=%d",
                        e.getMessage(), retryCount, MAX_RETRIES,
                        expectedBlockNumber, totalBytesTransferred, elapsedMs, portOpen, availableBytes);
                    reportError(detailedMessage);
                    sendCancel();
                    return null;
                }
                serialPort.write(NAK);
                continue;
            }

            if (header == -1) {
                // EOF/timeout case
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    long elapsedMs = System.currentTimeMillis() - transferStartTime;
                    boolean portOpen = serialPort.isOpen();
                    int availableBytes = 0;
                    try {
                        availableBytes = serialPort.available();
                    } catch (IOException e) {
                        // Ignore, we are already failing the transfer
                    }
                    String detailedMessage = String.format(
                        "Too many errors, aborting transfer: EOF/timeout while reading header (read returned -1), " +
                        "retryCount=%d/%d, expectedBlock=%d, bytesTransferred=%d, elapsedMs=%d, " +
                        "portOpen=%s, bytesAvailable=%d",
                        retryCount, MAX_RETRIES,
                        expectedBlockNumber, totalBytesTransferred, elapsedMs, portOpen, availableBytes);
                    reportError(detailedMessage);
                    sendCancel();
                    return null;
                }
                serialPort.write(NAK);
                continue;
            }

            if (header == EOT) {
                // End of transmission
                serialPort.write(ACK);
                break;
            }

            if (header == CAN) {
                reportError("Transfer cancelled by sender");
                return null;
            }

            // Determine block size based on header
            int blockSize;
            if (header == STX) {
                blockSize = LARGE_BLOCK_SIZE;
            } else if (header == SOH) {
                blockSize = BLOCK_SIZE_128;
            } else {
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    long elapsedMs = System.currentTimeMillis() - transferStartTime;
                    boolean portOpen = serialPort.isOpen();
                    int availableBytes = 0;
                    try {
                        availableBytes = serialPort.available();
                    } catch (IOException e) {
                        // Ignore, we are already failing the transfer
                    }
                    String detailedMessage;
                    if ((header & 0xFF) >= 32 && (header & 0xFF) <= 126) {
                        // Printable ASCII character - might be protocol message interference
                        detailedMessage = String.format(
                            "XMODEM protocol error: received unexpected character '%c' (0x%02X) instead of block header. " +
                            "This usually indicates stale protocol data in the serial buffer. " +
                            "retryCount=%d/%d, expectedBlock=%d, bytesTransferred=%d, elapsedMs=%d, " +
                            "portOpen=%s, bytesAvailable=%d",
                            (char)(header & 0xFF), header & 0xFF, retryCount, MAX_RETRIES,
                            expectedBlockNumber, totalBytesTransferred, elapsedMs, portOpen, availableBytes);
                    } else {
                        // Non-printable byte
                        detailedMessage = String.format(
                            "XMODEM protocol error: invalid header byte 0x%02X (expected STX=0x%02X or SOH=0x%02X). " +
                            "This usually indicates protocol synchronization issues. " +
                            "retryCount=%d/%d, expectedBlock=%d, bytesTransferred=%d, elapsedMs=%d, " +
                            "portOpen=%s, bytesAvailable=%d",
                            header & 0xFF, STX & 0xFF, SOH & 0xFF, retryCount, MAX_RETRIES,
                            expectedBlockNumber, totalBytesTransferred, elapsedMs, portOpen, availableBytes);
                    }
                    reportError(detailedMessage);
                    sendCancel();
                    return null;
                }
                
                // Instead of immediately failing on unexpected header, try to clear buffer and retry
                performThoroughBufferClear();
                serialPort.write(NAK);
                continue;
            }

            // Read block number and its complement
            int blockNum;
            int blockNumComplement;
            try {
                blockNum = readByteWithTimeout(TIMEOUT_MS);
                blockNumComplement = readByteWithTimeout(TIMEOUT_MS);
            } catch (IOException e) {
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    long elapsedMs = System.currentTimeMillis() - transferStartTime;
                    boolean portOpen = serialPort.isOpen();
                    int availableBytes = 0;
                    try {
                        availableBytes = serialPort.available();
                    } catch (IOException ex) {
                        // Ignore, we are already failing the transfer
                    }
                    String detailedMessage = String.format(
                        "Too many errors, aborting transfer: read timeout/IO error while reading block number " +
                        "(error=%s), retryCount=%d/%d, expectedBlock=%d, bytesTransferred=%d, elapsedMs=%d, " +
                        "portOpen=%s, bytesAvailable=%d",
                        e.getMessage(), retryCount, MAX_RETRIES,
                        expectedBlockNumber, totalBytesTransferred, elapsedMs, portOpen, availableBytes);
                    reportError(detailedMessage);
                    sendCancel();
                    return null;
                }
                serialPort.write(NAK);
                continue;
            }

            if (blockNum == -1 || blockNumComplement == -1) {
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    long elapsedMs = System.currentTimeMillis() - transferStartTime;
                    boolean portOpen = serialPort.isOpen();
                    int availableBytes = 0;
                    try {
                        availableBytes = serialPort.available();
                    } catch (IOException e) {
                        // Ignore, we are already failing the transfer
                    }
                    String detailedMessage = String.format(
                        "Too many errors, aborting transfer: EOF/timeout while reading block number " +
                        "(blockNum=%d, blockNumComplement=%d), retryCount=%d/%d, expectedBlock=%d, " +
                        "bytesTransferred=%d, elapsedMs=%d, portOpen=%s, bytesAvailable=%d",
                        blockNum, blockNumComplement, retryCount, MAX_RETRIES,
                        expectedBlockNumber, totalBytesTransferred, elapsedMs, portOpen, availableBytes);
                    reportError(detailedMessage);
                    sendCancel();
                    return null;
                }
                serialPort.write(NAK);
                continue;
            }

            // Verify block number
            if (blockNum + blockNumComplement != 255) {
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    long elapsedMs = System.currentTimeMillis() - transferStartTime;
                    boolean portOpen = serialPort.isOpen();
                    int availableBytes = 0;
                    try {
                        availableBytes = serialPort.available();
                    } catch (IOException e) {
                        // Ignore, we are already failing the transfer
                    }
                    String detailedMessage = String.format(
                        "Too many errors, aborting transfer: invalid block number complement " +
                        "(blockNum=0x%02X, blockNumComplement=0x%02X, sum=%d, expected=255), " +
                        "retryCount=%d/%d, expectedBlock=%d, bytesTransferred=%d, elapsedMs=%d, " +
                        "portOpen=%s, bytesAvailable=%d",
                        blockNum & 0xFF, blockNumComplement & 0xFF, (blockNum + blockNumComplement) & 0xFF,
                        retryCount, MAX_RETRIES, expectedBlockNumber, totalBytesTransferred, elapsedMs, portOpen, availableBytes);
                    reportError(detailedMessage);
                    sendCancel();
                    return null;
                }
                serialPort.write(NAK);
                continue;
            }

            // Read data block
            byte[] block;
            try {
                block = serialPort.readExact(blockSize, TIMEOUT_MS);
            } catch (IOException e) {
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    long elapsedMs = System.currentTimeMillis() - transferStartTime;
                    boolean portOpen = serialPort.isOpen();
                    int availableBytes = 0;
                    try {
                        availableBytes = serialPort.available();
                    } catch (IOException ex) {
                        // Ignore, we are already failing the transfer
                    }
                    String detailedMessage = String.format(
                        "Too many errors, aborting transfer: read timeout/IO error while reading data block " +
                        "(error=%s, blockSize=%d, blockNum=%d), retryCount=%d/%d, expectedBlock=%d, " +
                        "bytesTransferred=%d, elapsedMs=%d, portOpen=%s, bytesAvailable=%d",
                        e.getMessage(), blockSize, blockNum & 0xFF, retryCount, MAX_RETRIES,
                        expectedBlockNumber, totalBytesTransferred, elapsedMs, portOpen, availableBytes);
                    reportError(detailedMessage);
                    sendCancel();
                    return null;
                }
                serialPort.write(NAK);
                continue;
            }

            // Read CRC (2 bytes, high byte first)
            int crcHigh;
            int crcLow;
            try {
                crcHigh = readByteWithTimeout(TIMEOUT_MS);
                crcLow = readByteWithTimeout(TIMEOUT_MS);
            } catch (IOException e) {
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    long elapsedMs = System.currentTimeMillis() - transferStartTime;
                    boolean portOpen = serialPort.isOpen();
                    int availableBytes = 0;
                    try {
                        availableBytes = serialPort.available();
                    } catch (IOException ex) {
                        // Ignore, we are already failing the transfer
                    }
                    String detailedMessage = String.format(
                        "Too many errors, aborting transfer: read timeout/IO error while reading CRC " +
                        "(error=%s, blockNum=%d), retryCount=%d/%d, expectedBlock=%d, " +
                        "bytesTransferred=%d, elapsedMs=%d, portOpen=%s, bytesAvailable=%d",
                        e.getMessage(), blockNum & 0xFF, retryCount, MAX_RETRIES,
                        expectedBlockNumber, totalBytesTransferred, elapsedMs, portOpen, availableBytes);
                    reportError(detailedMessage);
                    sendCancel();
                    return null;
                }
                serialPort.write(NAK);
                continue;
            }

            if (crcHigh == -1 || crcLow == -1) {
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    long elapsedMs = System.currentTimeMillis() - transferStartTime;
                    boolean portOpen = serialPort.isOpen();
                    int availableBytes = 0;
                    try {
                        availableBytes = serialPort.available();
                    } catch (IOException e) {
                        // Ignore, we are already failing the transfer
                    }
                    String detailedMessage = String.format(
                        "Too many errors, aborting transfer: EOF/timeout while reading CRC " +
                        "(crcHigh=%d, crcLow=%d, blockNum=%d), retryCount=%d/%d, expectedBlock=%d, " +
                        "bytesTransferred=%d, elapsedMs=%d, portOpen=%s, bytesAvailable=%d",
                        crcHigh, crcLow, blockNum & 0xFF, retryCount, MAX_RETRIES,
                        expectedBlockNumber, totalBytesTransferred, elapsedMs, portOpen, availableBytes);
                    reportError(detailedMessage);
                    sendCancel();
                    return null;
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
                    long elapsedMs = System.currentTimeMillis() - transferStartTime;
                    boolean portOpen = serialPort.isOpen();
                    int availableBytes = 0;
                    try {
                        availableBytes = serialPort.available();
                    } catch (IOException e) {
                        // Ignore, we are already failing the transfer
                    }
                    String detailedMessage = String.format(
                        "Too many CRC errors, aborting transfer: blockNum=%d, receivedCRC=0x%04X, calculatedCRC=0x%04X, " +
                        "retryCount=%d/%d, expectedBlock=%d, bytesTransferred=%d, elapsedMs=%d, " +
                        "portOpen=%s, bytesAvailable=%d",
                        blockNum & 0xFF, receivedCrc, calculatedCrc, retryCount, MAX_RETRIES,
                        expectedBlockNumber, totalBytesTransferred, elapsedMs, portOpen, availableBytes);
                    reportError(detailedMessage);
                    sendCancel();
                    return null;
                }
                serialPort.write(NAK);
                continue;
            }

            // Check block number
            if (blockNum == (expectedBlockNumber & 0xFF)) {
                outputStream.write(block);
                expectedBlockNumber++;
                retryCount = 0;
                serialPort.write(ACK);
                totalBytesTransferred += blockSize;
                reportProgress(expectedBlockNumber - 1, -1, totalBytesTransferred);  // -1 means unknown total
            } else if (blockNum == ((expectedBlockNumber - 1) & 0xFF)) {
                // Duplicate block, ACK but don't save
                serialPort.write(ACK);
            } else {
                // Out of sequence
                serialPort.write(NAK);
            }
        }

        // Remove padding from the last block
        byte[] result = outputStream.toByteArray();
        return removePadding(result);
    }

    /**
     * Perform thorough buffer clearing to remove stale data that could interfere with protocol.
     * This is more comprehensive than the basic clearInputBuffer() and includes retries.
     */
    private void performThoroughBufferClear() throws IOException {
        if (!serialPort.isOpen()) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        int totalCleared = 0;
        
        // Keep clearing until no more data is available or timeout reached
        while (System.currentTimeMillis() - startTime < 100) { // 100ms max clearing time
            try {
                // Clear any available data
                int available = serialPort.available();
                if (available > 0) {
                    byte[] tempBuffer = new byte[Math.min(available, 1024)];
                    int actuallyRead = serialPort.read(tempBuffer);
                    totalCleared += actuallyRead;
                } else {
                    // No more data available, brief pause to catch in-flight data
                    Thread.sleep(1);
                }
            } catch (IOException e) {
                // If we can't read, stop clearing
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // If we cleared more than expected, log it (but only for debugging)
        if (totalCleared > 0) {
            // Optional: Could add debug logging here if needed
        }
    }

    private boolean waitForHandshake() throws IOException {
        long startTime = System.currentTimeMillis();
        
        // Perform thorough buffer clearing before starting handshake
        performThoroughBufferClear();

        while (System.currentTimeMillis() - startTime < HANDSHAKE_TIMEOUT_MS) {
            int b = readByteWithTimeout(100); // Reduced from 1000ms to 100ms
            if (b == C) {
                return true;
            }
            if (b == NAK) {
                // Checksum mode requested, but we only support CRC
                // Keep waiting for 'C'
                continue;
            }
            // Use yield instead of implicit sleep in readByteWithTimeout
            Thread.yield();
        }
        return false;
    }

    /**
     * Drain any extra 'C' or NAK characters from the buffer after handshake.
     * The receiver may have sent multiple 'C' chars before the sender started listening,
     * and these stale chars could interfere with ACK detection during block sending.
     */
    private void drainExtraHandshakeChars() throws IOException {
        // Perform aggressive buffer clearing to remove all stale handshake characters
        performThoroughBufferClear();
        
        // Additional brief drain to catch any in-flight characters
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < 50) { // 50ms timeout
            if (serialPort.available() > 0) {
                break; // Data available, proceed to drain
            }
        }
        
        // Drain any 'C', NAK, or other stale characters
        while (serialPort.available() > 0) {
            int b = serialPort.read() & 0xFF;
            // Keep draining until we find expected data or buffer is empty
            if (b != C && b != NAK && b != ACK) {
                // Unexpected byte found, drain everything and restart protocol
                performThoroughBufferClear();
                break;
            }
        }
    }

    private boolean initiateReceive() throws IOException {
        serialPort.clearInputBuffer();

        // Send 'C' to request CRC mode
        for (int i = 0; i < MAX_RETRIES; i++) {
            serialPort.write(C);
            
            // Wait up to 1 second for response, checking frequently
            long waitStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - waitStart < 1000) {
                if (serialPort.available() > 0) {
                    return true;
                }
                // Use a very short yield instead of sleep for better responsiveness
                Thread.yield();
            }
        }
        return false;
    }

    private boolean sendBlock(byte[] block, int blockNumber, byte headerByte) throws IOException {
        int blockSize = block.length;
        byte[] packet = new byte[3 + blockSize + 2];  // Header + blockNum + complement + data + CRC
        packet[0] = headerByte;
        packet[1] = (byte) (blockNumber & 0xFF);
        packet[2] = (byte) (255 - (blockNumber & 0xFF));
        System.arraycopy(block, 0, packet, 3, blockSize);

        int crc = calculateCRC16(block);
        packet[3 + blockSize] = (byte) ((crc >> 8) & 0xFF);
        packet[3 + blockSize + 1] = (byte) (crc & 0xFF);

        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            // Clear any stale characters before sending (especially on retry)
            performThoroughBufferClear();
            
            serialPort.write(packet);

            int response = readByteWithTimeout(TIMEOUT_MS);
            if (response == ACK) {
                return true;
            }
            if (response == CAN) {
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

    private void sendCancel() throws IOException {
        // Send CAN twice to ensure it's received
        serialPort.write(CAN);
        serialPort.write(CAN);
    }

    private int readByteWithTimeout(int timeoutMs) throws IOException {
        serialPort.setReadTimeout(timeoutMs);
        int result = serialPort.read();
        return result;
    }

    /**
     * Calculate CRC-16-CCITT
     */
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

    private void reportProgress(int currentBlock, int totalBlocks, long bytesTransferred) {
        if (progressListener != null) {
            double speedBytesPerSec = calculateSpeed(bytesTransferred);
            progressListener.onProgress(currentBlock, totalBlocks, bytesTransferred, speedBytesPerSec);
        }
    }

    /**
     * Calculate transfer speed in bytes per second
     */
    private double calculateSpeed(long bytesTransferred) {
        long elapsed = System.currentTimeMillis() - transferStartTime;
        if (elapsed <= 0) {
            return 0;
        }
        return (bytesTransferred * 1000.0) / elapsed;
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
     * Get the last error message reported by this transfer instance.
     * May return null if no error has occurred yet.
     */
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    /**
     * Progress listener interface for transfer status updates
     */
    public interface TransferProgressListener {
        void onProgress(int currentBlock, int totalBlocks, long bytesTransferred, double speedBytesPerSec);
        void onError(String message);
    }
}

