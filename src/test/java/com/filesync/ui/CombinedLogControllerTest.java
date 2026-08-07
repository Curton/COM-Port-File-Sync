package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.filesync.config.SettingsManager;
import com.filesync.serial.SerialPortManager;
import com.filesync.sync.FileSyncManager;
import java.awt.FontMetrics;
import javax.swing.JMenuItem;
import javax.swing.border.LineBorder;
import javax.swing.plaf.basic.BasicMenuItemUI;
import org.junit.jupiter.api.Test;

class CombinedLogControllerTest {

    @Test
    void saveCombinedLogItem_rendersTextCentered() {
        SettingsManager settings = new SettingsManager(true);
        FileSyncManager syncManager = new FileSyncManager(mock(SerialPortManager.class), settings);
        MainFrameComponents components = new MainFrameComponents();
        LogController logController = new LogController(components.getLogTextArea());
        FolderController folderController =
                new FolderController(
                        components,
                        settings,
                        syncManager,
                        new MainFrameState(),
                        logController,
                        () -> {});
        CombinedLogController controller =
                new CombinedLogController(
                        components, syncManager, folderController, settings, logController);

        JMenuItem item = controller.getSaveCombinedLogItem();

        assertEquals("Save combined log", item.getText());
        assertTrue(
                item.getUI() instanceof BasicMenuItemUI,
                "The menu entry must use the paintText-overriding UI that centers the text");
    }

    @Test
    void popupMenu_sizedToTextAndThinBordered() {
        SettingsManager settings = new SettingsManager(true);
        FileSyncManager syncManager = new FileSyncManager(mock(SerialPortManager.class), settings);
        MainFrameComponents components = new MainFrameComponents();
        LogController logController = new LogController(components.getLogTextArea());
        FolderController folderController =
                new FolderController(
                        components,
                        settings,
                        syncManager,
                        new MainFrameState(),
                        logController,
                        () -> {});
        CombinedLogController controller =
                new CombinedLogController(
                        components, syncManager, folderController, settings, logController);

        JMenuItem item = controller.getSaveCombinedLogItem();
        FontMetrics fm = item.getFontMetrics(item.getFont());
        // The entry must hug its text instead of reserving the Windows icon column.
        assertEquals(
                fm.stringWidth(item.getText()) + 12,
                item.getPreferredSize().width,
                "The entry width must be the text width plus a small padding");
        assertEquals(
                fm.getHeight() + 8,
                item.getPreferredSize().height,
                "The entry height must be the text height plus a small padding");
        assertTrue(
                controller.getPopupMenu().getBorder() instanceof LineBorder,
                "The thick system popup border must be replaced by a thin line border");
    }
}
