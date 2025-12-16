package com.filesync.sync;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.filesync.protocol.SyncProtocol;

/**
 * Coordinates sync operations, manifest exchange, and file transfers.
 */
public class SyncCoordinator {

    private final SyncProtocol protocol;
    private final SyncEventBus eventBus;
    private final Supplier<File> syncFolderSupplier;
    private final BooleanSupplier strictSyncModeSupplier;
    private final BooleanSupplier respectGitignoreModeSupplier;
    private final BooleanSupplier fastModeSupplier;
    private final BooleanSupplier connectionAliveSupplier;
    private final BooleanSupplier isSenderSupplier;
    private final AtomicBoolean syncing;
    private final Runnable onSyncIdle;
    private final Runnable heartbeatTouch;

    private ScheduledExecutorService executor;
    private Future<?> syncFuture;

    public SyncCoordinator(SyncProtocol protocol,
                           SyncEventBus eventBus,
                           Supplier<File> syncFolderSupplier,
                           BooleanSupplier strictSyncModeSupplier,
                           BooleanSupplier respectGitignoreModeSupplier,
                           BooleanSupplier fastModeSupplier,
                           BooleanSupplier connectionAliveSupplier,
                           BooleanSupplier isSenderSupplier,
                           AtomicBoolean syncing,
                           Runnable onSyncIdle,
                           Runnable heartbeatTouch) {
        this.protocol = protocol;
        this.eventBus = eventBus;
        this.syncFolderSupplier = syncFolderSupplier;
        this.strictSyncModeSupplier = strictSyncModeSupplier;
        this.respectGitignoreModeSupplier = respectGitignoreModeSupplier;
        this.fastModeSupplier = fastModeSupplier;
        this.connectionAliveSupplier = connectionAliveSupplier;
        this.isSenderSupplier = isSenderSupplier;
        this.syncing = syncing;
        this.onSyncIdle = onSyncIdle;
        this.heartbeatTouch = heartbeatTouch;
    }

    public void setExecutor(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    public boolean isSyncing() {
        return syncing.get();
    }

    public void startSync() {
        if (!isSenderSupplier.getAsBoolean()) {
            eventBus.post(new SyncEvent.ErrorEvent("Cannot initiate sync as receiver. Change direction first."));
            return;
        }
        if (!connectionAliveSupplier.getAsBoolean()) {
            eventBus.post(new SyncEvent.ErrorEvent("Cannot initiate sync while disconnected"));
            return;
        }
        if (syncing.get()) {
            eventBus.post(new SyncEvent.ErrorEvent("Sync already in progress"));
            return;
        }
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null || !syncFolder.exists()) {
            eventBus.post(new SyncEvent.ErrorEvent("Please select a sync folder first"));
            return;
        }
        syncing.set(true);
        if (executor != null) {
            syncFuture = executor.submit(this::performSync);
        } else {
            performSync();
        }
    }

    public void cancelOngoingSync() {
        if (syncFuture != null) {
            syncFuture.cancel(true);
            syncFuture = null;
        }
        syncing.set(false);
    }

    /**
     * Handle manifest request from sender.
     * Uses sender's settings if provided, otherwise falls back to local settings.
     * This ensures both sides generate manifests with the same options (especially fast mode).
     * 
     * @param senderRespectGitignore sender's respect gitignore setting, or null to use local
     * @param senderFastMode sender's fast mode setting, or null to use local
     */
    public void handleManifestRequest(Boolean senderRespectGitignore, Boolean senderFastMode) throws IOException {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null || !syncFolder.exists()) {
            protocol.sendError("Sync folder not configured");
            return;
        }
        
        // Use sender's settings if provided, otherwise use local settings
        boolean respectGitignore = senderRespectGitignore != null ? senderRespectGitignore : respectGitignoreModeSupplier.getAsBoolean();
        boolean fastMode = senderFastMode != null ? senderFastMode : fastModeSupplier.getAsBoolean();
        
