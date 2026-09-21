package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.config.SettingsManager;
import com.filesync.serial.SerialPortManager;
import com.filesync.sync.FileSyncManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Opening a port and tearing a connection down both run on background threads, and while they run
 * the connect button used to stay enabled and keep its "Cancel"/"Disconnect" label. A second click
 * in that window therefore started a second attempt: two concurrent {@code open()} calls share one
 * SerialPortManager whose fields the loser nulls out, two teardowns call the non-reentrant {@code
 * stopListening()}, and a connection timeout can tear down while a disconnect is already doing it.
 * Every attempt must be single-flight, and the button must show that one is in progress instead of
 * inviting another click.
 */
class ConnectionSingleFlightTest {

    @Test
    @Timeout(30)
    void aSecondClickWhileConnectingDoesNotOpenAnotherPort() throws Exception {
        CountingSerialPortManager serialPort = new CountingSerialPortManager();
        serialPort.blockOpens = true;
        CountingFileSyncManager syncManager = new CountingFileSyncManager(serialPort);
        MainFrameComponents components = new MainFrameComponents();
        components.getPortComboBox().addItem("COM1");
        components.getPortComboBox().setSelectedIndex(0);
        ConnectionController controller =
                createConnectionController(components, serialPort, syncManager);
        controller.initEventHandlers(() -> {});

        SwingUtilities.invokeAndWait(() -> components.getConnectButton().doClick());
        assertTrue(
                serialPort.firstOpenEntered.await(5, TimeUnit.SECONDS),
                "the click must start opening the port");

        SwingUtilities.invokeAndWait(() -> components.getConnectButton().doClick());
        try {
            assertFalse(
                    serialPort.secondOpenEntered.await(1, TimeUnit.SECONDS),
                    "a second click while connecting must not open the same port manager again");
            assertEquals(
                    "Connecting...",
                    components.getConnectButton().getText(),
                    "the button must say the attempt is in progress, not \"Cancel\"");
            assertFalse(
                    components.getConnectButton().isEnabled(),
                    "the button must not invite a second click while connecting");
        } finally {
            serialPort.releaseOpens.countDown();
        }
    }

    @Test
    @Timeout(30)
    void aSecondClickWhileDisconnectingDoesNotTearDownTwice() throws Exception {
        CountingSerialPortManager serialPort = new CountingSerialPortManager();
        serialPort.blockOpens = false;
        CountingFileSyncManager syncManager = new CountingFileSyncManager(serialPort);
        MainFrameComponents components = new MainFrameComponents();
        components.getPortComboBox().addItem("COM1");
        MainFrameState state = new MainFrameState();
        state.setConnected(true);
        ConnectionController controller =
                createConnectionController(components, serialPort, syncManager, state);
        controller.initEventHandlers(() -> {});

        SwingUtilities.invokeAndWait(() -> components.getConnectButton().doClick());
        assertTrue(
                syncManager.firstDisconnectEntered.await(5, TimeUnit.SECONDS),
                "the click must start tearing the connection down");

        SwingUtilities.invokeAndWait(() -> components.getConnectButton().doClick());
        try {
            assertFalse(
                    syncManager.secondDisconnectEntered.await(1, TimeUnit.SECONDS),
                    "a second click while disconnecting must not run the teardown twice");
            assertEquals(
                    "Disconnecting...",
                    components.getConnectButton().getText(),
                    "the button must say the teardown is in progress, not \"Disconnect\"");
            assertFalse(
                    components.getConnectButton().isEnabled(),
                    "the button must show the teardown is in progress");
        } finally {
            syncManager.releaseDisconnect.countDown();
        }
    }

