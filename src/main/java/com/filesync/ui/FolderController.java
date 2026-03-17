package com.filesync.ui;

import java.io.File;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

import com.filesync.config.SettingsManager;
import com.filesync.sync.FileSyncManager;

/**
 * Folder history and folder-selection behaviors.
 */
public class FolderController {
    private final MainFrameComponents components;
    private final SettingsManager settings;
    private final FileSyncManager syncManager;
    private final MainFrameState state;
    private final LogController logController;
    private final Runnable updateSyncButtonState;

    public FolderController(MainFrameComponents components,
                           SettingsManager settings,
                           FileSyncManager syncManager,
                           MainFrameState state,
                           LogController logController,
                           Runnable updateSyncButtonState) {
        this.components = components;
        this.settings = settings;
        this.syncManager = syncManager;
        this.state = state;
        this.logController = logController;
        this.updateSyncButtonState = updateSyncButtonState;
    }

    public void initEventHandlers() {
        components.getBrowseFolderButton().addActionListener(event -> browseFolder());
        components.getFolderComboBox().addActionListener(event -> {
            if (state.isSuppressFolderSelectionEvents()) {
                return;
            }
            String selectedFolder = (String) components.getFolderComboBox().getSelectedItem();
            if (selectedFolder != null && !selectedFolder.isBlank()) {
                applyFolderSelection(selectedFolder, true);
                logController.log("Selected folder: " + selectedFolder);
            }
        });
    }

    public void loadFolderHistory() {
        components.getFolderComboBox().removeAllItems();
        for (String folderPath : settings.getRecentFolders()) {
            if (folderPath == null || folderPath.isEmpty()) {
                continue;
            }
            File folder = new File(folderPath);
            if (folder.exists() && folder.isDirectory()) {
                components.getFolderComboBox().addItem(folderPath);
            }
        }
        while (components.getFolderComboBox().getItemCount() > SettingsManager.MAX_RECENT_FOLDERS) {
            components.getFolderComboBox().removeItemAt(components.getFolderComboBox().getItemCount() - 1);
        }
    }

    public void applyFolderSelection(String folderPath, boolean rememberFolder) {
        if (folderPath == null || folderPath.isBlank()) {
            return;
        }

        String normalizedFolderPath = folderPath.strip();
        File folder = new File(normalizedFolderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            components.getFolderComboBox().removeItem(normalizedFolderPath);
            syncManager.setSyncFolder(null);
            updateSyncButtonState.run();
            return;
        }

        syncManager.setSyncFolder(folder);
        updateSyncButtonState.run();

        if (rememberFolder) {
            try {
                state.setSuppressFolderSelectionEvents(true);
                components.getFolderComboBox().removeItem(normalizedFolderPath);
                components.getFolderComboBox().insertItemAt(normalizedFolderPath, 0);
                while (components.getFolderComboBox().getItemCount() > SettingsManager.MAX_RECENT_FOLDERS) {
                    components.getFolderComboBox().removeItemAt(components.getFolderComboBox().getItemCount() - 1);
                }
                components.getFolderComboBox().setSelectedItem(normalizedFolderPath);
            } finally {
                state.setSuppressFolderSelectionEvents(false);
            }
            settings.addRecentFolder(normalizedFolderPath);
        } else if (components.getFolderComboBox().getItemCount() == 0) {
            try {
                state.setSuppressFolderSelectionEvents(true);
                components.getFolderComboBox().addItem(normalizedFolderPath);
                components.getFolderComboBox().setSelectedItem(normalizedFolderPath);
            } finally {
                state.setSuppressFolderSelectionEvents(false);
            }
        }
    }

    public File getCurrentFolderFromSelection() {
        String selectedFolder = (String) components.getFolderComboBox().getSelectedItem();
        if (selectedFolder != null && !selectedFolder.isBlank()) {
            File selectedFolderFile = new File(selectedFolder);
            if (selectedFolderFile.exists() && selectedFolderFile.isDirectory()) {
                return selectedFolderFile;
            }
        }
        return null;
    }

    public void browseFolder() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("Select Sync Folder");

        String lastFolder = settings.getLastFolder();
        if (lastFolder != null && !lastFolder.isEmpty()) {
            File lastDir = new File(lastFolder);
            if (lastDir.exists()) {
                fileChooser.setCurrentDirectory(lastDir);
            }
        }

        File currentFolder = getCurrentFolderFromSelection();
        if (currentFolder != null && currentFolder.exists()) {
            fileChooser.setCurrentDirectory(currentFolder);
        }

        if (fileChooser.showOpenDialog(SwingUtilities.getWindowAncestor(components.getFolderComboBox())) == JFileChooser.APPROVE_OPTION) {
            File selectedFolder = fileChooser.getSelectedFile();
            applyFolderSelection(selectedFolder.getAbsolutePath(), true);
            logController.log("Selected folder: " + selectedFolder.getAbsolutePath());
        }
    }
}
