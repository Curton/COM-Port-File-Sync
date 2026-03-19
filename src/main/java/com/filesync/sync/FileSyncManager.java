package com.filesync.sync;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.filesync.config.SettingsManager;
import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;

/**
 * Orchestrates file synchronization operations between two machines.
 * Delegates to dedicated services for connection, negotiation, shared text, and sync coordination.
 */
public class FileSyncManager {

    private static final long INITIAL_CONNECT_TIMEOUT_MS = 60000;  // 60 seconds timeout for initial connection

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

    private final SyncEventBus eventBus;
    private final ConnectionService connectionService;
    private final RoleNegotiationService roleNegotiationService;
    private final SharedTextService sharedTextService;
    private final SyncCoordinator syncCoordinator;
    private final FileDropService fileDropService;

    private ScheduledExecutorService executor;
    private Future<?> listenerFuture;

    public FileSyncManager(SerialPortManager serialPort, SettingsManager settings) {
        this.serialPort = serialPort;
        this.protocol = new SyncProtocol(serialPort);
        this.settings = settings;
        this.eventBus = new SimpleSyncEventBus();

        this.connectionService = new ConnectionService(
                serialPort,
                protocol,
                eventBus,
                running,
                connectionAlive,
                syncing::get,
                this::isProtocolExchangeBusy,
                this::onConnectionLost,
                this::onConnectionRestored);

        this.roleNegotiationService = new RoleNegotiationService(
                protocol,
                eventBus,
                isSender,
                roleNegotiated,
                connectionAlive::get);

        this.sharedTextService = new SharedTextService(
                protocol,
                eventBus,
                running::get,
                connectionAlive::get,
                syncing::get,
                protocol::isXmodemInProgress,
                roleNegotiationService::isRoleNegotiated);

        this.fileDropService = new FileDropService(
                protocol,
                eventBus,
                running::get,
                connectionAlive::get,
                syncing::get,
                protocol::isXmodemInProgress);

        this.syncCoordinator = new SyncCoordinator(
                protocol,
                eventBus,
                this::getSyncFolder,
                this::isStrictSyncMode,
                this::isRespectGitignoreMode,
                this::isFastMode,
                connectionAlive::get,
                roleNegotiationService::isSender,
                roleNegotiationService::isRoleNegotiated,
                syncing,
                sharedTextService::onSyncIdle,
                sharedTextService::onSyncBoundary,
                connectionService::recordMessageActivity);

        protocol.setMessageActivityCallback(connectionService::recordMessageActivity);

        protocol.setProgressListener(new XModemTransfer.TransferProgressListener() {
            @Override
            public void onProgress(int currentBlock, int totalBlocks, long bytesTransferred, double speedBytesPerSec) {
                if (syncCoordinator.isSyncing() || fileDropService.isTransferInProgress()) {
                    eventBus.post(new SyncEvent.TransferProgressEvent(
                            currentBlock,
                            totalBlocks,
                            bytesTransferred,
                            speedBytesPerSec));
                }
            }

            @Override
            public void onError(String message) {
                if (syncCoordinator.isSyncing() || fileDropService.isTransferInProgress()) {
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
     * Returns true when sync or XMODEM transfer is in progress.
     * Direction change is not allowed during this time.
     */
    public boolean isTransferBusy() {
        return syncCoordinator.isSyncing() || protocol.isXmodemInProgress();
    }

    public boolean isConnectionAlive() {
        return connectionService.isConnectionAlive();
    }

    public boolean isRoleNegotiated() {
        return roleNegotiationService.isRoleNegotiated();
    }

    public boolean confirmCurrentRoleIfNeeded(boolean isSender) {
        return roleNegotiationService.confirmCurrentRoleIfNeeded(isSender);
    }

    /**
     * Start listening for incoming sync requests.
     */
    public void startListening() {
        if (running.get()) {
            return;
        }

        running.set(true);
        connectionAlive.set(false);
        roleNegotiated.set(false);
        syncing.set(false);

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

    /**
     * Stop listening and tear down background tasks.
     */
    public void stopListening() {
        running.set(false);
        connectionAlive.set(false);
        roleNegotiated.set(false);
        syncing.set(false);
        connectionService.stop();
        syncCoordinator.cancelOngoingSync();
        sharedTextService.clearPendingSharedText();
        serialPort.close();

        if (listenerFuture != null) {
            listenerFuture.cancel(true);
            listenerFuture = null;
        }

        shutdownExecutor();
    }

    /**
     * Disconnect from the connected peer and stop local sync services.
     *
     * @param notifyRemote when true, send a best-effort disconnect notification before teardown
     */
    public void disconnect(boolean notifyRemote) {
        if (notifyRemote && running.get() && serialPort.isOpen()) {
            try {
                protocol.sendDisconnect();
            } catch (IOException e) {
                eventBus.post(new SyncEvent.LogEvent("Failed to send disconnect notification: " + e.getMessage()));
            }
        }
        stopListening();
    }

    /**
     * Send shared text to remote.
     */
    public void sendSharedText(String text) {
        sharedTextService.queueSharedText(text);
    }

    public void sendDropFile(File file) {
        fileDropService.sendDropFile(file);
    }

    /**
     * Initiate synchronization as sender.
     */
    public void initiateSync() {
        syncCoordinator.startSync();
    }

    public void initiateSync(SyncPreviewPlan plan) {
        syncCoordinator.startSync(plan);
    }

    /**
     * Initiate synchronization using a pre-computed preview plan.
     * Skips manifest roundtrip when plan is valid for current state.
     */
    public void initiateSyncWithPlan(SyncPreviewPlan plan) {
        initiateSync(plan);
    }

    public void cancelSync() {
        syncCoordinator.cancelOngoingSync();
    }

    private static final int FOLDER_CONTEXT_TIMEOUT_MS = 5000;

    /**
     * Request remote folder context from the peer (receiver).
     * Call only when this side is sender. Pauses listener during exchange to avoid message stealing.
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
        String path = (folder != null && folder.exists())
                ? SettingsManager.normalizeFolderPath(folder.getAbsolutePath()) : "";
        protocol.sendFolderContextResponse(path);
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
            throw new IllegalStateException("Cannot initiate sync preview as receiver. Change direction first.");
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

    /**
     * Notify remote of direction change.
     */
    public void notifyDirectionChange() {
        roleNegotiationService.notifyDirectionChange();
    }

    /**
     * Notify remote (receiver) that the sender folder has changed.
     * Looks up the mapped receiver path and sends it; the receiver has no mapping stored
     * (only sender stores it after sync), so we send the target path directly.
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
            eventBus.post(new SyncEvent.ErrorEvent("Failed to send folder change notification: " + e.getMessage()));
        }
    }

    /**
     * Get initial connection timeout value.
     */
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
                // while we are actively sending a sync or doing a blocking protocol exchange (e.g. folder context).
                if ((syncCoordinator.isSyncing() || senderBlockingProtocolExchange.get())
                        && roleNegotiationService.isSender()) {
                    Thread.sleep(50);
                    continue;
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
                    eventBus.post(new SyncEvent.ErrorEvent("Protocol parse error: " + e.getMessage()));
                    try {
                        protocol.sendError("Protocol parse error");
                    } catch (IOException ignored) {
                        // Ignore send failures while handling malformed inbound messages.
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    eventBus.post(new SyncEvent.ErrorEvent("Communication error: " + e.getMessage()));
                    connectionService.reportCommunicationFailure(
                            "Connection lost - communication error: " + e.getMessage());
                }
            }
        }
    }

    private void handleIncomingMessage(SyncProtocol.Message msg) throws IOException {
        switch (msg.getCommand()) {
            case SyncProtocol.CMD_MANIFEST_REQ:
                // Extract sender's settings if provided (for consistent manifest generation)
                Boolean senderRespectGitignore = null;
                Boolean senderFastMode = null;
                if (msg.getParams().length >= 2) {
                    senderRespectGitignore = msg.getParamAsBoolean(0);
                    senderFastMode = msg.getParamAsBoolean(1);
                }
                syncCoordinator.handleManifestRequest(senderRespectGitignore, senderFastMode);
                break;

            case SyncProtocol.CMD_FOLDER_CONTEXT_REQ:
                handleFolderContextRequest();
                break;

            case SyncProtocol.CMD_FOLDER_CHANGE:
                handleFolderChange(msg.getParam(0));
                break;

            case SyncProtocol.CMD_MANIFEST_DATA:
                // Handled in initiateSync flow
                break;

            case SyncProtocol.CMD_FILE_REQ:
                syncCoordinator.handleFileRequest(msg.getParam(0));
                break;

            case SyncProtocol.CMD_FILE_DATA:
                syncCoordinator.handleIncomingFileData(msg);
                break;

            case SyncProtocol.CMD_DIRECTION_CHANGE:
                if (syncCoordinator.isSyncing() || protocol.isXmodemInProgress()) {
                    eventBus.post(new SyncEvent.LogEvent("Ignoring direction change during data transfer"));
                    break;
                }
                roleNegotiationService.handleDirectionChange(msg.getParamAsBoolean(0));
                break;

            case SyncProtocol.CMD_SYNC_COMPLETE:
                syncCoordinator.handleSyncComplete();
                break;

            case SyncProtocol.CMD_ERROR:
                syncCoordinator.cancelOngoingSync();
                eventBus.post(new SyncEvent.ErrorEvent("Remote error: " + msg.getParam(0)));
                break;

            case SyncProtocol.CMD_CANCEL:
                String cancelReason = msg.getParams().length > 0 ? msg.getParam(0) : "";
                syncCoordinator.handleRemoteCancel(cancelReason);
                break;

            case SyncProtocol.CMD_HEARTBEAT:
                connectionService.handleHeartbeat();
                break;

            case SyncProtocol.CMD_HEARTBEAT_ACK:
                connectionService.handleHeartbeatAck();
                break;

            case SyncProtocol.CMD_DISCONNECT:
                connectionService.reportCommunicationFailure("Connection closed by remote");
                break;

            case SyncProtocol.CMD_ROLE_NEGOTIATE:
                long remotePriority = msg.getParamAsLong(0);
                long remoteTieBreaker = msg.getParamAsLong(1);
                roleNegotiationService.handleRoleNegotiate(remotePriority, remoteTieBreaker);
                sharedTextService.resendLatestSharedText();
                break;

            case SyncProtocol.CMD_FILE_DELETE:
                syncCoordinator.handleFileDelete(msg.getParam(0));
                break;

            case SyncProtocol.CMD_MKDIR:
                syncCoordinator.handleMkdir(msg.getParam(0));
                break;

            case SyncProtocol.CMD_RMDIR:
                syncCoordinator.handleRmdir(msg.getParam(0));
                break;

            case SyncProtocol.CMD_SHARED_TEXT:
                if (msg.getParams().length >= 2) {
                    sharedTextService.handleIncomingSharedText(msg.getParamAsLong(0), msg.getParam(1));
                } else {
                    sharedTextService.handleIncomingSharedText(msg.getParam(0));
                }
                break;

            case SyncProtocol.CMD_SHARED_TEXT_DATA:
                if (msg.getParams().length >= 3) {
                    sharedTextService.handleIncomingSharedTextData(msg.getParamAsLong(0), msg.getParamAsBoolean(1), msg.getParamAsInt(2));
                } else {
                    eventBus.post(new SyncEvent.ErrorEvent("Invalid shared text data message"));
                }
                break;

            case SyncProtocol.CMD_DROP_FILE:
                fileDropService.handleIncomingDropFile(msg);
                break;

            default:
                break;
        }
    }

    private void ensureExecutor() {
        if (executor == null || executor.isShutdown()) {
            ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(4, runnable -> {
                Thread t = new Thread(runnable);
                t.setName("FileSync-" + t.getId());
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
        resetSyncStateForLinkTransition(false);
        roleNegotiationService.sendRoleNegotiation();
        sharedTextService.resendLatestSharedText();
    }

    private void onConnectionLost() {
        // Keep any pending shared text so it can be re-sent when connectivity returns.
        resetSyncStateForLinkTransition(false);
        stopListening();
    }

    private void resetSyncStateForLinkTransition(boolean clearBufferedText) {
        syncCoordinator.cancelOngoingSync();
        syncing.set(false);
        roleNegotiationService.resetForReconnect();
        if (clearBufferedText) {
            sharedTextService.clearPendingSharedText();
        }
    }

    private boolean isProtocolExchangeBusy() {
        return protocol.isXmodemInProgress() || senderBlockingProtocolExchange.get();
    }
}