    @Test
    @Timeout(30)
    void aConnectionTimeoutDoesNotTearDownWhileADisconnectIsInFlight() throws Exception {
        CountingSerialPortManager serialPort = new CountingSerialPortManager();
        serialPort.blockOpens = false;
        CountingFileSyncManager syncManager = new CountingFileSyncManager(serialPort);
        syncManager.blockWaiter = true;
        syncManager.waiterResult = false;
        MainFrameComponents components = new MainFrameComponents();
        components.getPortComboBox().addItem("COM1");
        components.getPortComboBox().setSelectedIndex(0);
        ConnectionController controller =
                createConnectionController(components, serialPort, syncManager);
        controller.initEventHandlers(() -> {});

        // The port opens and the waiter parks until the peer answers.
        SwingUtilities.invokeAndWait(() -> components.getConnectButton().doClick());
        assertTrue(
                syncManager.waiterEntered.await(5, TimeUnit.SECONDS),
                "the port must open and the connection waiter must start");

        // The user gives up waiting and disconnects while the waiter is still parked.
        SwingUtilities.invokeAndWait(() -> components.getConnectButton().doClick());
        assertTrue(
                syncManager.firstDisconnectEntered.await(5, TimeUnit.SECONDS),
                "the click must start disconnecting");

        // The waiter now times out: it must not tear the link down a second time.
        syncManager.releaseWaiter.countDown();
        try {
            assertFalse(
                    syncManager.stopListeningEntered.await(1, TimeUnit.SECONDS),
                    "a connection timeout must not stop the listener while a disconnect is tearing down");
            assertFalse(
                    serialPort.closeEntered.await(1, TimeUnit.SECONDS),
                    "a connection timeout must not close the port while a disconnect is tearing down");
        } finally {
            syncManager.releaseDisconnect.countDown();
        }
    }

    private ConnectionController createConnectionController(
            MainFrameComponents components,
            CountingSerialPortManager serialPort,
            CountingFileSyncManager syncManager) {
        return createConnectionController(
                components, serialPort, syncManager, new MainFrameState());
    }

    private ConnectionController createConnectionController(
            MainFrameComponents components,
            CountingSerialPortManager serialPort,
            CountingFileSyncManager syncManager,
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

    private static void awaitRelease(CountDownLatch release) {
        try {
            release.await(25, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Serial port manager that counts and can park the open the controller triggers. */
    private static final class CountingSerialPortManager extends SerialPortManager {
        final AtomicInteger openCalls = new AtomicInteger();
        final CountDownLatch firstOpenEntered = new CountDownLatch(1);
        final CountDownLatch secondOpenEntered = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseOpens = new CountDownLatch(1);
        volatile boolean blockOpens = true;

        @Override
        public boolean open(String portName) {
            if (openCalls.incrementAndGet() == 1) {
                firstOpenEntered.countDown();
            } else {
                secondOpenEntered.countDown();
            }
            if (blockOpens) {
                awaitRelease(releaseOpens);
            }
            return true;
        }

        @Override
        public void close() {
            closeEntered.countDown();
        }
    }

    /** Sync manager that counts and can park the teardown and the connection waiter. */
    private static final class CountingFileSyncManager extends FileSyncManager {
        final AtomicInteger disconnectCalls = new AtomicInteger();
        final CountDownLatch firstDisconnectEntered = new CountDownLatch(1);
        final CountDownLatch secondDisconnectEntered = new CountDownLatch(1);
        final CountDownLatch stopListeningEntered = new CountDownLatch(1);
        final CountDownLatch waiterEntered = new CountDownLatch(1);
        final CountDownLatch releaseDisconnect = new CountDownLatch(1);
        final CountDownLatch releaseWaiter = new CountDownLatch(1);
        volatile boolean blockDisconnect = true;
        volatile boolean blockWaiter = false;
        volatile boolean waiterResult = false;

        CountingFileSyncManager(SerialPortManager serialPort) {
            super(serialPort, new SettingsManager(true));
        }

        @Override
        public void disconnect(boolean notifyRemote) {
            if (disconnectCalls.incrementAndGet() == 1) {
                firstDisconnectEntered.countDown();
            } else {
                secondDisconnectEntered.countDown();
            }
            if (blockDisconnect) {
                awaitRelease(releaseDisconnect);
            }
        }

        @Override
        public void stopListening() {
            stopListeningEntered.countDown();
        }

        @Override
        public void startListening(String portName) {
            // Intentionally ignored in test.
        }

        @Override
        public boolean waitForConnection(long timeoutMs) {
            waiterEntered.countDown();
            if (blockWaiter) {
                awaitRelease(releaseWaiter);
            }
            return waiterResult;
        }

        @Override
        public boolean isConnectionAlive() {
            return true;
        }

        @Override
        public boolean wasManuallyDisconnected() {
            // Suppresses the timeout dialog, which cannot be shown in a headless test.
            return true;
        }
    }
}
