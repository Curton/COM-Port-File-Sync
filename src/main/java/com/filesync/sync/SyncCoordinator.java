package com.filesync.sync;

import com.filesync.protocol.BatchTransferSession;
import com.filesync.protocol.SyncProtocol;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Coordinates sync operations, manifest exchange, and file transfers. */
public class SyncCoordinator {

    /** Unchecked exception used to abort sync when cancellation is requested. */
    private static class SyncCancelledException extends RuntimeException {
        SyncCancelledException() {}
    }

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
    private final Runnable onSyncIdle;
    private final Runnable onSyncBoundary;
    private final Runnable heartbeatTouch;

    private ScheduledExecutorService executor;

    // Cancellation flag for ongoing sync operations
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    public SyncCoordinator(
            SyncProtocol protocol,
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
     * Start sync, optionally using a pre-computed preview plan. When plan is non-null and matches
     * current sync options, skips manifest roundtrip. Plan is ignored if strict mode has changed
     * since it was created.
     */
    public void startSyncWithPlan(SyncPreviewPlan plan) {
        if (!isSenderSupplier.getAsBoolean()) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Cannot initiate sync as receiver. Change direction first."));
            return;
        }
        if (!connectionAliveSupplier.getAsBoolean()) {
            eventBus.post(new SyncEvent.ErrorEvent("Cannot initiate sync while disconnected"));
            return;
        }
        if (!roleNegotiatedSupplier.getAsBoolean()) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Cannot initiate sync until role negotiation completes"));
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
        final SyncPreviewPlan planToUse =
                (plan != null && plan.isStrictSyncMode() != strictSyncModeSupplier.getAsBoolean())
                        ? null
                        : plan;
        cancelRequested.set(false); // Reset cancellation flag before starting new sync
        syncing.set(true);
        if (executor != null) {
            executor.submit(() -> performSync(planToUse));
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

        FileChangeDetector.FileManifest localManifest =
                FileChangeDetector.generateManifest(syncFolder, respectGitignore, fastMode);

        eventBus.post(new SyncEvent.LogEvent("Requesting remote manifest..."));
        // Send our settings to the receiver so it generates manifest with the same options
        protocol.requestManifest(respectGitignore, fastMode);

        SyncProtocol.Message manifestMessage =
                protocol.waitForCommand(SyncProtocol.CMD_MANIFEST_DATA);
        protocol.sendAck();
        int expectedManifestSize =
                manifestMessage != null && manifestMessage.getParams().length > 0
                        ? manifestMessage.getParamAsInt(0)
                        : -1;
        FileChangeDetector.FileManifest remoteManifest =
                protocol.receiveManifest(expectedManifestSize);

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
        List<String> filesToDelete =
                strictMode
                        ? FileChangeDetector.getFilesToDelete(localManifest, remoteManifest)
                        : new ArrayList<>();
        filesToDelete.sort(Comparator.naturalOrder());

        List<String> emptyDirsToDelete =
                strictMode
                        ? FileChangeDetector.getEmptyDirectoriesToDelete(
                                localManifest, remoteManifest)
                        : new ArrayList<>();

        long totalBytesToTransfer =
                filesToSync.stream().mapToLong(FileChangeDetector.FileInfo::getSize).sum();

        // Detect conflicts: files modified on both sides
        List<ConflictInfo> conflicts =
                ConflictAnalyzer.findConflicts(localManifest, remoteManifest, syncFolder);

        if (!conflicts.isEmpty()) {
            eventBus.post(
                    new SyncEvent.LogEvent(
                            "Detected " + conflicts.size() + " potential conflict(s)"));
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

    /**
     * Cancel the ongoing sync. Clears sync state so the coordinator is ready for the next sync
     * attempt. Protocol-level cancellation is handled by the caller (FileSyncManager) via
     * restartListening which fully resets the connection.
     */
    public void cancelOngoingSync() {
        cancelRequested.set(true);
        syncing.set(false);
    }

    /**
     * Check if sync cancellation has been requested and exit early if so. Used between operation
     * groups in performSync() to allow early cancellation. The finally block in performSync()
     * handles cleanup.
     */
    private void exitSyncIfCancelled() {
        if (cancelRequested.get()) {
            eventBus.post(new SyncEvent.LogEvent("Sync cancelled"));
            eventBus.post(new SyncEvent.SyncCancelledEvent());
            throw new SyncCancelledException();
        }
    }

    /**
     * Handle manifest request from sender. Uses sender's settings if provided, otherwise falls back
     * to local settings. This ensures both sides generate manifests with the same options
     * (especially fast mode).
     *
     * @param senderRespectGitignore sender's respect gitignore setting, or null to use local
     * @param senderFastMode sender's fast mode setting, or null to use local
     */
    public void handleManifestRequest(Boolean senderRespectGitignore, Boolean senderFastMode)
            throws IOException {
        // Note: The original code used getAndSet(true) to detect nested calls and avoid
        // calling onSyncIdle for inner calls. This was redundant because the sync protocol
        // is single-threaded and manifest requests are processed sequentially (one peer
        // sends a manifest, the other receives it, then roles swap). The check is thus
        // unnecessary and this simplification ensures onSyncIdle always runs after completion.
        syncing.set(true);
        try {
            File syncFolder = syncFolderSupplier.get();
            if (syncFolder == null || !syncFolder.exists()) {
                protocol.sendError("Sync folder not configured");
                return;
            }

            // Use sender's settings if provided, otherwise use local settings
            boolean respectGitignore =
                    senderRespectGitignore != null
                            ? senderRespectGitignore
                            : respectGitignoreModeSupplier.getAsBoolean();
            boolean fastMode =
                    senderFastMode != null ? senderFastMode : fastModeSupplier.getAsBoolean();

            eventBus.post(new SyncEvent.LogEvent("Sending manifest..."));
            FileChangeDetector.FileManifest manifest =
                    FileChangeDetector.generateManifest(syncFolder, respectGitignore, fastMode);
            protocol.sendManifest(manifest);
            String logMsg = "Manifest sent (" + manifest.getFileCount() + " files";
            if (manifest.getEmptyDirectoryCount() > 0) {
                logMsg += ", " + manifest.getEmptyDirectoryCount() + " empty dirs";
            }
            logMsg += ")";
            eventBus.post(new SyncEvent.LogEvent(logMsg));
        } finally {
            syncing.set(false);
            onSyncIdle.run();
            touchHeartbeat();
            // Manifest XMODEM posts TRANSFER_PROGRESS (disables direction); preview has no
            // SYNC_COMPLETE on receiver.
            eventBus.post(new SyncEvent.SyncControlRefreshEvent());
        }
    }

    public void handleFileRequest(String relativePath) throws IOException {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null) {
            protocol.sendError("Sync folder not configured");
            return;
        }
        resolveSafe(syncFolder, relativePath);
        eventBus.post(new SyncEvent.LogEvent("Sending file: " + relativePath));
        protocol.sendFile(syncFolder, relativePath);
    }

    public void handleIncomingBatch(int expectedSize, int totalOperations) throws IOException {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null) {
            syncing.set(false);
            onSyncIdle.run();
            return;
        }
        syncing.set(true);
        try {
            BatchTransferSession.BatchProgressCallback callback =
                    (idx, total, relPath) -> {
                        eventBus.post(
                                new SyncEvent.LogEvent(
                                        "Batch receiving ["
                                                + (idx + 1)
                                                + "/"
                                                + totalOperations
                                                + "]: "
                                                + relPath));
                        touchHeartbeat();
                    };
            protocol.receiveBatch(expectedSize, totalOperations, callback, syncFolder);
            eventBus.post(new SyncEvent.LogEvent("Batch received successfully"));
        } finally {
            syncing.set(false);
            onSyncIdle.run();
        }
    }

    /**
     * Receive a batch when the total operation count is unknown (e.g., receiver-initiated sync).
     * Uses the batch entry count for progress reporting instead of overall operation count.
     */
    public void handleIncomingBatchUnknownTotal(int expectedSize) throws IOException {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null) {
            syncing.set(false);
            onSyncIdle.run();
            return;
        }
        syncing.set(true);
        try {
            BatchTransferSession.BatchProgressCallback callback =
                    (idx, total, relPath) -> {
                        eventBus.post(
                                new SyncEvent.LogEvent(
                                        "Batch receiving ["
                                                + (idx + 1)
                                                + "/"
                                                + total
                                                + "]: "
                                                + relPath));
                        touchHeartbeat();
                    };
            protocol.receiveBatch(expectedSize, 0, callback, syncFolder);
            eventBus.post(new SyncEvent.LogEvent("Batch received successfully"));
        } finally {
            syncing.set(false);
            onSyncIdle.run();
        }
    }

