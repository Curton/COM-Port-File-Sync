package com.filesync.sync;

import com.filesync.delta.DeltaEncoder;
import com.filesync.delta.FileSignatures;
import com.filesync.delta.HashUtil;
import com.filesync.delta.SignatureSet;
import com.filesync.delta.SignatureUtil;
import com.filesync.protocol.BatchTransferSession;
import com.filesync.protocol.FileWriteException;
import com.filesync.protocol.SyncProtocol;
import com.filesync.protocol.TransferCancelledException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
    private final PendingFileWriteService pendingFileWriteService;
    private final AtomicBoolean syncing;
    private final Runnable onSyncIdle;
    private final Runnable onSyncBoundary;
    private final Runnable heartbeatTouch;

    /**
     * Opens/closes the sender-side protocol-exchange gate while a synchronous serial exchange is in
     * flight, so the heartbeat scheduler pauses outbound heartbeats (concurrent writes to the
     * serial stream would interleave frames). No-op unless wired via {@link
     * #setProtocolExchangeGate(Consumer)}.
     */
    private Consumer<Boolean> protocolExchangeGate = value -> {};

    /**
     * Reports unrecoverable link failures (e.g. read timeouts) so the connection layer tears the
     * link down and starts recovery immediately instead of waiting for the next heartbeat check.
     */
    private Consumer<String> communicationFailureReporter = reason -> {};

    private ScheduledExecutorService executor;

    /**
     * The worker running the current sync, if any. Held only to interrupt a worker blocked in a
     * serial read when the user cancels; cleared by the worker itself on exit.
     */
    private volatile Future<?> syncWorkerFuture;

    /**
     * Signature cache of the sync currently in progress, if any. Shared with {@code
     * handleIncomingBaseStale} (which may run on the listener thread): recording the rejection on
     * the session's instance keeps a later session flush from overwriting it with the stale
     * pre-rejection in-memory map.
     */
    private volatile SignatureCache activeSignatureCache;

    // Cancellation flag for ongoing sync operations
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    /** Minimum file size for rsync-style delta transfer; below this a full transfer is cheaper. */
    static final long MIN_DELTA_SIZE = 8 * 1024L;

    /**
     * Minimum wire-byte saving for a delta to justify its own XMODEM session. Measured, not
     * estimated: {@code DeltaThresholdBenchmark} runs the real protocol stack over a wire paced at
     * the true 115200-baud byte time and shows a per-file session costs ~80-100 ms even for a
     * near-empty payload (command round trip, handshake, per-block ACKs, EOT) — roughly 1 KB of
     * wire bytes — while a batch entry rides a session that is already happening. The value keeps
     * ~2x margin over that measured floor for USB-serial round-trip latency and scheduler variance;
     * the old 8 KB guess was ~9x the floor and pushed files saving 1.5-7 KB into a full-content
     * batch session that measured ~300 ms slower end to end.
     */
    static final long MIN_DELTA_SAVINGS_BYTES = 2 * 1024L;

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
            PendingFileWriteService pendingFileWriteService,
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
        this.pendingFileWriteService = pendingFileWriteService;
        this.syncing = syncing;
        this.onSyncIdle = onSyncIdle;
        this.onSyncBoundary = onSyncBoundary;
        this.heartbeatTouch = heartbeatTouch;
    }

    public void setExecutor(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    public void setProtocolExchangeGate(Consumer<Boolean> gate) {
        this.protocolExchangeGate = gate != null ? gate : value -> {};
    }

    public void setCommunicationFailureReporter(Consumer<String> reporter) {
        this.communicationFailureReporter = reporter != null ? reporter : reason -> {};
    }

    /**
     * Whether the IOException signals a read timeout — the peer stopped responding on the serial
     * line (SerialPortManager's "Read timeout..." or waitForCommand's "Timeout waiting for
     * command..."), as opposed to a protocol-level error from a healthy link.
     */
    static boolean isReadTimeout(IOException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("Read timeout")
                        || message.contains("Timeout waiting for command"));
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
            syncWorkerFuture = executor.submit(() -> performSync(planToUse));
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
                FileChangeDetector.generateManifestWithCache(
                        syncFolder, respectGitignore, fastMode);

        eventBus.post(new SyncEvent.LogEvent("Requesting remote manifest..."));
        // Send our settings to the receiver so it generates manifest with the same options

        // Use an extended timeout for the manifest exchange because the receiver may need
        // significant time to walk and hash its folder (especially for large projects).
        FileChangeDetector.FileManifest remoteManifest;
        int savedTimeout = protocol.getTimeout();
        protocol.setTimeout(120_000); // 120 seconds for large manifests
        // The protocol-exchange gate opens only now, after local manifest generation: hashing the
        // local tree touches no serial I/O, and holding the gate open during it silences outbound
        // heartbeats long enough (folder walks can take a minute) for the idle peer to declare
        // "Connection lost - no heartbeat response" and tear the link down mid-preview.
        protocolExchangeGate.accept(true);
        try {
            protocol.requestManifest(respectGitignore, fastMode);

            SyncProtocol.Message manifestMessage =
                    protocol.waitForCommand(SyncProtocol.CMD_MANIFEST_DATA);
            protocol.sendAck();
            int expectedManifestSize =
                    manifestMessage != null && manifestMessage.getParams().length > 0
                            ? manifestMessage.getParamAsInt(0)
                            : -1;
            remoteManifest = protocol.receiveManifest(expectedManifestSize);
        } finally {
            protocolExchangeGate.accept(false);
            protocol.setTimeout(savedTimeout);
        }

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

        // A receiver file that is a byte-prefix of the sender's (e.g. an archive copied in halfway
        // through an outside-the-sync transfer, so its copy mtime reads as "receiver modified") is
        // not a conflict. Exempt it from the conflict path so it reaches the append/delta machinery
        // and only the missing tail is transferred instead of the full file.
        Set<String> appendResumablePaths =
                ConflictAnalyzer.exemptPrefixShapedConflicts(conflicts, syncFolder);
        if (!appendResumablePaths.isEmpty()) {
            eventBus.post(
                    new SyncEvent.LogEvent(
                            appendResumablePaths.size()
                                    + " conflicted file(s) match a receiver-side prefix;"
                                    + " transferring only the missing tail"));
        }

        // Identify delta candidates: files present on both sides that differ, are large enough,
        // and are not in conflict. Content type is not filtered — block matching is
        // content-agnostic, and append-only logs in particular benefit from it.
        // Signatures are exchanged lazily in performSync only when a sync actually runs.
        Set<String> deltaCandidatePaths =
                selectDeltaCandidates(filesToSync, remoteManifest, conflicts, syncFolder);
        if (!deltaCandidatePaths.isEmpty()) {
            eventBus.post(
                    new SyncEvent.LogEvent(
                            deltaCandidatePaths.size() + " file(s) eligible for delta transfer"));
        }

        // Probe the non-conflict candidates for the same prefix shape: after an interrupted append
        // is salvaged the receiver's mtime matches the sender's, so the file classifies as a plain
        // modification even though only the missing tail will be sent. Skip paths already exempted
        // above so a large file is not prefix-hashed twice per preview.
        Set<String> probedCandidates = new LinkedHashSet<>(deltaCandidatePaths);
        probedCandidates.removeAll(appendResumablePaths);
        Set<String> appendShapedModified =
                ConflictAnalyzer.findPrefixShapedDeltaCandidates(
                        probedCandidates, remoteManifest, syncFolder);
        if (!appendShapedModified.isEmpty()) {
            eventBus.post(
                    new SyncEvent.LogEvent(
                            appendShapedModified.size()
                                    + " modified file(s) match a receiver-side prefix;"
                                    + " transferring only the missing tail"));
            appendResumablePaths.addAll(appendShapedModified);
        }

        return new SyncPreviewPlan(
                filesToSync,
                emptyDirsToCreate,
                filesToDelete,
                emptyDirsToDelete,
                totalBytesToTransfer,
                strictMode,
                conflicts,
                deltaCandidatePaths,
                appendResumablePaths,
                new HashSet<>(remoteManifest.getFiles().keySet()),
                remoteManifest.getFiles());
    }

    /**
     * Select paths eligible for rsync-style delta transfer: present on both sides (so the receiver
     * has a base to diff against), large enough to be worth the signature round-trip, and not
     * subject to a conflict. Text and binary content are treated alike; files whose blocks do not
     * match (e.g. cross-platform line-ending variants) simply produce a non-beneficial delta and
     * fall back to the full batch transfer.
     */
    Set<String> selectDeltaCandidates(
            List<FileChangeDetector.FileInfo> filesToSync,
            FileChangeDetector.FileManifest remoteManifest,
            List<ConflictInfo> conflicts,
            File syncFolder) {
        Set<String> conflictPaths = new LinkedHashSet<>();
        for (ConflictInfo c : conflicts) {
            conflictPaths.add(c.getPath());
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (FileChangeDetector.FileInfo fi : filesToSync) {
            String path = fi.getPath();
            if (fi.getSize() < MIN_DELTA_SIZE) {
                continue;
            }
            if (!remoteManifest.getFiles().containsKey(path)) {
                continue; // new file on sender, no receiver base to delta against
            }
            if (conflictPaths.contains(path)) {
                continue; // conflicts need full-transfer / merge handling
            }
            File file = new File(syncFolder, path);
            if (!file.isFile()) {
                continue;
            }
            candidates.add(path);
        }
        return candidates;
    }

    /** An append-only transfer candidate: the sender's file is the receiver's file plus a tail. */
    private static final class AppendCandidate {
        final FileChangeDetector.FileInfo fileInfo;
        final File file;
        final String path;
        final byte[] tail;
        final long baseSize;
        final long finalSize;
        final String finalMd5;

        AppendCandidate(
                FileChangeDetector.FileInfo fileInfo,
                File file,
                byte[] tail,
                long baseSize,
                long finalSize,
                String finalMd5) {
            this.fileInfo = fileInfo;
            this.file = file;
            this.path = fileInfo.getPath();
            this.tail = tail;
            this.baseSize = baseSize;
            this.finalSize = finalSize;
            this.finalMd5 = finalMd5;
        }
    }

    /**
     * Detect whether a delta candidate is a pure append of the receiver's file: the remote copy
     * must have a manifest md5, be strictly shorter than the local file, and the local prefix of
     * that length must hash (with the manifest's line-ending normalization) to the same md5. Only
     * then is the change provably "old content + new tail", and only the tail needs transferring.
     *
     * <p>The prefix is hashed streaming off disk and only the tail is ever held in memory, so
     * detection cost is bounded regardless of file size. Returns null when the shape does not hold
     * (new file, shrink, mid-file edit, unreadable file, quick-hash manifest without an md5, or a
     * receiver state that already rejected a previous transfer); the caller then keeps the file on
     * the regular signature-delta path.
     */
    private AppendCandidate detectAppendCandidate(
            FileChangeDetector.FileInfo fi,
            SyncPreviewPlan syncPlan,
            File syncFolder,
            SignatureCache signatureCache) {
        FileChangeDetector.FileInfo remote = syncPlan.getRemoteFileInfo(fi.getPath());
        if (remote == null || remote.getMd5() == null || remote.getMd5().isEmpty()) {
            return null;
        }
        if (signatureCache != null && signatureCache.isRejected(fi.getPath(), remote)) {
            return null; // the receiver already refused a transfer against this exact state
        }
        File file = new File(syncFolder, fi.getPath());
        long baseSize = remote.getSize();
        long localLength = file.length();
        if (!file.isFile()
                || baseSize < 0
                || localLength <= baseSize
                || localLength > Integer.MAX_VALUE) {
            return null;
        }
        FileChangeDetector.PrefixHash prefixHash;
        try {
            prefixHash = FileChangeDetector.hashFilePrefix(file, baseSize);
        } catch (IOException e) {
            return null; // unreadable, or the file shrank below the base while hashing
        }
        if (!prefixHash.manifestMd5().equals(remote.getMd5())) {
            return null; // not a pure append: the prefix content differs
        }
        byte[] tail = new byte[(int) (localLength - baseSize)];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(baseSize);
            raf.readFully(tail);
        } catch (IOException e) {
            return null; // the file changed shape while the tail was being read
        }
        return new AppendCandidate(
                fi, file, tail, baseSize, localLength, prefixHash.rawMd5With(tail));
    }

    /**
     * Sender-side signature cache for the given sync folder. Factory method so tests can redirect
     * the on-disk location; the default stores one JSON file per sync folder under {@code
     * <user.home>/.filesync/}.
     */
    SignatureCache createSignatureCache(File syncFolder) {
        return SignatureCache.forFolder(syncFolder);
    }

    /**
     * Cancel the ongoing sync. Clears sync state so the coordinator is ready for the next sync
     * attempt. The connection, listener and negotiated role are untouched; the caller
     * (FileSyncManager) notifies the peer and calls {@link #interruptOngoingSync()} so a worker
     * blocked in a serial read aborts promptly instead of at the next retry timeout.
     */
    public void cancelOngoingSync() {
        cancelRequested.set(true);
        syncing.set(false);
    }

    /**
     * Interrupt the worker of the sync currently in progress (if any), so a blocking serial read
     * unwinds as an interrupt-driven IOException and performSync exits through its benign
     * cancellation path. The link, listener and heartbeat scheduler stay up.
     */
    public void interruptOngoingSync() {
        Future<?> worker = syncWorkerFuture;
        if (worker != null) {
            worker.cancel(true);
        }
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
        // Time-sync marker paired with the sender's (posted in performSync) so the combined-log
        // save can measure the clock offset between the two machines.
        eventBus.post(new SyncEvent.LogEvent(TimeSyncMarker.markerMessage()));
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
                    FileChangeDetector.generateManifestWithCache(
                            syncFolder, respectGitignore, fastMode);
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
            int[] failedCount = new int[1];
            BatchTransferSession.BatchProgressCallback callback =
                    (idx, total, relPath) -> {
                        pendingFileWriteService.markWritten(relPath);
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
            BatchTransferSession.WriteFailureHandler failureHandler =
                    (path, data, lastModified, message) -> {
                        failedCount[0]++;
                        pendingFileWriteService.enqueue(
                                syncFolder, path, data, lastModified, message);
                    };
            int written =
                    protocol.receiveBatch(
                            expectedSize, totalOperations, callback, syncFolder, failureHandler);
            if (failedCount[0] > 0) {
                eventBus.post(
                        new SyncEvent.LogEvent(
                                "Batch received ("
                                        + written
                                        + " files, "
                                        + failedCount[0]
                                        + " waiting for user decision)"));
            } else {
                eventBus.post(new SyncEvent.LogEvent("Batch received successfully"));
            }
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
            int[] failedCount = new int[1];
            BatchTransferSession.BatchProgressCallback callback =
                    (idx, total, relPath) -> {
                        pendingFileWriteService.markWritten(relPath);
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
            BatchTransferSession.WriteFailureHandler failureHandler =
                    (path, data, lastModified, message) -> {
                        failedCount[0]++;
                        pendingFileWriteService.enqueue(
                                syncFolder, path, data, lastModified, message);
                    };
            int written =
                    protocol.receiveBatch(expectedSize, 0, callback, syncFolder, failureHandler);
            if (failedCount[0] > 0) {
                eventBus.post(
                        new SyncEvent.LogEvent(
                                "Batch received ("
                                        + written
                                        + " files, "
                                        + failedCount[0]
                                        + " waiting for user decision)"));
            } else {
                eventBus.post(new SyncEvent.LogEvent("Batch received successfully"));
            }
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
            pendingFileWriteService.markWritten(relativePath);
            touchHeartbeat();
            flushSharedTextBetweenOperations();
        } catch (FileWriteException e) {
            // The transfer succeeded but the target file is locked by another program: queue it
            // for a user decision instead of dropping it or tearing down the connection.
            syncing.set(false);
            onSyncIdle.run();
            // Transfer-progress events left the Sync Control button in its "Cancel" state.
            // Now that syncing is false, refresh it so the user sees "Start Sync" while the
            // pending-write dialog is open instead of a stale, enabled "Cancel" button.
            eventBus.post(new SyncEvent.SyncControlRefreshEvent());
            pendingFileWriteService.enqueue(
                    syncFolder,
                    e.getRelativePath(),
                    e.getData(),
                    e.getLastModified(),
                    e.getMessage());
        } catch (IOException e) {
            syncing.set(false);
            onSyncIdle.run();
            // Re-throw so the listenLoop's catch(IOException) handles it (may restart)
            throw e;
        }
    }

    /**
     * Receiver-side handler for {@link SyncProtocol#CMD_DELTA_SIG_REQ}: compute block signatures
     * for the requested paths that exist locally and return them as a single {@link SignatureSet}.
     * Missing or unreadable files are silently omitted; the sender treats an absent path as "no
     * signatures available" and falls back to a full transfer for it.
     */
    public void handleDeltaSigRequest(List<String> paths) throws IOException {
        File syncFolder = syncFolderSupplier.get();
        if (syncFolder == null || !syncFolder.exists()) {
            protocol.sendError("Sync folder not configured");
            return;
        }
        syncing.set(true);
        try {
            List<FileSignatures> entries = new ArrayList<>();
            for (String path : paths) {
                try {
                    File file = resolveSafe(syncFolder, path);
                    if (!file.exists() || !file.isFile()) {
                        continue;
                    }
                    entries.add(SignatureUtil.compute(path, file));
                } catch (IOException e) {
                    // Skip unreadable files; sender falls back to full transfer.
                    eventBus.post(
                            new SyncEvent.LogEvent(
                                    "Skipping signature for " + path + ": " + e.getMessage()));
                }
            }
            eventBus.post(
                    new SyncEvent.LogEvent(
                            "Sending block signatures for " + entries.size() + " file(s)..."));
            protocol.sendDeltaSignatures(new SignatureSet(entries));
        } finally {
            syncing.set(false);
            onSyncIdle.run();
        }
    }

    /**
     * Receiver-side handler for {@link SyncProtocol#CMD_FILE_DELTA}: receive the delta, reconstruct
     * the file from the existing local copy, verify the MD5, and write it. A locked-target write
     * failure queues the reconstructed bytes for deferred retry; an MD5 mismatch or decode error
     * re-throws so the listen loop can restart (the file is re-evaluated on the next sync).
     */
    public void handleIncomingFileDelta(SyncProtocol.Message msg) throws IOException {
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
        long sourceSize = msg.getParams().length > 4 ? msg.getParamAsLong(4) : 0L;
        String sourceMd5 = msg.getParams().length > 5 ? msg.getParam(5) : null;

        eventBus.post(new SyncEvent.LogEvent("Receiving delta: " + relativePath));
        protocol.sendAck();
        try {
            resolveSafe(syncFolder, relativePath);
            protocol.receiveFileDelta(
                    syncFolder,
                    relativePath,
                    size,
                    compressed,
                    lastModified,
                    sourceSize,
                    sourceMd5);
            eventBus.post(new SyncEvent.LogEvent("Delta applied: " + relativePath));
            pendingFileWriteService.markWritten(relativePath);
            touchHeartbeat();
            flushSharedTextBetweenOperations();
        } catch (FileWriteException e) {
            // Reconstruction succeeded but the target is locked: queue the reconstructed bytes.
            syncing.set(false);
            onSyncIdle.run();
            // Refresh the Sync Control button (see handleIncomingFileData for rationale): the
            // delta transfer left it as an enabled "Cancel", but syncing is now false.
            eventBus.post(new SyncEvent.SyncControlRefreshEvent());
            pendingFileWriteService.enqueue(
                    syncFolder,
                    e.getRelativePath(),
                    e.getData(),
                    e.getLastModified(),
                    e.getMessage());
        } catch (IOException e) {
            syncing.set(false);
            onSyncIdle.run();
            // Re-throw: an MD5 mismatch or decode error aborts/restarts the sync; the file is
            // recompared against fresh manifests on the next attempt and sent fully if needed.
            throw e;
        }
    }

    /**
     * Receiver-side handler for {@link SyncProtocol#CMD_FILE_APPEND}: receive only the appended
     * tail of a file whose prefix matches the local copy, verify the reconstruction against the
     * sender's raw MD5, and write it. Failure handling mirrors {@link #handleIncomingFileDelta}: a
     * locked-target write failure queues the reconstructed bytes for deferred retry; any other
     * failure re-throws so the listen loop can restart (the file is re-evaluated on the next sync).
     */
    public void handleIncomingFileAppend(SyncProtocol.Message msg) throws IOException {
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
        long baseSize = msg.getParams().length > 4 ? msg.getParamAsLong(4) : 0L;
        long finalSize = msg.getParams().length > 5 ? msg.getParamAsLong(5) : 0L;
        String finalMd5 = msg.getParams().length > 6 ? msg.getParam(6) : null;

        eventBus.post(new SyncEvent.LogEvent("Receiving append: " + relativePath));
        protocol.sendAck();
        try {
            resolveSafe(syncFolder, relativePath);
            protocol.receiveFileAppend(
                    syncFolder,
                    relativePath,
                    size,
                    compressed,
                    lastModified,
                    baseSize,
                    finalSize,
                    finalMd5);
            eventBus.post(new SyncEvent.LogEvent("Append applied: " + relativePath));
            pendingFileWriteService.markWritten(relativePath);
            touchHeartbeat();
            flushSharedTextBetweenOperations();
        } catch (FileWriteException e) {
            // Reconstruction succeeded but the target is locked: queue the reconstructed bytes.
            syncing.set(false);
            onSyncIdle.run();
            // Refresh the Sync Control button (see handleIncomingFileData for rationale): the
            // append transfer left it as an enabled "Cancel", but syncing is now false.
            eventBus.post(new SyncEvent.SyncControlRefreshEvent());
            pendingFileWriteService.enqueue(
                    syncFolder,
                    e.getRelativePath(),
                    e.getData(),
                    e.getLastModified(),
                    e.getMessage());
        } catch (IOException e) {
            syncing.set(false);
            onSyncIdle.run();
            // Re-throw: a verification failure aborts/restarts the sync; the file is recompared
            // against fresh manifests on the next attempt and sent fully if needed.
            throw e;
        }
    }

    /**
     * Sender-side handler for {@link SyncProtocol#CMD_BASE_STALE}: the receiver rejected a
     * delta/append because its current file is not the state this side diffed against — a change
     * the manifest cannot see (e.g. a lone-CR/LF swap with identical size, lastModified and
     * normalized md5). Record the receiver state named in the message as rejected so both fast
     * paths skip it until the file visibly changes; without the memo the sender would repeat the
     * same rejected transfer on every sync. Must not throw: it also runs from the listener thread's
     * dispatch and from the middle of {@code SyncProtocol#waitForCommand}.
     */
    public void handleIncomingBaseStale(SyncProtocol.Message msg) {
        if (msg.getParams().length < 4) {
            eventBus.post(new SyncEvent.LogEvent("Ignoring malformed BASE_STALE notification"));
            return;
        }
        String path = msg.getParam(0);
        long size = msg.getParamAsLong(1);
        long lastModified = msg.getParamAsLong(2);
        String md5 = msg.getParam(3);
        eventBus.post(
                new SyncEvent.LogEvent(
                        "Remote rejected stale base for "
                                + path
                                + "; fresh data will be exchanged on the next sync"));
        SignatureCache cache = activeSignatureCache;
        if (cache == null) {
            File syncFolder = syncFolderSupplier.get();
            if (syncFolder == null) {
                return;
            }
            cache = createSignatureCache(syncFolder);
        }
        cache.markRejected(path, size, lastModified, md5);
        cache.flush();
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
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Cannot create directory '"
                                    + relativePath
                                    + "': sync folder not configured"));
            return;
        }
        File dirToCreate;
        try {
            dirToCreate = resolveSafe(syncFolder, relativePath);
        } catch (IOException e) {
            eventBus.post(new SyncEvent.ErrorEvent("Invalid path: " + e.getMessage()));
            return;
        }
        if (dirToCreate.isDirectory()) {
            // Already exists as a directory - nothing to do
            return;
        }
        if (dirToCreate.exists()) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Cannot create directory '"
                                    + relativePath
                                    + "': a file exists at this path"));
            return;
        }
        eventBus.post(new SyncEvent.LogEvent("Creating directory: " + relativePath));
        if (dirToCreate.mkdirs()) {
            eventBus.post(new SyncEvent.LogEvent("Directory created: " + relativePath));
            flushSharedTextBetweenOperations();
        } else {
            eventBus.post(new SyncEvent.ErrorEvent("Failed to create directory: " + relativePath));
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
            // Both peers log a time-sync marker at sync start so the combined-log save can align
            // the two machines' clocks (they may differ) before merging their timestamps.
            eventBus.post(new SyncEvent.LogEvent(TimeSyncMarker.markerMessage()));
            SyncPreviewPlan syncPlan =
                    providedPlan != null ? providedPlan : createSyncPreviewPlan();
            File syncFolder = syncFolderSupplier.get();

            // Apply conflict resolutions to local files first (e.g. KEEP_REMOTE + BOTH)
            // Must run before totalOperations check so KEEP_REMOTE-only sync still applies local
            // writes
            applyConflictResolutionsToLocalFiles(syncPlan, syncFolder);

            int rawTotalOperations = syncPlan.getTotalOperations();
            if (rawTotalOperations == 0) {
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
            int skippedCount = 0;
            for (FileChangeDetector.FileInfo fi : filesToTransfer) {
                ConflictInfo conflict = syncPlan.getConflict(fi.getPath());
                if (conflict != null
                        && conflict.getResolution() == ConflictInfo.Resolution.MERGE
                        && conflict.getMergedContentAsBytes() != null) {
                    conflictFiles.add(fi);
                } else if (conflict != null
                        && (conflict.getResolution() == ConflictInfo.Resolution.KEEP_REMOTE
                                || conflict.getResolution() == ConflictInfo.Resolution.SKIP)) {
                    // Do not transfer files where the user chose to keep the remote
                    // version or skip entirely. Sending the local version would
                    // overwrite the remote's newer content.
                    skippedCount++;
                    eventBus.post(
                            new SyncEvent.LogEvent(
                                    "Skipping transfer for "
                                            + fi.getPath()
                                            + " ("
                                            + conflict.getResolution()
                                            + ")"));
                } else {
                    regularFiles.add(fi);
                }
            }
            // totalOperations is derived from filesToTransfer.size() and includes
            // KEEP_REMOTE/SKIP files that we just dropped above, so adjust the
            // denominator to match the work actually being performed. Wrapped in
            // a 1-element array so batch progress lambdas can capture the latest
            // value (they're defined further down and read the current total).
            final int[] totalOperationsRef = {rawTotalOperations - skippedCount};

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
                                + totalOperationsRef[0]
                                + "]: "
                                + filePath;
                if (wasCompressed) msg += " (compressed)";
                msg += String.format(" [%dms]", fileSendMs);
                eventBus.post(new SyncEvent.LogEvent(msg));
                eventBus.post(
                        new SyncEvent.FileProgressEvent(
                                operationIndex, totalOperationsRef[0], filePath));
                flushSharedTextBetweenOperations();
            }

            // Partition regular files: delta candidates are sent individually via CMD_FILE_DELTA
            // after a block-signature exchange (or via CMD_FILE_APPEND when the change is a pure
            // appended tail); the rest go through the batch path. Candidates whose delta is not
            // beneficial (or have no receiver signature) fall back to the batch path so total
            // transferred content is unchanged.
            Set<String> deltaCandidatePaths = syncPlan.getDeltaCandidatePaths();
            List<FileChangeDetector.FileInfo> deltaCandidates = new ArrayList<>();
            List<FileChangeDetector.FileInfo> batchFiles = new ArrayList<>();
            for (FileChangeDetector.FileInfo fi : regularFiles) {
                if (deltaCandidatePaths.contains(fi.getPath())) {
                    deltaCandidates.add(fi);
                } else {
                    batchFiles.add(fi);
                }
            }

            // The signature cache backs both fast paths: the append gate skips receiver states
            // that rejected a previous transfer, and the delta path reuses cached signatures.
            // Opened once here so handleIncomingBaseStale can share the instance mid-session.
            SignatureCache signatureCache = null;
            if (!deltaCandidates.isEmpty()) {
                signatureCache = createSignatureCache(syncFolder);
                activeSignatureCache = signatureCache;
            }

            // Append-only fast path: candidates whose local file is the receiver's file plus a
            // pure appended tail — verified by hashing the local prefix the same way the remote
            // manifest does — skip the signature exchange entirely and transfer only the tail.
            // This is the common shape for actively-written log files. Each candidate is detected
            // and sent immediately, so at most one tail is in memory at any moment.
            Iterator<FileChangeDetector.FileInfo> candidateIt = deltaCandidates.iterator();
            while (candidateIt.hasNext()) {
                FileChangeDetector.FileInfo fi = candidateIt.next();
                AppendCandidate append =
                        detectAppendCandidate(fi, syncPlan, syncFolder, signatureCache);
                if (append == null) {
                    continue; // no append shape: the file stays on the signature-delta path
                }
                candidateIt.remove();
                exitSyncIfCancelled();
                operationIndex++;
                String path = append.path;
                long lastModified = append.file.lastModified();
                long sendStart = System.currentTimeMillis();
                try {
                    boolean wasCompressed =
                            protocol.sendFileAppend(
                                    path,
                                    append.tail,
                                    lastModified,
                                    append.baseSize,
                                    append.finalSize,
                                    append.finalMd5);
                    long sendMs = System.currentTimeMillis() - sendStart;
                    String msg =
                            "Append-only syncing ["
                                    + operationIndex
                                    + "/"
                                    + totalOperationsRef[0]
                                    + "]: "
                                    + path
                                    + " (+"
                                    + append.tail.length
                                    + " bytes of "
                                    + append.finalSize
                                    + ")";
                    if (wasCompressed) msg += " (compressed)";
                    msg += String.format(" [%dms]", sendMs);
                    eventBus.post(new SyncEvent.LogEvent(msg));
                    eventBus.post(
                            new SyncEvent.FileProgressEvent(
                                    operationIndex, totalOperationsRef[0], path));
                    savedOpIndex = operationIndex;
                    touchHeartbeat();
                    flushSharedTextBetweenOperations();
                } catch (IOException e) {
                    if (e instanceof TransferCancelledException) {
                        // The peer cancelled the session; re-sending this file (or the rest of
                        // the plan) would push data the peer just refused. Abort the sync.
                        throw (TransferCancelledException) e;
                    }
                    if (cancelRequested.get()) {
                        // A local cancel interrupted the blocked send; exit as cancelled instead
                        // of re-queueing the file for a full transfer.
                        exitSyncIfCancelled();
                    }
                    // The handshake failed: either the peer never ACKed the command (an older
                    // peer that does not know FILE_APPEND silently ignores it) or the XMODEM
                    // phase broke. Re-queue for the batch path: in the former case the batch
                    // fallback lets this sync still complete; in the latter the link is already
                    // lost, so the fallback (and the rest of the session) fails too and the
                    // file is re-evaluated against fresh manifests on the next sync.
                    eventBus.post(
                            new SyncEvent.LogEvent(
                                    "Append fast path failed for "
                                            + path
                                            + " ("
                                            + e.getMessage()
                                            + "); using full transfer"));
                    batchFiles.add(append.fileInfo);
                }
            }

            SignatureSet signatureSet = SignatureSet.empty();
            if (!deltaCandidates.isEmpty()) {
                exitSyncIfCancelled();
                // The signature exchange is the dominant serial-link cost of the delta path, so
                // reuse the signatures cached from a previous sync while the receiver's file is
                // unchanged (same size, lastModified and md5 as recorded with the cache entry).
                if (!syncPlan.getExistingRemotePaths().isEmpty()) {
                    // Only prune when the plan actually carries remote metadata, so a hand-built
                    // plan cannot wipe the cache for paths it simply does not describe.
                    signatureCache.prune(syncPlan.getExistingRemotePaths());
                }
                Map<String, FileSignatures> cachedSignatures = new HashMap<>();
                List<String> candidatePaths = new ArrayList<>();
                for (FileChangeDetector.FileInfo fi : deltaCandidates) {
                    String path = fi.getPath();
                    FileSignatures cached =
                            signatureCache.lookup(path, syncPlan.getRemoteFileInfo(path));
                    if (cached != null) {
                        cachedSignatures.put(path, cached);
                    } else {
                        candidatePaths.add(path);
                    }
                }
                if (!cachedSignatures.isEmpty()) {
                    eventBus.post(
                            new SyncEvent.LogEvent(
                                    "Using cached block signatures for "
                                            + cachedSignatures.size()
                                            + " file(s)"));
                }
                List<FileSignatures> merged = new ArrayList<>(cachedSignatures.values());
                if (!candidatePaths.isEmpty()) {
                    eventBus.post(
                            new SyncEvent.LogEvent(
                                    "Requesting block signatures for "
                                            + candidatePaths.size()
                                            + " file(s)..."));
                    try {
                        SignatureSet fetched = protocol.requestDeltaSignatures(candidatePaths);
                        for (String path : candidatePaths) {
                            FileSignatures sigs = fetched.get(path);
                            FileChangeDetector.FileInfo remote = syncPlan.getRemoteFileInfo(path);
                            if (sigs != null && remote != null) {
                                try {
                                    signatureCache.store(path, remote, sigs);
                                } catch (IOException e) {
                                    // A failed cache write only costs a future exchange.
                                }
                            }
                        }
                        for (FileSignatures sigs : fetched.entries()) {
                            if (!cachedSignatures.containsKey(sigs.getPath())) {
                                merged.add(sigs);
                            }
                        }
                    } catch (IOException e) {
                        if (e instanceof TransferCancelledException) {
                            // The peer cancelled the session; do not resume sending.
                            throw (TransferCancelledException) e;
                        }
                        if (cancelRequested.get()) {
                            // A local cancel interrupted the exchange; exit as cancelled.
                            exitSyncIfCancelled();
                        }
                        // The exchange failed (timeout, IO error, session torn down). Cached
                        // signatures (if any) are still usable; every candidate without one
                        // falls back to full transfer via the per-file null check.
                        eventBus.post(
                                new SyncEvent.LogEvent(
                                        "Signature exchange failed ("
                                                + e.getMessage()
                                                + "); full transfer for files without"
                                                + " cached signatures"));
                    }
                }
                signatureCache.flush();
                signatureSet = new SignatureSet(merged);

                if (signatureSet.isEmpty()) {
                    // No signatures: send all candidates through the full batch path.
                    batchFiles.addAll(deltaCandidates);
                } else {
                    eventBus.post(
                            new SyncEvent.LogEvent(
                                    "Block signatures received for "
                                            + signatureSet.size()
                                            + " file(s)"));
                    List<FileChangeDetector.FileInfo> deltaFallback = new ArrayList<>();
                    for (FileChangeDetector.FileInfo fi : deltaCandidates) {
                        exitSyncIfCancelled();
                        String path = fi.getPath();
                        FileSignatures sigs = signatureSet.get(path);
                        if (sigs == null) {
                            // Receiver lacked the file or could not sign it: full transfer.
                            deltaFallback.add(fi);
                            continue;
                        }
                        File file = new File(syncFolder, path);
                        byte[] source;
                        try {
                            source = Files.readAllBytes(file.toPath());
                        } catch (IOException e) {
                            eventBus.post(
                                    new SyncEvent.ErrorEvent(
                                            "Failed to read "
                                                    + path
                                                    + " for delta: "
                                                    + e.getMessage()));
                            deltaFallback.add(fi);
                            continue;
                        }
                        byte[] delta = DeltaEncoder.encode(source, sigs);
                        CompressionUtil.CompressedData deltaCompressed =
                                CompressionUtil.compressIfBeneficial(path, delta);
                        CompressionUtil.CompressedData fullCompressed =
                                CompressionUtil.compressIfBeneficial(path, source);
                        long savedBytes =
                                (long) fullCompressed.getData().length
                                        - deltaCompressed.getData().length;
                        if (!DeltaEncoder.isBeneficial(
                                        deltaCompressed.getData().length,
                                        fullCompressed.getData().length)
                                || savedBytes < MIN_DELTA_SAVINGS_BYTES) {
                            // The saving does not pay for a dedicated XMODEM session: the
                            // batch path amortizes one session across many files.
                            deltaFallback.add(fi);
                            eventBus.post(
                                    new SyncEvent.LogEvent(
                                            "Delta saving for "
                                                    + path
                                                    + " too small ("
                                                    + savedBytes
                                                    + " bytes); using batch transfer"));
                            continue;
                        }
                        String sourceMd5 = HashUtil.md5Hex(source);
                        operationIndex++;
                        long lastModified = file.lastModified();
                        long sendStart = System.currentTimeMillis();
                        boolean wasCompressed =
                                protocol.sendFileDelta(
                                        path, delta, lastModified, source.length, sourceMd5);
                        long sendMs = System.currentTimeMillis() - sendStart;
                        int pct =
                                (int)
                                        (100
                                                * savedBytes
                                                / Math.max(1, fullCompressed.getData().length));
                        String msg =
                                "Delta syncing ["
                                        + operationIndex
                                        + "/"
                                        + totalOperationsRef[0]
                                        + "]: "
                                        + path;
                        if (wasCompressed) msg += " (compressed)";
                        msg += " (saved " + pct + "%)" + String.format(" [%dms]", sendMs);
                        eventBus.post(new SyncEvent.LogEvent(msg));
                        eventBus.post(
                                new SyncEvent.FileProgressEvent(
                                        operationIndex, totalOperationsRef[0], path));
                        savedOpIndex = operationIndex;
                        touchHeartbeat();
                        flushSharedTextBetweenOperations();
                    }
                    // Fallbacks rejoin the batch path; their indices are counted by the batch loop.
                    batchFiles.addAll(deltaFallback);
                }
            }

            // Send regular files in batches to reduce per-file XMODEM handshakes.
            // Each batch is a single XMODEM transfer; files within a batch are encoded
            // in a binary envelope and decoded atomically on the receiver side.
            if (!batchFiles.isEmpty()) {
                final int BATCH_BYTE_TARGET = 32 * 1024; // ~32 KB per batch; tune as needed
                List<Object[]> batch = new ArrayList<>();

                for (FileChangeDetector.FileInfo fileInfo : batchFiles) {
                    File file = new File(syncFolder, fileInfo.getPath());

                    // A large file must not ride the batch envelope: the receiver's batch decode
                    // rejects oversized payloads, and a batch buffers the whole payload in memory
                    // on both sides. Sent individually instead, the receiver streams the transfer
                    // to disk and keeps a resumable prefix if the link drops mid-transfer.
                    if (file.length() > SyncProtocol.PARTIAL_DISK_WRITE_THRESHOLD_BYTES) {
                        exitSyncIfCancelled();
                        operationIndex++;
                        savedOpIndex++;
                        long t0 = System.currentTimeMillis();
                        boolean sentOk = false;
                        try {
                            sentOk = protocol.sendFile(syncFolder, fileInfo.getPath());
                        } catch (IOException | IllegalStateException e) {
                            if (e instanceof TransferCancelledException) {
                                // The peer cancelled the session; do not send the next file.
                                throw (TransferCancelledException) e;
                            }
                            if (cancelRequested.get()) {
                                break;
                            }
                            eventBus.post(
                                    new SyncEvent.ErrorEvent(
                                            "Failed to send large file "
                                                    + fileInfo.getPath()
                                                    + ": "
                                                    + e.getMessage()));
                        }
                        long ms = System.currentTimeMillis() - t0;
                        if (sentOk) {
                            touchHeartbeat();
                            eventBus.post(
                                    new SyncEvent.LogEvent(
                                            "Syncing ["
                                                    + savedOpIndex
                                                    + "/"
                                                    + totalOperationsRef[0]
                                                    + "]: "
                                                    + fileInfo.getPath()
                                                    + String.format(" [%dms]", ms)));
                        }
                        eventBus.post(
                                new SyncEvent.FileProgressEvent(
                                        savedOpIndex, totalOperationsRef[0], fileInfo.getPath()));
                        if (!sentOk) {
                            try {
                                protocol.sendTransferCancel();
                            } catch (IOException ignored) {
                                // The link is already gone; the local error is reported above.
                            }
                            throw new IOException(
                                    "Failed to transfer large file " + fileInfo.getPath());
                        }
                        flushSharedTextBetweenOperations();
                        continue;
                    }

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
                                                            + totalOperationsRef[0]
                                                            + "]: "
                                                            + relPath));
                                    eventBus.post(
                                            new SyncEvent.FileProgressEvent(
                                                    current, totalOperationsRef[0], relPath));
                                };
                        long batchStart = System.currentTimeMillis();
                        int inBatch = batch.size();
                        boolean ok =
                                protocol.sendBatch(
                                        batch, BATCH_BYTE_TARGET, batchCallback, syncFolder);
                        long batchMs = System.currentTimeMillis() - batchStart;
                        if (!ok) {
                            // A cancel-driven batch failure is expected, not an error; the
                            // fallback loop below stops on the same flag.
                            if (!cancelRequested.get()) {
                                eventBus.post(
                                        new SyncEvent.ErrorEvent(
                                                "Batch transfer failed for "
                                                        + inBatch
                                                        + " file(s); falling back to per-file"));
                            }
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
                                    if (e instanceof TransferCancelledException) {
                                        // The peer cancelled the session; do not send the
                                        // remaining fallback files.
                                        throw (TransferCancelledException) e;
                                    }
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
                                                            + totalOperationsRef[0]
                                                            + "]: "
                                                            + rp
                                                            + String.format(" [%dms]", ms)));
                                }
                                eventBus.post(
                                        new SyncEvent.FileProgressEvent(
                                                savedOpIndex, totalOperationsRef[0], rp));
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
                                                        + totalOperationsRef[0]
                                                        + "]: "
                                                        + relPath));
                                eventBus.post(
                                        new SyncEvent.FileProgressEvent(
                                                current, totalOperationsRef[0], relPath));
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
                                                        + totalOperationsRef[0]
                                                        + "]: "
                                                        + rp
                                                        + String.format(" [%dms]", ms)));
                            }
                            eventBus.post(
                                    new SyncEvent.FileProgressEvent(
                                            savedOpIndex, totalOperationsRef[0], rp));
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
                                        + totalOperationsRef[0]
                                        + "]: "
                                        + dirPath));
                eventBus.post(
                        new SyncEvent.FileProgressEvent(
                                operationIndex, totalOperationsRef[0], "[DIR] " + dirPath));
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
                                        + totalOperationsRef[0]
                                        + "]: "
                                        + pathToDelete));
                eventBus.post(
                        new SyncEvent.FileProgressEvent(
                                operationIndex, totalOperationsRef[0], "[DEL] " + pathToDelete));
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
                                        + totalOperationsRef[0]
                                        + "]: "
                                        + dirToDelete));
                eventBus.post(
                        new SyncEvent.FileProgressEvent(
                                operationIndex, totalOperationsRef[0], "[RMDIR] " + dirToDelete));
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
        } catch (TransferCancelledException e) {
            // The peer aborted the session (its user clicked cancel). A peer cancel applies to
            // the whole sync, so stop here instead of pushing the remaining files it refused.
            eventBus.post(new SyncEvent.LogEvent("Sync cancelled by remote"));
            eventBus.post(new SyncEvent.SyncCancelledEvent());
        } catch (IOException e) {
            if (cancelRequested.get()) {
                // The user's cancel interrupted a blocking serial read; surface it as a
                // cancellation, not as a failed sync.
                eventBus.post(new SyncEvent.LogEvent("Sync cancelled"));
                eventBus.post(new SyncEvent.SyncCancelledEvent());
            } else {
                // A read timeout means the peer stopped responding mid-exchange (link torn down
                // on its side, cable pulled, ...). Fail the connection immediately so recovery
                // starts instead of idling until the next heartbeat check declares the loss.
                if (isReadTimeout(e)) {
                    communicationFailureReporter.accept(
                            "Connection lost - read timeout during sync: " + e.getMessage());
                }
                eventBus.post(new SyncEvent.ErrorEvent("Sync failed: " + e.getMessage()));
            }
        } finally {
            activeSignatureCache = null;
            syncWorkerFuture = null;
            syncing.set(false);
            protocol.resetXmodemInProgress();
            touchHeartbeat();
            onSyncIdle.run();
            // The sync just released the transfer gates; refresh the sync controls in case a
            // cancellation event reached the UI before this cleanup ran.
            eventBus.post(new SyncEvent.SyncControlRefreshEvent());
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
        return totalRead < fileSize ? Arrays.copyOf(sample, totalRead) : sample;
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
            throw new IOException("Path traversal rejected: " + relativePath);
        }
        return new File(baseDir, relativePath);
    }
}
