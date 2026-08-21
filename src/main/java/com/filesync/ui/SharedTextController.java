package com.filesync.ui;

import com.filesync.sync.FileSyncManager;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.MouseInputAdapter;

/** Shared text area behavior, clipboard actions and manual send. */
public class SharedTextController {
    private final MainFrameComponents components;
    private final MainFrameState state;
    private final FileSyncManager syncManager;
    private final LogController logController;
    private final SharedTextUndoManager undoManager = new SharedTextUndoManager();

    public SharedTextController(
            MainFrameComponents components,
            MainFrameState state,
            FileSyncManager syncManager,
            LogController logController) {
        this.components = components;
        this.state = state;
        this.syncManager = syncManager;
        this.logController = logController;
    }

    public void initEventHandlers() {
        components.getSharedTextArea().getDocument().addUndoableEditListener(undoManager);
        InputMap inputMap = components.getSharedTextArea().getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = components.getSharedTextArea().getActionMap();
        inputMap.put(KeyStroke.getKeyStroke("control Z"), "sharedText.undo");
        actionMap.put(
                "sharedText.undo",
                new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        undoManager.tryUndo();
                    }
                });
        inputMap.put(KeyStroke.getKeyStroke("control Y"), "sharedText.redo");
        inputMap.put(KeyStroke.getKeyStroke("control shift Z"), "sharedText.redo");
        actionMap.put(
                "sharedText.redo",
                new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        undoManager.tryRedo();
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
                .getSendSharedTextButton()
                .addActionListener(
                        event -> {
                            // Send result ("Shared text sent" / "queued - reason") is logged
                            // by SharedTextService so the log reflects what actually happened.
                            pushSharedTextToRemote();
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
                                undoManager.runAsSingleEdit(
                                        () ->
                                                components
                                                        .getSharedTextArea()
                                                        .setText(clipboardText));
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
                                undoManager.runAsSingleEdit(
                                        () -> {
                                            if (!components
                                                    .getSharedTextArea()
                                                    .getText()
                                                    .isEmpty()) {
                                                components.getSharedTextArea().append("\n");
                                            }
                                            components.getSharedTextArea().append(clipboardText);
                                        });
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
                () ->
                        undoManager.runAsSingleEdit(
                                () -> components.getSharedTextArea().setText(text)));
    }

    public void pushSharedTextToRemote() {
        if (!state.isConnected() || !syncManager.isConnectionAlive()) {
            logController.log("Cannot send shared text - not connected");
            return;
        }
        syncManager.sendSharedText(components.getSharedTextArea().getText());
    }
}
