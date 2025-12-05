package com.filesync.serial;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Implements XMODEM protocol for reliable file transfer over serial port.
 * Uses 4096-byte blocks for large payloads, 1024-byte blocks (STX) with CRC-16 checksum,
 * and falls back to 128-byte (SOH) for small data.
 */
public class XModemTransfer {

    // XMODEM control characters
    public static final byte SOH = 0x01;  // Start of Header (128 byte block)
    public static final byte STX = 0x02;  // Start of Header (1024 byte block) - XMODEM-1K
    public static final byte STX4K = 0x05;  // Start of Header (4096 byte block) - XMODEM-4K (custom)
    public static final byte EOT = 0x04;  // End of Transmission
    public static final byte ACK = 0x06;  // Acknowledge
    public static final byte NAK = 0x15;  // Negative Acknowledge
    public static final byte CAN = 0x18;  // Cancel
    public static final byte C = 0x43;    // 'C' for CRC mode

    private static final int BLOCK_SIZE_1K = 1024;  // XMODEM-1K block size
    private static final int BLOCK_SIZE_4K = 4096;  // XMODEM-4K block size
    private static final int BLOCK_SIZE_128 = 128;  // Standard XMODEM block size
    private static final int MAX_RETRIES = 10;
    private static final int TIMEOUT_MS = 10000;
    private static final int HANDSHAKE_TIMEOUT_MS = 60000;
    private static final byte PADDING = 0x1A;  // CTRL-Z for padding
    private static final int POLL_INTERVAL_MS = 1;  // Reduced from 10ms for better throughput

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
     * Send data using XMODEM protocol (supports 4096/1024/128-byte blocks)
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
        int totalBlocks = estimateTotalBlocks(data.length);

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
     * Receive data using XMODEM protocol (supports 4096, 1024 and 128-byte blocks)
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
            int header = readByteWithTimeout(TIMEOUT_MS);

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
            switch (header) {
                case STX4K -> blockSize = BLOCK_SIZE_4K;
                case STX -> blockSize = BLOCK_SIZE_1K;
                case SOH -> blockSize = BLOCK_SIZE_128;
                default -> {
                    retryCount++;
                    if (retryCount > MAX_RETRIES) {
                        reportError("Too many errors, aborting transfer");
                        sendCancel();
                        return null;
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

    private boolean waitForHandshake() throws IOException {
        long startTime = System.currentTimeMillis();
        serialPort.clearInputBuffer();

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
     * Drain any extra 'C' or NAK characters from the buffer after handshake.
     * The receiver may have sent multiple 'C' chars before the sender started listening,
     * and these stale chars could interfere with ACK detection during block sending.
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

        // Send 'C' to request CRC mode
        for (int i = 0; i < MAX_RETRIES; i++) {
            serialPort.write(C);
            
            // Wait up to 1 second for response, checking frequently
            long waitStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - waitStart < 1000) {
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
        byte[] packet = new byte[3 + blockSize + 2];  // Header + blockNum + complement + data + CRC
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
                    break;  // Unexpected byte, stop draining
                }
            }
            
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

    private record BlockFormat(int size, byte header) { }

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

