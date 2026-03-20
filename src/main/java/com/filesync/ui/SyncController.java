package com.filesync.ui;

import java.io.File;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

import com.filesync.config.SettingsManager;
import com.filesync.sync.FileSyncManager;
import com.filesync.sync.SyncPreviewPlan;

/**
 * Sync flow and control panel actions.
 */
public class SyncController implements SyncPreviewRenderer.ConflictResolver {
    private static final String START_SYNC_TEXT = "Start Sync";
    private static final String CANCEL_SYNC_TEXT = "Cancel";

    private final JFrame owner;
    private final MainFrameComponents components;
    private final FileSyncManager syncManager;
    private final MainFrameState state;
    private final SettingsManager settings;
    private final LogController logController;
    private SyncPreviewRenderer previewRenderer;

    public SyncController(JFrame owner,
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
    }

    public void setPreviewRenderer(SyncPreviewRenderer previewRenderer) {
        this.previewRenderer = previewRenderer;
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

        components.getRespectGitignoreCheckBox().addActionListener(event -> {
            boolean respectGitignore = components.getRespectGitignoreCheckBox().isSelected();
            syncManager.setRespectGitignoreMode(respectGitignore);
            settings.setRespectGitignore(respectGitignore);
            settings.save();
            logController.log("Respect .gitignore: " + (respectGitignore ? "enabled" : "disabled"));
        });

        components.getStrictSyncCheckBox().addActionListener(event -> {
            boolean strictMode = components.getStrictSyncCheckBox().isSelected();
            syncManager.setStrictSyncMode(strictMode);
            settings.setStrictSync(strictMode);
            settings.save();
            logController.log("Strict sync mode: " + (strictMode ? "enabled" : "disabled"));
            updateRespectGitignoreState();
        });

        components.getFastModeCheckBox().addActionListener(event -> {
            boolean fastMode = components.getFastModeCheckBox().isSelected();
            syncManager.setFastMode(fastMode);
            settings.setFastMode(fastMode);
            settings.save();
            logController.log("Fast mode: " + (fastMode ? "enabled" : "disabled"));
        });
    }

    private void onSyncButtonClicked() {
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
            JOptionPane.showMessageDialog(owner, "Cannot change direction during data transfer",
                    "Direction Change Blocked", JOptionPane.WARNING_MESSAGE);
            return;
        }