    public void handleIncomingFileData(SyncProtocol.Message msg) throws IOException {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null) {
            syncing.set(false);
            onSyncIdle.run();
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
            resolveSafe(syncFolder, relativePath);
            protocol.receiveFile(syncFolder, relativePath, size, compressed, lastModified);
            eventBus.post(new SyncEvent.LogEvent("File received: " + relativePath));
            touchHeartbeat();
            flushSharedTextBetweenOperations();
        } catch (IOException e) {
            syncing.set(false);
            onSyncIdle.run();
            // Re-throw so the listenLoop's catch(IOException) handles it (may restart)
            throw e;
        }
    }

    public void handleSyncComplete() {
        syncing.set(false);
        protocol.resetXmodemInProgress();
        touchHeartbeat();
        eventBus.post(new SyncEvent.SyncCompleteEvent());
    }

    public void handleFileDelete(String relativePath) throws IOException {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null) {
            return;
        }
        File fileToDelete = resolveSafe(syncFolder, relativePath);
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
        File dirToCreate;
        try {
            dirToCreate = resolveSafe(syncFolder, relativePath);
        } catch (IOException e) {
            eventBus.post(new SyncEvent.ErrorEvent("Invalid path: " + e.getMessage()));
            return;
        }
        if (!dirToCreate.exists()) {
            eventBus.post(new SyncEvent.LogEvent("Creating directory: " + relativePath));
            if (dirToCreate.mkdirs()) {
                eventBus.post(new SyncEvent.LogEvent("Directory created: " + relativePath));
                flushSharedTextBetweenOperations();
            } else {
                eventBus.post(
                        new SyncEvent.ErrorEvent("Failed to create directory: " + relativePath));
            }
        }
    }

    public void handleRmdir(String relativePath) {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null) {
            return;
        }
        File dirToDelete;
        try {
            dirToDelete = resolveSafe(syncFolder, relativePath);
        } catch (IOException e) {
            eventBus.post(new SyncEvent.ErrorEvent("Invalid path: " + e.getMessage()));
            return;
        }
        if (dirToDelete.exists() && dirToDelete.isDirectory()) {
            eventBus.post(new SyncEvent.LogEvent("Deleting directory: " + relativePath));
            if (deleteDirectoryRecursively(dirToDelete)) {
                eventBus.post(new SyncEvent.LogEvent("Directory deleted: " + relativePath));
                cleanupEmptyDirectories(dirToDelete.getParentFile(), syncFolder);
                flushSharedTextBetweenOperations();
            } else {
                eventBus.post(
                        new SyncEvent.ErrorEvent("Failed to delete directory: " + relativePath));
            }
        }
    }

    private void performSync(SyncPreviewPlan providedPlan) {
        try {
            eventBus.post(new SyncEvent.SyncStartedEvent());
            SyncPreviewPlan syncPlan =
                    providedPlan != null ? providedPlan : createSyncPreviewPlan();
            File syncFolder = syncFolderSupplier.get();

            // Apply conflict resolutions to local files first (e.g. KEEP_REMOTE + BOTH)
            // Must run before totalOperations check so KEEP_REMOTE-only sync still applies local
            // writes
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
            int savedOpIndex = 0;

            // Batch small files together to amortize XMODEM handshake overhead.
            // Files with conflicts must be sent individually because merged content
            // is computed per-file and may differ from the on-disk version.
            List<FileChangeDetector.FileInfo> filesToTransfer = syncPlan.getFilesToTransfer();
            List<FileChangeDetector.FileInfo> regularFiles = new ArrayList<>();
            List<FileChangeDetector.FileInfo> conflictFiles = new ArrayList<>();
            for (FileChangeDetector.FileInfo fi : filesToTransfer) {
                ConflictInfo conflict = syncPlan.getConflict(fi.getPath());
                if (conflict != null
                        && conflict.getResolution() == ConflictInfo.Resolution.MERGE
                        && conflict.getMergedContentAsBytes() != null) {
                    conflictFiles.add(fi);
                } else if (conflict != null
                        && conflict.getApplyTarget() == ConflictInfo.ApplyTarget.REMOTE_ONLY
                        && (conflict.getResolution() == ConflictInfo.Resolution.KEEP_REMOTE
                                || conflict.getResolution() == ConflictInfo.Resolution.SKIP)) {
                    // KEEP_REMOTE or SKIP with REMOTE_ONLY target: do not send local
                    // version (which is the older local file) to the remote.
                    eventBus.post(
                            new SyncEvent.LogEvent(
                                    "Skipping transfer for " + fi.getPath() + " ("
                                            + conflict.getResolution() + ")"));
                } else {
                    regularFiles.add(fi);
                }
            }

            // Send conflicted/merged files individually (each may have unique merged content)
            for (FileChangeDetector.FileInfo fileInfo : conflictFiles) {
                operationIndex++;
                String filePath = fileInfo.getPath();
                long fileSendStart = System.currentTimeMillis();
                byte[] mergedContent = syncPlan.getConflict(filePath).getMergedContentAsBytes();
                File mergedFile = new File(syncFolder, filePath);
                long lastModified = mergedFile.exists() ? mergedFile.lastModified() : 0L;
                boolean wasCompressed =
                        protocol.sendFile(syncFolder, filePath, mergedContent, lastModified);
                long fileSendMs = System.currentTimeMillis() - fileSendStart;
                String msg =
                        "Syncing (merged) ["
                                + operationIndex
                                + "/"
                                + totalOperations
                                + "]: "
                                + filePath;
                if (wasCompressed) msg += " (compressed)";
                msg += String.format(" [%dms]", fileSendMs);
                eventBus.post(new SyncEvent.LogEvent(msg));
                eventBus.post(
                        new SyncEvent.FileProgressEvent(operationIndex, totalOperations, filePath));
                flushSharedTextBetweenOperations();
            }

            // Send regular files in batches to reduce per-file XMODEM handshakes.
            // Each batch is a single XMODEM transfer; files within a batch are encoded
            // in a binary envelope and decoded atomically on the receiver side.
            if (!regularFiles.isEmpty()) {
                final int BATCH_BYTE_TARGET = 32 * 1024; // ~32 KB per batch; tune as needed
                List<Object[]> batch = new ArrayList<>();

                for (FileChangeDetector.FileInfo fileInfo : regularFiles) {
                    File file = new File(syncFolder, fileInfo.getPath());
                    batch.add(new Object[] {file, fileInfo.getPath()});

                    if (batch.size() >= 256 || estimateBatchSize(batch) >= BATCH_BYTE_TARGET) {
                        // Each batch gets its own callback capturing the correct starting index.
                        // savedOpIndex tracks the highest operation index already confirmed
                        // (by batch callback or fallback per-file progress), so the next batch
                        // continues without gaps or collisions.
                        int batchStartOpIdx = savedOpIndex + 1;
                        BatchTransferSession.BatchProgressCallback batchCallback =
                                (entryIdx, total, relPath) -> {
                                    int current = batchStartOpIdx + entryIdx;
                                    eventBus.post(
                                            new SyncEvent.LogEvent(
                                                    "Batch ["
                                                            + current
                                                            + "/"
                                                            + totalOperations
                                                            + "]: "
                                                            + relPath));
                                    eventBus.post(
                                            new SyncEvent.FileProgressEvent(
                                                    current, totalOperations, relPath));
                                };
                        long batchStart = System.currentTimeMillis();
                        int inBatch = batch.size();
                        boolean ok =
                                protocol.sendBatch(
                                        batch, BATCH_BYTE_TARGET, batchCallback, syncFolder);
                        long batchMs = System.currentTimeMillis() - batchStart;
                        if (!ok) {
                            eventBus.post(
                                    new SyncEvent.ErrorEvent(
                                            "Batch transfer failed for "
                                                    + inBatch
                                                    + " file(s); falling back to per-file"));
                            boolean anyFileFailed = false;
                            for (int i = 0; i < batch.size(); i++) {
                                if (cancelRequested.get()) {
                                    eventBus.post(
                                            new SyncEvent.LogEvent(
                                                    "Sync cancelled - stopping fallback transfers"));
                                    break;
                                }
                                String rp = (String) batch.get(i)[1];
                                savedOpIndex++;
                                operationIndex++;
                                long t0 = System.currentTimeMillis();
                                boolean sentOk = false;
                                try {
                                    sentOk = protocol.sendFile(syncFolder, rp);
                                } catch (IOException | IllegalStateException e) {
                                    if (cancelRequested.get()) {
                                        break;
                                    }
                                    anyFileFailed = true;
                                    eventBus.post(
                                            new SyncEvent.ErrorEvent(
                                                    "Failed to send file (fallback) "
                                                            + rp
                                                            + ": "
                                                            + e.getMessage()));
                                }
                                long ms = System.currentTimeMillis() - t0;
                                if (sentOk) {
                                    touchHeartbeat();
                                    eventBus.post(
                                            new SyncEvent.LogEvent(
                                                    "Syncing (fallback) ["
                                                            + savedOpIndex
                                                            + "/"
                                                            + totalOperations
                                                            + "]: "
                                                            + rp
                                                            + String.format(" [%dms]", ms)));
                                }
                                eventBus.post(
                                        new SyncEvent.FileProgressEvent(
                                                savedOpIndex, totalOperations, rp));
                            }
                            if (anyFileFailed) {
                                protocol.sendTransferCancel();
                                throw new IOException(
                                        "Failed to transfer "
                                                + inBatch
                                                + " file(s) after fallback attempts");
                            }
                        } else {
                            savedOpIndex = batchStartOpIdx + inBatch - 1;
                            operationIndex = savedOpIndex;
                            eventBus.post(
                                    new SyncEvent.LogEvent(
                                            "Batch of "
                                                    + inBatch
                                                    + " files sent in "
                                                    + batchMs
                                                    + "ms"));
                        }
                        batch.clear();
                        flushSharedTextBetweenOperations();
                    }
                }

                // Flush remaining small files as one final batch
                if (!batch.isEmpty()) {
                    int batchStartOpIdx = savedOpIndex + 1;
                    BatchTransferSession.BatchProgressCallback batchCallback =
                            (entryIdx, total, relPath) -> {
                                int current = batchStartOpIdx + entryIdx;
                                eventBus.post(
                                        new SyncEvent.LogEvent(
                                                "Batch ["
                                                        + current
                                                        + "/"
                                                        + totalOperations
                                                        + "]: "
                                                        + relPath));
                                eventBus.post(
                                        new SyncEvent.FileProgressEvent(
                                                current, totalOperations, relPath));
                            };
                    int inBatch = batch.size();
                    long batchStart = System.currentTimeMillis();
                    boolean ok =
                            protocol.sendBatch(batch, BATCH_BYTE_TARGET, batchCallback, syncFolder);
                    long batchMs = System.currentTimeMillis() - batchStart;
                    if (!ok) {
                        eventBus.post(
                                new SyncEvent.ErrorEvent(
                                        "Final batch transfer failed; falling back to per-file"));
                        boolean anyFileFailed = false;
                        for (int i = 0; i < batch.size(); i++) {
                            if (cancelRequested.get()) {
                                eventBus.post(
                                        new SyncEvent.LogEvent(
                                                "Sync cancelled - stopping fallback transfers"));
                                break;
                            }
                            String rp = (String) batch.get(i)[1];
                            savedOpIndex++;
                            operationIndex++;
                            long t0 = System.currentTimeMillis();
                            boolean sentOk = false;
                            try {
                                sentOk = protocol.sendFile(syncFolder, rp);
                            } catch (IOException | IllegalStateException e) {
                                if (cancelRequested.get()) {
                                    break;
                                }
                                anyFileFailed = true;
                                eventBus.post(
                                        new SyncEvent.ErrorEvent(
                                                "Failed to send file (fallback) "
                                                        + rp
                                                        + ": "
                                                        + e.getMessage()));
                            }
                            long ms = System.currentTimeMillis() - t0;
                            if (sentOk) {
                                touchHeartbeat();
                                eventBus.post(
                                        new SyncEvent.LogEvent(
                                                "Syncing (fallback) ["
                                                        + savedOpIndex
                                                        + "/"
                                                        + totalOperations
                                                        + "]: "
                                                        + rp
                                                        + String.format(" [%dms]", ms)));
                            }
                            eventBus.post(
                                    new SyncEvent.FileProgressEvent(
                                            savedOpIndex, totalOperations, rp));
                        }
                        if (anyFileFailed) {
                            protocol.sendTransferCancel();
                            throw new IOException(
                                    "Failed to transfer "
                                            + inBatch
                                            + " file(s) after fallback attempts");
                        }
                    } else {
                        savedOpIndex = batchStartOpIdx + inBatch - 1;
                        operationIndex = savedOpIndex;
                        eventBus.post(
                                new SyncEvent.LogEvent(
                                        "Batch of "
                                                + inBatch
                                                + " files sent in "
                                                + batchMs
                                                + "ms"));
                    }
                    batch.clear();
                    flushSharedTextBetweenOperations();
                }
            }

            exitSyncIfCancelled();

            for (String dirPath : syncPlan.getEmptyDirectoriesToCreate()) {
                operationIndex++;
                eventBus.post(
                        new SyncEvent.LogEvent(
                                "Creating dir ["
                                        + operationIndex
                                        + "/"
                                        + totalOperations
                                        + "]: "
                                        + dirPath));
                eventBus.post(
                        new SyncEvent.FileProgressEvent(
                                operationIndex, totalOperations, "[DIR] " + dirPath));
                protocol.sendMkdir(dirPath);
                flushSharedTextBetweenOperations();
            }

            exitSyncIfCancelled();

            for (String pathToDelete : syncPlan.getFilesToDelete()) {
                operationIndex++;
                eventBus.post(
                        new SyncEvent.LogEvent(
                                "Deleting ["
                                        + operationIndex
                                        + "/"
                                        + totalOperations
                                        + "]: "
                                        + pathToDelete));
                eventBus.post(
                        new SyncEvent.FileProgressEvent(
                                operationIndex, totalOperations, "[DEL] " + pathToDelete));
                protocol.sendFileDelete(pathToDelete);
                flushSharedTextBetweenOperations();
            }

            exitSyncIfCancelled();

            for (String dirToDelete : syncPlan.getEmptyDirectoriesToDelete()) {
                operationIndex++;
                eventBus.post(
                        new SyncEvent.LogEvent(
                                "Deleting dir ["
                                        + operationIndex
                                        + "/"
                                        + totalOperations
                                        + "]: "
                                        + dirToDelete));
                eventBus.post(
                        new SyncEvent.FileProgressEvent(
                                operationIndex, totalOperations, "[RMDIR] " + dirToDelete));
                protocol.sendRmdir(dirToDelete);
                flushSharedTextBetweenOperations();
            }

            exitSyncIfCancelled();

            protocol.sendSyncComplete();
            eventBus.post(new SyncEvent.LogEvent("Sync completed successfully"));
            eventBus.post(new SyncEvent.TransferCompleteEvent());
            eventBus.post(new SyncEvent.SyncCompleteEvent());
        } catch (SyncCancelledException e) {
            // Cancellation was already posted by exitSyncIfCancelled(); only cleanup needed here.
        } catch (IOException e) {
            eventBus.post(new SyncEvent.ErrorEvent("Sync failed: " + e.getMessage()));
        } finally {
            syncing.set(false);
            protocol.resetXmodemInProgress();
            touchHeartbeat();
            onSyncIdle.run();
        }
    }

    /**
     * Write resolved conflict content to local files. Only when ApplyTarget.BOTH: KEEP_REMOTE
     * overwrites local with remote; MERGE overwrites local with merged. When
     * ApplyTarget.REMOTE_ONLY, local file is not modified (changes apply to remote only). Sets
     * lastModified to match remote (KEEP_REMOTE) or preserve write time for MERGE so the next sync
     * does not re-detect the same conflict (fast mode uses size+lastModified).
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
                        eventBus.post(
                                new SyncEvent.LogEvent(
                                        "Could not set lastModified for "
                                                + path
                                                + ", may re-detect conflict"));
                    }
                }
                eventBus.post(
                        new SyncEvent.LogEvent("Applied conflict resolution to local: " + path));
            } catch (IOException e) {
                eventBus.post(
                        new SyncEvent.ErrorEvent(
                                "Failed to apply conflict resolution to "
                                        + path
                                        + ": "
                                        + e.getMessage()));
            }
        }
    }

    private void logSyncSummary(SyncPreviewPlan syncPlan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Files to sync: ").append(syncPlan.getFilesToTransfer().size());
        if (!syncPlan.getEmptyDirectoriesToCreate().isEmpty()) {
            sb.append(", Empty dirs to create: ")
                    .append(syncPlan.getEmptyDirectoriesToCreate().size());
        }
        if (syncPlan.isStrictSyncMode()) {
            sb.append(", Files to delete: ").append(syncPlan.getFilesToDelete().size());
            if (!syncPlan.getEmptyDirectoriesToDelete().isEmpty()) {
                sb.append(", Empty dirs to delete: ")
                        .append(syncPlan.getEmptyDirectoriesToDelete().size());
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

    private int estimateBatchSize(List<Object[]> batch) {
        long total = 0;
        for (Object[] entry : batch) {
            File f = (File) entry[0];
            String path = (String) entry[1];
            long rawSize = f.length();
            long estimatedContentSize = estimateCompressedSize(f, path, rawSize);
            total +=
                    2
                            + path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            + 8
                            + 1
                            + 4
                            + estimatedContentSize;
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * Estimate compressed file size using CompressionUtil.hasHighCompressionPotential. Reads a
     * sample of the file to determine if compression is beneficial, then uses the estimated
     * compression ratio to calculate the expected size.
     */
    private long estimateCompressedSize(File file, String relativePath, long rawSize) {
        if (rawSize <= 0) {
            return rawSize;
        }
        try {
            byte[] sample = readFileSample(file);
            if (CompressionUtil.hasHighCompressionPotential(relativePath, sample)) {
                double ratio = CompressionUtil.estimateCompressionRatio(sample);
                return Math.max(1, (long) (rawSize * ratio));
            }
        } catch (IOException e) {
            // Fall through to raw size on error
        }
        return rawSize;
    }

    /** Read a sample of file content (up to 4096 bytes) for compression analysis. */
    private byte[] readFileSample(File file) throws IOException {
        long fileSize = file.length();
        int sampleSize = (int) Math.min(fileSize, 4096);
        byte[] sample = new byte[sampleSize];
        int totalRead = 0;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            while (totalRead < sampleSize) {
                int read = fis.read(sample, totalRead, sampleSize - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
        }
        return totalRead < fileSize ? java.util.Arrays.copyOf(sample, totalRead) : sample;
    }

    /**
     * Resolve a remote-supplied relative path against a base directory, rejecting paths that
     * attempt to escape the base directory via {@code ../} segments.
     */
    static File resolveSafe(File baseDir, String relativePath) throws IOException {
        String normalized = relativePath.replace('\\', '/');
        if (normalized.startsWith("/")
                || normalized.contains("../")
                || normalized.contains("..\\")) {
            throw new IOException(
                    "Path traversal rejected: " + relativePath);
        }
        return new File(baseDir, relativePath);
    }
}
