package com.filesync.protocol;

import com.filesync.delta.DeltaDecoder;
import com.filesync.delta.HashUtil;
import com.filesync.delta.SignatureSet;
import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;
import com.filesync.sync.CompressionUtil;
import com.filesync.sync.FileChangeDetector;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Protocol for file synchronization commands over serial port. Handles message framing, command
 * exchange, and file transfer coordination.
 */
public class SyncProtocol {

    // Flag to indicate XMODEM transfer is in progress
    // When true, the listener thread should not read from serial port
    private final AtomicBoolean xmodemInProgress = new AtomicBoolean(false);

    // Async messages (e.g. SHARED_TEXT, DIRECTION_CHANGE) that arrived while a synchronous
    // exchange (waitForCommand / waitForFileContentResponse) was reading the stream. They are
    // stashed here instead of being silently dropped, and drained by the listener loop.
    private final Queue<Message> stashedMessages = new ConcurrentLinkedQueue<>();

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
    public static final String CMD_FILE_CONTENT_XFER = "FILE_CONTENT_XFER";
    public static final String CMD_DISCONNECT = "DISCONNECT";
    public static final String CMD_CANCEL = "CANCEL";
    public static final String CMD_BATCH_DATA = "BATCH_DATA";
    public static final String CMD_BATCH_COMPLETE = "BATCH_COMPLETE";
    public static final String CMD_LOG_REQ = "LOG_REQ";
    public static final String CMD_LOG_DATA = "LOG_DATA";
    public static final String CMD_LOG_XFER = "LOG_XFER";
    public static final String CMD_LOG_MARKER_REQ = "LOG_MARKER_REQ";
    public static final String CMD_DELTA_SIG_REQ = "DELTA_SIG_REQ";
    public static final String CMD_DELTA_SIG_DATA = "DELTA_SIG_DATA";
    public static final String CMD_FILE_DELTA = "FILE_DELTA";

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
     * Set callback invoked when HEARTBEAT or HEARTBEAT_ACK is received during command waits. Used
     * to refresh liveness so long protocol waits do not trigger false connection loss.
     */
    public void setMessageActivityCallback(Runnable callback) {
        this.messageActivityCallback = callback;
    }

