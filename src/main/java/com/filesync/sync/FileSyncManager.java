package com.filesync.sync;

import com.filesync.config.SettingsManager;
import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Orchestrates file synchronization operations between two machines. Delegates to dedicated
 * services for connection, negotiation, shared text, and sync coordination.
 */
public class FileSyncManager {

    private static final long INITIAL_CONNECT_TIMEOUT_MS =
            60000; // 60 seconds timeout for initial connection
    private static final long RECONNECT_DELAY_MS = 5000L;
    private static final long RECONNECT_TIMEOUT_MS = 60000L;

    /** Files above this size use XMODEM instead of inline Base64 for content requests. */
    private static final long XMODEM_CONTENT_THRESHOLD = 64 * 1024;

    private final SerialPortManager serialPort;
    private final SyncProtocol protocol;
    private final SettingsManager settings;

    private volatile File syncFolder;
    private volatile boolean strictSyncMode = false;
    private volatile boolean respectGitignoreMode = false;
    private volatile boolean fastMode = false;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicBoolean senderBlockingProtocolExchange = new AtomicBoolean(false);
    private final AtomicBoolean connectionAlive = new AtomicBoolean(false);
    private final AtomicBoolean roleNegotiated = new AtomicBoolean(false);
    private final AtomicBoolean isSender = new AtomicBoolean(true);
    private final AtomicBoolean wasManuallyDisconnected = new AtomicBoolean(false);
    private final AtomicBoolean remoteInitiatedDisconnect = new AtomicBoolean(false);
    private final AtomicBoolean syncCancelInProgress = new AtomicBoolean(false);
    private final AtomicBoolean reconnectAttempted = new AtomicBoolean(false);

    private final AtomicLong threadIdGenerator =
            new AtomicLong(0); // names FileSync-N threads in executor

    /** Provides this device's log text when the remote peer requests it. */
    private volatile Supplier<String> logTextProvider;

    /**
     * Synchronous log-marker writer (wired to {@code LogController#logMarker} by the UI layer). The
     * remote peer asks this device to log a TIME-SYNC marker via the serial listener thread, which
     * must not wait on the EDT; the sink appends to the thread-safe log mirror directly.
     */
    private volatile Consumer<String> logMarkerSink;

    private final SyncEventBus eventBus;
    private final ConnectionService connectionService;
    private final RoleNegotiationService roleNegotiationService;
    private final SharedTextService sharedTextService;
    private final PendingFileWriteService pendingFileWriteService;
    private final SyncCoordinator syncCoordinator;
    private final FileDropService fileDropService;

    private ScheduledExecutorService executor;
    private Future<?> listenerFuture;
    private volatile String lastPortName;
    private volatile ScheduledExecutorService reconnectExecutor;

    public FileSyncManager(SerialPortManager serialPort, SettingsManager settings) {
        this.serialPort = serialPort;
        this.protocol = new SyncProtocol(serialPort);
        this.settings = settings;
        this.eventBus = new SimpleSyncEventBus();

        this.connectionService =
                new ConnectionService(
                        serialPort,
                        protocol,
                        eventBus,
                        running,
                        connectionAlive,
                        syncing::get,
                        this::isProtocolExchangeBusy,
                        this::onConnectionLost,
                        this::onConnectionRestored);

        this.roleNegotiationService =
                new RoleNegotiationService(
                        protocol, eventBus, isSender, roleNegotiated, connectionAlive::get);

        this.sharedTextService =
                new SharedTextService(
                        protocol,
                        eventBus,
                        running::get,
                        connectionAlive::get,
                        syncing::get,
                        protocol::isXmodemInProgress,
                        roleNegotiationService::isRoleNegotiated);

        this.pendingFileWriteService = new PendingFileWriteService(eventBus);

        this.fileDropService =
                new FileDropService(
                        protocol,
                        eventBus,
                        running::get,
                        connectionAlive::get,
                        syncing::get,
                        protocol::isXmodemInProgress);

        this.syncCoordinator =
                new SyncCoordinator(
                        protocol,
                        eventBus,
                        this::getSyncFolder,
                        this::isStrictSyncMode,
                        this::isRespectGitignoreMode,
                        this::isFastMode,
                        connectionAlive::get,
                        roleNegotiationService::isSender,
                        roleNegotiationService::isRoleNegotiated,
                        pendingFileWriteService,
                        syncing,
                        sharedTextService::onSyncIdle,
                        sharedTextService::onSyncBoundary,
                        connectionService::recordMessageActivity);

        protocol.setMessageActivityCallback(connectionService::recordMessageActivity);

        protocol.setProgressListener(
                new XModemTransfer.TransferProgressListener() {
                    @Override
                    public void onProgress(
                            int currentBlock,
                            int totalBlocks,
                            long bytesTransferred,
                            double speedBytesPerSec) {
                        if (syncCoordinator.isSyncing()
                                || fileDropService.isTransferInProgress()
                                || protocol.isXmodemInProgress()) {
                            connectionService.recordMessageActivity();
                            eventBus.post(
                                    new SyncEvent.TransferProgressEvent(
                                            currentBlock,
                                            totalBlocks,
                                            bytesTransferred,
                                            speedBytesPerSec));
                        }
                    }

                    @Override
                    public void onError(String message) {
                        if (syncCoordinator.isSyncing()
                                || fileDropService.isTransferInProgress()
                                || protocol.isXmodemInProgress()) {
                            eventBus.post(new SyncEvent.ErrorEvent(message));
                        }
                    }
                });
    }

