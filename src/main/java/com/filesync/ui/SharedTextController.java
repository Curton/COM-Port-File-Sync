package com.filesync.ui;

import com.filesync.sync.FileSyncManager;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.MouseInputAdapter;

/** Shared text area behavior, clipboard actions and debounced outbound sync. */
public class SharedTextController {
    private final MainFrameComponents components;
    private final MainFrameState state;
    private final FileSyncManager syncManager;
    private final LogController logController;
    private final Timer sharedTextSyncTimer;

    public SharedTextController(
            MainFrameComponents components,
            MainFrameState state,
            FileSyncManager syncManager,
            LogController logController) {
        this.components = components;
        this.state = state;
        this.syncManager = syncManager;
        this.logController = logController;

        sharedTextSyncTimer = new Timer(2000, e -> pushSharedTextToRemote());
        sharedTextSyncTimer.setRepeats(false);
    }

    public void initEventHandlers() {
        components
                .getSharedTextArea()
                .getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            @Override
                            public void insertUpdate(DocumentEvent e) {
                                onSharedTextEdited();
                            }

                            @Override
                            public void removeUpdate(DocumentEvent e) {
                                onSharedTextEdited();
                            }

                            @Override
                            public void changedUpdate(DocumentEvent e) {
                                onSharedTextEdited();
                            }
                        });

        components
                .getSharedTextArea()
                .addMouseListener(
                        new MouseInputAdapter() {
                            @Override
                            public void mouseClicked(java.awt.event.MouseEvent e) {
                                if (e.getClickCount() == 2) {
                                    String text = components.getSharedTextArea().getText();
                                    StringSelection selection = new StringSelection(text);
                                    Toolkit.getDefaultToolkit()
                                            .getSystemClipboard()
                                            .setContents(selection, null);
                                    logController.log("Shared text copied to clipboard");
                                }
                            }
                        });

        components
                .getOverwriteFromClipboardButton()
                .addActionListener(
                        event -> {
                            try {
                                String clipboardText =
                                        (String)
                                                Toolkit.getDefaultToolkit()
                                                        .getSystemClipboard()
                                                        .getData(DataFlavor.stringFlavor);
                                components.getSharedTextArea().setText(clipboardText);
                                logController.log("Text overwritten from clipboard");
                            } catch (UnsupportedFlavorException ex) {
                                logController.log("Clipboard does not contain text data");
                            } catch (java.io.IOException ex) {
                                logController.log(
                                        "Failed to read from clipboard: " + ex.getMessage());
                            }
                        });

        components
                .getAppendFromClipboardButton()
                .addActionListener(
                        event -> {
                            try {
                                String clipboardText =
                                        (String)
                                                Toolkit.getDefaultToolkit()
                                                        .getSystemClipboard()
                                                        .getData(DataFlavor.stringFlavor);
                                if (!components.getSharedTextArea().getText().isEmpty()) {
                                    components.getSharedTextArea().append("\n");
                                }
                                components.getSharedTextArea().append(clipboardText);
                                logController.log("Text appended from clipboard");
                            } catch (UnsupportedFlavorException ex) {
                                logController.log("Clipboard does not contain text data");
                            } catch (java.io.IOException ex) {
                                logController.log(
                                        "Failed to read from clipboard: " + ex.getMessage());
                            }
                        });

        components
                .getCopyFromClipboardButton()
                .addActionListener(
                        event -> {
                            String text = components.getSharedTextArea().getText();
                            StringSelection selection = new StringSelection(text);
                            Toolkit.getDefaultToolkit()
                                    .getSystemClipboard()
                                    .setContents(selection, null);
                            logController.log("Shared text copied to clipboard");
                        });
    }

    public void onSharedTextReceived(String text) {
        SwingUtilities.invokeLater(
                () -> {
                    state.setSuppressSharedTextEvents(true);
                    try {
                        sharedTextSyncTimer.stop();
                        components.getSharedTextArea().setText(text);
                    } finally {
                        state.setSuppressSharedTextEvents(false);
                    }
                });
    }

    private void onSharedTextEdited() {
        if (state.isSuppressSharedTextEvents()) {
            return;
        }
        sharedTextSyncTimer.restart();
    }

    public void pushSharedTextToRemote() {
        if (!state.isConnected() || !syncManager.isConnectionAlive()) {
            return;
        }
        syncManager.sendSharedText(components.getSharedTextArea().getText());
    }

    public void shutdown() {
        sharedTextSyncTimer.stop();
    }
}
