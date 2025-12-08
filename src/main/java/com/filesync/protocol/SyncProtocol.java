package com.filesync.protocol;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;
import com.filesync.sync.CompressionUtil;
import com.filesync.sync.FileChangeDetector;

/**
 * Protocol for file synchronization commands over serial port.
 * Handles message framing, command exchange, and file transfer coordination.
 */
public class SyncProtocol {

    // Flag to indicate XMODEM transfer is in progress
    // When true, the listener thread should not read from serial port
    private final AtomicBoolean xmodemInProgress = new AtomicBoolean(false);

    // Protocol commands
    public static final String CMD_MANIFEST_REQ = "MANIFEST_REQ";
    public static final String CMD_MANIFEST_DATA = "MANIFEST_DATA";
    public static final String CMD_FILE_REQ = "FILE_REQ";
    public static final String CMD_FILE_DATA = "FILE_DATA";
    public static final String CMD_SYNC_COMPLETE = "SYNC_COMPLETE";
    public static final String CMD_DIRECTION_CHANGE = "DIRECTION_CHANGE";
    public static final String CMD_ACK = "ACK";
    public static final String CMD_ERROR = "ERROR";
    public static final String CMD_HEARTBEAT = "HEARTBEAT";
    public static final String CMD_HEARTBEAT_ACK = "HEARTBEAT_ACK";
    public static final String CMD_ROLE_NEGOTIATE = "ROLE_NEGOTIATE";
    public static final String CMD_FILE_DELETE = "FILE_DELETE";
    public static final String CMD_MKDIR = "MKDIR";
    public static final String CMD_RMDIR = "RMDIR";
    public static final String CMD_SHARED_TEXT = "SHARED_TEXT";

    // Protocol markers
    private static final String START_MARKER = "[[SYNC:";
    private static final String END_MARKER = "]]";
    private static final String SEPARATOR = ":";

    private static final int DEFAULT_TIMEOUT_MS = 30000;

    private final SerialPortManager serialPort;
    private final XModemTransfer xmodem;
    private int timeoutMs;
    private static final java.util.Base64.Encoder BASE64_ENCODER = java.util.Base64.getEncoder();
    private static final java.util.Base64.Decoder BASE64_DECODER = java.util.Base64.getDecoder();

    public SyncProtocol(SerialPortManager serialPort) {
        this.serialPort = serialPort;
        this.xmodem = new XModemTransfer(serialPort);
        this.timeoutMs = DEFAULT_TIMEOUT_MS;
    }

    public void setProgressListener(XModemTransfer.TransferProgressListener listener) {
        xmodem.setProgressListener(listener);
    }

    public void setTimeout(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Send a command message
     */
    public void sendCommand(String command, String... params) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(START_MARKER).append(command);
        for (String param : params) {
            sb.append(SEPARATOR).append(param);
        }
        sb.append(END_MARKER);
        serialPort.writeLine(sb.toString());
    }

    /**
     * Receive and parse a command message
     */
    public Message receiveCommand() throws IOException {
        String line = serialPort.readLine(timeoutMs);
        return parseMessage(line);
    }

    /**
     * Parse a protocol message
     */
    public static Message parseMessage(String line) {
        if (line == null || !line.startsWith(START_MARKER) || !line.endsWith(END_MARKER)) {
            return null;
        }

        String content = line.substring(START_MARKER.length(), line.length() - END_MARKER.length());
        String[] parts = content.split(SEPARATOR, -1);

        if (parts.length == 0) {
            return null;
        }

        String command = parts[0];
        String[] params = new String[parts.length - 1];
        System.arraycopy(parts, 1, params, 0, params.length);

        return new Message(command, params);
    }

    /**
     * Request manifest from remote
     */
    public void requestManifest() throws IOException {
        sendCommand(CMD_MANIFEST_REQ);
    }

    /**
     * Send manifest data
     */
    public void sendManifest(FileChangeDetector.FileManifest manifest) throws IOException {
        String json = FileChangeDetector.manifestToJson(manifest);
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        byte[] compressed = CompressionUtil.compress(data);

        sendCommand(CMD_MANIFEST_DATA, String.valueOf(compressed.length));

        // Wait for receiver ACK to ensure proper synchronization
        Message ackMsg = waitForCommand(CMD_ACK);

        // Send manifest data via XMODEM
        xmodemInProgress.set(true);
        try {
            xmodem.send(compressed);
        } finally {
            xmodemInProgress.set(false);
        }
    }