    public SyncEventBus getEventBus() {
        return eventBus;
    }

    public void setSyncFolder(File folder) {
        this.syncFolder = folder;
    }

    public File getSyncFolder() {
        return syncFolder;
    }

    /**
     * Registers a provider for this device's log text. The provider is invoked on the listener
     * thread when the remote peer requests the log (save combined log), so it must be safe to call
     * from any thread.
     */
    public void setLogTextProvider(Supplier<String> logTextProvider) {
        this.logTextProvider = logTextProvider;
    }

    /**
     * Sets the synchronous log-marker writer used when the remote peer asks this device to log a
     * TIME-SYNC marker (see {@link SyncProtocol#CMD_LOG_MARKER_REQ}). When no sink is set, the
     * marker is posted as a regular log event instead.
     */
    public void setLogMarkerSink(Consumer<String> logMarkerSink) {
        this.logMarkerSink = logMarkerSink;
    }

    public void setIsSender(boolean isSender) {
        roleNegotiationService.setSender(isSender);
    }

    public boolean isSender() {
        return roleNegotiationService.isSender();
    }

    public void setStrictSyncMode(boolean strictSyncMode) {
        this.strictSyncMode = strictSyncMode;
    }

    public boolean isStrictSyncMode() {
        return strictSyncMode;
    }

    public void setRespectGitignoreMode(boolean respectGitignoreMode) {
        this.respectGitignoreMode = respectGitignoreMode;
    }

    public boolean isRespectGitignoreMode() {
        return respectGitignoreMode;
    }

    public void setFastMode(boolean fastMode) {
        this.fastMode = fastMode;
    }

