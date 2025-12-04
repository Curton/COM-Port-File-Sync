package com.filesync.sync;

import com.filesync.protocol.SyncProtocol;
import com.filesync.serial.SerialPortManager;
import com.filesync.serial.XModemTransfer;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates file synchronization operations between two machines.
 * Coordinates with SyncProtocol to handle manifest exchange and file transfers.
 */
public class FileSyncManager {

    private final SerialPortManager serialPort;
    private final SyncProtocol protocol;
    private File syncFolder;
    private boolean isSender;
    private final AtomicBoolean running;
    private final AtomicBoolean syncing;

    private SyncEventListener eventListener;
    private Thread listenerThread;

    public FileSyncManager(SerialPortManager serialPort) {
        this.serialPort = serialPort;
        this.protocol = new SyncProtocol(serialPort);
        this.running = new AtomicBoolean(false);
        this.syncing = new AtomicBoolean(false);
        this.isSender = true;
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

    /**
     * Start listening for incoming sync requests
     */
    public void startListening() {
        if (running.get()) {
            return;
        }

        running.set(true);
        listenerThread = new Thread(this::listenLoop, "SyncListener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    /**
     * Stop listening
     */
    public void stopListening() {
        running.set(false);
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
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

                if (protocol.hasData()) {
                    SyncProtocol.Message msg = protocol.receiveCommand();
                    if (msg != null) {
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
        }
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
        void onLog(String message);
        void onError(String message);
    }
}

