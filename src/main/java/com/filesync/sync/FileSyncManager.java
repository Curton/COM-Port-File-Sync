package com.filesync.sync;

import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates file synchronization operations between two machines.
 * Coordinates with SyncProtocol to handle manifest exchange and file transfers.
 */
public class FileSyncManager {

    private static final long HEARTBEAT_INTERVAL_MS = 5000;  // Send heartbeat every 5 seconds
    private static final long HEARTBEAT_TIMEOUT_MS = 15000;  // Connection lost if no heartbeat for 15 seconds
    private static final int HEARTBEAT_CHECK_TIMEOUT_MS = 1000;  // Short timeout for heartbeat check
    private static final long INITIAL_CONNECT_TIMEOUT_MS = 60000;  // 60 seconds timeout for initial connection

    private final SerialPortManager serialPort;
    private final SyncProtocol protocol;
    private File syncFolder;
    private boolean isSender;
    private final AtomicBoolean running;
    private final AtomicBoolean syncing;
    private final AtomicBoolean connectionAlive;

    private SyncEventListener eventListener;
    private Thread listenerThread;
    private Thread heartbeatThread;
    private volatile long lastHeartbeatReceived;
    private volatile long lastHeartbeatSent;
    private final AtomicLong localPriority;
    private final AtomicBoolean roleNegotiated;
    private static final Random RANDOM = new Random();

    public FileSyncManager(SerialPortManager serialPort) {
        this.serialPort = serialPort;
        this.protocol = new SyncProtocol(serialPort);
        this.running = new AtomicBoolean(false);
        this.syncing = new AtomicBoolean(false);
        this.connectionAlive = new AtomicBoolean(false);
        this.isSender = true;
        this.lastHeartbeatReceived = 0;
        this.lastHeartbeatSent = 0;
        this.localPriority = new AtomicLong(0);
        this.roleNegotiated = new AtomicBoolean(false);
    }

    public void setEventListener(SyncEventListener listener) {
        this.eventListener = listener;
        protocol.setProgressListener(new XModemTransfer.TransferProgressListener() {
            @Override
            public void onProgress(int currentBlock, int totalBlocks) {
                if (eventListener != null) {
                    eventListener.onTransferProgress(currentBlock, totalBlocks);
                }
            }

            @Override
            public void onError(String message) {
                if (eventListener != null) {
                    eventListener.onError(message);
                }
            }
        });
    }

    public void setSyncFolder(File folder) {
        this.syncFolder = folder;
    }

    public File getSyncFolder() {
        return syncFolder;
    }

    public void setIsSender(boolean isSender) {
        this.isSender = isSender;
    }

