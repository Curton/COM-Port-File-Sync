package com.filesync.ui;

import com.filesync.config.SettingsManager;
import com.filesync.sync.ConflictInfo;
import com.filesync.sync.FileSyncManager;
import com.filesync.sync.SyncPreviewPlan;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

/** Sync flow and control panel actions. */
public class SyncController implements SyncPreviewRenderer.ConflictResolver {
    private static final String START_SYNC_TEXT = "Start Sync";
    private static final String CANCEL_SYNC_TEXT = "Cancel";

    /** How long a finished transfer's caption lingers before the bar reverts to Ready. */
    private static final int PROGRESS_RESET_DELAY_MS = 15_000;

    private final JFrame owner;
    private final MainFrameComponents components;
    private final FileSyncManager syncManager;
    private final MainFrameState state;
    private final SettingsManager settings;
    private final LogController logController;
    private SyncPreviewRenderer previewRenderer;
    private javax.swing.JDialog pendingWriteDialog;
    private javax.swing.JList<String> pendingWriteList;
    private Runnable onDisconnectedCallback;
    private final javax.swing.Timer progressResetTimer;

    public SyncController(
            JFrame owner,
            MainFrameComponents components,
            FileSyncManager syncManager,
            MainFrameState state,
            SettingsManager settings,
            LogController logController) {
        this.owner = owner;
        this.components = components;
        this.syncManager = syncManager;
        this.state = state;
        this.settings = settings;
        this.logController = logController;
        this.progressResetTimer =
                new javax.swing.Timer(
                        PROGRESS_RESET_DELAY_MS,
                        event -> {
                            javax.swing.JProgressBar bar = components.getProgressBar();
                            bar.setIndeterminate(false);
                            bar.setValue(0);
                            bar.setString("Ready");
                        });
        this.progressResetTimer.setRepeats(false);
    }

    public void setPreviewRenderer(SyncPreviewRenderer previewRenderer) {
        this.previewRenderer = previewRenderer;
    }

    /** Injected by MainFrame so a disconnect can refresh the (possibly changed) port list. */
    public void setOnDisconnectedCallback(Runnable callback) {
        this.onDisconnectedCallback = callback;
    }

    @Override
    public byte[] fetchRemoteContent(String path) {
        try {
            return syncManager.fetchRemoteFileContent(path);
        } catch (Exception e) {
            logController.log("Failed to fetch remote content for " + path + ": " + e.getMessage());
            return null;
        }
    }

    public void initActionHandlers() {
        components.getDirectionButton().addActionListener(event -> toggleDirection());
        components.getSyncButton().addActionListener(event -> onSyncButtonClicked());
        components.getPreviewSyncButton().addActionListener(event -> previewSync());

        bindModeCheckBox(
                components.getRespectGitignoreCheckBox(),
                syncManager::setRespectGitignoreMode,
                settings::setRespectGitignore,
                "Respect .gitignore");

        bindModeCheckBox(
                components.getStrictSyncCheckBox(),
                syncManager::setStrictSyncMode,
                settings::setStrictSync,
                "Strict sync mode");
        // Mirror mode constrains the .gitignore option; re-evaluate it once the mode is applied.
        components
                .getStrictSyncCheckBox()
                .addActionListener(event -> updateRespectGitignoreState());

        bindModeCheckBox(
                components.getFastModeCheckBox(),
                syncManager::setFastMode,
                settings::setFastMode,
                "Fast mode");
    }

    /** Wires a mode checkbox: apply to the manager, persist, and log the new state. */
    private void bindModeCheckBox(
            JCheckBox box,
            Consumer<Boolean> applyToManager,
            Consumer<Boolean> saveToSettings,
            String label) {
        box.addActionListener(
                event -> {
                    boolean enabled = box.isSelected();
                    applyToManager.accept(enabled);
                    saveToSettings.accept(enabled);
                    settings.save();
                    logController.log(label + ": " + (enabled ? "enabled" : "disabled"));
                });
    }

    private void onSyncButtonClicked() {
        logController.log("[DEBUG] onSyncButtonClicked: syncing=" + syncManager.isSyncing());
        if (syncManager.isSyncing()) {
            cancelSync();
            return;
        }
        startSync();
    }

