package com.filesync.sync;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;

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
    private boolean strictSyncMode = false;
    private boolean respectGitignoreMode = false;
    private boolean fastMode = false;
    private volatile String pendingSharedText = null;

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
            public void onProgress(int currentBlock, int totalBlocks, long bytesTransferred, double speedBytesPerSec) {
                if (eventListener != null) {
                    eventListener.onTransferProgress(currentBlock, totalBlocks, bytesTransferred, speedBytesPerSec);
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
     * Send shared text to remote
     */
    public void sendSharedText(String text) {
        pendingSharedText = text;  // Keep the latest edit so we can resend after transfers
        flushPendingSharedTextIfIdle();
    }

    /**
     * Best-effort delivery of pending shared text when the link is idle.
     * Skips sending while a file transfer is running to avoid corrupting XMODEM traffic.
     */
    private void flushPendingSharedTextIfIdle() {
        String textToSend = pendingSharedText;
        if (textToSend == null) {
            return;
        }
        if (!running.get() || !connectionAlive.get()) {
            if (eventListener != null) {
                eventListener.onError("Cannot send shared text - not connected");
            }
            return;
        }
        if (syncing.get() || protocol.isXmodemInProgress()) {
            return; // Defer until after current transfer finishes
        }
        try {
            protocol.sendSharedText(textToSend);
            pendingSharedText = null;
        } catch (IOException e) {
            if (eventListener != null) {
                eventListener.onError("Failed to send shared text: " + e.getMessage());
            }
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
                    Thread.sleep(1000);
                    continue;
                }

                long now = System.currentTimeMillis();

                // Check if connection is lost (no heartbeat received for too long)
                // Only check after we've established connection at least once
                // Skip timeout check during sync - heartbeat exchange is paused during file transfers
                if (connectionAlive.get() && lastHeartbeatReceived > 0 
                        && !syncing.get()
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

            case SyncProtocol.CMD_FILE_DELETE:
                handleFileDelete(msg.getParam(0));
                break;

            case SyncProtocol.CMD_MKDIR:
                handleMkdir(msg.getParam(0));
                break;

            case SyncProtocol.CMD_RMDIR:
                handleRmdir(msg.getParam(0));
                break;

            case SyncProtocol.CMD_SHARED_TEXT:
                handleSharedText(msg.getParam(0));
                break;
        }
    }

    /**
     * Handle mkdir command from remote - create empty directory
     */
    private void handleMkdir(String relativePath) {
        if (syncFolder == null) {
            return;
        }

        File dirToCreate = new File(syncFolder, relativePath);
        if (!dirToCreate.exists()) {
            if (eventListener != null) {
                eventListener.onLog("Creating directory: " + relativePath);
            }
            if (dirToCreate.mkdirs()) {
                if (eventListener != null) {
                    eventListener.onLog("Directory created: " + relativePath);
                }
            } else {
                if (eventListener != null) {
                    eventListener.onError("Failed to create directory: " + relativePath);
                }
            }
        }
    }

    /**
     * Handle rmdir command from remote - delete empty directory (strict sync mode)
     */
    private void handleRmdir(String relativePath) {
        if (syncFolder == null) {
            return;
        }

        File dirToDelete = new File(syncFolder, relativePath);
        if (dirToDelete.exists() && dirToDelete.isDirectory()) {
            if (eventListener != null) {
                eventListener.onLog("Deleting directory: " + relativePath);
            }
            // Delete recursively in case it has content
            if (deleteDirectoryRecursively(dirToDelete)) {
                if (eventListener != null) {
                    eventListener.onLog("Directory deleted: " + relativePath);
                }
                // Clean up empty parent directories
                cleanupEmptyDirectories(dirToDelete.getParentFile());
            } else {
                if (eventListener != null) {
                    eventListener.onError("Failed to delete directory: " + relativePath);
                }
            }
        }
    }

    /**
     * Delete a directory recursively
     */
    private boolean deleteDirectoryRecursively(File directory) {
        if (directory == null || !directory.exists()) {
            return true;
        }
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!deleteDirectoryRecursively(file)) {
                        return false;
                    }
                }
            }
        }
        return directory.delete();
    }

    /**
     * Handle file delete command from remote (strict sync mode)
     */
    private void handleFileDelete(String relativePath) throws IOException {
        if (syncFolder == null) {
            return;
        }

        File fileToDelete = new File(syncFolder, relativePath);
        if (fileToDelete.exists() && fileToDelete.isFile()) {
            if (eventListener != null) {
                eventListener.onLog("Deleting file: " + relativePath);
            }
            if (fileToDelete.delete()) {
                if (eventListener != null) {
                    eventListener.onLog("File deleted: " + relativePath);
                }
                // Clean up empty parent directories
                cleanupEmptyDirectories(fileToDelete.getParentFile());
            } else {
                if (eventListener != null) {
                    eventListener.onError("Failed to delete file: " + relativePath);
                }
            }
        }
    }

    /**
     * Clean up empty parent directories after file deletion
     */
    private void cleanupEmptyDirectories(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return;
        }
        // Don't delete the sync folder itself
        if (directory.equals(syncFolder)) {
            return;
        }
        // Only delete if empty
        String[] contents = directory.list();
        if (contents != null && contents.length == 0) {
            File parent = directory.getParentFile();
            if (directory.delete()) {
                // Recursively clean up parent directories
                cleanupEmptyDirectories(parent);
            }
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
            // Reset role negotiation state on reconnection
            roleNegotiated.set(false);
            // Reset syncing state in case sync was interrupted
            syncing.set(false);
            // Generate new priority for role negotiation on reconnection
            localPriority.set(System.currentTimeMillis() * 1000 + RANDOM.nextInt(1000));
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
            // Reset role negotiation state on reconnection
            roleNegotiated.set(false);
            // Reset syncing state in case sync was interrupted
            syncing.set(false);
            // Generate new priority for role negotiation on reconnection
            localPriority.set(System.currentTimeMillis() * 1000 + RANDOM.nextInt(1000));
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
        }
        
        roleNegotiated.set(true);
        
        // Always notify direction change after role negotiation completes
        // This ensures UI updates (like sync button state) even if role didn't change
        if (eventListener != null) {
            eventListener.onDirectionChanged(this.isSender);
            eventListener.onLog("Role negotiated: " + (isSender ? "Sender" : "Receiver"));
        }
        
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

        FileChangeDetector.FileManifest manifest = FileChangeDetector.generateManifest(syncFolder, respectGitignoreMode, fastMode);
        protocol.sendManifest(manifest);

        if (eventListener != null) {
            String logMsg = "Manifest sent (" + manifest.getFileCount() + " files";
            if (manifest.getEmptyDirectoryCount() > 0) {
                logMsg += ", " + manifest.getEmptyDirectoryCount() + " empty dirs";
            }
            logMsg += ")";
            eventListener.onLog(logMsg);
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

        // Mark as syncing to prevent heartbeat timeout during file receive
        syncing.set(true);

        String relativePath = msg.getParam(0);
        int size = msg.getParamAsInt(1);
        boolean compressed = msg.getParamAsBoolean(2);
        long lastModified = msg.getParams().length > 3 ? msg.getParamAsLong(3) : 0L;

        if (eventListener != null) {
            eventListener.onLog("Receiving file: " + relativePath);
        }

        // Send ACK to synchronize with sender
        protocol.sendAck();

        protocol.receiveFile(syncFolder, relativePath, size, compressed, lastModified);

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
        // Reset heartbeat timer to prevent false timeout detection after sync
        lastHeartbeatReceived = System.currentTimeMillis();
        flushPendingSharedTextIfIdle(); // Push any edits that happened during the sync
        if (eventListener != null) {
            eventListener.onSyncComplete();
        }
    }

    /**
     * Handle shared text received from remote
     */
    private void handleSharedText(String encodedPayload) {
        if (eventListener == null) {
            return;
        }
        String text = protocol.decodeSharedText(encodedPayload);
        eventListener.onSharedTextReceived(text);
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
            FileChangeDetector.FileManifest localManifest = FileChangeDetector.generateManifest(syncFolder, respectGitignoreMode, fastMode);

            // Request remote manifest
            if (eventListener != null) {
                eventListener.onLog("Requesting remote manifest...");
            }
            protocol.requestManifest();

            // Wait for manifest data command
            SyncProtocol.Message manifestMsg = protocol.waitForCommand(SyncProtocol.CMD_MANIFEST_DATA);

            // Send ACK to synchronize with sender
            protocol.sendAck();

            // Receive manifest via XMODEM
            FileChangeDetector.FileManifest remoteManifest = protocol.receiveManifest();
            if (eventListener != null) {
                String logMsg = "Remote manifest received (" + remoteManifest.getFileCount() + " files";
                if (remoteManifest.getEmptyDirectoryCount() > 0) {
                    logMsg += ", " + remoteManifest.getEmptyDirectoryCount() + " empty dirs";
                }
                logMsg += ")";
                eventListener.onLog(logMsg);
            }

            // Compare manifests to find files that need syncing
            List<FileChangeDetector.FileInfo> filesToSync = 
                    FileChangeDetector.getChangedFiles(localManifest, remoteManifest);

            // Get empty directories that need to be created
            List<String> emptyDirsToCreate = 
                    FileChangeDetector.getEmptyDirectoriesToCreate(localManifest, remoteManifest);

            // Get files to delete if strict sync mode is enabled
            List<String> filesToDelete = strictSyncMode ? 
                    FileChangeDetector.getFilesToDelete(localManifest, remoteManifest) : 
                    new java.util.ArrayList<>();

            // Get empty directories to delete if strict sync mode is enabled
            List<String> emptyDirsToDelete = strictSyncMode ?
                    FileChangeDetector.getEmptyDirectoriesToDelete(localManifest, remoteManifest) :
                    new java.util.ArrayList<>();

            int totalOperations = filesToSync.size() + emptyDirsToCreate.size() + filesToDelete.size() + emptyDirsToDelete.size();
            
            if (totalOperations == 0) {
                if (eventListener != null) {
                    eventListener.onLog("No files need to be synced or deleted");
                    eventListener.onSyncComplete();
                }
                syncing.set(false);
                return;
            }

            if (eventListener != null) {
                String logMsg = "Files to sync: " + filesToSync.size();
                if (!emptyDirsToCreate.isEmpty()) {
                    logMsg += ", Empty dirs to create: " + emptyDirsToCreate.size();
                }
                if (strictSyncMode) {
                    logMsg += ", Files to delete: " + filesToDelete.size();
                    if (!emptyDirsToDelete.isEmpty()) {
                        logMsg += ", Empty dirs to delete: " + emptyDirsToDelete.size();
                    }
                }
                eventListener.onLog(logMsg);
            }

            // Send each changed file
            int operationIndex = 0;
            for (FileChangeDetector.FileInfo fileInfo : filesToSync) {
                operationIndex++;
                
                boolean wasCompressed = protocol.sendFile(syncFolder, fileInfo.getPath());
                
                if (eventListener != null) {
                    String logMessage = "Syncing [" + operationIndex + "/" + totalOperations + "]: " + fileInfo.getPath();
                    if (wasCompressed) {
                        logMessage += " (compressed)";
                    }
                    eventListener.onLog(logMessage);
                    eventListener.onFileProgress(operationIndex, totalOperations, fileInfo.getPath());
                }
            }

            // Create empty directories on remote
            for (String dirPath : emptyDirsToCreate) {
                operationIndex++;
                if (eventListener != null) {
                    eventListener.onLog("Creating dir [" + operationIndex + "/" + totalOperations + "]: " + dirPath);
                    eventListener.onFileProgress(operationIndex, totalOperations, "[DIR] " + dirPath);
                }
                protocol.sendMkdir(dirPath);
            }

            // Send delete commands for files that should be removed (strict sync mode)
            for (String pathToDelete : filesToDelete) {
                operationIndex++;
                if (eventListener != null) {
                    eventListener.onLog("Deleting [" + operationIndex + "/" + totalOperations + "]: " + pathToDelete);
                    eventListener.onFileProgress(operationIndex, totalOperations, "[DEL] " + pathToDelete);
                }
                protocol.sendFileDelete(pathToDelete);
            }

            // Delete empty directories that should be removed (strict sync mode)
            for (String dirToDelete : emptyDirsToDelete) {
                operationIndex++;
                if (eventListener != null) {
                    eventListener.onLog("Deleting dir [" + operationIndex + "/" + totalOperations + "]: " + dirToDelete);
                    eventListener.onFileProgress(operationIndex, totalOperations, "[RMDIR] " + dirToDelete);
                }
                protocol.sendRmdir(dirToDelete);
            }

            // Send sync complete
            protocol.sendSyncComplete();

            if (eventListener != null) {
                eventListener.onLog("Sync completed successfully");
                eventListener.onTransferComplete();
                eventListener.onSyncComplete();
            }

        } catch (IOException e) {
            if (eventListener != null) {
                eventListener.onError("Sync failed: " + e.getMessage());
            }
        } finally {
            syncing.set(false);
            // Reset heartbeat timer to prevent false timeout detection after sync
            lastHeartbeatReceived = System.currentTimeMillis();
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
        void onTransferComplete();
        void onFileProgress(int currentFile, int totalFiles, String fileName);
        void onTransferProgress(int currentBlock, int totalBlocks, long bytesTransferred, double speedBytesPerSec);
        void onDirectionChanged(boolean isSender);
        void onConnectionStatusChanged(boolean isConnected);
        void onLog(String message);
        void onError(String message);
        void onSharedTextReceived(String text);
    }
}