        eventBus.post(new SyncEvent.LogEvent("Sending manifest..."));
        FileChangeDetector.FileManifest manifest = FileChangeDetector.generateManifest(
                syncFolder,
                respectGitignore,
                fastMode);
        protocol.sendManifest(manifest);
        String logMsg = "Manifest sent (" + manifest.getFileCount() + " files";
        if (manifest.getEmptyDirectoryCount() > 0) {
            logMsg += ", " + manifest.getEmptyDirectoryCount() + " empty dirs";
        }
        logMsg += ")";
        eventBus.post(new SyncEvent.LogEvent(logMsg));
    }

    public void handleFileRequest(String relativePath) throws IOException {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null) {
            protocol.sendError("Sync folder not configured");
            return;
        }
        eventBus.post(new SyncEvent.LogEvent("Sending file: " + relativePath));
        protocol.sendFile(syncFolder, relativePath);
    }

    public void handleIncomingFileData(SyncProtocol.Message msg) throws IOException {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null) {
            return;
        }
        syncing.set(true);
        String relativePath = msg.getParam(0);
        int size = msg.getParamAsInt(1);
        boolean compressed = msg.getParamAsBoolean(2);
        long lastModified = msg.getParams().length > 3 ? msg.getParamAsLong(3) : 0L;

        eventBus.post(new SyncEvent.LogEvent("Receiving file: " + relativePath));
        protocol.sendAck();
        protocol.receiveFile(syncFolder, relativePath, size, compressed, lastModified);
        eventBus.post(new SyncEvent.LogEvent("File received: " + relativePath));
        touchHeartbeat();
    }

    public void handleSyncComplete() {
        syncing.set(false);
        touchHeartbeat();
        onSyncIdle.run();
        eventBus.post(new SyncEvent.SyncCompleteEvent());
    }

    public void handleFileDelete(String relativePath) throws IOException {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null) {
            return;
        }
        File fileToDelete = new File(syncFolder, relativePath);
        if (fileToDelete.exists() && fileToDelete.isFile()) {
            eventBus.post(new SyncEvent.LogEvent("Deleting file: " + relativePath));
            if (fileToDelete.delete()) {
                eventBus.post(new SyncEvent.LogEvent("File deleted: " + relativePath));
                cleanupEmptyDirectories(fileToDelete.getParentFile(), syncFolder);
            } else {
                eventBus.post(new SyncEvent.ErrorEvent("Failed to delete file: " + relativePath));
            }
        }
    }

    public void handleMkdir(String relativePath) {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null) {
            return;
        }
        File dirToCreate = new File(syncFolder, relativePath);
        if (!dirToCreate.exists()) {
            eventBus.post(new SyncEvent.LogEvent("Creating directory: " + relativePath));
            if (dirToCreate.mkdirs()) {
                eventBus.post(new SyncEvent.LogEvent("Directory created: " + relativePath));
            } else {
                eventBus.post(new SyncEvent.ErrorEvent("Failed to create directory: " + relativePath));
            }
        }
    }

    public void handleRmdir(String relativePath) {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null) {
            return;
        }
        File dirToDelete = new File(syncFolder, relativePath);
        if (dirToDelete.exists() && dirToDelete.isDirectory()) {
            eventBus.post(new SyncEvent.LogEvent("Deleting directory: " + relativePath));
            if (deleteDirectoryRecursively(dirToDelete)) {
                eventBus.post(new SyncEvent.LogEvent("Directory deleted: " + relativePath));
                cleanupEmptyDirectories(dirToDelete.getParentFile(), syncFolder);
            } else {
                eventBus.post(new SyncEvent.ErrorEvent("Failed to delete directory: " + relativePath));
            }
        }
    }

    private void performSync() {
        try {
            eventBus.post(new SyncEvent.SyncStartedEvent());
            eventBus.post(new SyncEvent.LogEvent("Generating local manifest..."));

            File syncFolder = syncFolderSupplier.get();
            boolean respectGitignore = respectGitignoreModeSupplier.getAsBoolean();
            boolean fastMode = fastModeSupplier.getAsBoolean();
            
            FileChangeDetector.FileManifest localManifest = FileChangeDetector.generateManifest(
                    syncFolder,
                    respectGitignore,
                    fastMode);

            eventBus.post(new SyncEvent.LogEvent("Requesting remote manifest..."));
            // Send our settings to the receiver so it generates manifest with the same options
            protocol.requestManifest(respectGitignore, fastMode);

            protocol.waitForCommand(SyncProtocol.CMD_MANIFEST_DATA);
            protocol.sendAck();
            FileChangeDetector.FileManifest remoteManifest = protocol.receiveManifest();

            String logMsg = "Remote manifest received (" + remoteManifest.getFileCount() + " files";
            if (remoteManifest.getEmptyDirectoryCount() > 0) {
                logMsg += ", " + remoteManifest.getEmptyDirectoryCount() + " empty dirs";
            }
            logMsg += ")";
            eventBus.post(new SyncEvent.LogEvent(logMsg));

            List<FileChangeDetector.FileInfo> filesToSync =
                    FileChangeDetector.getChangedFiles(localManifest, remoteManifest);
            List<String> emptyDirsToCreate =
                    FileChangeDetector.getEmptyDirectoriesToCreate(localManifest, remoteManifest);

            List<String> filesToDelete = strictSyncModeSupplier.getAsBoolean()
                    ? FileChangeDetector.getFilesToDelete(localManifest, remoteManifest)
                    : new ArrayList<>();
            List<String> emptyDirsToDelete = strictSyncModeSupplier.getAsBoolean()
                    ? FileChangeDetector.getEmptyDirectoriesToDelete(localManifest, remoteManifest)
                    : new ArrayList<>();

            int totalOperations = filesToSync.size() + emptyDirsToCreate.size()
                    + filesToDelete.size() + emptyDirsToDelete.size();

            if (totalOperations == 0) {
                eventBus.post(new SyncEvent.LogEvent("No files need to be synced or deleted"));
                eventBus.post(new SyncEvent.SyncCompleteEvent());
                syncing.set(false);
                onSyncIdle.run();
                return;
            }

            logSyncSummary(filesToSync, emptyDirsToCreate, filesToDelete, emptyDirsToDelete);

            int operationIndex = 0;
            for (FileChangeDetector.FileInfo fileInfo : filesToSync) {
                operationIndex++;
                boolean wasCompressed = protocol.sendFile(syncFolder, fileInfo.getPath());
                String message = "Syncing [" + operationIndex + "/" + totalOperations + "]: "
                        + fileInfo.getPath();
                if (wasCompressed) {
                    message += " (compressed)";
                }
                eventBus.post(new SyncEvent.LogEvent(message));
                eventBus.post(new SyncEvent.FileProgressEvent(operationIndex, totalOperations, fileInfo.getPath()));
            }

            for (String dirPath : emptyDirsToCreate) {
                operationIndex++;
                eventBus.post(new SyncEvent.LogEvent("Creating dir [" + operationIndex + "/" + totalOperations + "]: " + dirPath));
                eventBus.post(new SyncEvent.FileProgressEvent(operationIndex, totalOperations, "[DIR] " + dirPath));
                protocol.sendMkdir(dirPath);
            }

            for (String pathToDelete : filesToDelete) {
                operationIndex++;
                eventBus.post(new SyncEvent.LogEvent("Deleting [" + operationIndex + "/" + totalOperations + "]: " + pathToDelete));
                eventBus.post(new SyncEvent.FileProgressEvent(operationIndex, totalOperations, "[DEL] " + pathToDelete));
                protocol.sendFileDelete(pathToDelete);
            }

            for (String dirToDelete : emptyDirsToDelete) {
                operationIndex++;
                eventBus.post(new SyncEvent.LogEvent("Deleting dir [" + operationIndex + "/" + totalOperations + "]: " + dirToDelete));
                eventBus.post(new SyncEvent.FileProgressEvent(operationIndex, totalOperations, "[RMDIR] " + dirToDelete));
                protocol.sendRmdir(dirToDelete);
            }

            protocol.sendSyncComplete();
            eventBus.post(new SyncEvent.LogEvent("Sync completed successfully"));
            eventBus.post(new SyncEvent.TransferCompleteEvent());
            eventBus.post(new SyncEvent.SyncCompleteEvent());
        } catch (IOException e) {
            eventBus.post(new SyncEvent.ErrorEvent("Sync failed: " + e.getMessage()));
        } finally {
            syncing.set(false);
            touchHeartbeat();
            onSyncIdle.run();
        }
    }

    private void logSyncSummary(List<FileChangeDetector.FileInfo> filesToSync,
                                List<String> emptyDirsToCreate,
                                List<String> filesToDelete,
                                List<String> emptyDirsToDelete) {
        StringBuilder sb = new StringBuilder();
        sb.append("Files to sync: ").append(filesToSync.size());
        if (!emptyDirsToCreate.isEmpty()) {
            sb.append(", Empty dirs to create: ").append(emptyDirsToCreate.size());
        }
        if (strictSyncModeSupplier.getAsBoolean()) {
            sb.append(", Files to delete: ").append(filesToDelete.size());
            if (!emptyDirsToDelete.isEmpty()) {
                sb.append(", Empty dirs to delete: ").append(emptyDirsToDelete.size());
            }
        }
        eventBus.post(new SyncEvent.LogEvent(sb.toString()));
    }

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

    private void cleanupEmptyDirectories(File directory, File syncFolder) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return;
        }
        if (directory.equals(syncFolder)) {
            return;
        }
        String[] contents = directory.list();
        if (contents != null && contents.length == 0) {
            File parent = directory.getParentFile();
            if (directory.delete()) {
                cleanupEmptyDirectories(parent, syncFolder);
            }
        }
    }

    private void touchHeartbeat() {
        if (heartbeatTouch != null) {
            heartbeatTouch.run();
        }
    }
}