    public void updateRespectGitignoreState() {
        boolean strictMode = components.getStrictSyncCheckBox().isSelected();
        if (strictMode) {
            components.getRespectGitignoreCheckBox().setEnabled(false);
            components.getRespectGitignoreCheckBox().setSelected(false);
            syncManager.setRespectGitignoreMode(false);
        } else {
            components.getRespectGitignoreCheckBox().setEnabled(true);
        }
    }

    public void applyDirection(boolean isSender) {
        state.setSender(isSender);
        components.updateDirectionButton(isSender);
        updateSyncButtonState();
    }

    public void toggleDirection() {
        if (syncManager.isTransferBusy()) {
            logController.log("Cannot change direction during data transfer");
            JOptionPane.showMessageDialog(
                    owner,
                    "Cannot change direction during data transfer",
                    "Direction Change Blocked",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        state.setSender(!state.isSender());
        applyDirection(state.isSender());
        syncManager.setIsSender(state.isSender());
        syncManager.notifyDirectionChange();
        updateSyncButtonState();
        logController.log(
                "Direction changed: "
                        + (state.isSender() ? "Sender (A -> B)" : "Receiver (B <- A)"));
    }

    public void startSync() {
        logController.log("[DEBUG] startSync: sender=" + state.isSender());
        if (state.isPreviewInProgress()) {
            // Another Start Sync / preview is already running its manifest roundtrip; two at
            // once would steal each other's protocol responses.
            logController.log("Sync preparation already in progress, ignoring request");
            return;
        }
        if (!ensureSenderRoleReady()) {
            logController.log("Waiting for sync from sender...");
            return;
        }
        logController.log("[DEBUG] startSync: running preflight -> doStartSync");
        runSyncWithPreflight(this::doStartSync);
    }

    public void previewSync() {
        logController.log("[DEBUG] previewSync: entered");
        if (!ensureSenderRoleReady()) {
            logController.log("Waiting for sync from sender...");
            return;
        }
        logController.log("[DEBUG] previewSync: starting preflight -> runSyncPreview");
        runSyncWithPreflight(this::runSyncPreview);
    }

    /**
     * Start Sync after the folder-mapping preflight. The manifest roundtrip runs here first so the
     * operations that would otherwise destroy receiver-side data silently are confirmed by the
     * user: conflicts (an unresolved conflict defaults to "local wins", discarding the receiver's
     * version) and strict-mode deletions. The computed plan is handed to {@code initiateSync} so
     * manifests are not generated a second time.
     */
    private void doStartSync() {
        logController.log("[DEBUG] doStartSync: preparing plan for confirmation");
        state.setPreviewInProgress(true);
        updateSyncButtonState();

        SwingWorker<SyncPreviewPlan, Void> confirmWorker =
                new SwingWorker<SyncPreviewPlan, Void>() {
                    @Override
                    protected SyncPreviewPlan doInBackground() {
                        return syncManager.previewSync();
                    }

                    @Override
                    protected void done() {
                        state.setPreviewInProgress(false);
                        updateSyncButtonState();
                        try {
                            SyncPreviewPlan plan = get();
                            if (plan == null) {
                                // The pre-check could not produce a plan; start without the
                                // warning and let the sync itself report real failures.
                                logController.log(
                                        "Sync plan unavailable; starting without confirmation");
                                syncManager.initiateSync();
                                return;
                            }
                            if (!plan.getConflicts().isEmpty()
                                    || !plan.getFilesToDelete().isEmpty()
                                    || !plan.getEmptyDirectoriesToDelete().isEmpty()) {
                                if (!confirmDestructiveSync(plan)) {
                                    logController.log(
                                            "Sync cancelled: destructive changes not confirmed");
                                    resetProgressBar();
                                    return;
                                }
                            }
                            startSyncWithPlan(plan);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            logController.log("Sync start interrupted");
                        } catch (java.util.concurrent.ExecutionException e) {
                            Throwable cause = e.getCause();
                            String message = cause != null ? cause.getMessage() : e.getMessage();
                            logController.log(
                                    "Sync plan failed"
                                            + (message != null && !message.isEmpty()
                                                    ? ": " + message
                                                    : ""));
                            // Same fallback as a null plan: initiateSync runs its own checks
                            // and reports failures through the usual error events.
                            syncManager.initiateSync();
                        }
                    }
                };
        confirmWorker.execute();
    }

    /** Clear the progress bar after a sync the user aborted before any transfer started. */
    private void resetProgressBar() {
        components.getProgressBar().setIndeterminate(false);
        components.getProgressBar().setString("");
        components.getProgressBar().setValue(0);
    }

    private static final int MAX_LISTED_PATHS = 10;

    /** Shared "Continue/Cancel" warning dialog; returns the chosen JOptionPane option index. */
    private int confirmContinue(String title, Object message) {
        return JOptionPane.showOptionDialog(
                owner,
                message,
                title,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                new Object[] {"Continue", "Cancel"},
                "Cancel");
    }

    /**
     * Warning shown when a direct Start Sync would destroy receiver-side data the plain flow never
     * mentions: the receiver's version of conflicted files (an unresolved conflict defaults to
     * "local wins") and strict-mode deletions. Returns true when the user confirmed.
     */
    private boolean confirmDestructiveSync(SyncPreviewPlan plan) {
        List<ConflictInfo> conflicts = plan.getConflicts();
        List<String> deletions = new ArrayList<>(plan.getFilesToDelete());
        for (String dir : plan.getEmptyDirectoriesToDelete()) {
            deletions.add(dir + "/");
        }

        StringBuilder msg = new StringBuilder();
        msg.append("This sync will:\n");
        if (!conflicts.isEmpty()) {
            msg.append("  - overwrite the receiver's version of ")
                    .append(conflicts.size())
                    .append(conflicts.size() == 1 ? " file\n" : " files\n");
        }
        if (!deletions.isEmpty()) {
            msg.append("  - delete ")
                    .append(deletions.size())
                    .append(deletions.size() == 1 ? " file/directory\n" : " files/directories\n")
                    .append("    on the receiver (strict mode)\n");
        }
        msg.append('\n');
        if (!conflicts.isEmpty()) {
            msg.append("Conflicts (the receiver's version will be lost):\n");
            appendPathList(msg, conflicts.stream().map(ConflictInfo::getPath).toList());
            msg.append('\n');
        }
        if (!deletions.isEmpty()) {
            msg.append("Deletions on the receiver:\n");
            appendPathList(msg, deletions);
            msg.append('\n');
        }
        msg.append("Continue?");

        int response = confirmContinue("Confirm Sync", msg.toString());
        return response == 0;
    }

    /** Append up to {@link #MAX_LISTED_PATHS} paths, indented, plus a "(N more)" tail. */
    private void appendPathList(StringBuilder msg, List<String> paths) {
        int shown = Math.min(MAX_LISTED_PATHS, paths.size());
        for (int i = 0; i < shown; i++) {
            msg.append("  ").append(paths.get(i)).append('\n');
        }
        if (paths.size() > shown) {
            msg.append("  ... and ").append(paths.size() - shown).append(" more\n");
        }
    }

    public void cancelSync() {
        if (!syncManager.isTransferBusy()) {
            return;
        }
        logController.log("Cancelling sync...");
        syncManager.cancelSync();
        components.getProgressBar().setString("");
        components.getProgressBar().setValue(0);
        components.getStatusLabel().setText("Connected");
        components.getStatusLabel().setForeground(new Color(0, 128, 0));
        components.getConnectButton().setText("Disconnect");
        components.getDirectionButton().setEnabled(false);
        updateSyncButtonState();
    }

    private void runSyncWithPreflight(Runnable onProceed) {
        File localFolder = syncManager.getSyncFolder();
        if (localFolder == null || !localFolder.exists()) {
            return;
        }
        String port = (String) components.getPortComboBox().getSelectedItem();
        String localPath = localFolder.getAbsolutePath();

        SwingWorker<String, Void> preflightWorker =
                new SwingWorker<String, Void>() {
                    @Override
                    protected String doInBackground() {
                        return syncManager.requestRemoteFolderContext();
                    }

                    @Override
                    protected void done() {
                        try {
                            String remotePath = get();
                            logController.log(
                                    "[DEBUG] preflight done: remotePath="
                                            + (remotePath != null ? remotePath : "null"));
                            String nLocal = SettingsManager.normalizeFolderPath(localPath);
                            String nRemote = SettingsManager.normalizeFolderPath(remotePath);
                            List<String[]> rememberedMappings =
                                    settings.getRememberedFolderMappings(port);
                            boolean match = false;
                            for (String[] remembered : rememberedMappings) {
                                if (SettingsManager.isMappingMatch(
                                        nLocal, nRemote, remembered[0], remembered[1])) {
                                    match = true;
                                    break;
                                }
                            }

                            if (match) {
                                state.setPendingMappingRemotePath(nRemote);
                                logController.log(
                                        "[DEBUG] preflight: folder match, calling onProceed ("
                                                + onProceed.getClass().getSimpleName()
                                                + ")");
                                onProceed.run();
                                return;
                            }

                            boolean bothSidesChanged = !rememberedMappings.isEmpty();
                            for (String[] remembered : rememberedMappings) {
                                if (!SettingsManager.isBothSidesChangedFromRemembered(
                                        nLocal, nRemote, remembered[0], remembered[1])) {
                                    bothSidesChanged = false;
                                    break;
                                }
                            }
                            if (bothSidesChanged) {
                                state.setPendingMappingRemotePath(nRemote);
                                logController.log(
                                        "Detected folder changes on both sides; proceeding with current mapping.");
                                onProceed.run();
                                return;
                            }

                            StringBuilder msg = new StringBuilder();
                            msg.append("Folder mapping differs from last successful sync.\n\n");
                            msg.append("Remembered:\n");
                            if (rememberedMappings.isEmpty()) {
                                msg.append("  (none)\n");
                            } else {
                                String[] remembered = rememberedMappings.get(0);
                                msg.append("  ")
                                        .append(remembered[0].isEmpty() ? "(none)" : remembered[0])
                                        .append(" -> ")
                                        .append(remembered[1].isEmpty() ? "(none)" : remembered[1])
                                        .append('\n');
                            }
                            msg.append("\n\nCurrent: ").append(nLocal);
                            msg.append(" -> ").append(nRemote.isEmpty() ? "(unknown)" : nRemote);
                            msg.append(
                                    "\n\nProceed? The new mapping will be remembered after successful sync.");

                            int response =
                                    confirmContinue(
                                            "Confirm Folder Mapping Change", msg.toString());
                            if (response == 0) {
                                state.setPendingMappingRemotePath(nRemote);
                                onProceed.run();
                            } else {
                                // User cancelled the mapping prompt; re-evaluate the buttons in
                                // case a previous flow left them disabled.
                                updateSyncButtonState();
                            }
                        } catch (Exception e) {
                            logController.log(
                                    "Preflight check failed: "
                                            + (e.getCause() != null
                                                    ? e.getCause().getMessage()
                                                    : e.getMessage()));
                            state.setPendingMappingRemotePath(null);
                            onProceed.run();
                        }
                    }
                };
        preflightWorker.execute();
    }

    private boolean ensureSenderRoleReady() {
        if (!state.isSender()) {
            return false;
        }
        if (syncManager.confirmCurrentRoleIfNeeded(state.isSender())) {
            logController.log("Role negotiation pending; using selected sender mode");
        }
        return true;
    }

    private void runSyncPreview() {
        logController.log("[DEBUG] runSyncPreview: entered");
        if (state.isPreviewInProgress()) {
            logController.log("[DEBUG] runSyncPreview: preview already in progress, returning");
            return;
        }

        state.setPreviewInProgress(true);
        updateSyncButtonState();

        SwingWorker<SyncPreviewPlan, Void> previewWorker =
                new SwingWorker<SyncPreviewPlan, Void>() {
                    @Override
                    protected SyncPreviewPlan doInBackground() {
                        logController.log(
                                "[DEBUG] runSyncPreview: doInBackground calling previewSync");
                        return syncManager.previewSync();
                    }

                    @Override
                    protected void done() {
                        logController.log("[DEBUG] runSyncPreview: done() called");
                        state.setPreviewInProgress(false);
                        updateSyncButtonState();

                        try {
                            SyncPreviewPlan syncPreview = get();
                            logController.log(
                                    "[DEBUG] runSyncPreview: plan="
                                            + (syncPreview != null ? "non-null" : "null"));
                            if (syncPreview == null) {
                                showNoChangesPreview("Sync preview could not be computed.");
                                return;
                            }

                            logController.log(
                                    "[DEBUG] runSyncPreview: conflicts="
                                            + syncPreview.getConflicts().size()
                                            + ", filesToTransfer="
                                            + syncPreview.getFilesToTransfer().size()
                                            + ", totalOps="
                                            + syncPreview.getTotalOperations());

                            SyncPreviewRenderer.SyncPreviewResult previewResult =
                                    previewRenderer.showSyncPreviewDialogWithResult(
                                            syncPreview, syncManager.getSyncFolder());
                            logController.log(
                                    "[DEBUG] runSyncPreview: previewResult="
                                            + (previewResult != null
                                                    ? "non-null"
                                                    : "null (cancelled)"));
                            if (previewResult == null) {
                                // User cancelled the preview dialog; restore the button state so
                                // the sync controls are usable again.
                                updateSyncButtonState();
                                return;
                            }
                            SyncPreviewPlan selectedPlan = previewResult.getPlan();
                            DefaultTableModel previewModel = previewResult.getModel();
                            List<SyncPreviewRow> previewRows = previewResult.getRows();

                            logController.log(
                                    "[DEBUG] runSyncPreview: selectedPlan conflicts="
                                            + selectedPlan.getConflicts().size()
                                            + ", filesToTransfer="
                                            + selectedPlan.getFilesToTransfer().size());

                            // Resolve conflicts for selected files before starting sync. The
                            // remote content is fetched on a worker thread, so the flow
                            // continues from the callback instead of inline.
                            if (!selectedPlan.getConflicts().isEmpty()) {
                                logController.log(
                                        "[DEBUG] runSyncPreview: calling resolveConflictsForSelectedFiles");
                                SyncPreviewPlan planToResolve = selectedPlan;
                                previewRenderer.resolveConflictsForSelectedFiles(
                                        planToResolve,
                                        previewModel,
                                        previewRows,
                                        conflictsResolved -> {
                                            logController.log(
                                                    "[DEBUG] runSyncPreview: conflictsResolved="
                                                            + conflictsResolved);
                                            if (!conflictsResolved) {
                                                logController.log(
                                                        "[DEBUG] runSyncPreview: user cancelled conflict resolution");
                                                // User cancelled conflict resolution; restore
                                                // the button state so the sync controls are
                                                // usable again.
                                                updateSyncButtonState();
                                                return;
                                            }
                                            // Re-create filtered plan now that conflicts have
                                            // resolutions (SKIP/KEEP_REMOTE exclude from
                                            // transfer)
                                            logController.log(
                                                    "[DEBUG] runSyncPreview: re-creating filtered plan");
                                            SyncPreviewPlan filteredPlan =
                                                    previewRenderer.createFilteredSyncPlan(
                                                            syncPreview, previewModel, previewRows);
                                            logController.log(
                                                    "[DEBUG] runSyncPreview: after resolution filesToTransfer="
                                                            + filteredPlan
                                                                    .getFilesToTransfer()
                                                                    .size());
                                            startSyncWithPlan(filteredPlan);
                                        });
                                return;
                            }
                            startSyncWithPlan(selectedPlan);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            String message = "Sync preview was interrupted";
                            logController.log(
                                    "[DEBUG] runSyncPreview: InterruptedException: " + message);
                            JOptionPane.showMessageDialog(
                                    owner,
                                    "Could not prepare sync preview.\n" + message,
                                    "Sync Preview Failed",
                                    JOptionPane.ERROR_MESSAGE);
                            logController.log("Sync preview failed: " + message);
                        } catch (java.util.concurrent.ExecutionException e) {
                            Throwable cause = e.getCause();
                            String message = cause != null ? cause.getMessage() : e.getMessage();
                            if (message == null || message.isEmpty()) {
                                message = "Failed to generate sync preview";
                            }
                            logController.log(
                                    "[DEBUG] runSyncPreview: ExecutionException: " + message);
                            JOptionPane.showMessageDialog(
                                    owner,
                                    "Could not prepare sync preview.\n" + message,
                                    "Sync Preview Failed",
                                    JOptionPane.ERROR_MESSAGE);
                            logController.log("Sync preview failed: " + message);
                        } catch (Exception e) {
                            logController.log(
                                    "[DEBUG] runSyncPreview: unexpected exception: "
                                            + e.getClass().getSimpleName()
                                            + ": "
                                            + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                };
        previewWorker.execute();
    }

    /** Disable the sync controls and start the sync with the (already filtered) plan. */
    private void startSyncWithPlan(SyncPreviewPlan plan) {
        logController.log("[DEBUG] startSyncWithPlan: calling initiateSync");
        components.getSyncButton().setEnabled(false);
        components.getPreviewSyncButton().setEnabled(false);
        components.getProgressBar().setValue(0);
        syncManager.initiateSync(plan);
        logController.log("[DEBUG] runSyncPreview: initiateSync returned");
    }

    /** Arms the one-shot timer that reverts the progress bar to Ready (EDT only). */
    private void scheduleProgressBarReset() {
        progressResetTimer.restart();
    }

    /** Disarms a pending Ready revert so it cannot stomp on newer progress (EDT only). */
    private void cancelProgressBarReset() {
        progressResetTimer.stop();
    }

    /** Count-based progress percentage, capped at 100. */
    private static int percent(int cur, int total) {
        return (int) Math.min(100L, (long) cur * 100 / total);
    }

    /** Called on the EDT by {@link SyncEventBridge}; no self-marshaling. */
    public void onSyncStarted() {
        cancelProgressBarReset();
        components.getSyncButton().setText(CANCEL_SYNC_TEXT);
        components.getSyncButton().setEnabled(true);
        components.getPreviewSyncButton().setEnabled(false);
        components.getDirectionButton().setEnabled(false);
        components.getProgressBar().setIndeterminate(false);
        components.getProgressBar().setValue(0);
        components.getProgressBar().setString("Starting sync...");
    }

    /** Called on the EDT by {@link SyncEventBridge}; no self-marshaling. */
    public void onSyncCancelled() {
        cancelProgressBarReset();
        components.getProgressBar().setIndeterminate(false);
        components.getProgressBar().setString("Sync cancelled");
        updateSyncButtonState();
    }

    /** Called on the EDT by {@link SyncEventBridge}; no self-marshaling. */
    public void onSyncComplete() {
        String port = (String) components.getPortComboBox().getSelectedItem();
        String remote = state.getPendingMappingRemotePath();
        if (remote != null && !remote.isEmpty()) {
            File localFolder = syncManager.getSyncFolder();
            if (localFolder != null && localFolder.exists()) {
                settings.setRememberedFolderMapping(
                        port,
                        SettingsManager.normalizeFolderPath(localFolder.getAbsolutePath()),
                        SettingsManager.normalizeFolderPath(remote));
            }
        }
        state.clearPendingMappingRemotePath();
        components.getProgressBar().setIndeterminate(false);
        components.getProgressBar().setValue(100);
        components.getProgressBar().setString("Sync complete");
        scheduleProgressBarReset();
        updateSyncButtonState();
    }

    public void showNoChangesPreview(String message) {
        JOptionPane.showMessageDialog(
                owner, message, "Sync Preview", JOptionPane.INFORMATION_MESSAGE);
    }

    public void updateSyncButtonState() {
        boolean canSync = state.canSync(syncManager);
        boolean transferBusy = syncManager.isTransferBusy();
        boolean isSyncing = syncManager.isSyncing();
        boolean canOperate = canSync && !state.isPreviewInProgress();

        if (isSyncing) {
            components.getSyncButton().setText(CANCEL_SYNC_TEXT);
            components.getSyncButton().setEnabled(canSync);
        } else {
            components.getSyncButton().setText(START_SYNC_TEXT);
            components.getSyncButton().setEnabled(canOperate && !transferBusy);
        }

        components.getPreviewSyncButton().setEnabled(canOperate && !transferBusy);
        components.getDirectionButton().setEnabled(!transferBusy && !state.isPreviewInProgress());
    }

    /**
     * Monotonic clamp for manifest percentages: processed counts are monotonic, but reports may
     * arrive slightly out of order across the hash pool, so without the clamp the bar could briefly
     * jump backwards between two throttled updates.
     */
    private int lastManifestPercent = 0;

    public void onManifestProgress(int processed, int total, String fileName) {
        cancelProgressBarReset();
        javax.swing.JProgressBar bar = components.getProgressBar();
        if (processed < 0) {
            // Sender waiting for the remote manifest: nothing countable yet, show motion instead
            // so the silence reads as work, not a hang.
            bar.setValue(0);
            bar.setIndeterminate(true);
            bar.setString("Waiting for remote manifest...");
            lastManifestPercent = 0;
            return;
        }
        String filePart = fileName != null && !fileName.isEmpty() ? ": " + fileName : "";
        bar.setIndeterminate(false);
        if (processed == 0) {
            // The generation's first (unthrottled) event: resets the clamp for a new run.
            lastManifestPercent = 0;
        }
        if (total > 0) {
            int percent = Math.max(percent(processed, total), lastManifestPercent);
            lastManifestPercent = percent;
            bar.setValue(percent);
            bar.setString(
                    "Generating manifest "
                            + percent
                            + "% ("
                            + processed
                            + "/"
                            + total
                            + ")"
                            + filePart);
        } else {
            bar.setValue(0);
            bar.setString("Generating manifest (" + processed + " files)" + filePart);
        }
    }

    public void onFileProgress(int currentFile, int totalFiles, String fileName) {
        cancelProgressBarReset();
        javax.swing.JProgressBar bar = components.getProgressBar();
        if (totalFiles <= 0) {
            // The receiver's unknown-total batch path reports 0; a percentage of zero is
            // Infinity, which used to slam the bar to 100% and report "File 3/0".
            bar.setIndeterminate(true);
            bar.setString("File " + currentFile + (fileName != null ? ": " + fileName : ""));
            return;
        }
        bar.setIndeterminate(false);
        bar.setValue(percent(currentFile, totalFiles));
        bar.setString("File " + currentFile + "/" + totalFiles + ": " + fileName);
    }

    public void onTransferProgress(
            int currentBlock, int totalBlocks, long bytesTransferred, double speedBytesPerSec) {
        cancelProgressBarReset();
        components.getProgressBar().setIndeterminate(false);
        String speedStr = UiFormatting.formatSpeed(speedBytesPerSec);
        if (totalBlocks > 0) {
            components.getProgressBar().setValue((int) ((double) currentBlock / totalBlocks * 100));
            components
                    .getProgressBar()
                    .setString("Block " + currentBlock + "/" + totalBlocks + " - " + speedStr);
        } else {
            components.getProgressBar().setString("Block " + currentBlock + " - " + speedStr);
        }
        updateSyncButtonState();
    }

    /** Called on the EDT by {@link SyncEventBridge}; no self-marshaling. */
    public void onTransferComplete() {
        components.getProgressBar().setIndeterminate(false);
        components.getProgressBar().setString("Transfer complete");
        components.getProgressBar().setValue(100);
        scheduleProgressBarReset();
        updateSyncButtonState();
    }

    /** Called on the EDT by {@link SyncEventBridge}; no self-marshaling. */
    public void onConnectionStatusChanged(boolean isAlive) {
        state.setConnected(isAlive);
        if (isAlive) {
            components.applyConnectedUi();
        } else {
            // Kept local rather than the shared applyDisconnectedUi(Color): this
            // remote-loss path deliberately leaves the connect and direction buttons
            // untouched (the updateSyncButtonState() below owns the direction button).
            components.getStatusLabel().setText("Disconnected");
            components.getStatusLabel().setForeground(new java.awt.Color(128, 128, 128));
            components.getConnectButton().setText("Connect");
            components.getPortComboBox().setEnabled(true);
            components.getRefreshPortsButton().setEnabled(true);
            components.getSettingsButton().setEnabled(true);
            // The port that just vanished (e.g. an unplugged COM adapter) may no
            // longer exist; rescan so the combo box reflects the available ports.
            if (onDisconnectedCallback != null) {
                onDisconnectedCallback.run();
            }
        }
        updateSyncButtonState();
    }

    /** Called on the EDT by {@link SyncEventBridge}; no self-marshaling. */
    public void onLog(String message) {
        logController.log(message);
    }

    /** Called on the EDT by {@link SyncEventBridge}; no self-marshaling. */
    public void onError(String message) {
        logController.log("ERROR: " + message);
        cancelProgressBarReset();
        components.getProgressBar().setIndeterminate(false);
        components.getProgressBar().setString("Error");
        updateSyncButtonState();
    }

    /**
     * Called on the EDT when received file(s) could not be written because another program holds
     * them open. Shows a reusable dialog where the user chooses to retry, skip, or skip all; the
     * dialog stays open until every pending file is resolved and never blocks the transfer. An
     * empty list closes the dialog.
     */
    public void onPendingWrites(List<String> pendingPaths) {
        if (pendingPaths == null || pendingPaths.isEmpty()) {
            closePendingWriteDialog();
            return;
        }
        ensurePendingWriteDialog();
        pendingWriteList.setListData(pendingPaths.toArray(new String[0]));
        if (!pendingWriteDialog.isVisible()) {
            pendingWriteDialog.setVisible(true);
        }
    }

    private void ensurePendingWriteDialog() {
        if (pendingWriteDialog != null) {
            return;
        }
        pendingWriteList = new javax.swing.JList<>();
        pendingWriteList.setSelectionMode(
                javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        pendingWriteList.setVisibleRowCount(8);

        javax.swing.JButton retryButton = new javax.swing.JButton("Retry");
        retryButton.addActionListener(
                e -> syncManager.retryPendingWrites(getPendingWriteSelection()));
        javax.swing.JButton skipButton = new javax.swing.JButton("Skip");
        skipButton.addActionListener(
                e -> syncManager.skipPendingWrites(getPendingWriteSelection()));
        javax.swing.JButton skipAllButton = new javax.swing.JButton("Skip All");
        skipAllButton.addActionListener(e -> syncManager.skipAllPendingWrites());

        javax.swing.JPanel buttons =
                new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        buttons.add(retryButton);
        buttons.add(skipButton);
        buttons.add(skipAllButton);

        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout(8, 8));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(
                new javax.swing.JLabel(
                        "<html>The following files are locked by another program and could not"
                                + " be written:<br>"
                                + "Close the programs using them, then click &quot;Retry&quot;, or"
                                + " select files and click &quot;Skip&quot;"
                                + " (skipped files will be picked up again on the next"
                                + " sync):</html>"),
                java.awt.BorderLayout.NORTH);
        content.add(new javax.swing.JScrollPane(pendingWriteList), java.awt.BorderLayout.CENTER);
        content.add(buttons, java.awt.BorderLayout.SOUTH);

        pendingWriteDialog = new javax.swing.JDialog(owner, "Files In Use", false);
        pendingWriteDialog.setContentPane(content);
        pendingWriteDialog.setSize(520, 320);
        pendingWriteDialog.setLocationRelativeTo(owner);
    }

    /** Selected paths, or all listed paths when nothing is selected. */
    private List<String> getPendingWriteSelection() {
        List<String> selected = pendingWriteList.getSelectedValuesList();
        if (!selected.isEmpty()) {
            return selected;
        }
        List<String> all = new java.util.ArrayList<>();
        for (int i = 0; i < pendingWriteList.getModel().getSize(); i++) {
            all.add(pendingWriteList.getModel().getElementAt(i));
        }
        return all;
    }

    private void closePendingWriteDialog() {
        if (pendingWriteDialog != null && pendingWriteDialog.isVisible()) {
            pendingWriteDialog.setVisible(false);
        }
    }
}