    public boolean isSender() {
        return isSender;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isSyncing() {
        return syncing.get();
    }

    public boolean isConnectionAlive() {
        return connectionAlive.get();
    }

    /**
     * Start listening for incoming sync requests
     */
    public void startListening() {
        if (running.get()) {
            return;
        }

        running.set(true);
        connectionAlive.set(false);  // Not connected until heartbeat response received
        lastHeartbeatReceived = 0;  // No heartbeat received yet
        lastHeartbeatSent = System.currentTimeMillis();
        // Generate random priority for role negotiation (use timestamp + random for uniqueness)
        localPriority.set(System.currentTimeMillis() * 1000 + RANDOM.nextInt(1000));
        roleNegotiated.set(false);

        listenerThread = new Thread(this::listenLoop, "SyncListener");
        listenerThread.setDaemon(true);
        listenerThread.start();

        heartbeatThread = new Thread(this::heartbeatLoop, "HeartbeatMonitor");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    /**
     * Wait for initial connection with the other side (waits for heartbeat response)
     * @param timeoutMs timeout in milliseconds
     * @return true if connected, false if timeout
     */
    public boolean waitForConnection(long timeoutMs) {
        long startTime = System.currentTimeMillis();
        
        // Send initial heartbeat immediately
        try {
            protocol.sendHeartbeat();
            lastHeartbeatSent = System.currentTimeMillis();
        } catch (IOException e) {
            // Ignore, will retry in loop
        }
        
        while (running.get() && !connectionAlive.get()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return false;  // Timeout
            }
            
            try {
                Thread.sleep(500);
                
                // Send heartbeat more frequently during initial connection
                if (System.currentTimeMillis() - lastHeartbeatSent >= 2000) {
                    try {
                        protocol.sendHeartbeat();
                        lastHeartbeatSent = System.currentTimeMillis();
                    } catch (IOException e) {
                        // Ignore, will retry
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        return connectionAlive.get();
    }

    /**
     * Get initial connection timeout value
     */
    public static long getInitialConnectTimeoutMs() {
        return INITIAL_CONNECT_TIMEOUT_MS;
    }

    /**
     * Stop listening
     */
    public void stopListening() {
        running.set(false);
        connectionAlive.set(false);
        roleNegotiated.set(false);
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
    }

    /**
     * Main listening loop for incoming commands
     */
    private void listenLoop() {
        while (running.get()) {
            try {
                if (!serialPort.isOpen()) {
                    Thread.sleep(500);
                    continue;
                }

                // Skip reading if XMODEM transfer is in progress on another thread
                // to avoid consuming XMODEM binary data as protocol commands
                if (protocol.isXmodemInProgress()) {
                    Thread.sleep(100);
                    continue;
                }

                if (protocol.hasData()) {
                    SyncProtocol.Message msg = protocol.receiveCommand();
                    if (msg != null) {
                        // Update last heartbeat received time on any message
                        lastHeartbeatReceived = System.currentTimeMillis();
                        handleIncomingMessage(msg);
                    }
                } else {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                if (running.get() && eventListener != null) {
                    eventListener.onError("Communication error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Heartbeat monitoring loop - sends heartbeat when idle and monitors connection status
     */
    private void heartbeatLoop() {
        while (running.get()) {
            try {
                if (!serialPort.isOpen()) {
                    Thread.sleep(500);
                    continue;
                }

                // Skip heartbeat operations during XMODEM transfer
                // to avoid interfering with binary data transfer
                if (protocol.isXmodemInProgress()) {
                    Thread.sleep(100);
                    continue;
                }

                long now = System.currentTimeMillis();

                // Check if connection is lost (no heartbeat received for too long)
                // Only check after we've established connection at least once
                if (connectionAlive.get() && lastHeartbeatReceived > 0 
                        && (now - lastHeartbeatReceived) > HEARTBEAT_TIMEOUT_MS) {
                    connectionAlive.set(false);
                    if (eventListener != null) {
                        eventListener.onConnectionStatusChanged(false);
                        eventListener.onLog("Connection lost - no heartbeat response");
                    }
                }

                // Send heartbeat if not syncing and interval has passed
                if (!syncing.get() && (now - lastHeartbeatSent) >= HEARTBEAT_INTERVAL_MS) {
                    try {
                        protocol.sendHeartbeat();
                        lastHeartbeatSent = now;
                    } catch (IOException e) {
                        // Heartbeat send failed, connection might be lost
                        if (connectionAlive.get()) {
                            connectionAlive.set(false);
                            if (eventListener != null) {
                                eventListener.onConnectionStatusChanged(false);
                                eventListener.onLog("Connection lost - heartbeat send failed");
                            }
                        }
                    }
                }

                Thread.sleep(1000);  // Check every second
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Handle incoming protocol messages
     */
    private void handleIncomingMessage(SyncProtocol.Message msg) throws IOException {
        switch (msg.getCommand()) {
            case SyncProtocol.CMD_MANIFEST_REQ:
                handleManifestRequest();
                break;

            case SyncProtocol.CMD_MANIFEST_DATA:
                // Handled in initiateSync flow
                break;

            case SyncProtocol.CMD_FILE_REQ:
                handleFileRequest(msg.getParam(0));
                break;

            case SyncProtocol.CMD_FILE_DATA:
                handleFileData(msg);
                break;

            case SyncProtocol.CMD_DIRECTION_CHANGE:
                handleDirectionChange(msg.getParamAsBoolean(0));
                break;

            case SyncProtocol.CMD_SYNC_COMPLETE:
                handleSyncComplete();
                break;

            case SyncProtocol.CMD_ERROR:
                if (eventListener != null) {
                    eventListener.onError("Remote error: " + msg.getParam(0));
                }
                break;

            case SyncProtocol.CMD_HEARTBEAT:
                handleHeartbeat();
                break;

            case SyncProtocol.CMD_HEARTBEAT_ACK:
                handleHeartbeatAck();
                break;

            case SyncProtocol.CMD_ROLE_NEGOTIATE:
                handleRoleNegotiate(msg.getParamAsLong(0));
                break;
        }
    }

    /**
     * Handle incoming heartbeat - send acknowledgment
     */
    private void handleHeartbeat() throws IOException {
        protocol.sendHeartbeatAck();
        // Mark connection as alive if it was previously lost
        if (!connectionAlive.get()) {
            connectionAlive.set(true);
            if (eventListener != null) {
                eventListener.onConnectionStatusChanged(true);
                eventListener.onLog("Connection restored");
            }
            // Start role negotiation after connection is established
            sendRoleNegotiation();
        }
    }

    /**
     * Handle heartbeat acknowledgment - connection is alive
     */
    private void handleHeartbeatAck() {
        lastHeartbeatReceived = System.currentTimeMillis();
        // Mark connection as alive if it was previously lost
        if (!connectionAlive.get()) {
            connectionAlive.set(true);
            if (eventListener != null) {
                eventListener.onConnectionStatusChanged(true);
                eventListener.onLog("Connection restored");
            }
            // Start role negotiation after connection is established
            sendRoleNegotiation();
        }
    }

    /**
     * Send role negotiation message
     */
    private void sendRoleNegotiation() {
        if (roleNegotiated.get()) {
            return;
        }
        try {
            protocol.sendRoleNegotiate(localPriority.get());
        } catch (IOException e) {
            if (eventListener != null) {
                eventListener.onError("Failed to send role negotiation: " + e.getMessage());
            }
        }
    }

    /**
     * Handle incoming role negotiation
     */
    private void handleRoleNegotiate(long remotePriority) throws IOException {
        if (roleNegotiated.get()) {
            return;  // Already negotiated
        }
        
        long myPriority = localPriority.get();
        
        // Higher priority becomes Sender, lower becomes Receiver
        // If equal (very rare), the one receiving this message becomes Receiver
        boolean shouldBeSender = myPriority > remotePriority;
        
        if (this.isSender != shouldBeSender) {
            this.isSender = shouldBeSender;
            if (eventListener != null) {
                eventListener.onDirectionChanged(this.isSender);
                eventListener.onLog("Role negotiated: " + (isSender ? "Sender" : "Receiver"));
            }
        }
        
        roleNegotiated.set(true);
        
        // Send our priority back so the other side can also determine its role
        protocol.sendRoleNegotiate(myPriority);
    }

    /**
     * Handle manifest request from remote
     */
    private void handleManifestRequest() throws IOException {
        if (syncFolder == null || !syncFolder.exists()) {
            protocol.sendError("Sync folder not configured");
            return;
        }

        if (eventListener != null) {
            eventListener.onLog("Sending manifest...");
        }

        FileChangeDetector.FileManifest manifest = FileChangeDetector.generateManifest(syncFolder);
        protocol.sendManifest(manifest);

        if (eventListener != null) {
            eventListener.onLog("Manifest sent (" + manifest.getFileCount() + " files)");
        }
    }

    /**
     * Handle file request from remote
     */
    private void handleFileRequest(String relativePath) throws IOException {
        if (syncFolder == null) {
            protocol.sendError("Sync folder not configured");
            return;
        }

        if (eventListener != null) {
            eventListener.onLog("Sending file: " + relativePath);
        }

        protocol.sendFile(syncFolder, relativePath);
    }

    /**
     * Handle incoming file data
     */
    private void handleFileData(SyncProtocol.Message msg) throws IOException {
        if (syncFolder == null) {
            return;
        }

        String relativePath = msg.getParam(0);
        int size = msg.getParamAsInt(1);
        boolean compressed = msg.getParamAsBoolean(2);

        if (eventListener != null) {
            eventListener.onLog("Receiving file: " + relativePath);
        }

        protocol.receiveFile(syncFolder, relativePath, size, compressed);

        if (eventListener != null) {
            eventListener.onLog("File received: " + relativePath);
        }
    }

    /**
     * Handle direction change notification
     */
    private void handleDirectionChange(boolean remoteSender) {
        // If remote is sender, we become receiver (and vice versa)
        this.isSender = !remoteSender;
        if (eventListener != null) {
            eventListener.onDirectionChanged(this.isSender);
        }
    }

    /**
     * Handle sync complete notification
     */
    private void handleSyncComplete() {
        syncing.set(false);
        if (eventListener != null) {
            eventListener.onSyncComplete();
        }
    }

    /**
     * Initiate synchronization as sender
     */
    public void initiateSync() {
        if (!isSender) {
            if (eventListener != null) {
                eventListener.onError("Cannot initiate sync as receiver. Change direction first.");
            }
            return;
        }

        if (syncing.get()) {
            if (eventListener != null) {
                eventListener.onError("Sync already in progress");
            }
            return;
        }

        if (syncFolder == null || !syncFolder.exists()) {
            if (eventListener != null) {
                eventListener.onError("Please select a sync folder first");
            }
            return;
        }

        syncing.set(true);
        Thread syncThread = new Thread(this::performSync, "SyncThread");
        syncThread.start();
    }

    /**
     * Perform the actual sync operation
     */
    private void performSync() {
        try {
            if (eventListener != null) {
                eventListener.onSyncStarted();
            }

            // Generate local manifest
            if (eventListener != null) {
                eventListener.onLog("Generating local manifest...");
            }
            FileChangeDetector.FileManifest localManifest = FileChangeDetector.generateManifest(syncFolder);

            // Request remote manifest
            if (eventListener != null) {
                eventListener.onLog("Requesting remote manifest...");
            }
            protocol.requestManifest();

            // Wait for manifest data command
            SyncProtocol.Message manifestMsg = protocol.waitForCommand(SyncProtocol.CMD_MANIFEST_DATA);

            // Receive manifest via XMODEM
            FileChangeDetector.FileManifest remoteManifest = protocol.receiveManifest();
            if (eventListener != null) {
                eventListener.onLog("Remote manifest received (" + remoteManifest.getFileCount() + " files)");
            }

            // Compare manifests to find files that need syncing
            List<FileChangeDetector.FileInfo> filesToSync = 
                    FileChangeDetector.getChangedFiles(localManifest, remoteManifest);

            if (filesToSync.isEmpty()) {
                if (eventListener != null) {
                    eventListener.onLog("No files need to be synced");
                    eventListener.onSyncComplete();
                }
                syncing.set(false);
                return;
            }

            if (eventListener != null) {
                eventListener.onLog("Files to sync: " + filesToSync.size());
            }

            // Send each changed file
            int fileIndex = 0;
            for (FileChangeDetector.FileInfo fileInfo : filesToSync) {
                fileIndex++;
                if (eventListener != null) {
                    eventListener.onLog("Syncing [" + fileIndex + "/" + filesToSync.size() + "]: " + fileInfo.getPath());
                    eventListener.onFileProgress(fileIndex, filesToSync.size(), fileInfo.getPath());
                }

                protocol.sendFile(syncFolder, fileInfo.getPath());
            }

            // Send sync complete
            protocol.sendSyncComplete();

            if (eventListener != null) {
                eventListener.onLog("Sync completed successfully");
                eventListener.onSyncComplete();
            }

        } catch (IOException e) {
            if (eventListener != null) {
                eventListener.onError("Sync failed: " + e.getMessage());
            }
        } finally {
            syncing.set(false);
        }
    }

    /**
     * Notify remote of direction change
     */
    public void notifyDirectionChange() {
        try {
            protocol.sendDirectionChange(isSender);
        } catch (IOException e) {
            if (eventListener != null) {
                eventListener.onError("Failed to notify direction change: " + e.getMessage());
            }
        }
    }

    /**
     * Event listener interface for sync status updates
     */
    public interface SyncEventListener {
        void onSyncStarted();
        void onSyncComplete();
        void onFileProgress(int currentFile, int totalFiles, String fileName);
        void onTransferProgress(int currentBlock, int totalBlocks);
        void onDirectionChanged(boolean isSender);
        void onConnectionStatusChanged(boolean isConnected);
        void onLog(String message);
        void onError(String message);
    }
}

