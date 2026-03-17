package com.filesync.ui;

import java.io.InputStream;
import java.util.Properties;

import javax.swing.JPanel;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import com.filesync.config.SettingsManager;
import com.filesync.serial.SerialPortManager;
import com.filesync.sync.FileSyncManager;
import com.filesync.sync.SyncEventListener;

/**
 * Main application window for COM Port File Sync.
 * Provides UI orchestration and delegates UI operations to specialized controllers.
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
    private final DragDropController dragDropController;
    private final JPanel mainPanel;

    public MainFrame() {
        settings = new SettingsManager();

        serialPort = new SerialPortManager(
                settings.getBaudRate(),
                settings.getDataBits(),
                settings.getStopBits(),
                settings.getParity());
        syncManager = new FileSyncManager(serialPort);
        eventBus = syncManager.getEventBus();

        state = new MainFrameState();
        components = new MainFrameComponents();
        logController = new LogController(components.getLogTextArea());
        settingsDialog = new SettingsDialog();

        SyncPreviewRenderer syncPreviewRenderer = new SyncPreviewRenderer(this);
        syncController = new SyncController(
                this,
                components,
                syncManager,
                state,
                settings,
                logController,
                syncPreviewRenderer);
        connectionController = new ConnectionController(
                this,
                components,
                settings,
                serialPort,
                syncManager,
                state,
                logController,
                syncController::updateSyncButtonState,
                settingsDialog);
        folderController = new FolderController(
                components,
                settings,
                syncManager,
                state,
                logController,
                syncController::updateSyncButtonState);
        sharedTextController = new SharedTextController(
                components,
                state,
                syncManager,
                logController);

        eventBusListener = new SyncEventBridge(
                syncController,
                logController,
                sharedTextController
        )::handleSyncEvent;
        mainPanel = components.createMainPanel();
        dragDropController = new DragDropController(
                mainPanel,
                components,
                syncManager,
                state,
                logController);

        setTitle("COM Port File Sync v" + getApplicationVersion());
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setContentPane(mainPanel);
        components.configureFrame(this);

        initializeWorkflow();
    }

    private void initializeWorkflow() {
        eventBus.register(eventBusListener);

        syncController.initActionHandlers();
        connectionController.initEventHandlers(this::updateSettingsLabel);
        folderController.initEventHandlers();
        sharedTextController.initEventHandlers();

        connectionController.refreshPorts();
        loadSavedState();
        connectionController.attemptAutoConnectOnStartup();
        dragDropController.setupDragAndDrop();

        addWindowListener(new WindowAdapter() {
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
            folderController.applyFolderSelection((String) components.getFolderComboBox().getItemAt(0), false);
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

    private String getApplicationVersion() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String version = props.getProperty("application.version");
                if (version != null && !version.trim().isEmpty()) {
                    return version.trim();
                }
            }
        } catch (Exception e) {
            // Ignore and use fallback
        }
        return "1.0.0";
    }

    private void cleanup() {
        sharedTextController.shutdown();
        if (eventBus != null && eventBusListener != null) {
            eventBus.unregister(eventBusListener);
        }
        syncManager.stopListening();
        serialPort.close();
    }
}