    /** Send a command message */
    public synchronized void sendCommand(String command, String... params) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(START_MARKER).append(command);
        for (String param : params) {
            sb.append(SEPARATOR).append(escapeProtocolParam(param));
        }
        sb.append(END_MARKER);
        serialPort.writeLine(sb.toString());
    }

    /** Receive and parse a command message */
    public Message receiveCommand() throws IOException {
        String line = serialPort.readLine(timeoutMs);
        return parseMessage(line);
    }

    /** Parse a protocol message */
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
                .replace(SEPARATOR, String.valueOf(ESCAPE_CHAR) + SEPARATOR_CHAR)
                .replace("\n", " ")
                .replace(
                        END_MARKER,
                        String.valueOf(ESCAPE_CHAR) + END_MARKER.charAt(0) + END_MARKER.charAt(1));
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

    /** Request manifest from remote */
    public void requestManifest() throws IOException {
        sendCommand(CMD_MANIFEST_REQ);
    }

    /**
     * Request manifest from remote with specific settings. This ensures the receiver uses the same
     * manifest generation settings as the sender.
     *
     * @param respectGitignore whether to respect .gitignore
     * @param fastMode whether to use fast mode (skip MD5 computation)
     */
    public void requestManifest(boolean respectGitignore, boolean fastMode) throws IOException {
        sendCommand(CMD_MANIFEST_REQ, String.valueOf(respectGitignore), String.valueOf(fastMode));
    }

    /** Send manifest data */
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

    /** Receive manifest data */
    public FileChangeDetector.FileManifest receiveManifest() throws IOException {
        return receiveManifest(-1);
    }

    /** Receive manifest data with optional expected payload length. */
    public FileChangeDetector.FileManifest receiveManifest(int expectedCompressedLength)
            throws IOException {
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

    // ---- rsync-style block-signature exchange for binary delta sync ----

    /**
     * Sender side: request block signatures from the receiver for the given candidate paths, then
     * wait for the receiver to announce and transfer the {@link SignatureSet} payload. The receiver
     * computes signatures only for files that exist locally; paths missing on the receiver are
     * simply absent from the returned set, and the sender will fall back to a full transfer for
     * them. Returns an empty set if {@code paths} is empty (no round-trip performed).
     */
    public SignatureSet requestDeltaSignatures(List<String> paths) throws IOException {
        if (paths == null || paths.isEmpty()) {
            return SignatureSet.empty();
        }
        sendCommand(CMD_DELTA_SIG_REQ, paths.toArray(new String[0]));

        Message msg = waitForCommand(CMD_DELTA_SIG_DATA);
        sendAck();
        // waitForCommand never returns null (it throws on timeout), so only the empty-params
        // case falls back to -1.
        int expectedSize = msg.getParams().length > 0 ? msg.getParamAsInt(0) : -1;
        return receiveDeltaSignatures(expectedSize);
    }

    /** Receive and deserialize the signature-set payload sent by the receiver. */
    public SignatureSet receiveDeltaSignatures(int expectedCompressedLength) throws IOException {
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
            throw new IOException("Failed to receive delta signatures (" + detail + ")");
        }
        byte[] data = CompressionUtil.decompress(compressed);
        return SignatureSet.fromBytes(data);
    }

    /**
     * Receiver side: send the computed {@link SignatureSet} to the sender. The payload is always
     * GZIP-compressed (like the manifest) and transferred via a single XMODEM session.
     */
    public void sendDeltaSignatures(SignatureSet set) throws IOException {
        byte[] data = set.toBytes();
        byte[] compressed = CompressionUtil.compress(data);

        sendCommand(CMD_DELTA_SIG_DATA, String.valueOf(compressed.length));
        waitForCommand(CMD_ACK);

        xmodemInProgress.set(true);
        try {
            boolean success = xmodem.send(compressed);
            if (!success) {
                String detail = xmodem.getLastErrorMessage();
                if (detail == null || detail.isEmpty()) {
                    detail = "unknown XMODEM error";
                }
                throw new IOException("Failed to send delta signatures (" + detail + ")");
            }
        } finally {
            xmodemInProgress.set(false);
        }
    }

    /**
     * Sender side: send a delta-encoded file. The {@code delta} bytes are compressed if beneficial.
     * The {@code sourceMd5} is forwarded so the receiver can verify its reconstruction; {@code
     * sourceSize} lets the receiver pre-size the output buffer.
     *
     * <p>Recovery contract: the command/ACK handshake is retried up to {@code maxAttempts} times,
     * because a failure there occurs before the receiver enters {@code xmodem.receive()} and a
     * re-sent command is safe. Once the XMODEM phase is entered, any failure ({@code xmodem.send}
     * returning {@code false} or throwing) is terminal: a transfer-cancel is sent to release the
     * receiver from its blocking {@code xmodem.receive()} and no further attempts are made. A
     * re-sent command after a mid-transfer failure would otherwise be consumed as XMODEM data,
     * desynchronizing the peers; the cancel-driven reset (peer {@code restartListening}) is the
     * intended recovery, and the file is re-evaluated on the next sync.
     *
     * @return true if the delta was compressed, false otherwise
     */
    public boolean sendFileDelta(
            String relativePath,
            byte[] delta,
            long lastModified,
            long sourceSize,
            String sourceMd5)
            throws IOException {
        CompressionUtil.CompressedData compressedData =
                CompressionUtil.compressIfBeneficial(relativePath, delta);
        boolean wasCompressed = compressedData.isCompressed();
        long ts = lastModified > 0 ? lastModified : System.currentTimeMillis();

        final int maxAttempts = 3;
        int attemptsUsed = 0;
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attemptsUsed = attempt;
            boolean xmodemPhase = false;
            try {
                sendCommand(
                        CMD_FILE_DELTA,
                        relativePath,
                        String.valueOf(compressedData.getData().length),
                        String.valueOf(wasCompressed),
                        String.valueOf(ts),
                        String.valueOf(sourceSize),
                        sourceMd5);
                waitForCommand(CMD_ACK);

                xmodemInProgress.set(true);
                xmodemPhase = true;
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
                lastFailure = e;
            }

            if (xmodemPhase) {
                // XMODEM-phase failure: the receiver may still be blocked in xmodem.receive(),
                // so a re-sent command would be swallowed as XMODEM data. Cancel the peer's
                // receive and stop instead of retrying.
                try {
                    sendTransferCancel();
                } catch (IOException ignored) {
                }
                try {
                    serialPort.clearInputBuffer();
                } catch (IOException ignored) {
                }
                break;
            }

            // Command/ACK-phase failure: the receiver has not entered xmodem.receive() yet, so
            // re-sending the command is safe.
            try {
                serialPort.clearInputBuffer();
            } catch (IOException ignored) {
            }
            if (attempt < maxAttempts) {
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
        IOException finalEx =
                new IOException(
                        "Failed to send file delta for "
                                + relativePath
                                + " after "
                                + attemptsUsed
                                + " attempt(s) ("
                                + detail
                                + ")");
        if (lastFailure != null) {
            finalEx.addSuppressed(lastFailure);
        }
        try {
            sendError("File delta send failed: " + relativePath);
        } catch (IOException ignored) {
        }
        throw finalEx;
    }

    /**
     * Receiver side: receive a delta-encoded file, reconstruct the source bytes from the existing
     * local file, verify the MD5 against the sender's {@code sourceMd5}, and write the result.
     *
     * <p>A write failure (e.g. the target is locked) throws {@link FileWriteException} carrying the
     * reconstructed bytes so the caller can queue a deferred retry. An MD5 mismatch or decode error
     * throws a plain {@link IOException} so the caller can request a full retransfer.
     *
     * @param baseDir base directory containing the existing file
     * @param relativePath relative path of the file
     * @param expectedSize announced compressed/raw delta length
     * @param compressed whether the delta payload is GZIP-compressed
     * @param lastModified sender timestamp to preserve
     * @param sourceSize sender's total source byte length (informational)
     * @param sourceMd5 sender's MD5 of the source, for reconstruction verification
     */
    public void receiveFileDelta(
            File baseDir,
            String relativePath,
            int expectedSize,
            boolean compressed,
            long lastModified,
            long sourceSize,
            String sourceMd5)
            throws IOException {
        xmodemInProgress.set(true);
        byte[] payload;
        try {
            payload = xmodem.receive(expectedSize);
        } finally {
            xmodemInProgress.set(false);
        }
        if (payload == null) {
            try {
                serialPort.clearInputBuffer();
            } catch (IOException ignored) {
            }
            String detail = xmodem.getLastErrorMessage();
            if (detail == null || detail.isEmpty()) {
                detail = "no detailed XMODEM error available";
            }
            throw new IOException(
                    "Failed to receive file delta for " + relativePath + " (" + detail + ")");
        }
        validateReceivedSize("file delta", relativePath, expectedSize, payload);

        byte[] deltaBytes = compressed ? CompressionUtil.decompress(payload) : payload;

        File existing = new File(baseDir, relativePath);
        if (!existing.exists() || !existing.isFile()) {
            throw new IOException(
                    "Cannot apply delta: existing file missing on receiver: " + relativePath);
        }
        byte[] existingBytes = Files.readAllBytes(existing.toPath());
        byte[] reconstructed = DeltaDecoder.decode(existingBytes, deltaBytes);

        // Verify reconstruction against the sender's MD5 to guard against a stale signature
        // (the receiver's file changed between signature generation and delta application).
        String actualMd5 = HashUtil.md5Hex(reconstructed);
        if (sourceMd5 != null && !sourceMd5.isEmpty() && !sourceMd5.equals(actualMd5)) {
            throw new IOException(
                    "Delta reconstruction verification failed for "
                            + relativePath
                            + " (expected "
                            + sourceMd5
                            + ", got "
                            + actualMd5
                            + ")");
        }

        File targetFile = new File(baseDir, relativePath);
        // The existing-file check above guarantees the target's parent already exists
        // (existing and target share the same path), so no mkdir is needed here.
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(reconstructed);
        } catch (IOException e) {
            throw new FileWriteException(relativePath, reconstructed, lastModified, e.getMessage(), e);
        }
        if (lastModified > 0) {
            targetFile.setLastModified(lastModified);
        }
    }

    /**
     * Send a batch of files as a single XMODEM transfer to amortize handshake overhead. The batch
     * is built from the given list of (File, relativePath) pairs and sent under one XMODEM session.
     * On the receiver side the batch is decoded and files are written.
     *
     * @param files list of entries; each entry is a Object[] { File file, String relativePath }
     * @param maxBatchSizeBytes soft upper bound on total encoded bytes (count + content, approx.)
     * @param batchProgressCallback called with (entryIndex, totalEntries, relativePath) during
     *     decode; may be null
     * @param baseDirForReceive base directory used on the receiver side to write files
     * @return true on success, false on failure
     */
    public boolean sendBatch(
            List<Object[]> files,
            int maxBatchSizeBytes,
            BatchTransferSession.BatchProgressCallback batchProgressCallback,
            File baseDirForReceive)
            throws IOException {
        if (files == null || files.isEmpty()) {
            return true;
        }

        byte[] batch = BatchTransferSession.buildBatch(files, maxBatchSizeBytes);

        // Retry only the command/ACK handshake. Once the XMODEM phase is entered, a failure is
        // terminal: the receiver may be blocked in xmodem.receive() and a re-sent command would
        // be consumed as XMODEM data, desynchronizing the peers. See sendFileDelta for the full
        // rationale; the cancel-driven reset (peer restartListening) is the intended recovery.
        final int maxAttempts = 3;
        int attemptsUsed = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attemptsUsed = attempt;
            boolean xmodemPhase = false;
            try {
                sendCommand(CMD_BATCH_DATA, String.valueOf(batch.length));

                waitForCommand(CMD_ACK);

                xmodemInProgress.set(true);
                xmodemPhase = true;
                boolean success;
                try {
                    success = xmodem.send(batch);
                } finally {
                    xmodemInProgress.set(false);
                }

                if (success) {
                    return true;
                }
            } catch (IOException e) {
                // lastFailure tracking is unnecessary here: sendBatch returns false rather than
                // reporting a cause, and the detail is not surfaced.
            }

            if (xmodemPhase) {
                // XMODEM-phase failure: cancel the peer's blocked xmodem.receive() and stop.
                try {
                    sendTransferCancel();
                } catch (IOException ignored) {
                }
                try {
                    serialPort.clearInputBuffer();
                } catch (IOException ignored) {
                }
                break;
            }

            // Command/ACK-phase failure: safe to retry.
            try {
                serialPort.clearInputBuffer();
            } catch (IOException ignored) {
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // All attempts failed. Notify the receiver so it exits any XMODEM receive loop still
        // pending from a command-phase failure (an XMODEM-phase failure already sent a cancel).
        try {
            sendError("Batch transfer failed after " + attemptsUsed + " attempt(s)");
        } catch (IOException ignored) {
            // Best-effort; if this also fails, receiver will eventually timeout
        }
        return false;
    }

    /**
     * Receive a batch transfer initiated by {@link #sendBatch(File, List, int,
     * BatchTransferSession.BatchProgressCallback, File)}. A single entry whose write fails aborts
     * the whole batch (legacy behavior).
     *
     * @param expectedSize announced batch size in bytes
     * @param batchProgressCallback called after each file is decoded and written; may be null
     * @param baseDir base directory to extract files under
     * @return number of files written
     */
    public int receiveBatch(
            int expectedSize,
            int totalEntries,
            BatchTransferSession.BatchProgressCallback batchProgressCallback,
            File baseDir)
            throws IOException {
        return receiveBatch(expectedSize, totalEntries, batchProgressCallback, baseDir, null);
    }

    /**
     * Receive a batch transfer initiated by {@link #sendBatch(File, List, int,
     * BatchTransferSession.BatchProgressCallback, File)}. Entries whose write fails (e.g. the
     * target file is locked by another program) are reported through the failure handler and the
     * remaining entries are still written.
     *
     * @param expectedSize announced batch size in bytes
     * @param batchProgressCallback called after each file is decoded and written; may be null
     * @param baseDir base directory to extract files under
     * @param failureHandler called when a single entry cannot be written; may be null
     * @return number of files written
     */
    public int receiveBatch(
            int expectedSize,
            int totalEntries,
            BatchTransferSession.BatchProgressCallback batchProgressCallback,
            File baseDir,
            BatchTransferSession.WriteFailureHandler failureHandler)
            throws IOException {
        xmodemInProgress.set(true);
        byte[] batch;
        try {
            batch = xmodem.receive(expectedSize);
        } finally {
            xmodemInProgress.set(false);
        }
        if (batch == null) {
            throw new IOException("Failed to receive batch: " + xmodem.getLastErrorMessage());
        }

        return BatchTransferSession.decodeAndWriteBatch(
                baseDir, batch, totalEntries, batchProgressCallback, failureHandler);
    }

    /** Request a specific file */
    public void requestFile(String relativePath) throws IOException {
        sendCommand(CMD_FILE_REQ, relativePath);
    }

    /**
     * Send file data. Performs limited retries around the underlying XMODEM transfer so that
     * transient handshake issues do not abort the entire sync. The sender includes its lastModified
     * timestamp so the receiver can preserve it and avoid unnecessary re-syncs in fast mode.
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
        CompressionUtil.CompressedData compressedData =
                CompressionUtil.compressIfBeneficial(file.getName(), data);
        boolean wasCompressed = compressedData.isCompressed();
        long lastModified = file.lastModified();

        // Retry only the command/ACK handshake; an XMODEM-phase failure is terminal because the
        // receiver may be blocked in xmodem.receive() and a re-sent command would be consumed as
        // XMODEM data. See sendFileDelta for the full rationale.
        final int maxAttempts = 3;
        int attemptsUsed = 0;
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attemptsUsed = attempt;
            boolean xmodemPhase = false;
            try {
                // Send file header for this attempt
                sendCommand(
                        CMD_FILE_DATA,
                        relativePath,
                        String.valueOf(compressedData.getData().length),
                        String.valueOf(wasCompressed),
                        String.valueOf(lastModified));

                // Wait for receiver ACK to ensure proper synchronization
                waitForCommand(CMD_ACK);

                // Send file data via XMODEM
                xmodemInProgress.set(true);
                xmodemPhase = true;
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
                // Failed attempt - continue to cleanup and retry/cancel logic below
                lastFailure = e;
            }

            if (xmodemPhase) {
                // XMODEM-phase failure: cancel the peer's blocked xmodem.receive() and stop.
                try {
                    sendTransferCancel();
                } catch (IOException ignored) {
                }
                try {
                    serialPort.clearInputBuffer();
                } catch (IOException ignored) {
                }
                break;
            }

            // Command/ACK-phase failure: safe to retry.
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
        IOException finalEx =
                new IOException(
                        "Failed to send file "
                                + relativePath
                                + " after "
                                + attemptsUsed
                                + " attempt(s) ("
                                + detail
                                + ")");
        if (lastFailure != null) {
            finalEx.addSuppressed(lastFailure);
        }
        // Notify the receiver so it exits any XMODEM receive loop still pending from a
        // command-phase failure (an XMODEM-phase failure already sent a cancel).
        try {
            sendError("File send failed: " + relativePath);
        } catch (IOException ignored) {
            // Best-effort; if this also fails, receiver will eventually timeout
        }
        throw finalEx;
    }

    /**
     * Send file data with pre-computed content (used for merged conflict resolution). Performs
     * limited retries around the underlying XMODEM transfer so that transient handshake issues do
     * not abort the entire sync.
     *
     * @param baseDir the base directory containing the file
     * @param relativePath the relative path within the base directory
     * @param content the pre-computed file content to send (e.g., merged content)
     * @return true if file was compressed, false otherwise
     */
    public boolean sendFile(File baseDir, String relativePath, byte[] content) throws IOException {
        return sendFile(baseDir, relativePath, content, System.currentTimeMillis());
    }

    /**
     * Send file data with pre-computed content and explicit lastModified. Use when the local file
     * was just written so sender and receiver share the same timestamp and the next sync does not
     * re-detect a conflict (fast mode).
     *
     * @param baseDir the base directory containing the file
     * @param relativePath the relative path within the base directory
     * @param content the pre-computed file content to send (e.g., merged content)
     * @param lastModified timestamp to send; use file.lastModified() when file was just written
     * @return true if file was compressed, false otherwise
     */
    public boolean sendFile(File baseDir, String relativePath, byte[] content, long lastModified)
            throws IOException {
        if (content == null) {
            sendCommand(CMD_ERROR, "File content is null: " + relativePath);
            return false;
        }

        // Smart compression based on content analysis
        CompressionUtil.CompressedData compressedData =
                CompressionUtil.compressIfBeneficial(relativePath, content);
        boolean wasCompressed = compressedData.isCompressed();
        long ts = lastModified > 0 ? lastModified : System.currentTimeMillis();

        // Retry only the command/ACK handshake; an XMODEM-phase failure is terminal because the
        // receiver may be blocked in xmodem.receive() and a re-sent command would be consumed as
        // XMODEM data. See sendFileDelta for the full rationale.
        final int maxAttempts = 3;
        int attemptsUsed = 0;
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attemptsUsed = attempt;
            boolean xmodemPhase = false;
            try {
                sendCommand(
                        CMD_FILE_DATA,
                        relativePath,
                        String.valueOf(compressedData.getData().length),
                        String.valueOf(wasCompressed),
                        String.valueOf(ts));

                waitForCommand(CMD_ACK);

                xmodemInProgress.set(true);
                xmodemPhase = true;
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
                lastFailure = e;
            }

            if (xmodemPhase) {
                // XMODEM-phase failure: cancel the peer's blocked xmodem.receive() and stop.
                try {
                    sendTransferCancel();
                } catch (IOException ignored) {
                }
                try {
                    serialPort.clearInputBuffer();
                } catch (IOException ignored) {
                }
                break;
            }

            // Command/ACK-phase failure: safe to retry.
            try {
                serialPort.clearInputBuffer();
            } catch (IOException e) {
            }

            if (attempt < maxAttempts) {
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
        IOException finalEx =
                new IOException(
                        "Failed to send merged file "
                                + relativePath
                                + " after "
                                + attemptsUsed
                                + " attempt(s) ("
                                + detail
                                + ")");
        if (lastFailure != null) {
            finalEx.addSuppressed(lastFailure);
        }
        // Notify the receiver so it exits any XMODEM receive loop still pending from a
        // command-phase failure (an XMODEM-phase failure already sent a cancel).
        try {
            sendError("File send failed: " + relativePath);
        } catch (IOException ignored) {
            // Best-effort; if this also fails, receiver will eventually timeout
        }
        throw finalEx;
    }

    /** Send a single dropped file to the peer. */
    public void sendDropFile(File file) throws IOException {
        if (file == null) {
            throw new IOException("Cannot send a null file");
        }
        if (!file.exists() || !file.isFile()) {
            throw new IOException("Drop file not found or not a file: " + file.getAbsolutePath());
        }

        String fileName = sanitizeDropFileName(file.getName());
        byte[] data = readFileContent(file);
        CompressionUtil.CompressedData compressedData =
                CompressionUtil.compressIfBeneficial(fileName, data);
        sendCommand(
                CMD_DROP_FILE,
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
                throw new IOException(
                        "Failed to send dropped file " + fileName + " (" + detail + ")");
            }
        } finally {
            xmodemInProgress.set(false);
        }
    }

    /** Receive file data and save to directory */
    public void receiveFile(
            File baseDir,
            String relativePath,
            int expectedSize,
            boolean compressed,
            long lastModified)
            throws IOException {
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
            throw new IOException(
                    "Failed to receive file data for " + relativePath + " (" + detail + ")");
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

        // Write file; a failure here (e.g. target locked by another program) is surfaced as a
        // FileWriteException carrying the payload so the caller can queue a later retry instead of
        // tearing down the connection.
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(data);
        } catch (IOException e) {
            throw new FileWriteException(relativePath, data, lastModified, e.getMessage(), e);
        }

        // Preserve sender timestamp so subsequent manifest comparisons match
        if (lastModified > 0) {
            targetFile.setLastModified(lastModified);
        }
    }

    /** Receive a dropped file and save it to the Downloads directory. */
    public File receiveDropFile(
            File downloadsDir, String originalFileName, int expectedSize, boolean compressed)
            throws IOException {
        if (downloadsDir == null) {
            throw new IOException("Downloads folder is not configured");
        }
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw new IOException(
                    "Failed to create Downloads directory: " + downloadsDir.getAbsolutePath());
        }
        if (!downloadsDir.isDirectory()) {
            throw new IOException(
                    "Downloads path is not a directory: " + downloadsDir.getAbsolutePath());
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
            throw new IOException(
                    "Failed to receive dropped file " + fileName + " (" + detail + ")");
        }

        // Verify sender-reported size before any transformation or disk write
        try {
            validateReceivedSize("dropped file", fileName, expectedSize, data);
        } catch (IOException e) {
            // Send ERROR so sender does not timeout waiting for next command.
            // ACK was already sent above (sender is waiting for protocol response,
            // not another XMODEM packet-level ACK).
            sendError("Size mismatch for dropped file: " + e.getMessage());
            throw e;
        }

        if (compressed) {
            data = CompressionUtil.decompress(data);
        }

        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(data);
        }

        return targetFile;
    }

    /** Send sync complete notification */
    public void sendSyncComplete() throws IOException {
        sendCommand(CMD_SYNC_COMPLETE);
    }

    /**
     * Encode a folder path for protocol transmission (Base64) so colons and separators do not break
     * framing.
     */
    public static String encodePathForProtocol(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return new String(
                BASE64_ENCODER.encode(path.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
    }

    /** Decode a folder path from protocol transmission. */
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

    /** Request folder context from remote (sender asks receiver for its sync folder path). */
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
     * Send folder change notification to remote receiver. The receiver should look up the mapped
     * folder and switch to it.
     *
     * @param folderPath local sync folder path (will be Base64 encoded)
     */
    public void sendFolderChange(String folderPath) throws IOException {
        String encoded = encodePathForProtocol(folderPath);
        sendCommand(CMD_FOLDER_CHANGE, encoded);
    }

    /**
     * Wait for folder context response and return decoded remote folder path. Call after
     * sendFolderContextRequest().
     */
    public String receiveFolderContextResponse() throws IOException {
        Message msg = waitForCommand(CMD_FOLDER_CONTEXT_DATA);
        if (msg == null || msg.getParams().length == 0) {
            return "";
        }
        return decodePathFromProtocol(msg.getParam(0));
    }

    /**
     * Send file content response for conflict resolution. The content is Base64 encoded and sent
     * inline within the protocol message.
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
     * Wait for file content response and return Base64-encoded content. Call after sending
     * CMD_FILE_CONTENT_REQ.
     *
     * @param timeoutMs maximum time to wait in milliseconds
     * @return Base64-encoded file content, or null if timeout/error
     */
    public String waitForFileContentResponse(long timeoutMs) throws IOException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            Message msg = receiveCommand();
            if (msg == null) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }
                continue;
            }
            String cmd = msg.getCommand();
            if (CMD_FILE_CONTENT_DATA.equals(cmd)) {
                return msg.getParam(1);
            }
            if (CMD_CANCEL.equals(cmd)) {
                return null; // User cancelled, return null gracefully
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
            } else {
                stashAsyncMessage(msg);
            }
        }
        return null;
    }

    /**
     * Send file content via XMODEM for conflict resolution (large files). Sends a
     * CMD_FILE_CONTENT_XFER with the file size, waits for ACK, then transfers via XMODEM.
     *
     * @param data the file content bytes
     * @param fileSize the exact file size in bytes
     */
    public void sendFileContentViaXmodem(byte[] data, int fileSize) throws IOException {
        sendCommand(CMD_FILE_CONTENT_XFER, String.valueOf(fileSize));
        waitForCommand(CMD_ACK);
        xmodemInProgress.set(true);
        try {
            boolean success = xmodem.send(data);
            if (!success) {
                String detail = xmodem.getLastErrorMessage();
                if (detail == null || detail.isEmpty()) {
                    detail = "unknown XMODEM error";
                }
                throw new IOException("Failed to send file content via XMODEM (" + detail + ")");
            }
        } finally {
            xmodemInProgress.set(false);
        }
    }

    /**
     * Receive file content via XMODEM for conflict resolution. Sends ACK, then receives via XMODEM.
     *
     * @param expectedSize the expected file size in bytes
     * @return the file content bytes
     */
    public byte[] receiveFileContentViaXmodem(int expectedSize) throws IOException {
        sendAck();
        xmodemInProgress.set(true);
        try {
            byte[] data = xmodem.receive(expectedSize);
            if (data == null) {
                String detail = xmodem.getLastErrorMessage();
                if (detail == null || detail.isEmpty()) {
                    detail = "no detailed XMODEM error available";
                }
                throw new IOException("Failed to receive file content via XMODEM (" + detail + ")");
            }
            return data;
        } finally {
            xmodemInProgress.set(false);
        }
    }

    /** Request the remote peer's log text (used by the combined-log save). */
    public void sendLogRequest() throws IOException {
        sendCommand(CMD_LOG_REQ);
    }

    /**
     * Ask the remote peer to log a TIME-SYNC marker before its log is fetched, so the combined-log
     * save can align the two machines' clocks. The peer answers with an ACK once the marker has
     * been written to its log mirror.
     */
    public void sendLogMarkerRequest() throws IOException {
        sendCommand(CMD_LOG_MARKER_REQ);
    }

    /** Send the log text inline, Base64 encoded, as a CMD_LOG_DATA response. */
    public void sendLogData(String base64Log) throws IOException {
        sendCommand(CMD_LOG_DATA, base64Log);
    }

    /**
     * Send the log text via XMODEM for large logs. Sends a CMD_LOG_XFER announcement with the exact
     * size, waits for ACK, then transfers via XMODEM (mirrors {@link #sendFileContentViaXmodem}).
     *
     * @param data the log text bytes
     * @param logSize the exact log size in bytes
     */
    public void sendLogViaXmodem(byte[] data, int logSize) throws IOException {
        sendCommand(CMD_LOG_XFER, String.valueOf(logSize));
        waitForCommand(CMD_ACK);
        xmodemInProgress.set(true);
        try {
            boolean success = xmodem.send(data);
            if (!success) {
                String detail = xmodem.getLastErrorMessage();
                if (detail == null || detail.isEmpty()) {
                    detail = "unknown XMODEM error";
                }
                throw new IOException("Failed to send log via XMODEM (" + detail + ")");
            }
        } finally {
            xmodemInProgress.set(false);
        }
    }

    /** Send direction change notification */
    public void sendDirectionChange(boolean isSender) throws IOException {
        sendCommand(CMD_DIRECTION_CHANGE, String.valueOf(isSender));
    }

    /** Send acknowledgment */
    public void sendAck() throws IOException {
        sendCommand(CMD_ACK);
    }

    /** Send error message */
    public void sendError(String message) throws IOException {
        sendCommand(CMD_ERROR, message);
    }

    /** Notify peer that a sync was cancelled. */
    public void sendCancelCommand() throws IOException {
        sendCommand(CMD_CANCEL);
    }

    /**
     * Cancel an in-flight XMODEM transfer (control-plane cancel). Sends both the XMODEM CAN signal
     * (data-plane) and CMD_CANCEL (control-plane) to ensure the remote is notified at both levels
     * and does not timeout.
     */
    public void sendTransferCancel() throws IOException {
        xmodem.sendCancelSignal();
        sendCancelCommand();
    }

    /** Send heartbeat to check connection */
    public void sendHeartbeat() throws IOException {
        sendCommand(CMD_HEARTBEAT);
    }

    /** Send heartbeat acknowledgment */
    public void sendHeartbeatAck() throws IOException {
        sendCommand(CMD_HEARTBEAT_ACK);
    }

    /** Send disconnect notification */
    public void sendDisconnect() throws IOException {
        sendCommand(CMD_DISCONNECT);
    }

    /** Send role negotiation with priority and tie-breaker values */
    public void sendRoleNegotiate(long priority) throws IOException {
        sendRoleNegotiate(priority, 0L);
    }

    /** Send role negotiation with priority and tie-breaker values */
    public void sendRoleNegotiate(long priority, long tieBreaker) throws IOException {
        sendCommand(CMD_ROLE_NEGOTIATE, String.valueOf(priority), String.valueOf(tieBreaker));
    }

    /** Send file delete command to delete a file on remote */
    public void sendFileDelete(String relativePath) throws IOException {
        sendCommand(CMD_FILE_DELETE, relativePath);
    }

    /** Send mkdir command to create a directory on remote */
    public void sendMkdir(String relativePath) throws IOException {
        sendCommand(CMD_MKDIR, relativePath);
    }

    /** Send rmdir command to delete an empty directory on remote */
    public void sendRmdir(String relativePath) throws IOException {
        sendCommand(CMD_RMDIR, relativePath);
    }

    /** Send shared text payload (Base64 encoded to protect delimiters) */
    public void sendSharedText(String text) throws IOException {
        sendSharedText(System.currentTimeMillis(), text);
    }

    /** Send shared text payload with a last-changed timestamp. */
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
     *
     * @param expectedDataLength expected payload size in bytes
     */
    public String receiveSharedTextData(boolean wasCompressed, int expectedDataLength)
            throws IOException {
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

    /** Decode shared text payload received from remote */
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

    /** Send shared text via XMODEM with a last-changed timestamp. */
    private void sendSharedTextData(long timestamp, byte[] textBytes) throws IOException {
        CompressionUtil.CompressedData payload =
                CompressionUtil.compressIfBeneficial(SHARED_TEXT_TRANSFER_NAME, textBytes);
        xmodemInProgress.set(true);
        try {
            sendCommand(
                    CMD_SHARED_TEXT_DATA,
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

    private void validateReceivedSize(
            String transferType, String targetName, int expectedSize, byte[] actualData)
            throws IOException {
        if (expectedSize < 0 || actualData == null) {
            return;
        }
        if (actualData.length != expectedSize) {
            throw new IOException(
                    "Size mismatch while receiving "
                            + transferType
                            + " '"
                            + targetName
                            + "': expected "
                            + expectedSize
                            + " bytes, received "
                            + actualData.length
                            + " bytes");
        }
    }

    private int getSharedTextInlineEncodedLimit() {
        long bytesPerSecond = Math.max(serialPort.getBaudRate() / 10L, 1L);
        long budgetBytes = (bytesPerSecond * SHARED_TEXT_INLINE_BUDGET_MS) / 1000L;
        long framingBytes =
                START_MARKER.length() + CMD_SHARED_TEXT.length() + END_MARKER.length() + 2L;
        long limit = budgetBytes - framingBytes;
        return (int) Math.max(limit, MIN_SHARED_TEXT_INLINE_ENCODED_CHARS);
    }

    /**
     * Try to receive a command with short timeout for heartbeat check Returns null if no data
     * available or timeout
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
     * Wait for specific command. Handles HEARTBEAT and HEARTBEAT_ACK to keep liveness active during
     * long waits. Throws IOException when CMD_ERROR is received.
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
            } else {
                stashAsyncMessage(msg);
            }
        }
        throw new IOException("Timeout waiting for command: " + expectedCommand);
    }

    /**
     * Stash an async message that arrived during a synchronous exchange so the listener loop can
     * dispatch it later instead of silently dropping it.
     */
    public void stashAsyncMessage(Message msg) {
        if (msg != null) {
            stashedMessages.offer(msg);
        }
    }

    /**
     * Poll a message stashed during a synchronous exchange, or null if none. The listener loop
     * drains these before reading new data from the serial stream.
     */
    public Message pollStashedMessage() {
        return stashedMessages.poll();
    }

    /**
     * Discard stashed messages. Called on session teardown so stale messages from a previous
     * session are not delivered after a reconnect.
     */
    public void clearStashedMessages() {
        stashedMessages.clear();
    }

    private void runMessageActivityCallback() {
        if (messageActivityCallback != null) {
            messageActivityCallback.run();
        }
    }

    /**
     * For testing: invoke the message activity callback. Used by protocol subclasses to simulate
     * heartbeat handling.
     */
    protected void notifyMessageActivity() {
        runMessageActivityCallback();
    }

    /** Check if there's data available */
    public boolean hasData() throws IOException {
        return serialPort.available() > 0;
    }

    /**
     * Check if XMODEM transfer is in progress. When true, other threads should not read from serial
     * port.
     */
    public boolean isXmodemInProgress() {
        return xmodemInProgress.get();
    }

    /**
     * Reset XMODEM in-progress flag. Called after sync completes to ensure heartbeats can resume.
     */
    public void resetXmodemInProgress() {
        xmodemInProgress.set(false);
    }

    private byte[] readFileContent(File file) throws IOException {
        long fileSize = file.length();
        if (fileSize > Integer.MAX_VALUE) {
            throw new IOException(
                    "File too large: "
                            + fileSize
                            + " bytes (max: "
                            + Integer.MAX_VALUE
                            + " bytes)");
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

    /** Protocol message class */
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
                        "Invalid integer parameter at index "
                                + index
                                + " for command '"
                                + command
                                + "': "
                                + param,
                        e);
            }
        }

        private long parseLongParameter(int index) {
            String param = getRequiredParam(index, "long");
            try {
                return Long.parseLong(param);
            } catch (NumberFormatException e) {
                throw new ProtocolFieldParseException(
                        "Invalid long parameter at index "
                                + index
                                + " for command '"
                                + command
                                + "': "
                                + param,
                        e);
            }
        }

        private String getRequiredParam(int index, String expectedType) {
            String param = getParam(index);
            if (param == null || param.trim().isEmpty()) {
                throw new ProtocolFieldParseException(
                        "Missing "
                                + expectedType
                                + " parameter at index "
                                + index
                                + " for command '"
                                + command
                                + "'.");
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
        String fileName =
                requestedFileName == null || requestedFileName.trim().isEmpty()
                        ? "file"
                        : requestedFileName.trim();
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
