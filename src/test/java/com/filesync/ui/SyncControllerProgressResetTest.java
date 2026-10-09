package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.config.SettingsManager;
import com.filesync.serial.SerialPortManager;
import com.filesync.sync.FileSyncManager;
import java.lang.reflect.Field;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A cancelled sync - this side's or the peer's - is a finished transfer as far as the progress bar
 * is concerned, so it must revert to Ready on its own instead of parking the bar on the last
 * percentage until the next sync starts.
 */
class SyncControllerProgressResetTest {

    /** Serial port stub: the controller under test never touches the link. */
    private static final class SilentSerialPortManager extends SerialPortManager {
        @Override
        public boolean open(String portName) {
            return true;
        }

        @Override
        public void close() {
            // Nothing to tear down.
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }

    /** Manager in the state a finished session leaves behind: idle, link up. */
    private static class IdleSyncManager extends FileSyncManager {
        IdleSyncManager() {
            super(new SilentSerialPortManager(), new SettingsManager(true));
        }

        @Override
        public boolean isSyncing() {
            return false;
        }
    }

    /** Manager mid-session: the bar on screen belongs to a live sync. */
    private static final class SyncingSyncManager extends IdleSyncManager {
        @Override
        public boolean isSyncing() {
            return true;
        }
    }

    private static SyncController controllerFor(
            MainFrameComponents components, FileSyncManager syncManager) {
        return new SyncController(
                null,
                components,
                syncManager,
                new MainFrameState(),
                new SettingsManager(true),
                new LogController(new javax.swing.JTextArea()));
    }

    private static Timer progressResetTimerOf(SyncController controller) throws Exception {
        Field field = SyncController.class.getDeclaredField("progressResetTimer");
        field.setAccessible(true);
        return (Timer) field.get(controller);
    }

    /**
     * Waits for a condition the event dispatch thread sets, so a test never races its assertions.
     */
    private static void await(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        for (int i = 0; i < 200 && !condition.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    @Test
    @Timeout(30)
    void aCancelledSyncArmsTheReadyRevert() throws Exception {
        MainFrameComponents components = new MainFrameComponents();
        SyncController controller = controllerFor(components, new IdleSyncManager());

        SwingUtilities.invokeAndWait(
                () -> {
                    components.getProgressBar().setValue(45);
                    components.getProgressBar().setString("File 4/9: big.bin");
                    controller.onSyncCancelled();
                });

        assertTrue(
                progressResetTimerOf(controller).isRunning(),
                "a cancelled sync must keep the revert armed, or the bar never returns to Ready");
        assertEquals(0, components.getProgressBar().getValue(), "the cancelled bar drops to zero");
        assertEquals("Sync cancelled", components.getProgressBar().getString());
    }

    @Test
    @Timeout(30)
    void theCancelledBarRevertsToReadyWhenTheTimerFires() throws Exception {
        MainFrameComponents components = new MainFrameComponents();
        SyncController controller = controllerFor(components, new IdleSyncManager());
        Timer timer = progressResetTimerOf(controller);

        SwingUtilities.invokeAndWait(
                () -> {
                    components.getProgressBar().setValue(45);
                    controller.onSyncCancelled();
                    // The production delay is 15 s; shorten it so the test observes the firing
                    // rather than sleeping through it.
                    timer.setInitialDelay(50);
                    timer.restart();
                });

        await(
                "the bar must return to Ready",
                () -> "Ready".equals(components.getProgressBar().getString()));
        assertEquals(0, components.getProgressBar().getValue());
        assertFalse(components.getProgressBar().isIndeterminate());
    }

    @Test
    @Timeout(30)
    void aLateCancelDoesNotRevertABarThatBelongsToALiveSync() throws Exception {
        MainFrameComponents components = new MainFrameComponents();
        SyncController controller = controllerFor(components, new SyncingSyncManager());
        Timer timer = progressResetTimerOf(controller);

        SwingUtilities.invokeAndWait(
                () -> {
                    components.getProgressBar().setValue(45);
                    controller.onSyncCancelled();
                    timer.setInitialDelay(50);
                    timer.restart();
                });

        Thread.sleep(300);

        assertEquals(
                "Sync cancelled",
                components.getProgressBar().getString(),
                "a cancel that arrives while another sync is already running must not flash Ready"
                        + " over that sync's live progress");
    }
}