    /**
     * Receive manifest data
     */
    public FileChangeDetector.FileManifest receiveManifest() throws IOException {
        xmodemInProgress.set(true);
        byte[] compressed;
        try {
            compressed = xmodem.receive();
        } finally {
            xmodemInProgress.set(false);
        }
        if (compressed == null) {
            String detail = xmodem.getLastErrorMessage();
            if (detail == null || detail.isEmpty()) {
                detail = "no detailed XMODEM error available";
            }
            throw new IOException("Failed to receive manifest data (" + detail + ")");
        }

        byte[] data = CompressionUtil.decompress(compressed);
        String json = new String(data, StandardCharsets.UTF_8);
        return FileChangeDetector.manifestFromJson(json);
    }

    /**
     * Request a specific file
     */
    public void requestFile(String relativePath) throws IOException {
        sendCommand(CMD_FILE_REQ, relativePath);
    }

    /**
     * Send file data.
     * Performs limited retries around the underlying XMODEM transfer so that
     * transient handshake issues do not abort the entire sync.
     * The sender includes its lastModified timestamp so the receiver can
     * preserve it and avoid unnecessary re-syncs in fast mode.
     *
     * @return true if file was compressed, false otherwise
     */
    public boolean sendFile(File baseDir, String relativePath) throws IOException {
        File file = new File(baseDir, relativePath);
        if (!file.exists() || !file.isFile()) {
            sendCommand(CMD_ERROR, "File not found: " + relativePath);
            return false;
        }

        // Read file content
        byte[] data = readFileContent(file);

        // Smart compression based on content analysis
        CompressionUtil.CompressedData compressedData = CompressionUtil.compressIfBeneficial(file.getName(), data);
        boolean wasCompressed = compressedData.isCompressed();
        long lastModified = file.lastModified();

        // Try to send the file with limited retries in case of handshake issues
        final int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Send file header for this attempt
            sendCommand(CMD_FILE_DATA,
                    relativePath,
                    String.valueOf(compressedData.getData().length),
                    String.valueOf(wasCompressed),
                    String.valueOf(lastModified));

            try {
                // Wait for receiver ACK to ensure proper synchronization
                Message ackMsg = waitForCommand(CMD_ACK);

                // Send file data via XMODEM
                xmodemInProgress.set(true);
                boolean success;
                try {
                    success = xmodem.send(compressedData.getData());
                } finally {
                    xmodemInProgress.set(false);
                }

                if (success) {
                    return wasCompressed;
                }
            } catch (IOException e) {
                // Failed attempt - continue to cleanup and retry logic below
                throw e;
            }

            // Best-effort cleanup between attempts
            try {
                serialPort.clearInputBuffer();
            } catch (IOException e) {
                // Ignore cleanup errors; we will surface the XMODEM error if all attempts fail
            }

            if (attempt < maxAttempts) {
                // Small backoff before retrying
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String detail = xmodem.getLastErrorMessage();
        if (detail == null || detail.isEmpty()) {
            detail = "unknown XMODEM error";
        }
        throw new IOException("Failed to send file " + relativePath +
                " after " + maxAttempts + " attempts (" + detail + ")");
    }

    /**
     * Receive file data and save to directory
     */
    public void receiveFile(File baseDir, String relativePath, int expectedSize, boolean compressed, long lastModified) throws IOException {
        xmodemInProgress.set(true);
        byte[] data;
        try {
            data = xmodem.receive();
        } finally {
            xmodemInProgress.set(false);
        }
        if (data == null) {
            // Best-effort recovery: clear any stale data from the input buffer
            try {
                serialPort.clearInputBuffer();
            } catch (IOException e) {
                // Ignore cleanup failure; we are already reporting a higher-level error
            }

            String detail = xmodem.getLastErrorMessage();
            if (detail == null || detail.isEmpty()) {
                detail = "no detailed XMODEM error available";
            }
            throw new IOException("Failed to receive file data for " + relativePath + " (" + detail + ")");
        }

        // Decompress if needed
        if (compressed) {
            data = CompressionUtil.decompress(data);
        }

        // Create directories if needed
        File targetFile = new File(baseDir, relativePath);
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // Write file
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(data);
        }

        // Preserve sender timestamp so subsequent manifest comparisons match
        if (lastModified > 0) {
            targetFile.setLastModified(lastModified);
        }
    }

    /**
     * Send sync complete notification
     */
    public void sendSyncComplete() throws IOException {
        sendCommand(CMD_SYNC_COMPLETE);
    }

