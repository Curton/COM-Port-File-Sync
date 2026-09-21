package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.config.SettingsManager;
import com.filesync.serial.SerialPortManager;
import com.filesync.sync.FileSyncManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Serial-port work must never run on the event dispatch thread. Opening a port sleeps between its
 * attempts, tearing the connection down waits on the executor, and sending shared text can run a
 * whole XMODEM transfer — each of them freezes the UI for as long as it takes.
 */
class UiEdtBlockingTest {

    @Test
    @Timeout(30)
    void onFileProgressWithUnknownTotalDoesNotPinTheBarAtFull() throws Exception {
        MainFrameComponents components = new MainFrameComponents();
        SyncController controller =
                new SyncController(
                        null,
                        components,
                        null,
                        new MainFrameState(),
                        new SettingsManager(true),
                        new LogController(new JTextArea()));

        SwingUtilities.invokeAndWait(() -> controller.onFileProgress(3, 0, "a.txt"));

        JProgressBar bar = components.getProgressBar();
        assertTrue(
                bar.isIndeterminate(),
                "an unknown total must show motion, not a percentage of zero");
        assertTrue(bar.getValue() < 100, "the bar must not be slammed to 100%: " + bar.getValue());
    }

    @Test
    @Timeout(30)
    void openingTheSerialPortDoesNotBlockTheEventDispatchThread() throws Exception {
        BlockingSerialPortManager serialPort = new BlockingSerialPortManager();
        BlockingFileSyncManager syncManager = new BlockingFileSyncManager(serialPort);
        MainFrameComponents components = new MainFrameComponents();
        components.getPortComboBox().addItem("COM1");
        components.getPortComboBox().setSelectedIndex(0);
        ConnectionController controller =
                createConnectionController(components, serialPort, syncManager);
        controller.initEventHandlers(() -> {});

        CountDownLatch clickDone = new CountDownLatch(1);
        SwingUtilities.invokeLater(
                () -> {
                    components.getConnectButton().doClick();
                    clickDone.countDown();
                });

        assertTrue(
                serialPort.openEntered.await(5, TimeUnit.SECONDS), "open() must have been called");
        try {
            assertTrue(
                    clickDone.await(2, TimeUnit.SECONDS),
                    "the click must return without waiting for open() to finish");
        } finally {
            // Always let the parked thread go, even when the assertion above fails.
            serialPort.release.countDown();
        }
    }

    @Test
    @Timeout(30)
    void disconnectingDoesNotBlockTheEventDispatchThread() throws Exception {
        BlockingSerialPortManager serialPort = new BlockingSerialPortManager();
        BlockingFileSyncManager syncManager = new BlockingFileSyncManager(serialPort);
        MainFrameComponents components = new MainFrameComponents();
        components.getPortComboBox().addItem("COM1");
        MainFrameState state = new MainFrameState();
        state.setConnected(true);
        ConnectionController controller =
                createConnectionController(components, serialPort, syncManager, state);
        controller.initEventHandlers(() -> {});

        CountDownLatch clickDone = new CountDownLatch(1);
        SwingUtilities.invokeLater(
                () -> {
                    components.getConnectButton().doClick();
                    clickDone.countDown();
                });

        assertTrue(
                syncManager.disconnectEntered.await(5, TimeUnit.SECONDS),
                "disconnect() must have been called");
        try {
            assertTrue(
                    clickDone.await(2, TimeUnit.SECONDS),
                    "the click must not wait for the teardown to finish");
        } finally {
            syncManager.release.countDown();
        }
    }

    @Test
    @Timeout(30)
    void sendingSharedTextDoesNotBlockTheEventDispatchThread() throws Exception {
        BlockingSerialPortManager serialPort = new BlockingSerialPortManager();
        BlockingFileSyncManager syncManager = new BlockingFileSyncManager(serialPort);
        MainFrameComponents components = new MainFrameComponents();
        MainFrameState state = new MainFrameState();
        state.setConnected(true);
        SharedTextController controller =
                new SharedTextController(
                        components, state, syncManager, new LogController(new JTextArea()));

        CountDownLatch callDone = new CountDownLatch(1);
        SwingUtilities.invokeLater(
                () -> {
                    controller.pushSharedTextToRemote();
                    callDone.countDown();
                });

        assertTrue(
                syncManager.sendEntered.await(5, TimeUnit.SECONDS),
                "sendSharedText() must have been called");
        try {
            assertTrue(
                    callDone.await(2, TimeUnit.SECONDS),
                    "the call must not wait for the serial write to finish");
        } finally {
            syncManager.release.countDown();
        }
    }

    private ConnectionController createConnectionController(
            MainFrameComponents components,
            BlockingSerialPortManager serialPort,
            BlockingFileSyncManager syncManager) {
        return createConnectionController(
                components, serialPort, syncManager, new MainFrameState());
    }

    private ConnectionController createConnectionController(
            MainFrameComponents components,
            BlockingSerialPortManager serialPort,
            BlockingFileSyncManager syncManager,
            MainFrameState state) {
        return new ConnectionController(
                null,
                components,
                new SettingsManager(true),
                serialPort,
                syncManager,
                state,
                new LogController(new JTextArea()),
                () -> {},
                null);
    }

    /** Serial port whose {@code open} blocks until the test releases it. */
    private static final class BlockingSerialPortManager extends SerialPortManager {
        final CountDownLatch openEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean open(String portName) {
            openEntered.countDown();
            awaitRelease();
            return true;
        }

        @Override
        public void close() {
            // Intentionally ignored in test.
        }

        private void awaitRelease() {
            try {
                release.await(25, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Sync manager whose blocking serial operations hold until the test releases them. */
    private static final class BlockingFileSyncManager extends FileSyncManager {
        final CountDownLatch disconnectEntered = new CountDownLatch(1);
        final CountDownLatch sendEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        BlockingFileSyncManager(SerialPortManager serialPort) {
            super(serialPort, new SettingsManager(true));
        }

        @Override
        public void disconnect(boolean notifyRemote) {
            disconnectEntered.countDown();
            awaitRelease();
        }

        @Override
        public void sendSharedText(String text) {
            sendEntered.countDown();
            awaitRelease();
        }

        @Override
        public void startListening(String portName) {
            // Intentionally ignored in test.
        }

        @Override
        public void stopListening() {
            // Intentionally ignored in test.
        }

        @Override
        public boolean waitForConnection(long timeoutMs) {
            return true;
        }

        @Override
        public boolean isConnectionAlive() {
            return true;
        }

        private void awaitRelease() {
            try {
                release.await(25, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
