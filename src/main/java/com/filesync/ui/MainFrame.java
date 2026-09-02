package com.filesync.ui;

import com.filesync.AppVersion;
import com.filesync.config.SettingsManager;
import com.filesync.serial.SerialPortManager;
import com.filesync.sync.FileSyncManager;
import com.filesync.sync.SyncEventListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.WindowConstants;

/**
 * Main application window for COM Port File Sync. Provides UI orchestration and delegates UI
 * operations to specialized controllers.
 */
public class MainFrame extends JFrame {
    private final SettingsManager settings;
    private final SerialPortManager serialPort;
    private final FileSyncManager syncManager;
    private final com.filesync.sync.SyncEventBus eventBus;
    private final SyncEventListener eventBusListener;
    private final MainFrameState state;
    private final MainFrameComponents components;
    private final LogController logController;
    private final SettingsDialog settingsDialog;
    private final ConnectionController connectionController;
    private final FolderController folderController;
    private final SyncController syncController;
    private final SharedTextController sharedTextController;
    private final CombinedLogController combinedLogController;
    private final DragDropController dragDropController;
    private final JPanel mainPanel;

    public MainFrame() {
        settings = new SettingsManager();

        serialPort =
                new SerialPortManager(
                        settings.getBaudRate(),
                        settings.getDataBits(),
                        settings.getStopBits(),
                        settings.getParity());
        syncManager = new FileSyncManager(serialPort, settings);
        eventBus = syncManager.getEventBus();

        state = new MainFrameState();
        components = new MainFrameComponents();
        logController = new LogController(components.getLogTextArea());
        logController.setSettingsManager(settings);
        settingsDialog = new SettingsDialog();

        syncController =
                new SyncController(this, components, syncManager, state, settings, logController);
        SyncPreviewRenderer syncPreviewRenderer = new SyncPreviewRenderer(this, syncController);
        syncController.setPreviewRenderer(syncPreviewRenderer);
        connectionController =
                new ConnectionController(
                        this,
                        components,
                        settings,
                        serialPort,
                        syncManager,
                        state,
                        logController,
                        syncController::updateSyncButtonState,
                        settingsDialog);
        folderController =
                new FolderController(
                        components,
                        settings,
                        syncManager,
                        state,
                        logController,
                        syncController::updateSyncButtonState);
        sharedTextController =
                new SharedTextController(components, state, syncManager, logController);
        combinedLogController =
                new CombinedLogController(
                        components, syncManager, folderController, settings, logController);
        // The remote peer requests this device's log via the serial listener thread; the
        // controller's buffer snapshot is safe to read from any thread (unlike the JTextArea).
        syncManager.setLogTextProvider(logController::getLogText);
        // TIME-SYNC marker requests (sent before a combined-log save) must write the marker into
        // the log mirror synchronously: the listener thread cannot wait for the EDT, and the marker
        // must be present before the requester fetches this device's log.
        syncManager.setLogMarkerSink(logController::logMarker);

        eventBusListener =
                new SyncEventBridge(syncController, logController, sharedTextController)
                        ::handleSyncEvent;
        mainPanel = components.createMainPanel();
        dragDropController =
                new DragDropController(mainPanel, components, syncManager, state, logController);

        String appVersion = AppVersion.get();
        setTitle("COM Port File Sync v" + appVersion);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setContentPane(mainPanel);
        components.configureFrame(this);

        // First log line: the combined-log merger pairs both peers' logs by timestamp, so the
        // build each side runs is visible side by side in the merged file.
        logController.log("COM Port File Sync v" + appVersion);

        initializeWorkflow();
    }

    private void initializeWorkflow() {
        eventBus.register(eventBusListener);

        syncController.initActionHandlers();
        connectionController.initEventHandlers(this::updateSettingsLabel);
        // Returning to the initial disconnected state rescans ports so a just-unplugged
        // adapter disappears from the combo box.
        syncController.setOnDisconnectedCallback(connectionController::refreshPorts);
        folderController.initEventHandlers();
        sharedTextController.initEventHandlers();
        combinedLogController.initEventHandlers();

        connectionController.refreshPorts();
        loadSavedState();
        connectionController.attemptAutoConnectOnStartup();
        dragDropController.setupDragAndDrop();

        addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        cleanup();
                    }
                });
    }

    private void loadSavedState() {
        String lastPort = settings.getLastPort();
        if (lastPort != null && !lastPort.isEmpty()) {
            for (int i = 0; i < components.getPortComboBox().getItemCount(); i++) {
                if (lastPort.equals(components.getPortComboBox().getItemAt(i))) {
                    components.getPortComboBox().setSelectedIndex(i);
                    break;
                }
            }
        }

        folderController.loadFolderHistory();
        if (components.getFolderComboBox().getItemCount() > 0) {
            folderController.applyFolderSelection(
                    (String) components.getFolderComboBox().getItemAt(0), false);
        } else {
            folderController.applyFolderSelection(settings.getLastFolder(), false);
        }

        boolean strictSync = settings.isStrictSync();
        components.getStrictSyncCheckBox().setSelected(strictSync);
        syncManager.setStrictSyncMode(strictSync);

        boolean respectGitignore = settings.isRespectGitignore();
        components.getRespectGitignoreCheckBox().setSelected(respectGitignore);
        syncManager.setRespectGitignoreMode(respectGitignore);

        boolean fastMode = settings.isFastMode();
        components.getFastModeCheckBox().setSelected(fastMode);
        syncManager.setFastMode(fastMode);

        syncController.updateRespectGitignoreState();
        syncController.applyDirection(state.isSender());
        updateSettingsLabel();
        syncController.updateSyncButtonState();
        components.getProgressBar().setString("Ready");
    }

    private void updateSettingsLabel() {
        components.setSettingsLabel(SettingsDialog.getSettingsString(settings));
    }

    private void cleanup() {
        if (eventBus != null && eventBusListener != null) {
            eventBus.unregister(eventBusListener);
        }
        syncManager.disconnect(true);
    }
}