    /**
     * Send direction change notification
     */
    public void sendDirectionChange(boolean isSender) throws IOException {
        sendCommand(CMD_DIRECTION_CHANGE, String.valueOf(isSender));
    }

    /**
     * Send acknowledgment
     */
    public void sendAck() throws IOException {
        sendCommand(CMD_ACK);
    }

    /**
     * Send error message
     */
    public void sendError(String message) throws IOException {
        sendCommand(CMD_ERROR, message);
    }

    /**
     * Send heartbeat to check connection
     */
    public void sendHeartbeat() throws IOException {
        sendCommand(CMD_HEARTBEAT);
    }

    /**
     * Send heartbeat acknowledgment
     */
    public void sendHeartbeatAck() throws IOException {
        sendCommand(CMD_HEARTBEAT_ACK);
    }

    /**
     * Send role negotiation with priority value
     */
    public void sendRoleNegotiate(long priority) throws IOException {
        sendCommand(CMD_ROLE_NEGOTIATE, String.valueOf(priority));
    }

    /**
     * Send file delete command to delete a file on remote
     */
    public void sendFileDelete(String relativePath) throws IOException {
        sendCommand(CMD_FILE_DELETE, relativePath);
    }

    /**
     * Send mkdir command to create a directory on remote
     */
    public void sendMkdir(String relativePath) throws IOException {
        sendCommand(CMD_MKDIR, relativePath);
    }

    /**
     * Send rmdir command to delete an empty directory on remote
     */
    public void sendRmdir(String relativePath) throws IOException {
        sendCommand(CMD_RMDIR, relativePath);
    }

    /**
     * Send shared text payload (Base64 encoded to protect delimiters)
     */
    public void sendSharedText(String text) throws IOException {
        String encoded = encodeText(text);
        sendCommand(CMD_SHARED_TEXT, encoded);
    }

    /**
     * Decode shared text payload received from remote
     */
    public String decodeSharedText(String encodedPayload) {
        if (encodedPayload == null) {
            return "";
        }
        byte[] data = BASE64_DECODER.decode(encodedPayload);
        return new String(data, StandardCharsets.UTF_8);
    }

    private String encodeText(String text) {
        if (text == null) {
            text = "";
        }
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return BASE64_ENCODER.encodeToString(data);
    }

    /**
     * Try to receive a command with short timeout for heartbeat check
     * Returns null if no data available or timeout
     */
    public Message tryReceiveCommand(int shortTimeoutMs) throws IOException {
        int originalTimeout = timeoutMs;
        try {
            serialPort.setReadTimeout(shortTimeoutMs);
            if (serialPort.available() > 0) {
                String line = serialPort.readLine(shortTimeoutMs);
                return parseMessage(line);
            }
            return null;
        } finally {
            serialPort.setReadTimeout(originalTimeout);
        }
    }

    /**
     * Wait for specific command
     */
    public Message waitForCommand(String expectedCommand) throws IOException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            Message msg = receiveCommand();
            if (msg != null && msg.getCommand().equals(expectedCommand)) {
                return msg;
            }
        }
        throw new IOException("Timeout waiting for command: " + expectedCommand);
    }

    /**
     * Check if there's data available
     */
    public boolean hasData() throws IOException {
        return serialPort.available() > 0;
    }

    /**
     * Check if XMODEM transfer is in progress.
     * When true, other threads should not read from serial port.
     */
    public boolean isXmodemInProgress() {
        return xmodemInProgress.get();
    }

    private byte[] readFileContent(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int totalRead = 0;
            while (totalRead < data.length) {
                int read = fis.read(data, totalRead, data.length - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
            return data;
        }
    }

    /**
     * Protocol message class
     */
    public static class Message {
        private final String command;
        private final String[] params;

        public Message(String command, String[] params) {
            this.command = command;
            this.params = params;
        }

        public String getCommand() {
            return command;
        }

        public String[] getParams() {
            return params;
        }

        public String getParam(int index) {
            if (index >= 0 && index < params.length) {
                return params[index];
            }
            return null;
        }

        public int getParamAsInt(int index) {
            String param = getParam(index);
            if (param != null) {
                return Integer.parseInt(param);
            }
            return 0;
        }

        public long getParamAsLong(int index) {
            String param = getParam(index);
            if (param != null) {
                return Long.parseLong(param);
            }
            return 0L;
        }

        public boolean getParamAsBoolean(int index) {
            String param = getParam(index);
            return Boolean.parseBoolean(param);
        }

        @Override
        public String toString() {
            return "Message{command='" + command + "', params=" + String.join(", ", params) + "}";
        }
    }
}

