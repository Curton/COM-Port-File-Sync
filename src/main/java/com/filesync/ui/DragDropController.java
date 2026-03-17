package com.filesync.ui;

import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

import com.filesync.sync.FileSyncManager;

/**
 * Drag-and-drop handling for incoming dropped files.
 */
public class DragDropController {
    private final JComponent dropTarget;
    private final MainFrameComponents components;
    private final FileSyncManager syncManager;
    private final MainFrameState state;
    private final LogController logController;
    private String progressBarTextBeforeDrop;

    public DragDropController(JComponent dropTarget,
                              MainFrameComponents components,
                              FileSyncManager syncManager,
                              MainFrameState state,
                              LogController logController) {
        this.dropTarget = dropTarget;
        this.components = components;
        this.syncManager = syncManager;
        this.state = state;
        this.logController = logController;
    }

    public void setupDragAndDrop() {
        dropTarget.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                boolean canImport = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
                setDropHint(canImport);
                return canImport;
            }

            @SuppressWarnings("unchecked")
            @Override
            public boolean importData(TransferSupport support) {
                clearDropHint();
                if (!canImport(support)) {
                    logController.log("Please drop one file to send.");
                    return false;
                }

                try {
                    List<File> droppedFiles = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    return handleDroppedFiles(droppedFiles);
                } catch (java.awt.datatransfer.UnsupportedFlavorException ex) {
                    logController.log("Cannot handle dropped item: unsupported data flavor");
                    return false;
                } catch (IOException ex) {
                    logController.log("Failed to process dropped item: " + ex.getMessage());
                    return false;
                }
            }
        });
    }

    private boolean handleDroppedFiles(List<File> droppedFiles) {
        if (droppedFiles == null || droppedFiles.isEmpty()) {
            logController.log("No dropped files found");
            return false;
        }
        if (droppedFiles.size() != 1) {
            logController.log("Only one file is supported for drag-and-drop");
            return false;
        }

        File droppedFile = droppedFiles.get(0);
        if (droppedFile == null || !droppedFile.isFile()) {
            logController.log("Only files can be dropped (folders are not supported)");
            return false;
        }

        if (!state.isConnected() || !syncManager.isConnectionAlive()) {
            logController.log("Cannot send dropped file: not connected");
            return false;
        }
        if (syncManager.isTransferBusy()) {
            logController.log("Cannot send dropped file while a transfer is in progress");
            return false;
        }

        new Thread(() -> syncManager.sendDropFile(droppedFile), "DroppedFileSender").start();
        logController.log("Sending dropped file: " + droppedFile.getAbsolutePath());
        return true;
    }

    private void setDropHint(boolean active) {
        SwingUtilities.invokeLater(() -> {
            if (active) {
                if (progressBarTextBeforeDrop == null) {
                    progressBarTextBeforeDrop = components.getProgressBar().getString();
                }
                components.getProgressBar().setString("Drop a file to send to remote");
            } else {
                clearDropHint();
            }
        });
    }

    private void clearDropHint() {
        SwingUtilities.invokeLater(() -> {
            if (progressBarTextBeforeDrop != null) {
                components.getProgressBar().setString(progressBarTextBeforeDrop);
                progressBarTextBeforeDrop = null;
            }
        });
    }
}
