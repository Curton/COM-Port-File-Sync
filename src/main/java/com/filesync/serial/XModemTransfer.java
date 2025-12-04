package com.filesync.serial;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Implements XMODEM-CRC protocol for reliable file transfer over serial port.
 * Uses 128-byte blocks with CRC-16 checksum.
 */
public class XModemTransfer {

    // XMODEM control characters
    public static final byte SOH = 0x01;  // Start of Header (128 byte block)
    public static final byte EOT = 0x04;  // End of Transmission
    public static final byte ACK = 0x06;  // Acknowledge
    public static final byte NAK = 0x15;  // Negative Acknowledge
    public static final byte CAN = 0x18;  // Cancel
    public static final byte C = 0x43;    // 'C' for CRC mode

    private static final int BLOCK_SIZE = 128;
    private static final int MAX_RETRIES = 10;
    private static final int TIMEOUT_MS = 10000;
    private static final int HANDSHAKE_TIMEOUT_MS = 60000;
    private static final byte PADDING = 0x1A;  // CTRL-Z for padding

    private final SerialPortManager serialPort;
    private TransferProgressListener progressListener;

    public XModemTransfer(SerialPortManager serialPort) {
        this.serialPort = serialPort;
    }

    public void setProgressListener(TransferProgressListener listener) {
        this.progressListener = listener;
    }

    /**
     * Send data using XMODEM-CRC protocol
     */
    public boolean send(byte[] data) throws IOException {
        // Wait for receiver to send 'C' to initiate CRC mode
        if (!waitForHandshake()) {
            reportError("Handshake failed: receiver not responding");
            return false;
        }

        ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
        byte[] block = new byte[BLOCK_SIZE];
        int blockNumber = 1;
        int totalBlocks = (data.length + BLOCK_SIZE - 1) / BLOCK_SIZE;

        while (true) {
            int bytesRead = inputStream.read(block);
            if (bytesRead <= 0) {
                break;
            }

            // Pad the last block if necessary
            if (bytesRead < BLOCK_SIZE) {
                for (int i = bytesRead; i < BLOCK_SIZE; i++) {
                    block[i] = PADDING;
                }
            }

            // Send block with retries
            if (!sendBlock(block, blockNumber)) {
                reportError("Failed to send block " + blockNumber + " after " + MAX_RETRIES + " retries");
                sendCancel();
                return false;
            }

            reportProgress(blockNumber, totalBlocks);
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
     * Receive data using XMODEM-CRC protocol
     */
    public byte[] receive() throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // Initiate transfer by sending 'C' for CRC mode
        if (!initiateReceive()) {
            reportError("Failed to initiate transfer");
            return null;
        }

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

            if (header != SOH) {
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    reportError("Too many errors, aborting transfer");
                    sendCancel();
                    return null;
                }
                serialPort.write(NAK);
                continue;
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
            byte[] block = serialPort.readExact(BLOCK_SIZE, TIMEOUT_MS);

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
                reportProgress(expectedBlockNumber - 1, -1);  // -1 means unknown total
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
                continue;
            }
        }
        return false;
    }

    private boolean initiateReceive() throws IOException {
        serialPort.clearInputBuffer();

        // Send 'C' to request CRC mode
        for (int i = 0; i < MAX_RETRIES; i++) {
            serialPort.write(C);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            // Check if sender responded
            if (serialPort.available() > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean sendBlock(byte[] block, int blockNumber) throws IOException {
        byte[] packet = new byte[3 + BLOCK_SIZE + 2];  // SOH + blockNum + complement + data + CRC
        packet[0] = SOH;
        packet[1] = (byte) (blockNumber & 0xFF);
        packet[2] = (byte) (255 - (blockNumber & 0xFF));
        System.arraycopy(block, 0, packet, 3, BLOCK_SIZE);

        int crc = calculateCRC16(block);
        packet[3 + BLOCK_SIZE] = (byte) ((crc >> 8) & 0xFF);
        packet[3 + BLOCK_SIZE + 1] = (byte) (crc & 0xFF);

        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            serialPort.write(packet);

            int response = readByteWithTimeout(TIMEOUT_MS);
            if (response == ACK) {
                return true;
            }
            if (response == CAN) {
                return false;
            }
            // NAK or timeout, retry
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
                Thread.sleep(10);
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

    private void reportProgress(int current, int total) {
        if (progressListener != null) {
            progressListener.onProgress(current, total);
        }
    }

    private void reportError(String message) {
        if (progressListener != null) {
            progressListener.onError(message);
        }
    }

    /**
     * Progress listener interface for transfer status updates
     */
    public interface TransferProgressListener {
        void onProgress(int currentBlock, int totalBlocks);
        void onError(String message);
    }
}

