package com.filesync.ui;

import com.filesync.config.SettingsManager;
import com.filesync.sync.FileSyncManager;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.util.function.Consumer;
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
    private final SettingsManager settings;
    private final LogController logController;
    private final SharedTextUndoManager undoManager = new SharedTextUndoManager();

    public SharedTextController(
            MainFrameComponents components,
            MainFrameState state,
            FileSyncManager syncManager,
            SettingsManager settings,
            LogController logController) {
        this.components = components;
        this.state = state;
        this.syncManager = syncManager;
        this.settings = settings;
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
                                    copySharedTextToClipboard();
                                }
                            }
                        });

        components
                .getSendToRemoteClipboardCheckBox()
                .addActionListener(
                        event -> {
                            settings.setSharedTextAutoCopy(
                                    components.getSendToRemoteClipboardCheckBox().isSelected());
                            settings.save();
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
                        event ->
                                applyClipboardText(
                                        "Text overwritten from clipboard",
                                        text -> components.getSharedTextArea().setText(text)));

        components
                .getAppendFromClipboardButton()
                .addActionListener(
                        event ->
                                applyClipboardText(
                                        "Text appended from clipboard",
                                        text -> {
                                            if (!components
                                                    .getSharedTextArea()
                                                    .getText()
                                                    .isEmpty()) {
                                                components.getSharedTextArea().append("\n");
                                            }
                                            components.getSharedTextArea().append(text);
                                        }));

        components
                .getCopyFromClipboardButton()
                .addActionListener(event -> copySharedTextToClipboard());
    }

    /** Copies the shared text area's content to the system clipboard and logs it. */
    private void copySharedTextToClipboard() {
        String text = components.getSharedTextArea().getText();
        writeTextToSystemClipboard(text);
        logController.log("Shared text copied to clipboard");
    }

    /** Writes {@code text} to the system clipboard; must be called on the EDT. */
    private void writeTextToSystemClipboard(String text) {
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
    }

    /**
     * Reads the clipboard as text and applies it to the shared text area as a single undoable edit,
     * logging {@code logMessage} on success.
     */
    private void applyClipboardText(String logMessage, Consumer<String> edit) {
        try {
            String clipboardText =
                    (String)
                            Toolkit.getDefaultToolkit()
                                    .getSystemClipboard()
                                    .getData(DataFlavor.stringFlavor);
            undoManager.runAsSingleEdit(() -> edit.accept(clipboardText));
            logController.log(logMessage);
        } catch (UnsupportedFlavorException ex) {
            logController.log("Clipboard does not contain text data");
        } catch (java.io.IOException ex) {
            logController.log("Failed to read from clipboard: " + ex.getMessage());
        }
    }

    /**
     * Called on the EDT by {@link SyncEventBridge}; no self-marshaling. When the sender asked for
     * it ({@code autoCopyToClipboard}), the text also lands in the local system clipboard so no
     * manual "Copy to Clipboard" click is needed.
     */
    public void onSharedTextReceived(String text, boolean autoCopyToClipboard) {
        undoManager.runAsSingleEdit(() -> components.getSharedTextArea().setText(text));
        if (autoCopyToClipboard) {
            writeTextToSystemClipboard(text);
            logController.log("Shared text copied to clipboard");
        }
    }

    public void pushSharedTextToRemote() {
        if (!state.isConnected() || !syncManager.isConnectionAlive()) {
            logController.log("Cannot send shared text - not connected");
            return;
        }
        String text = components.getSharedTextArea().getText();
        boolean sendToRemoteClipboard = components.getSendToRemoteClipboardCheckBox().isSelected();
        // The send either writes a frame inline or runs a whole XMODEM transfer, so it must not
        // run on the event dispatch thread. The button stays disabled until the send returns.
        components.getSendSharedTextButton().setEnabled(false);
        Thread sender =
                new Thread(
                        () -> {
                            try {
                                syncManager.sendSharedText(text, sendToRemoteClipboard);
                            } finally {
                                SwingUtilities.invokeLater(
                                        () ->
                                                components
                                                        .getSendSharedTextButton()
                                                        .setEnabled(true));
                            }
                        },
                        "SharedTextSend");
        sender.setDaemon(true);
        sender.start();
    }
}
