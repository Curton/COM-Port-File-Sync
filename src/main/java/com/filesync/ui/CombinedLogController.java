package com.filesync.ui;

import com.filesync.config.SettingsManager;
import com.filesync.sync.FileSyncManager;
import com.filesync.sync.TimeSyncMarker;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.plaf.basic.BasicMenuItemUI;

/**
 * Right-click menu on the log area: "Save combined log" fetches the remote log (sender only),
 * merges it with the local log using the TIME-SYNC markers written at sync start to align the two
 * machines' clocks, and saves the result into the currently selected sync folder.
 */
public class CombinedLogController {
    private final MainFrameComponents components;
    private final FileSyncManager syncManager;
    private final FolderController folderController;
    private final SettingsManager settings;
    private final LogController logController;

    private final JPopupMenu popupMenu = new JPopupMenu();
    private final JMenuItem saveCombinedLogItem = createCenteredMenuItem();

    /**
     * The Windows L&F menu layout pins the text after a leading icon column (menu items ignore
     * horizontalAlignment), so the entry would render visibly off-center and far wider than its
     * text. Size the entry to the text itself and draw the text centered over it, keeping the rest
     * of the standard menu rendering.
     */
    private static JMenuItem createCenteredMenuItem() {
        JMenuItem item =
                new JMenuItem("Save combined log") {
                    @Override
                    public Dimension getPreferredSize() {
                        FontMetrics fm = getFontMetrics(getFont());
                        return new Dimension(fm.stringWidth(getText()) + 12, fm.getHeight() + 8);
                    }
                };
        item.setUI(
                new BasicMenuItemUI() {
                    @Override
                    protected void paintText(
                            Graphics g, JMenuItem menuItem, Rectangle textRect, String text) {
                        Rectangle centered = new Rectangle(textRect);
                        centered.x = (menuItem.getWidth() - textRect.width) / 2;
                        super.paintText(g, menuItem, centered, text);
                    }
                });
        return item;
    }

    public CombinedLogController(
            MainFrameComponents components,
            FileSyncManager syncManager,
            FolderController folderController,
            SettingsManager settings,
            LogController logController) {
        this.components = components;
        this.syncManager = syncManager;
        this.folderController = folderController;
        this.settings = settings;
        this.logController = logController;
        // Replace the thick system popup border with a thin one so the menu hugs the entry.
        popupMenu.setBorder(BorderFactory.createLineBorder(new Color(0xA0A0A0)));
    }

    public void initEventHandlers() {
        saveCombinedLogItem.addActionListener(event -> saveCombinedLog());
        popupMenu.add(saveCombinedLogItem);

        JTextArea logTextArea = components.getLogTextArea();
        logTextArea.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        maybeShowPopup(e);
                    }

                    @Override
                    public void mouseReleased(MouseEvent e) {
                        maybeShowPopup(e);
                    }

                    private void maybeShowPopup(MouseEvent e) {
                        if (e.isPopupTrigger()) {
                            saveCombinedLogItem.setEnabled(canFetchRemoteLog());
                            popupMenu.show(logTextArea, e.getX(), e.getY());
                        }
                    }
                });
    }

    private boolean canFetchRemoteLog() {
        return syncManager.isConnectionAlive()
                && syncManager.isSender()
                && !syncManager.isTransferBusy();
    }

    /** Package-private for tests: the right-click menu entry, whose text is drawn centered. */
    JMenuItem getSaveCombinedLogItem() {
        return saveCombinedLogItem;
    }

    /** Package-private for tests: the popup menu shown on right-click. */
    JPopupMenu getPopupMenu() {
        return popupMenu;
    }

    void saveCombinedLog() {
        if (!syncManager.isConnectionAlive()) {
            logController.log("Cannot save combined log - not connected");
            return;
        }
        if (!syncManager.isSender()) {
            logController.log("Only the sender can save the combined log");
            return;
        }
        if (syncManager.isTransferBusy()) {
            logController.log("Cannot save combined log during data transfer");
            return;
        }

        File targetFolder = folderController.getCurrentFolderFromSelection();
        if (targetFolder == null) {
            String lastFolder = settings.getLastFolder();
            if (lastFolder != null && !lastFolder.isEmpty()) {
                File fallback = new File(lastFolder);
                if (fallback.exists() && fallback.isDirectory()) {
                    targetFolder = fallback;
                }
            }
        }
        if (targetFolder == null) {
            logController.log("Cannot save combined log - no folder selected");
            return;
        }

        final File folderToSave = targetFolder;
        // Log the local TIME-SYNC marker synchronously into the log mirror (the remote one is
        // requested over the serial protocol inside fetchRemoteLogText), then snapshot the local
        // log so both sides carry a fresh marker for the clock-offset alignment.
        logController.logMarker(TimeSyncMarker.markerMessage());
        final String localLogText = logController.getLogText();

        SwingWorker<String, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected String doInBackground() throws Exception {
                        return syncManager.fetchRemoteLogText();
                    }

                    @Override
                    protected void done() {
                        try {
                            String remoteLogText = get();
                            if (remoteLogText == null) {
                                logController.log("Failed to fetch remote log");
                                return;
                            }
                            String merged = CombinedLogMerger.merge(localLogText, remoteLogText);
                            File targetFile =
                                    new File(
                                            folderToSave,
                                            CombinedLogMerger.buildFileName(new Date()));
                            Files.writeString(targetFile.toPath(), merged, StandardCharsets.UTF_8);
                            int localLines = countLines(localLogText);
                            int remoteLines = countLines(remoteLogText);
                            Long offsetMs =
                                    CombinedLogMerger.computeClockOffsetMs(
                                            localLogText, remoteLogText);
                            String offsetNote =
                                    offsetMs != null
                                            ? " (clock offset applied: " + (offsetMs / 1000) + "s)"
                                            : " (no time markers found, merged by raw timestamps)";
                            logController.log(
                                    "Combined log saved to "
                                            + targetFile.getAbsolutePath()
                                            + " (local "
                                            + localLines
                                            + ", remote "
                                            + remoteLines
                                            + " lines)"
                                            + offsetNote);
                        } catch (Exception e) {
                            logController.log("Failed to save combined log: " + e.getMessage());
                        }
                    }
                };
        worker.execute();
    }

    private static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return text.split("\n").length;
    }
}