    public boolean isFastMode() {
        return fastMode;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isSyncing() {
        return syncCoordinator.isSyncing();
    }

    /**
     * Returns true when sync or XMODEM transfer is in progress. Direction change is not allowed
     * during this time.
     */
    public boolean isTransferBusy() {
        return syncCoordinator.isSyncing()
                || protocol.isXmodemInProgress()
                || fileDropService.isTransferInProgress();
    }

    public boolean isConnectionAlive() {
        return connectionService.isConnectionAlive();
    }

    public boolean isRoleNegotiated() {
        return roleNegotiationService.isRoleNegotiated();
    }

    public boolean wasManuallyDisconnected() {
        return wasManuallyDisconnected.get();
    }

    public boolean isReconnectInProgress() {
        ScheduledExecutorService recExec = reconnectExecutor;
        return recExec != null && !recExec.isShutdown();
    }

    public boolean confirmCurrentRoleIfNeeded(boolean isSender) {
        return roleNegotiationService.confirmCurrentRoleIfNeeded(isSender);
    }

    /**
     * Start listening for incoming sync requests.
     *
     * @param portName the serial port name (e.g. "COM3") used for re-opening on restart
     */
    public void startListening(String portName) {
        startListeningInternal(portName, true);
    }

    private void startListeningInternal(String portName, boolean isFreshConnect) {
        if (running.get()) {
            return;
        }

        if (isFreshConnect) {
            wasManuallyDisconnected.set(false);
            remoteInitiatedDisconnect.set(false);
            reconnectAttempted.set(false);
        }
        this.lastPortName = portName;
        running.set(true);
        connectionAlive.set(false);
        roleNegotiated.set(false);
        syncing.set(false);
        protocol.clearStashedMessages();

        ensureExecutor();
        connectionService.setExecutor(executor);
        syncCoordinator.setExecutor(executor);
        connectionService.start();

        listenerFuture = executor.submit(this::listenLoop);
    }

    /**
     * Wait for initial connection with the other side (waits for heartbeat response).
     *
     * @param timeoutMs timeout in milliseconds
     * @return true if connected, false if timeout
     */
    public boolean waitForConnection(long timeoutMs) {
        return connectionService.waitForConnection(timeoutMs);
    }

    /** Stop listening and tear down background tasks. */
    public void stopListening() {
        running.set(false);
        connectionAlive.set(false);
        roleNegotiated.set(false);
        syncing.set(false);
        connectionService.stop();
        sharedTextService.clearPendingSharedText();
        if (pendingFileWriteService.getPendingCount() > 0) {
            eventBus.post(
                    new SyncEvent.LogEvent(
                            pendingFileWriteService.getPendingCount()
                                    + " file(s) still locked by another program;"
                                    + " they will be re-synced on the next sync: "
                                    + String.join(
                                            ", ", pendingFileWriteService.getPendingPaths())));
        }
        protocol.clearStashedMessages();
        serialPort.close();

        if (listenerFuture != null) {
            listenerFuture.cancel(true);
            listenerFuture = null;
        }

        shutdownExecutor();
    }

    /**
     * Stop and immediately restart listening on the same serial port. Used for cancelling an
     * ongoing sync -- behaves like disconnect followed by reconnect, resetting all protocol state.
     */
    public void restartListening() {
        String portToReopen = this.lastPortName;
        stopListening();
        if (portToReopen != null && serialPort.open(portToReopen)) {
            startListening(portToReopen);
        }
        syncCancelInProgress.set(false);
    }

    /**
     * Disconnect from the connected peer and stop local sync services.
     *
     * @param notifyRemote when true, send a best-effort disconnect notification before teardown
     */
    public void disconnect(boolean notifyRemote) {
        wasManuallyDisconnected.set(true);
        cancelPendingReconnect();
        if (notifyRemote && running.get() && serialPort.isOpen()) {
            try {
                protocol.sendDisconnect();
            } catch (IOException e) {
                eventBus.post(
                        new SyncEvent.LogEvent(
                                "Failed to send disconnect notification: " + e.getMessage()));
            }
        }
        stopListening();
    }

    /** Send shared text to remote. */
    public void sendSharedText(String text) {
        sharedTextService.queueSharedText(text);
    }

    public void sendDropFile(File file) {
        fileDropService.sendDropFile(file);
    }

    /** Retry writing the given pending files (user chose "Retry" in the pending-write dialog). */
    public void retryPendingWrites(List<String> relativePaths) {
        pendingFileWriteService.retry(relativePaths);
    }

    /** Skip writing the given pending files (user chose "Skip"). */
    public void skipPendingWrites(List<String> relativePaths) {
        pendingFileWriteService.skip(relativePaths);
    }

    /** Skip all pending files (user chose "Skip All"). */
    public void skipAllPendingWrites() {
        pendingFileWriteService.skipAll();
    }

    /** Initiate synchronization as sender. */
    public void initiateSync() {
        syncCoordinator.startSync();
    }

    public void initiateSync(SyncPreviewPlan plan) {
        syncCoordinator.startSync(plan);
    }

    /**
     * Initiate synchronization using a pre-computed preview plan. Skips manifest roundtrip when
     * plan is valid for current state.
     */
    public void initiateSyncWithPlan(SyncPreviewPlan plan) {
        initiateSync(plan);
    }

    public void cancelSync() {
        syncCoordinator.cancelOngoingSync();
        syncCancelInProgress.set(true);
        // Notify the remote receiver so it aborts its blocking xmodem.receive() and resets its
        // connection via the CMD_CANCEL handler (which calls restartListening()). Without this,
        // the receiver's connection-loss detection stays suppressed while transferBusy is true, so
        // it can only recover via a slow XMODEM timeout and an implicit (fragile) stream resync
        // that
        // can leave it unable to reconnect without a program restart.
        if (serialPort.isOpen()) {
            try {
                protocol.sendTransferCancel();
            } catch (IOException e) {
                eventBus.post(
                        new SyncEvent.LogEvent(
                                "Failed to send cancel notification to remote: " + e.getMessage()));
            }
        }
        restartListening();
    }

    private static final int FOLDER_CONTEXT_TIMEOUT_MS = 5000;

    /**
     * Request remote folder context from the peer (receiver). Call only when this side is sender.
     * Pauses listener during exchange to avoid message stealing.
     *
     * @return remote sync folder path (normalized), or empty string on timeout/error
     */
    public String requestRemoteFolderContext() {
        if (!roleNegotiationService.isSender()) {
            return "";
        }
        File folder = getSyncFolder();
        if (folder == null || !folder.exists()) {
            return "";
        }
        senderBlockingProtocolExchange.set(true);
        try {
            int savedTimeout = protocol.getTimeout();
            protocol.setTimeout(FOLDER_CONTEXT_TIMEOUT_MS);
            try {
                protocol.sendFolderContextRequest();
                return protocol.receiveFolderContextResponse();
            } finally {
                protocol.setTimeout(savedTimeout);
            }
        } catch (IOException e) {
            return "";
        } finally {
            senderBlockingProtocolExchange.set(false);
            connectionService.recordMessageActivity();
        }
    }

    private void handleFolderContextRequest() throws IOException {
        File folder = getSyncFolder();
        String path =
                (folder != null && folder.exists())
                        ? SettingsManager.normalizeFolderPath(folder.getAbsolutePath())
                        : "";
        protocol.sendFolderContextResponse(path);
    }

    /**
     * Handles a remote TIME-SYNC marker request: log the marker (synchronously into the log mirror
     * when a sink is wired, so it is guaranteed present when the requester fetches this device's
     * log), then ACK so the requester knows the marker is in place.
     */
    private void handleLogMarkerRequest() throws IOException {
        if (logMarkerSink != null) {
            logMarkerSink.accept(TimeSyncMarker.markerMessage());
        } else {
            eventBus.post(new SyncEvent.LogEvent(TimeSyncMarker.markerMessage()));
        }
        protocol.sendAck();
    }

    private void handleLogRequest() throws IOException {
        String logText = logTextProvider != null ? logTextProvider.get() : "";
        if (logText == null) {
            logText = "";
        }
        byte[] logBytes = logText.getBytes(StandardCharsets.UTF_8);
        try {
            if (logBytes.length > XMODEM_CONTENT_THRESHOLD) {
                eventBus.post(
                        new SyncEvent.LogEvent(
                                "Sending large log via XMODEM (" + logBytes.length + " bytes)"));
                protocol.sendLogViaXmodem(logBytes, logBytes.length);
                // XMODEM progress events disable the sync controls while the transfer is in
                // flight; refresh them now that the transfer has completed.
                eventBus.post(new SyncEvent.SyncControlRefreshEvent());
            } else {
                protocol.sendLogData(Base64.getEncoder().encodeToString(logBytes));
            }
        } catch (IOException e) {
            eventBus.post(new SyncEvent.LogEvent("Failed to send log: " + e.getMessage()));
        }
    }

    private void handleFolderChange(String encodedPath) {
        // Only receiver should process folder change notifications
        if (roleNegotiationService.isSender()) {
            return;
        }
        // Sender sends the receiver path directly (sender has the mapping, receiver does not)
        String receiverFolder = SyncProtocol.decodePathFromProtocol(encodedPath);
        if (receiverFolder == null || receiverFolder.isEmpty()) {
            return;
        }
        File folder = new File(receiverFolder);
        if (folder.exists() && folder.isDirectory()) {
            setSyncFolder(folder);
            eventBus.post(new SyncEvent.RemoteFolderChangedEvent(receiverFolder));
        }
    }

    public SyncPreviewPlan previewSync() {
        if (!isSender()) {
            throw new IllegalStateException(
                    "Cannot initiate sync preview as receiver. Change direction first.");
        }
        if (!connectionAlive.get()) {
            throw new IllegalStateException("Cannot preview sync while disconnected");
        }
        if (!roleNegotiated.get()) {
            throw new IllegalStateException("Cannot preview sync until role negotiation completes");
        }
        if (syncing.get()) {
            throw new IllegalStateException("Sync already in progress");
        }
        if (getSyncFolder() == null || !getSyncFolder().exists()) {
            throw new IllegalStateException("Please select a sync folder first");
        }
        senderBlockingProtocolExchange.set(true);
        try {
            return syncCoordinator.createSyncPreviewPlan();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build sync preview: " + e.getMessage(), e);
        } finally {
            senderBlockingProtocolExchange.set(false);
            connectionService.recordMessageActivity();
        }
    }

    /** Notify remote of direction change. */
    public void notifyDirectionChange() {
        roleNegotiationService.notifyDirectionChange();
    }

    /**
     * Fetch remote file content for conflict resolution during preview. Sends a request to the
     * receiver (which is the "remote" during preview as sender) to get the content of a file that
     * has a conflict.
     *
     * @param relativePath the relative path of the file to fetch
     * @return the file content bytes, or null if unavailable/timeout/error
     */
    public byte[] fetchRemoteFileContent(String relativePath) {
        if (!isSender() || !connectionAlive.get() || syncFolder == null) {
            return null;
        }

        final long TIMEOUT_MS =
                10000; // 10 seconds - may need adjustment for slow serial connections
        senderBlockingProtocolExchange.set(true);

        try {
            protocol.sendCommand(
                    SyncProtocol.CMD_FILE_CONTENT_REQ,
                    SyncProtocol.encodePathForProtocol(relativePath));

            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
                SyncProtocol.Message msg = protocol.receiveCommand();
                if (msg == null) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                String cmd = msg.getCommand();
                if (SyncProtocol.CMD_FILE_CONTENT_DATA.equals(cmd)) {
                    String contentBase64 = msg.getParam(1);
                    if (contentBase64 != null && !contentBase64.isEmpty()) {
                        return Base64.getDecoder().decode(contentBase64);
                    }
                    return null;
                }
                if (SyncProtocol.CMD_FILE_CONTENT_XFER.equals(cmd)) {
                    int fileSize = msg.getParamAsInt(0);
                    byte[] content = protocol.receiveFileContentViaXmodem(fileSize);
                    // XMODEM progress events disable the sync controls while the transfer is in
                    // flight; refresh them now that the transfer has completed.
                    eventBus.post(new SyncEvent.SyncControlRefreshEvent());
                    return content;
                }
                if (SyncProtocol.CMD_CANCEL.equals(cmd)) {
                    return null;
                }
                if (SyncProtocol.CMD_ERROR.equals(cmd)) {
                    String errMsg = msg.getParams().length > 0 ? msg.getParam(0) : "unknown";
                    throw new IOException("Remote error during file content request: " + errMsg);
                }
                if (SyncProtocol.CMD_HEARTBEAT.equals(cmd)) {
                    protocol.sendHeartbeatAck();
                    connectionService.recordMessageActivity();
                } else if (SyncProtocol.CMD_HEARTBEAT_ACK.equals(cmd)) {
                    connectionService.recordMessageActivity();
                } else {
                    protocol.stashAsyncMessage(msg);
                }
            }
        } catch (IOException e) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Failed to fetch remote file content: " + e.getMessage()));
        } finally {
            senderBlockingProtocolExchange.set(false);
        }
        return null;
    }

    /** Default timeout for a remote log fetch (mirrors the file content fetch). */
    private static final long LOG_FETCH_TIMEOUT_MS = 10000;

    /**
     * Fetch the remote peer's log text for the "save combined log" feature. Call only when this
     * side is the sender (same protocol-exchange constraint as {@link #fetchRemoteFileContent}).
     * Pauses the listener during the exchange to avoid message stealing.
     *
     * @return the remote log text, or null on timeout/error/guard failure
     */
    public String fetchRemoteLogText() {
        return fetchRemoteLogText(LOG_FETCH_TIMEOUT_MS);
    }

    String fetchRemoteLogText(long timeoutMs) {
        if (!isSender() || !connectionAlive.get()) {
            return null;
        }

        senderBlockingProtocolExchange.set(true);
        try {
            // Ask the remote peer to log a TIME-SYNC marker before its log is fetched, so the
            // combined-log save can align the two machines' clocks. Best-effort: a peer that does
            // not ACK within the timeout (or an IO failure) falls back to a marker-less merge.
            try {
                protocol.sendLogMarkerRequest();
                long markerDeadline = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < markerDeadline) {
                    SyncProtocol.Message markerResponse = protocol.receiveCommand();
                    if (markerResponse == null) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }
                    if (SyncProtocol.CMD_ACK.equals(markerResponse.getCommand())) {
                        break;
                    }
                    // Any other frame during the marker exchange is not part of the log fetch;
                    // ignore it rather than stash it (the fetch loop below never replays stashed
                    // messages).
                }
            } catch (IOException e) {
                // Fall back to fetching without a marker; the merge will use raw timestamps.
            }

            protocol.sendCommand(SyncProtocol.CMD_LOG_REQ);

            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                SyncProtocol.Message msg = protocol.receiveCommand();
                if (msg == null) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                String cmd = msg.getCommand();
                if (SyncProtocol.CMD_LOG_DATA.equals(cmd)) {
                    String logBase64 = msg.getParam(0);
                    if (logBase64 == null) {
                        return null;
                    }
                    if (logBase64.isEmpty()) {
                        return "";
                    }
                    return new String(
                            Base64.getDecoder().decode(logBase64), StandardCharsets.UTF_8);
                }
                if (SyncProtocol.CMD_LOG_XFER.equals(cmd)) {
                    int logSize = msg.getParamAsInt(0);
                    byte[] data = protocol.receiveFileContentViaXmodem(logSize);
                    // XMODEM progress events disable the sync controls while the transfer is in
                    // flight; refresh them now that the transfer has completed.
                    eventBus.post(new SyncEvent.SyncControlRefreshEvent());
                    return data != null ? new String(data, StandardCharsets.UTF_8) : null;
                }
                if (SyncProtocol.CMD_CANCEL.equals(cmd)) {
                    return null;
                }
                if (SyncProtocol.CMD_ERROR.equals(cmd)) {
                    String errMsg = msg.getParams().length > 0 ? msg.getParam(0) : "unknown";
                    throw new IOException("Remote error during log request: " + errMsg);
                }
                if (SyncProtocol.CMD_HEARTBEAT.equals(cmd)) {
                    protocol.sendHeartbeatAck();
                    connectionService.recordMessageActivity();
                } else if (SyncProtocol.CMD_HEARTBEAT_ACK.equals(cmd)) {
                    connectionService.recordMessageActivity();
                } else {
                    protocol.stashAsyncMessage(msg);
                }
            }
        } catch (IOException e) {
            eventBus.post(
                    new SyncEvent.ErrorEvent("Failed to fetch remote log: " + e.getMessage()));
        } finally {
            senderBlockingProtocolExchange.set(false);
        }
        return null;
    }

    /**
     * Notify remote (receiver) that the sender folder has changed. Looks up the mapped receiver
     * path and sends it; the receiver has no mapping stored (only sender stores it after sync), so
     * we send the target path directly.
     *
     * <p>Note: This is sender-initiated only. The receiver cannot notify the sender of folder
     * changes - if both sides change folders simultaneously, there is no conflict resolution.
     *
     * @param senderFolderPath the new sender folder path (absolute)
     */
    public void notifyFolderChange(String senderFolderPath) {
        if (!isSender() || !isConnectionAlive()) {
            return;
        }
        String port = serialPort.getPortName();
        if (port == null) {
            port = "";
        }
        String receiverFolder = settings.findReceiverFolderForSender(senderFolderPath, port);
        if (receiverFolder == null || receiverFolder.isEmpty()) {
            return;
        }
        try {
            protocol.sendFolderChange(receiverFolder);
        } catch (IOException e) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Failed to send folder change notification: " + e.getMessage()));
        }
    }

    /** Get initial connection timeout value. */
    public static long getInitialConnectTimeoutMs() {
        return INITIAL_CONNECT_TIMEOUT_MS;
    }

    private void listenLoop() {
        while (running.get()) {
            try {
                if (!serialPort.isOpen()) {
                    Thread.sleep(500);
                    continue;
                }

                if (protocol.isXmodemInProgress()) {
                    Thread.sleep(100);
                    continue;
                }

                // Important: During an outgoing sync (this side is the sender), the sync thread
                // synchronously waits for specific protocol messages (e.g. ACK / MANIFEST_DATA)
                // using SyncProtocol.waitForCommand(), which reads from the same serial stream.
                // If this listener loop also reads at the same time, it can "steal" those ACKs,
                // causing the sender to never start XMODEM and the receiver to hit
                // "no response from sender after 10 handshake attempts".
                //
                // To avoid concurrent consumption of the command stream, pause this listener
                // while we are actively sending a sync or doing a blocking protocol exchange (e.g.
                // folder context).
                if ((syncCoordinator.isSyncing() || senderBlockingProtocolExchange.get())
                        && roleNegotiationService.isSender()) {
                    Thread.sleep(50);
                    continue;
                }

                // Dispatch messages stashed by synchronous exchanges (waitForCommand etc.)
                // before reading new data, so async commands (e.g. SHARED_TEXT) that
                // arrived mid-exchange are not lost.
                SyncProtocol.Message stashed;
                while ((stashed = protocol.pollStashedMessage()) != null) {
                    connectionService.recordMessageActivity();
                    handleIncomingMessage(stashed);
                }

                if (protocol.hasData()) {
                    SyncProtocol.Message msg = protocol.receiveCommand();
                    if (msg != null) {
                        connectionService.recordMessageActivity();
                        handleIncomingMessage(msg);
                    }
                } else {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (SyncProtocol.Message.ProtocolFieldParseException e) {
                if (running.get()) {
                    eventBus.post(
                            new SyncEvent.ErrorEvent("Protocol parse error: " + e.getMessage()));
                    try {
                        protocol.sendError("Protocol parse error");
                    } catch (IOException ignored) {
                        // Ignore send failures while handling malformed inbound messages.
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    eventBus.post(
                            new SyncEvent.ErrorEvent("Communication error: " + e.getMessage()));
                    connectionService.reportCommunicationFailure(
                            "Connection lost - communication error: " + e.getMessage());
                }
            }
        }
    }

    private void handleIncomingMessage(SyncProtocol.Message msg) throws IOException {
        switch (msg.getCommand()) {
            case SyncProtocol.CMD_MANIFEST_REQ -> {
                Boolean senderRespectGitignore = null;
                Boolean senderFastMode = null;
                if (msg.getParams().length >= 2) {
                    senderRespectGitignore = msg.getParamAsBoolean(0);
                    senderFastMode = msg.getParamAsBoolean(1);
                }
                syncCoordinator.handleManifestRequest(senderRespectGitignore, senderFastMode);
            }
            case SyncProtocol.CMD_FOLDER_CONTEXT_REQ -> handleFolderContextRequest();
            case SyncProtocol.CMD_FOLDER_CHANGE -> handleFolderChange(msg.getParam(0));
            case SyncProtocol.CMD_LOG_MARKER_REQ -> handleLogMarkerRequest();
            case SyncProtocol.CMD_LOG_REQ -> handleLogRequest();
            case SyncProtocol.CMD_FILE_CONTENT_REQ -> {
                String relativePath = SyncProtocol.decodePathFromProtocol(msg.getParam(0));
                if (relativePath != null && !relativePath.isEmpty()) {
                    File file;
                    try {
                        file = SyncCoordinator.resolveSafe(syncFolder, relativePath);
                    } catch (IOException e) {
                        eventBus.post(
                                new SyncEvent.ErrorEvent(
                                        "Invalid path in content request: " + e.getMessage()));
                        return;
                    }
                    if (file.exists() && file.isFile()) {
                        try {
                            long fileSize = file.length();
                            if (fileSize > 50 * 1024 * 1024) {
                                eventBus.post(
                                        new SyncEvent.LogEvent(
                                                "File too large for content request: "
                                                        + relativePath));
                                protocol.sendError(
                                        "File too large for content request: " + relativePath);
                                return;
                            }
                            byte[] content = Files.readAllBytes(file.toPath());
                            if (fileSize > XMODEM_CONTENT_THRESHOLD) {
                                eventBus.post(
                                        new SyncEvent.LogEvent(
                                                "Sending large file via XMODEM: "
                                                        + relativePath
                                                        + " ("
                                                        + fileSize
                                                        + " bytes)"));
                                protocol.sendFileContentViaXmodem(content, (int) fileSize);
                                // XMODEM progress events disable the sync controls while the
                                // transfer is in flight; refresh them now that the transfer has
                                // completed.
                                eventBus.post(new SyncEvent.SyncControlRefreshEvent());
                            } else {
                                protocol.sendFileContentResponse(relativePath, content);
                            }
                        } catch (IOException e) {
                            eventBus.post(
                                    new SyncEvent.LogEvent(
                                            "Failed to send file content: " + e.getMessage()));
                        }
                    }
                }
            }
            case SyncProtocol.CMD_MANIFEST_DATA -> {
                // Handled in initiateSync flow
            }
            case SyncProtocol.CMD_FILE_REQ -> syncCoordinator.handleFileRequest(msg.getParam(0));
            case SyncProtocol.CMD_FILE_DATA -> syncCoordinator.handleIncomingFileData(msg);
            case SyncProtocol.CMD_DELTA_SIG_REQ ->
                    syncCoordinator.handleDeltaSigRequest(List.of(msg.getParams()));
            case SyncProtocol.CMD_FILE_DELTA -> syncCoordinator.handleIncomingFileDelta(msg);
            case SyncProtocol.CMD_BATCH_DATA -> {
                int expectedSize = msg.getParamAsInt(0);
                protocol.sendAck();
                try {
                    syncCoordinator.handleIncomingBatchUnknownTotal(expectedSize);
                } catch (IOException e) {
                    eventBus.post(
                            new SyncEvent.ErrorEvent("Batch receive failed: " + e.getMessage()));
                }
            }
            case SyncProtocol.CMD_DIRECTION_CHANGE -> {
                if (syncCoordinator.isSyncing() || protocol.isXmodemInProgress()) {
                    eventBus.post(
                            new SyncEvent.LogEvent(
                                    "Ignoring direction change during data transfer"));
                } else {
                    roleNegotiationService.handleDirectionChange(msg.getParamAsBoolean(0));
                    sharedTextService.flushIfIdle();
                }
            }
            case SyncProtocol.CMD_SYNC_COMPLETE -> syncCoordinator.handleSyncComplete();
            case SyncProtocol.CMD_ERROR -> {
                syncCoordinator.cancelOngoingSync();
                eventBus.post(new SyncEvent.ErrorEvent("Remote error: " + msg.getParam(0)));
            }
            case SyncProtocol.CMD_CANCEL -> {
                eventBus.post(
                        new SyncEvent.LogEvent("Remote cancelled sync, resetting connection"));
                restartListening();
            }
            case SyncProtocol.CMD_HEARTBEAT -> connectionService.handleHeartbeat();
            case SyncProtocol.CMD_HEARTBEAT_ACK -> connectionService.handleHeartbeatAck();
            case SyncProtocol.CMD_DISCONNECT -> {
                // The remote side intentionally closed the connection. Mark it so that
                // onConnectionLost tears down without attempting an automatic reconnect.
                remoteInitiatedDisconnect.set(true);
                connectionService.reportCommunicationFailure("Connection closed by remote");
            }
            case SyncProtocol.CMD_ROLE_NEGOTIATE -> {
                long remotePriority = msg.getParamAsLong(0);
                long remoteTieBreaker = msg.getParamAsLong(1);
                roleNegotiationService.handleRoleNegotiate(remotePriority, remoteTieBreaker);
            }
            case SyncProtocol.CMD_FILE_DELETE -> syncCoordinator.handleFileDelete(msg.getParam(0));
            case SyncProtocol.CMD_MKDIR -> syncCoordinator.handleMkdir(msg.getParam(0));
            case SyncProtocol.CMD_RMDIR -> syncCoordinator.handleRmdir(msg.getParam(0));
            case SyncProtocol.CMD_SHARED_TEXT -> {
                if (msg.getParams().length >= 2) {
                    sharedTextService.handleIncomingSharedText(
                            msg.getParamAsLong(0), msg.getParam(1));
                } else {
                    sharedTextService.handleIncomingSharedText(msg.getParam(0));
                }
            }
            case SyncProtocol.CMD_SHARED_TEXT_DATA -> {
                if (msg.getParams().length >= 3) {
                    sharedTextService.handleIncomingSharedTextData(
                            msg.getParamAsLong(0), msg.getParamAsBoolean(1), msg.getParamAsInt(2));
                } else {
                    eventBus.post(new SyncEvent.ErrorEvent("Invalid shared text data message"));
                }
            }
            case SyncProtocol.CMD_DROP_FILE -> fileDropService.handleIncomingDropFile(msg);
            default -> {}
        }
    }

    private void ensureExecutor() {
        if (executor == null || executor.isShutdown()) {
            ScheduledThreadPoolExecutor exec =
                    new ScheduledThreadPoolExecutor(
                            4,
                            runnable -> {
                                Thread t = new Thread(runnable);
                                t.setName("FileSync-" + threadIdGenerator.incrementAndGet());
                                t.setDaemon(true);
                                return t;
                            });
            exec.setRemoveOnCancelPolicy(true);
            this.executor = exec;
        }
    }

    private void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    private void onConnectionRestored() {
        reconnectAttempted.set(false);
        roleNegotiationService.sendRoleNegotiation();
    }

    private void onConnectionLost() {
        if (syncCancelInProgress.get()) {
            return;
        }
        if (wasManuallyDisconnected.get() || remoteInitiatedDisconnect.get()) {
            // Either the local user disconnected, or the remote side actively closed the
            // connection. In both cases we tear down cleanly without auto-reconnecting.
            resetSyncStateForLinkTransition(false);
            stopListening();
            return;
        }
        if (reconnectAttempted.getAndSet(true)) {
            resetSyncStateForLinkTransition(false);
            stopListening();
            return;
        }

        resetSyncStateForLinkTransition(false);
        stopListening();

        String portName = this.lastPortName;
        if (portName == null) {
            return;
        }

        eventBus.post(new SyncEvent.LogEvent("Connection lost. Will try to reconnect in 5s..."));

        ScheduledExecutorService recExec =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r);
                            t.setName("FileSync-Reconnect");
                            t.setDaemon(true);
                            return t;
                        });
        this.reconnectExecutor = recExec;

        recExec.schedule(
                () -> {
                    try {
                        if (wasManuallyDisconnected.get()) {
                            return;
                        }

                        eventBus.post(
                                new SyncEvent.LogEvent(
                                        "Attempting to reconnect on " + portName + "..."));

                        serialPort.close();
                        if (!serialPort.open(portName)) {
                            eventBus.post(
                                    new SyncEvent.LogEvent(
                                            "Reconnect failed: could not open " + portName));
                            eventBus.post(new SyncEvent.ConnectionEvent(false));
                            return;
                        }

                        if (wasManuallyDisconnected.get()) {
                            serialPort.close();
                            return;
                        }

                        startListeningInternal(portName, false);

                        boolean connected =
                                connectionService.waitForConnection(RECONNECT_TIMEOUT_MS);
                        if (!connected) {
                            eventBus.post(
                                    new SyncEvent.LogEvent(
                                            "Reconnect failed: no response from remote"));
                            stopListening();
                            eventBus.post(new SyncEvent.ConnectionEvent(false));
                        }
                    } finally {
                        recExec.shutdownNow();
                    }
                },
                RECONNECT_DELAY_MS,
                TimeUnit.MILLISECONDS);
    }

    private void resetSyncStateForLinkTransition(boolean clearBufferedText) {
        if (syncing.get()) {
            syncCoordinator.cancelOngoingSync();
        }
        roleNegotiationService.resetForReconnect();
        if (clearBufferedText) {
            sharedTextService.clearPendingSharedText();
        }
    }

    private void cancelPendingReconnect() {
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
            reconnectExecutor = null;
        }
    }

    private boolean isProtocolExchangeBusy() {
        return protocol.isXmodemInProgress() || senderBlockingProtocolExchange.get();
    }
}
