package com.filesync.sync;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private volatile File syncFolder;
    private volatile boolean strictSyncMode = false;
    private volatile boolean respectGitignoreMode = false;
    private volatile boolean fastMode = false;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicBoolean connectionAlive = new AtomicBoolean(false);
    private final AtomicBoolean roleNegotiated = new AtomicBoolean(false);
    private final AtomicBoolean isSender = new AtomicBoolean(true);

    private final SyncEventBus eventBus;
    private final ConnectionService connectionService;
    private final RoleNegotiationService roleNegotiationService;
    private final SharedTextService sharedTextService;
    private final SyncCoordinator syncCoordinator;

    private ScheduledExecutorService executor;
    private Future<?> listenerFuture;

    public FileSyncManager(SerialPortManager serialPort) {
        this.serialPort = serialPort;
        this.protocol = new SyncProtocol(serialPort);
        this.eventBus = new SimpleSyncEventBus();

        this.connectionService = new ConnectionService(
                serialPort,
                protocol,
                eventBus,
                running,
                connectionAlive,
                syncing::get,
                protocol::isXmodemInProgress,
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
                syncing,
                sharedTextService::onSyncIdle,
                connectionService::recordMessageActivity);

        protocol.setProgressListener(new XModemTransfer.TransferProgressListener() {
            @Override
            public void onProgress(int currentBlock, int totalBlocks, long bytesTransferred, double speedBytesPerSec) {
                eventBus.post(new SyncEvent.TransferProgressEvent(currentBlock, totalBlocks, bytesTransferred, speedBytesPerSec));
            }

            @Override
            public void onError(String message) {
                eventBus.post(new SyncEvent.ErrorEvent(message));
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

    public boolean isConnectionAlive() {
        return connectionService.isConnectionAlive();
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

        if (listenerFuture != null) {
            listenerFuture.cancel(true);
            listenerFuture = null;
        }

        shutdownExecutor();
    }

    /**
     * Send shared text to remote.
     */
    public void sendSharedText(String text) {
        sharedTextService.queueSharedText(text);
    }

    /**
     * Initiate synchronization as sender.
     */
    public void initiateSync() {
        syncCoordinator.startSync();
    }

    /**
     * Notify remote of direction change.
     */
    public void notifyDirectionChange() {
        roleNegotiationService.notifyDirectionChange();
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
            } catch (IOException e) {
                if (running.get()) {
                    eventBus.post(new SyncEvent.ErrorEvent("Communication error: " + e.getMessage()));
                }
            }
        }
    }

    private void handleIncomingMessage(SyncProtocol.Message msg) throws IOException {
        switch (msg.getCommand()) {
            case SyncProtocol.CMD_MANIFEST_REQ:
                syncCoordinator.handleManifestRequest();
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
                roleNegotiationService.handleDirectionChange(msg.getParamAsBoolean(0));
                break;

            case SyncProtocol.CMD_SYNC_COMPLETE:
                syncCoordinator.handleSyncComplete();
                break;

            case SyncProtocol.CMD_ERROR:
                eventBus.post(new SyncEvent.ErrorEvent("Remote error: " + msg.getParam(0)));
                break;

            case SyncProtocol.CMD_HEARTBEAT:
                connectionService.handleHeartbeat();
                break;

            case SyncProtocol.CMD_HEARTBEAT_ACK:
                connectionService.handleHeartbeatAck();
                break;

            case SyncProtocol.CMD_ROLE_NEGOTIATE:
                roleNegotiationService.handleRoleNegotiate(msg.getParamAsLong(0));
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
                sharedTextService.handleIncomingSharedText(msg.getParam(0));
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
        syncing.set(false);
        roleNegotiationService.resetForReconnect();
        roleNegotiationService.sendRoleNegotiation();
    }
}