        state.setSender(!state.isSender());
        applyDirection(state.isSender());
        syncManager.setIsSender(state.isSender());
        syncManager.notifyDirectionChange();
        updateSyncButtonState();
        logController.log("Direction changed: " + (state.isSender() ? "Sender (A -> B)" : "Receiver (B <- A)"));
    }

    public void startSync() {
        if (!ensureSenderRoleReady()) {
            logController.log("Waiting for sync from sender...");
            return;
        }
        runSyncWithPreflight(this::doStartSync);
    }

    public void previewSync() {
        if (!ensureSenderRoleReady()) {
            logController.log("Waiting for sync from sender...");
            return;
        }
        runSyncWithPreflight(this::runSyncPreview);
    }

    private void doStartSync() {
        syncManager.initiateSync();
    }

    public void cancelSync() {
        if (!syncManager.isTransferBusy()) {
            return;
        }
        logController.log("Cancelling sync...");
        syncManager.cancelSync();
        components.getProgressBar().setString("Cancelling sync...");
        updateSyncButtonState();
    }

    private void runSyncWithPreflight(Runnable onProceed) {
        File localFolder = syncManager.getSyncFolder();
        if (localFolder == null || !localFolder.exists()) {
            return;
        }
        String port = (String) components.getPortComboBox().getSelectedItem();
        String localPath = localFolder.getAbsolutePath();

        SwingWorker<String, Void> preflightWorker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return syncManager.requestRemoteFolderContext();
            }

            @Override
            protected void done() {
                try {
                    String remotePath = get();
                    String nLocal = SettingsManager.normalizeFolderPath(localPath);
                    String nRemote = SettingsManager.normalizeFolderPath(remotePath);
                    List<String[]> rememberedMappings = settings.getRememberedFolderMappings(port);
                    boolean match = false;
                    for (String[] remembered : rememberedMappings) {
                        if (SettingsManager.isMappingMatch(
                                nLocal,
                                nRemote,
                                remembered[0],
                                remembered[1])) {
                            match = true;
                            break;
                        }
                    }

                    if (match) {
                        state.setPendingMappingRemotePath(nRemote);
                        onProceed.run();
                        return;
                    }

                    boolean bothSidesChanged = !rememberedMappings.isEmpty();
                    for (String[] remembered : rememberedMappings) {
                        if (!SettingsManager.isBothSidesChangedFromRemembered(
                                nLocal,
                                nRemote,
                                remembered[0],
                                remembered[1])) {
                            bothSidesChanged = false;
                            break;
                        }
                    }
                    if (bothSidesChanged) {
                        state.setPendingMappingRemotePath(nRemote);
                        logController.log("Detected folder changes on both sides; proceeding with current mapping.");
                        onProceed.run();
                        return;
                    }

                    StringBuilder msg = new StringBuilder();
                    msg.append("Folder mapping differs from last successful syncs.\n\n");
                    msg.append("Remembered:\n");
                    if (rememberedMappings.isEmpty()) {
                        msg.append("  (none)\n");
                    } else {
                        for (int i = 0; i < rememberedMappings.size(); i++) {
                            String[] remembered = rememberedMappings.get(i);
                            msg.append("  ")
                                    .append(i + 1)
                                    .append(") ")
                                    .append(remembered[0].isEmpty() ? "(none)" : remembered[0])
                                    .append(" -> ")
                                    .append(remembered[1].isEmpty() ? "(none)" : remembered[1])
                                    .append('\n');
                        }
                    }
                    msg.append("\n\nCurrent: ").append(nLocal);
                    msg.append(" -> ").append(nRemote.isEmpty() ? "(unknown)" : nRemote);
                    msg.append("\n\nProceed? The new mapping will be remembered after successful sync.");

                    int response = JOptionPane.showOptionDialog(
                            owner,
                            msg.toString(),
                            "Confirm Folder Mapping Change",
                            JOptionPane.OK_CANCEL_OPTION,
                            JOptionPane.WARNING_MESSAGE,
                            null,
                            new Object[] {"Continue", "Cancel"},
                            "Cancel");
                    if (response == 0) {
                        state.setPendingMappingRemotePath(nRemote);
                        onProceed.run();
                    }
                } catch (Exception e) {
                    logController.log("Preflight check failed: "
                            + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
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
        if (state.isPreviewInProgress()) {
            return;
        }

        state.setPreviewInProgress(true);
        updateSyncButtonState();

        SwingWorker<SyncPreviewPlan, Void> previewWorker = new SwingWorker<SyncPreviewPlan, Void>() {
            @Override
            protected SyncPreviewPlan doInBackground() {
                return syncManager.previewSync();
            }

            @Override
            protected void done() {
                state.setPreviewInProgress(false);
                updateSyncButtonState();

                try {
                    SyncPreviewPlan syncPreview = get();
                    if (syncPreview == null) {
                        showNoChangesPreview("Sync preview could not be computed.");
                        return;
                    }

                    SyncPreviewRenderer.SyncPreviewResult previewResult =
                            previewRenderer.showSyncPreviewDialogWithResult(syncPreview);
                    if (previewResult == null) {
                        return;
                    }
                    SyncPreviewPlan selectedPlan = previewResult.getPlan();
                    DefaultTableModel previewModel = previewResult.getModel();
                    List<SyncPreviewRow> previewRows = previewResult.getRows();

                    // Resolve conflicts for selected files before starting sync
                    if (!selectedPlan.getConflicts().isEmpty()) {
                        boolean conflictsResolved = previewRenderer.resolveConflictsForSelectedFiles(
                                selectedPlan,
                                previewModel,
                                previewRows);
                        if (!conflictsResolved) {
                            // User cancelled conflict resolution
                            return;
                        }
                        // Re-create filtered plan now that conflicts have resolutions (SKIP/KEEP_REMOTE exclude from transfer)
                        selectedPlan = previewRenderer.createFilteredSyncPlan(syncPreview, previewModel, previewRows);
                    }

                    components.getSyncButton().setEnabled(false);
                    components.getPreviewSyncButton().setEnabled(false);
                    components.getProgressBar().setValue(0);
                    syncManager.initiateSync(selectedPlan);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    String message = "Sync preview was interrupted";
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
                    JOptionPane.showMessageDialog(
                            owner,
                            "Could not prepare sync preview.\n" + message,
                            "Sync Preview Failed",
                            JOptionPane.ERROR_MESSAGE);
                    logController.log("Sync preview failed: " + message);
                }
            }
        };
        previewWorker.execute();
    }

    public void onSyncStarted() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            components.getSyncButton().setText(CANCEL_SYNC_TEXT);
            components.getSyncButton().setEnabled(true);
            components.getPreviewSyncButton().setEnabled(false);
            components.getDirectionButton().setEnabled(false);
            components.getProgressBar().setValue(0);
            components.getProgressBar().setString("Starting sync...");
        });
    }

    public void onSyncCancelled() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            components.getProgressBar().setString("Sync cancelled");
            updateSyncButtonState();
        });
    }

    public void onSyncComplete() {
        javax.swing.SwingUtilities.invokeLater(() -> {
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
            components.getProgressBar().setValue(100);
            components.getProgressBar().setString("Sync complete");
            updateSyncButtonState();
        });
    }

    public void showNoChangesPreview(String message) {
        JOptionPane.showMessageDialog(
                owner,
                message,
                "Sync Preview",
                JOptionPane.INFORMATION_MESSAGE);
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

    public void onFileProgress(int currentFile, int totalFiles, String fileName) {
        components.getProgressBar().setValue((int) ((double) currentFile / totalFiles * 100));
        components.getProgressBar().setString("File " + currentFile + "/" + totalFiles + ": " + fileName);
    }

    public void onTransferProgress(int currentBlock, int totalBlocks, long bytesTransferred, double speedBytesPerSec) {
        String speedStr = UiFormatting.formatSpeed(speedBytesPerSec);
        if (totalBlocks > 0) {
            components.getProgressBar().setValue((int) ((double) currentBlock / totalBlocks * 100));
            components.getProgressBar().setString("Block " + currentBlock + "/" + totalBlocks + " - " + speedStr);
        } else {
            components.getProgressBar().setString("Block " + currentBlock + " - " + speedStr);
        }
        updateSyncButtonState();
    }

    public void onTransferComplete() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            components.getProgressBar().setString("Transfer complete");
            components.getProgressBar().setValue(100);
            updateSyncButtonState();
        });
    }

    public void onConnectionStatusChanged(boolean isAlive) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            state.setConnected(isAlive);
            if (isAlive) {
                components.getStatusLabel().setText("Connected");
                components.getStatusLabel().setForeground(new java.awt.Color(0, 128, 0));
                components.getConnectButton().setText("Disconnect");
                components.getPortComboBox().setEnabled(false);
                components.getRefreshPortsButton().setEnabled(false);
                components.getSettingsButton().setEnabled(false);
                /* Re-enable Sync Control when connection restored; connect() disables them during connect */
                components.getDirectionButton().setEnabled(true);
            } else {
                components.getStatusLabel().setText("Connection Lost");
                components.getStatusLabel().setForeground(java.awt.Color.ORANGE);
                components.getConnectButton().setText("Connect");
                components.getPortComboBox().setEnabled(true);
                components.getRefreshPortsButton().setEnabled(true);
                components.getSettingsButton().setEnabled(true);
            }
            updateSyncButtonState();
        });
    }

    public void onLog(String message) {
        logController.log(message);
    }

    public void onError(String message) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            logController.log("ERROR: " + message);
            components.getProgressBar().setString("Error");
            updateSyncButtonState();
        });
    }
}
