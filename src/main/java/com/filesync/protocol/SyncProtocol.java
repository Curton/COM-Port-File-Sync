package com.filesync.protocol;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
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
    public static final String CMD_SHARED_TEXT_DATA = "SHARED_TEXT_DATA";
    public static final String CMD_DROP_FILE = "DROP_FILE";
    public static final String CMD_FOLDER_CONTEXT_REQ = "FOLDER_CONTEXT_REQ";
    public static final String CMD_FOLDER_CONTEXT_DATA = "FOLDER_CONTEXT_DATA";
    public static final String CMD_FOLDER_CHANGE = "FOLDER_CHANGE";
    public static final String CMD_FILE_CONTENT_REQ = "FILE_CONTENT_REQ";
    public static final String CMD_FILE_CONTENT_DATA = "FILE_CONTENT_DATA";
    public static final String CMD_DISCONNECT = "DISCONNECT";
    public static final String CMD_CANCEL = "CANCEL";

    // Protocol markers
    private static final String START_MARKER = "[[SYNC:";
    private static final String END_MARKER = "]]";
    private static final String SEPARATOR = ":";
    private static final char SEPARATOR_CHAR = ':';
    private static final char ESCAPE_CHAR = '\\';

    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int SHARED_TEXT_INLINE_BUDGET_MS = 5000;
    private static final int MIN_SHARED_TEXT_INLINE_ENCODED_CHARS = 128;
    private static final String SHARED_TEXT_TRANSFER_NAME = "shared-text.txt";

    private final SerialPortManager serialPort;
    private final XModemTransfer xmodem;
    private int timeoutMs;
    private Runnable messageActivityCallback;

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

    public int getTimeout() {
        return timeoutMs;
    }

    /**
     * Set callback invoked when HEARTBEAT or HEARTBEAT_ACK is received during command waits.
     * Used to refresh liveness so long protocol waits do not trigger false connection loss.
     */
    public void setMessageActivityCallback(Runnable callback) {
        this.messageActivityCallback = callback;
    }

    /**
     * Send a command message
     */
    public void sendCommand(String command, String... params) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(START_MARKER).append(command);
        for (String param : params) {
            sb.append(SEPARATOR).append(escapeProtocolParam(param));
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
        String[] parts = splitEscapedFields(content);

        if (parts.length == 0) {
            return null;
        }

        String command = parts[0];
        String[] params = new String[parts.length - 1];
        System.arraycopy(parts, 1, params, 0, params.length);

        return new Message(command, params);
    }

    private static String escapeProtocolParam(String param) {
        if (param == null) {
            return "";
        }
        return param.replace(String.valueOf(ESCAPE_CHAR), String.valueOf(ESCAPE_CHAR) + ESCAPE_CHAR)
                .replace(SEPARATOR, String.valueOf(ESCAPE_CHAR) + SEPARATOR_CHAR);
    }

    private static String[] splitEscapedFields(String content) {
        ArrayList<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaping = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escaping) {
                current.append(c);
                escaping = false;
                continue;
            }
            if (c == ESCAPE_CHAR) {
                escaping = true;
                continue;
            }
            if (c == SEPARATOR_CHAR) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        if (escaping) {
            current.append(ESCAPE_CHAR);
        }
        parts.add(current.toString());

        return parts.toArray(new String[0]);
    }

    /**
     * Request manifest from remote
     */
    public void requestManifest() throws IOException {
        sendCommand(CMD_MANIFEST_REQ);
    }
    
    /**
     * Request manifest from remote with specific settings.
     * This ensures the receiver uses the same manifest generation settings as the sender.
     * 
     * @param respectGitignore whether to respect .gitignore
     * @param fastMode whether to use fast mode (skip MD5 computation)
     */
    public void requestManifest(boolean respectGitignore, boolean fastMode) throws IOException {
        sendCommand(CMD_MANIFEST_REQ, String.valueOf(respectGitignore), String.valueOf(fastMode));
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
        waitForCommand(CMD_ACK);

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
        return receiveManifest(-1);
    }

    /**
     * Receive manifest data with optional expected payload length.
     */
    public FileChangeDetector.FileManifest receiveManifest(int expectedCompressedLength) throws IOException {
        xmodemInProgress.set(true);
        byte[] compressed;
        try {
            compressed = xmodem.receive(expectedCompressedLength);
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

        // Check file size limit (2GB max due to integer array allocation)
        if (file.length() > Integer.MAX_VALUE) {
            sendCommand(CMD_ERROR, "File too large: " + relativePath + " (max 2GB)");
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
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // Send file header for this attempt
                sendCommand(CMD_FILE_DATA,
                        relativePath,
                        String.valueOf(compressedData.getData().length),
                        String.valueOf(wasCompressed),
                        String.valueOf(lastModified));

                // Wait for receiver ACK to ensure proper synchronization
                waitForCommand(CMD_ACK);

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
                lastFailure = e;
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
            detail = lastFailure != null ? lastFailure.getMessage() : "unknown XMODEM error";
        }
        IOException finalEx = new IOException("Failed to send file " + relativePath +
                " after " + maxAttempts + " attempts (" + detail + ")");
        if (lastFailure != null) {
            finalEx.addSuppressed(lastFailure);
        }
        throw finalEx;
    }

    /**
     * Send a single dropped file to the peer.
     */
    public void sendDropFile(File file) throws IOException {
        if (file == null) {
            throw new IOException("Cannot send a null file");
        }
        if (!file.exists() || !file.isFile()) {
            throw new IOException("Drop file not found or not a file: " + file.getAbsolutePath());
        }

        String fileName = sanitizeDropFileName(file.getName());
        byte[] data = readFileContent(file);
        CompressionUtil.CompressedData compressedData = CompressionUtil.compressIfBeneficial(fileName, data);
        sendCommand(CMD_DROP_FILE,
                fileName,
                String.valueOf(compressedData.getData().length),
                String.valueOf(compressedData.isCompressed()));
        waitForCommand(CMD_ACK);

        xmodemInProgress.set(true);
        try {
            boolean success = xmodem.send(compressedData.getData());
            if (!success) {
                String detail = xmodem.getLastErrorMessage();
                if (detail == null || detail.isEmpty()) {
                    detail = "unknown XMODEM error";
                }
                throw new IOException("Failed to send dropped file " + fileName + " (" + detail + ")");
            }
        } finally {
            xmodemInProgress.set(false);
        }
    }

    /**
     * Receive file data and save to directory
     */
    public void receiveFile(File baseDir, String relativePath, int expectedSize, boolean compressed, long lastModified) throws IOException {
        xmodemInProgress.set(true);
        byte[] data;
        try {
            data = xmodem.receive(expectedSize);
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

        // Verify sender-reported size before any transformation or disk write
        validateReceivedSize("file", relativePath, expectedSize, data);

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
     * Receive a dropped file and save it to the Downloads directory.
     */
    public File receiveDropFile(File downloadsDir, String originalFileName, int expectedSize, boolean compressed) throws IOException {
        if (downloadsDir == null) {
            throw new IOException("Downloads folder is not configured");
        }
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw new IOException("Failed to create Downloads directory: " + downloadsDir.getAbsolutePath());
        }
        if (!downloadsDir.isDirectory()) {
            throw new IOException("Downloads path is not a directory: " + downloadsDir.getAbsolutePath());
        }

        String fileName = sanitizeDropFileName(originalFileName);
        File targetFile = resolveDropFileDestination(downloadsDir, fileName);
        xmodemInProgress.set(true);
        byte[] data;
        try {
            data = xmodem.receive(expectedSize);
        } finally {
            xmodemInProgress.set(false);
        }
        if (data == null) {
            String detail = xmodem.getLastErrorMessage();
            if (detail == null || detail.isEmpty()) {
                detail = "no detailed XMODEM error available";
            }
            throw new IOException("Failed to receive dropped file " + fileName + " (" + detail + ")");
        }

        // Verify sender-reported size before any transformation or disk write
        validateReceivedSize("dropped file", fileName, expectedSize, data);

        if (compressed) {
            data = CompressionUtil.decompress(data);
        }

        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(data);
        }

        return targetFile;
    }

    /**
     * Send sync complete notification
     */
    public void sendSyncComplete() throws IOException {
        sendCommand(CMD_SYNC_COMPLETE);
    }

    /**
     * Encode a folder path for protocol transmission (Base64) so colons and separators do not break framing.
     */
    public static String encodePathForProtocol(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return new String(BASE64_ENCODER.encode(path.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    /**
     * Decode a folder path from protocol transmission.
     */
    public static String decodePathFromProtocol(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        try {
            return new String(BASE64_DECODER.decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /**
     * Request folder context from remote (sender asks receiver for its sync folder path).
     */
    public void sendFolderContextRequest() throws IOException {
        sendCommand(CMD_FOLDER_CONTEXT_REQ);
    }

    /**
     * Send folder context response (receiver replies with its sync folder path, Base64 encoded).
     */
    public void sendFolderContextResponse(String folderPath) throws IOException {
        String encoded = encodePathForProtocol(folderPath != null ? folderPath : "");
        sendCommand(CMD_FOLDER_CONTEXT_DATA, encoded);
    }

    /**
     * Send folder change notification to remote receiver.
     * The receiver should look up the mapped folder and switch to it.
     *
     * @param folderPath local sync folder path (will be Base64 encoded)
     */
    public void sendFolderChange(String folderPath) throws IOException {
        String encoded = encodePathForProtocol(folderPath);
        sendCommand(CMD_FOLDER_CHANGE, encoded);
    }

    /**
     * Wait for folder context response and return decoded remote folder path.
     * Call after sendFolderContextRequest().
     */
    public String receiveFolderContextResponse() throws IOException {
        Message msg = waitForCommand(CMD_FOLDER_CONTEXT_DATA);
        if (msg == null || msg.getParams().length == 0) {
            return "";
        }
        return decodePathFromProtocol(msg.getParam(0));
    }

    /**
     * Send file content response for conflict resolution.
     * The content is Base64 encoded and sent inline within the protocol message.
     *
     * @param relativePath the relative path of the file being sent
     * @param content the file content bytes
     */
    public void sendFileContentResponse(String relativePath, byte[] content) throws IOException {
        String encoded = encodePathForProtocol(relativePath);
        String contentBase64 = BASE64_ENCODER.encodeToString(content);
        sendCommand(CMD_FILE_CONTENT_DATA, encoded, contentBase64);
    }

    /**
     * Wait for file content response and return Base64-encoded content.
     * Call after sending CMD_FILE_CONTENT_REQ.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return Base64-encoded file content, or null if timeout/error
     */
    public String waitForFileContentResponse(long timeoutMs) throws IOException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            Message msg = receiveCommand();
            if (msg == null) {
                try { Thread.sleep(10); } catch (InterruptedException e) { }
                continue;
            }
            String cmd = msg.getCommand();
            if (CMD_FILE_CONTENT_DATA.equals(cmd)) {
                return msg.getParam(1);
            }
            if (CMD_ERROR.equals(cmd)) {
                String errMsg = msg.getParams().length > 0 ? msg.getParam(0) : "unknown";
                throw new IOException("Remote error during file content request: " + errMsg);
            }
            if (CMD_HEARTBEAT.equals(cmd)) {
                sendHeartbeatAck();
                runMessageActivityCallback();
            } else if (CMD_HEARTBEAT_ACK.equals(cmd)) {
                runMessageActivityCallback();
            }
        }
        return null;
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
     * Notify peer that a sync was cancelled.
     */
    public void sendCancelCommand() throws IOException {
        sendCommand(CMD_CANCEL);
    }

    /**
     * Cancel an in-flight XMODEM transfer (control-plane cancel).
     */
    public void sendTransferCancel() throws IOException {
        xmodem.sendCancelSignal();
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
     * Send disconnect notification
     */
    public void sendDisconnect() throws IOException {
        sendCommand(CMD_DISCONNECT);
    }

    /**
     * Send role negotiation with priority and tie-breaker values
     */
    public void sendRoleNegotiate(long priority) throws IOException {
        sendRoleNegotiate(priority, 0L);
    }

    /**
     * Send role negotiation with priority and tie-breaker values
     */
    public void sendRoleNegotiate(long priority, long tieBreaker) throws IOException {
        sendCommand(CMD_ROLE_NEGOTIATE, String.valueOf(priority), String.valueOf(tieBreaker));
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
        sendSharedText(System.currentTimeMillis(), text);
    }

    /**
     * Send shared text payload with a last-changed timestamp.
     */
    public void sendSharedText(long timestamp, String text) throws IOException {
        if (text == null) {
            text = "";
        }
        String encoded = encodeText(text);
        if (shouldSendSharedTextInline(encoded)) {
            sendCommand(CMD_SHARED_TEXT, String.valueOf(timestamp), encoded);
            return;
        }
        sendSharedTextData(timestamp, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Receive shared text payload transferred via XMODEM.
     * @param expectedDataLength expected payload size in bytes
     */
    public String receiveSharedTextData(boolean wasCompressed, int expectedDataLength) throws IOException {
        xmodemInProgress.set(true);
        try {
            sendAck();
            byte[] payload = xmodem.receive(expectedDataLength);
            if (payload == null) {
                String detail = xmodem.getLastErrorMessage();
                if (detail == null || detail.isEmpty()) {
                    detail = "unknown XMODEM error";
                }
                throw new IOException("Failed to receive shared text (" + detail + ")");
            }
            byte[] decoded = CompressionUtil.decompressIfNeeded(payload, wasCompressed);
            return new String(decoded, StandardCharsets.UTF_8);
        } finally {
            xmodemInProgress.set(false);
        }
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

    private void sendSharedTextData(byte[] textBytes) throws IOException {
        sendSharedTextData(System.currentTimeMillis(), textBytes);
    }

    /**
     * Send shared text via XMODEM with a last-changed timestamp.
     */
    private void sendSharedTextData(long timestamp, byte[] textBytes) throws IOException {
        CompressionUtil.CompressedData payload =
                CompressionUtil.compressIfBeneficial(SHARED_TEXT_TRANSFER_NAME, textBytes);
        xmodemInProgress.set(true);
        try {
            sendCommand(CMD_SHARED_TEXT_DATA,
                    String.valueOf(timestamp),
                    String.valueOf(payload.isCompressed()),
                    String.valueOf(payload.getData().length));
            waitForCommand(CMD_ACK);
            boolean success = xmodem.send(payload.getData());
            if (!success) {
                String detail = xmodem.getLastErrorMessage();
                if (detail == null || detail.isEmpty()) {
                    detail = "unknown XMODEM error";
                }
                throw new IOException("Failed to send shared text (" + detail + ")");
            }
        } finally {
            xmodemInProgress.set(false);
        }
    }

    private boolean shouldSendSharedTextInline(String encodedPayload) {
        return encodedPayload.length() <= getSharedTextInlineEncodedLimit();
    }

    private void validateReceivedSize(String transferType, String targetName, int expectedSize, byte[] actualData) throws IOException {
        if (expectedSize < 0 || actualData == null) {
            return;
        }
        if (actualData.length != expectedSize) {
            throw new IOException("Size mismatch while receiving " + transferType + " '" + targetName
                    + "': expected " + expectedSize + " bytes, received " + actualData.length + " bytes");
        }
    }

    private int getSharedTextInlineEncodedLimit() {
        long bytesPerSecond = Math.max(serialPort.getBaudRate() / 10L, 1L);
        long budgetBytes = (bytesPerSecond * SHARED_TEXT_INLINE_BUDGET_MS) / 1000L;
        long framingBytes = START_MARKER.length() + CMD_SHARED_TEXT.length() + END_MARKER.length() + 2L;
        long limit = budgetBytes - framingBytes;
        return (int) Math.max(limit, MIN_SHARED_TEXT_INLINE_ENCODED_CHARS);
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
     * Wait for specific command.
     * Handles HEARTBEAT and HEARTBEAT_ACK to keep liveness active during long waits.
     * Throws IOException when CMD_ERROR is received.
     */
    public Message waitForCommand(String expectedCommand) throws IOException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            Message msg = receiveCommand();
            if (msg == null) {
                continue;
            }
            String cmd = msg.getCommand();
            if (cmd.equals(expectedCommand)) {
                return msg;
            }
            if (CMD_ERROR.equals(cmd)) {
                String errMsg = msg.getParams().length > 0 ? msg.getParam(0) : "unknown";
                throw new IOException("Remote error: " + errMsg);
            }
            if (CMD_HEARTBEAT.equals(cmd)) {
                sendHeartbeatAck();
                runMessageActivityCallback();
            } else if (CMD_HEARTBEAT_ACK.equals(cmd)) {
                runMessageActivityCallback();
            }
        }
        throw new IOException("Timeout waiting for command: " + expectedCommand);
    }

    private void runMessageActivityCallback() {
        if (messageActivityCallback != null) {
            messageActivityCallback.run();
        }
    }

    /**
     * For testing: invoke the message activity callback.
     * Used by protocol subclasses to simulate heartbeat handling.
     */
    protected void notifyMessageActivity() {
        runMessageActivityCallback();
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
        long fileSize = file.length();
        if (fileSize > Integer.MAX_VALUE) {
            throw new IOException("File too large: " + fileSize + " bytes (max: " + Integer.MAX_VALUE + " bytes)");
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) fileSize];
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
        public static class ProtocolFieldParseException extends IllegalArgumentException {
            ProtocolFieldParseException(String message) {
                super(message);
            }

            ProtocolFieldParseException(String message, Throwable cause) {
                super(message, cause);
            }
        }

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
            return parseIntParameter(index);
        }

        public long getParamAsLong(int index) {
            return parseLongParameter(index);
        }

        private int parseIntParameter(int index) {
            String param = getRequiredParam(index, "integer");
            try {
                return Integer.parseInt(param);
            } catch (NumberFormatException e) {
                throw new ProtocolFieldParseException(
                        "Invalid integer parameter at index " + index + " for command '" + command + "': " + param,
                        e);
            }
        }

        private long parseLongParameter(int index) {
            String param = getRequiredParam(index, "long");
            try {
                return Long.parseLong(param);
            } catch (NumberFormatException e) {
                throw new ProtocolFieldParseException(
                        "Invalid long parameter at index " + index + " for command '" + command + "': " + param,
                        e);
            }
        }

        private String getRequiredParam(int index, String expectedType) {
            String param = getParam(index);
            if (param == null || param.trim().isEmpty()) {
                throw new ProtocolFieldParseException(
                        "Missing " + expectedType + " parameter at index " + index + " for command '" + command + "'.");
            }
            return param.trim();
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

    private String sanitizeDropFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "file";
        }
        String name = new File(fileName).getName().trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        name = name.replaceAll("\\s+", " ");
        return name.isBlank() ? "file" : name;
    }

    private File resolveDropFileDestination(File downloadsDir, String requestedFileName) {
        String fileName = requestedFileName == null || requestedFileName.trim().isEmpty()
                ? "file" : requestedFileName.trim();
        File target = new File(downloadsDir, fileName);
        if (!target.exists()) {
            return target;
        }

        String base = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            base = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }

        int index = 1;
        while (true) {
            File candidate = new File(downloadsDir, base + " (" + index + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
            index++;
        }
    }
}

