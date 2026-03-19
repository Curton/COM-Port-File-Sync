package com.filesync.sync;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final BooleanSupplier roleNegotiatedSupplier;
    private final AtomicBoolean syncing;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final Runnable onSyncIdle;
    private final Runnable onSyncBoundary;
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
                           BooleanSupplier roleNegotiatedSupplier,
                           AtomicBoolean syncing,
                           Runnable onSyncIdle,
                           Runnable onSyncBoundary,
                           Runnable heartbeatTouch) {
        this.protocol = protocol;
        this.eventBus = eventBus;
        this.syncFolderSupplier = syncFolderSupplier;
        this.strictSyncModeSupplier = strictSyncModeSupplier;
        this.respectGitignoreModeSupplier = respectGitignoreModeSupplier;
        this.fastModeSupplier = fastModeSupplier;
        this.connectionAliveSupplier = connectionAliveSupplier;
        this.isSenderSupplier = isSenderSupplier;
        this.roleNegotiatedSupplier = roleNegotiatedSupplier;
        this.syncing = syncing;
        this.onSyncIdle = onSyncIdle;
        this.onSyncBoundary = onSyncBoundary;
        this.heartbeatTouch = heartbeatTouch;
    }

    public void setExecutor(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    public boolean isSyncing() {
        return syncing.get();
    }

    public void startSync() {
        startSync(null);
    }

    public void startSync(SyncPreviewPlan plan) {
        startSyncWithPlan(plan);
    }

    /**
     * Start sync, optionally using a pre-computed preview plan.
     * When plan is non-null and matches current sync options, skips manifest roundtrip.
     * Plan is ignored if strict mode has changed since it was created.
     */
    public void startSyncWithPlan(SyncPreviewPlan plan) {
        if (!isSenderSupplier.getAsBoolean()) {
            eventBus.post(new SyncEvent.ErrorEvent("Cannot initiate sync as receiver. Change direction first."));
            return;
        }
        if (!connectionAliveSupplier.getAsBoolean()) {
            eventBus.post(new SyncEvent.ErrorEvent("Cannot initiate sync while disconnected"));
            return;
        }
        if (!roleNegotiatedSupplier.getAsBoolean()) {
            eventBus.post(new SyncEvent.ErrorEvent("Cannot initiate sync until role negotiation completes"));
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
        cancelRequested.set(false);
        final SyncPreviewPlan planToUse = (plan != null && plan.isStrictSyncMode() != strictSyncModeSupplier.getAsBoolean())
                ? null
                : plan;
        syncing.set(true);
        if (executor != null) {
            syncFuture = executor.submit(() -> performSync(planToUse));
        } else {
            performSync(planToUse);
        }
    }

    public SyncPreviewPlan createSyncPreviewPlan() throws IOException {
        eventBus.post(new SyncEvent.LogEvent("Generating local manifest..."));

        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null || !syncFolder.exists()) {
            throw new IOException("Please select a sync folder first");
        }

        boolean respectGitignore = respectGitignoreModeSupplier.getAsBoolean();
        boolean fastMode = fastModeSupplier.getAsBoolean();

        FileChangeDetector.FileManifest localManifest = FileChangeDetector.generateManifest(
                syncFolder,
                respectGitignore,
                fastMode);

        eventBus.post(new SyncEvent.LogEvent("Requesting remote manifest..."));
        // Send our settings to the receiver so it generates manifest with the same options
        protocol.requestManifest(respectGitignore, fastMode);

        SyncProtocol.Message manifestMessage = protocol.waitForCommand(SyncProtocol.CMD_MANIFEST_DATA);
        protocol.sendAck();
        int expectedManifestSize = manifestMessage != null && manifestMessage.getParams().length > 0
                ? manifestMessage.getParamAsInt(0)
                : -1;
        FileChangeDetector.FileManifest remoteManifest = protocol.receiveManifest(expectedManifestSize);

        String logMsg = "Remote manifest received (" + remoteManifest.getFileCount() + " files";
        if (remoteManifest.getEmptyDirectoryCount() > 0) {
            logMsg += ", " + remoteManifest.getEmptyDirectoryCount() + " empty dirs";
        }
        logMsg += ")";
        eventBus.post(new SyncEvent.LogEvent(logMsg));

        List<FileChangeDetector.FileInfo> filesToSync =
                FileChangeDetector.getChangedFiles(localManifest, remoteManifest);
        filesToSync.sort(Comparator.comparing(FileChangeDetector.FileInfo::getPath));

        List<String> emptyDirsToCreate =
                FileChangeDetector.getEmptyDirectoriesToCreate(localManifest, remoteManifest);
        emptyDirsToCreate.sort(Comparator.naturalOrder());

        boolean strictMode = strictSyncModeSupplier.getAsBoolean();
        List<String> filesToDelete = strictMode
                ? FileChangeDetector.getFilesToDelete(localManifest, remoteManifest)
                : new ArrayList<>();
        filesToDelete.sort(Comparator.naturalOrder());

        List<String> emptyDirsToDelete = strictMode
                ? FileChangeDetector.getEmptyDirectoriesToDelete(localManifest, remoteManifest)
                : new ArrayList<>();

        long totalBytesToTransfer = filesToSync.stream()
                .mapToLong(FileChangeDetector.FileInfo::getSize)
                .sum();

        // Detect conflicts: files modified on both sides
        List<ConflictInfo> conflicts = ConflictAnalyzer.findConflicts(
                localManifest, remoteManifest, syncFolder);

        if (!conflicts.isEmpty()) {
            eventBus.post(new SyncEvent.LogEvent("Detected " + conflicts.size() + " potential conflict(s)"));
        }

        return new SyncPreviewPlan(
                filesToSync,
                emptyDirsToCreate,
                filesToDelete,
                emptyDirsToDelete,
                totalBytesToTransfer,
                strictMode,
                conflicts);
    }

    public void cancelOngoingSync() {
        cancelRequested.set(true);
        try {
            if (protocol.isXmodemInProgress()) {
                protocol.sendTransferCancel();
            } else {
                protocol.sendCancelCommand();
            }
        } catch (IOException ignored) {
            // Best effort cancellation; local sync state transitions are handled below.
        }
        if (syncFuture != null) {
            syncFuture.cancel(true);
            syncFuture = null;
        }
        syncing.set(false);
    }

    public void handleRemoteCancel(String reason) {
        cancelRequested.set(true);
        syncing.set(false);
        eventBus.post(new SyncEvent.LogEvent(buildCancellationMessage(reason)));
        eventBus.post(new SyncEvent.SyncCancelledEvent());
        onSyncIdle.run();
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
        boolean alreadySyncing = syncing.getAndSet(true);
        try {
            File syncFolder = syncFolderSupplier.get();
            if (syncFolder == null || !syncFolder.exists()) {
                protocol.sendError("Sync folder not configured");
                return;
            }

            // Use sender's settings if provided, otherwise use local settings
            boolean respectGitignore = senderRespectGitignore != null
                    ? senderRespectGitignore
                    : respectGitignoreModeSupplier.getAsBoolean();
            boolean fastMode = senderFastMode != null
                    ? senderFastMode
                    : fastModeSupplier.getAsBoolean();

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
        } finally {
            if (!alreadySyncing) {
                syncing.set(false);
                onSyncIdle.run();
            }
            touchHeartbeat();
        }
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
        try {
            protocol.receiveFile(syncFolder, relativePath, size, compressed, lastModified);
            eventBus.post(new SyncEvent.LogEvent("File received: " + relativePath));
            touchHeartbeat();
            flushSharedTextBetweenOperations();
        } catch (IOException e) {
            if (isSyncCancelledException(e)) {
                handleRemoteCancel("Transfer cancelled while receiving file " + relativePath);
            } else {
                throw e;
            }
        }
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
                flushSharedTextBetweenOperations();
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
                flushSharedTextBetweenOperations();
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
                flushSharedTextBetweenOperations();
            } else {
                eventBus.post(new SyncEvent.ErrorEvent("Failed to delete directory: " + relativePath));
            }
        }
    }

    private void performSync(SyncPreviewPlan providedPlan) {
        try {
            eventBus.post(new SyncEvent.SyncStartedEvent());
            SyncPreviewPlan syncPlan = providedPlan != null ? providedPlan : createSyncPreviewPlan();
            File syncFolder = syncFolderSupplier.get();

            // Apply conflict resolutions to local files first (e.g. KEEP_REMOTE + BOTH)
            // Must run before totalOperations check so KEEP_REMOTE-only sync still applies local writes
            applyConflictResolutionsToLocalFiles(syncPlan, syncFolder);

            int totalOperations = syncPlan.getTotalOperations();
            if (totalOperations == 0) {
                eventBus.post(new SyncEvent.LogEvent("No files need to be synced or deleted"));
                eventBus.post(new SyncEvent.SyncCompleteEvent());
                syncing.set(false);
                onSyncIdle.run();
                return;
            }

            logSyncSummary(syncPlan);

            int operationIndex = 0;
            for (FileChangeDetector.FileInfo fileInfo : syncPlan.getFilesToTransfer()) {
                operationIndex++;
                String filePath = fileInfo.getPath();
                ConflictInfo conflict = syncPlan.getConflict(filePath);
                boolean wasCompressed;
                String message;
                if (conflict != null && conflict.getResolution() == ConflictInfo.Resolution.MERGE) {
                    byte[] mergedContent = conflict.getMergedContentAsBytes();
                    if (mergedContent != null) {
                        File mergedFile = new File(syncFolder, filePath);
                        long lastModified = mergedFile.exists() ? mergedFile.lastModified() : 0L;
                        wasCompressed = protocol.sendFile(syncFolder, filePath, mergedContent, lastModified);
                        if (!wasCompressed) {
                            eventBus.post(new SyncEvent.ErrorEvent("Failed to send merged file: " + filePath));
                        }
                        message = "Syncing (merged) [" + operationIndex + "/" + totalOperations + "]: " + filePath;
                    } else {
                        wasCompressed = protocol.sendFile(syncFolder, filePath);
                        message = "Syncing [" + operationIndex + "/" + totalOperations + "]: " + filePath;
                    }
                } else {
                    wasCompressed = protocol.sendFile(syncFolder, filePath);
                    message = "Syncing [" + operationIndex + "/" + totalOperations + "]: " + filePath;
                }
                if (wasCompressed) {
                    message += " (compressed)";
                }
                eventBus.post(new SyncEvent.LogEvent(message));
                eventBus.post(new SyncEvent.FileProgressEvent(operationIndex, totalOperations, filePath));
                flushSharedTextBetweenOperations();
            }

            for (String dirPath : syncPlan.getEmptyDirectoriesToCreate()) {
                operationIndex++;
                eventBus.post(new SyncEvent.LogEvent("Creating dir [" + operationIndex + "/" + totalOperations + "]: " + dirPath));
                eventBus.post(new SyncEvent.FileProgressEvent(operationIndex, totalOperations, "[DIR] " + dirPath));
                protocol.sendMkdir(dirPath);
                flushSharedTextBetweenOperations();
            }

            for (String pathToDelete : syncPlan.getFilesToDelete()) {
                operationIndex++;
                eventBus.post(new SyncEvent.LogEvent("Deleting [" + operationIndex + "/" + totalOperations + "]: " + pathToDelete));
                eventBus.post(new SyncEvent.FileProgressEvent(operationIndex, totalOperations, "[DEL] " + pathToDelete));
                protocol.sendFileDelete(pathToDelete);
                flushSharedTextBetweenOperations();
            }

            for (String dirToDelete : syncPlan.getEmptyDirectoriesToDelete()) {
                operationIndex++;
                eventBus.post(new SyncEvent.LogEvent("Deleting dir [" + operationIndex + "/" + totalOperations + "]: " + dirToDelete));
                eventBus.post(new SyncEvent.FileProgressEvent(operationIndex, totalOperations, "[RMDIR] " + dirToDelete));
                protocol.sendRmdir(dirToDelete);
                flushSharedTextBetweenOperations();
            }

            protocol.sendSyncComplete();
            eventBus.post(new SyncEvent.LogEvent("Sync completed successfully"));
            eventBus.post(new SyncEvent.TransferCompleteEvent());
            eventBus.post(new SyncEvent.SyncCompleteEvent());
        } catch (IOException e) {
            if (cancelRequested.get()) {
                eventBus.post(new SyncEvent.LogEvent("Sync cancelled"));
                eventBus.post(new SyncEvent.SyncCancelledEvent());
            } else {
                eventBus.post(new SyncEvent.ErrorEvent("Sync failed: " + e.getMessage()));
            }
        } finally {
            syncing.set(false);
            cancelRequested.set(false);
            touchHeartbeat();
            onSyncIdle.run();
        }
    }

    /**
     * Write resolved conflict content to local files.
     * Only when ApplyTarget.BOTH: KEEP_REMOTE overwrites local with remote; MERGE overwrites local with merged.
     * When ApplyTarget.REMOTE_ONLY, local file is not modified (changes apply to remote only).
     * Sets lastModified to match remote (KEEP_REMOTE) or preserve write time for MERGE
     * so the next sync does not re-detect the same conflict (fast mode uses size+lastModified).
     */
    private void applyConflictResolutionsToLocalFiles(SyncPreviewPlan syncPlan, File syncFolder) {
        for (ConflictInfo conflict : syncPlan.getConflicts()) {
            if (conflict.getApplyTarget() != ConflictInfo.ApplyTarget.BOTH) {
                continue;
            }
            ConflictInfo.Resolution res = conflict.getResolution();
            byte[] contentToWrite = null;
            if (res == ConflictInfo.Resolution.KEEP_REMOTE) {
                contentToWrite = conflict.getRemoteContent();
            } else if (res == ConflictInfo.Resolution.MERGE) {
                contentToWrite = conflict.getMergedContentAsBytes();
            }
            if (contentToWrite == null) {
                continue;
            }
            String path = conflict.getPath();
            File file = new File(syncFolder, path);
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                Files.write(file.toPath(), contentToWrite);
                if (res == ConflictInfo.Resolution.KEEP_REMOTE) {
                    long remoteLastModified = conflict.getRemoteInfo().getLastModified();
                    if (remoteLastModified > 0 && !file.setLastModified(remoteLastModified)) {
                        eventBus.post(new SyncEvent.LogEvent("Could not set lastModified for " + path + ", may re-detect conflict"));
                    }
                }
                eventBus.post(new SyncEvent.LogEvent("Applied conflict resolution to local: " + path));
            } catch (IOException e) {
                eventBus.post(new SyncEvent.ErrorEvent("Failed to apply conflict resolution to " + path + ": " + e.getMessage()));
            }
        }
    }

    private void logSyncSummary(SyncPreviewPlan syncPlan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Files to sync: ").append(syncPlan.getFilesToTransfer().size());
        if (!syncPlan.getEmptyDirectoriesToCreate().isEmpty()) {
            sb.append(", Empty dirs to create: ").append(syncPlan.getEmptyDirectoriesToCreate().size());
        }
        if (syncPlan.isStrictSyncMode()) {
            sb.append(", Files to delete: ").append(syncPlan.getFilesToDelete().size());
            if (!syncPlan.getEmptyDirectoriesToDelete().isEmpty()) {
                sb.append(", Empty dirs to delete: ").append(syncPlan.getEmptyDirectoriesToDelete().size());
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

    private void flushSharedTextBetweenOperations() {
        if (onSyncBoundary != null) {
            onSyncBoundary.run();
        }
    }

    private boolean isSyncCancelledException(IOException e) {
        String message = e != null ? e.getMessage() : null;
        return message != null && message.contains("Transfer cancelled");
    }

    private String buildCancellationMessage(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Sync cancelled";
        }
        return "Sync cancelled: " + reason;
    }
}

