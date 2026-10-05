package com.filesync.cli;

import com.filesync.AppVersion;
import com.filesync.config.SettingsManager;
import com.filesync.serial.SerialPortManager;
import com.filesync.sync.ConflictInfo;
import com.filesync.sync.FileChangeDetector.FileInfo;
import com.filesync.sync.FileChangeDetector.FileRename;
import com.filesync.sync.FileSyncManager;
import com.filesync.sync.SyncEvent;
import com.filesync.sync.SyncPreviewPlan;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Runs one headless sync session: wires the core {@link FileSyncManager} the same way the Swing UI
 * does (minus the widgets), mirrors event-bus traffic to the console, and reduces the outcome to an
 * exit code.
 *
 * <p>Roles are forced rather than negotiated: {@code send}/{@code preview} take the sender role,
 * {@code receive} the receiver role, and the peer is told to adopt the complement — the same
 * exchange the UI's direction button performs. Unresolved conflicts keep the core's default "sender
 * wins" behavior.
 */
final class CliSession {

    /** Upper bound for the link to settle roles after connecting. */
    private static final long ROLE_SETTLE_TIMEOUT_MS = 10_000;

    private static final long POLL_INTERVAL_MS = 100;

    /**
     * How long a finished receive session waits for the sender to hang up before closing its own
     * end. See {@link #lingerForSenderGoodbye()}.
     */
    private static final long SENDER_GOODBYE_GRACE_MS = 2000;

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final CliSpec spec;
    private final SettingsManager settings;
    private final SerialPortManager portOverride;
    private final SessionOutcome outcome = new SessionOutcome();
    private final AtomicBoolean tornDown = new AtomicBoolean();

    private SerialPortManager port;
    private FileSyncManager manager;

    /** Counted down on the first connection-loss event; a finished receiver waits on it. */
    private final CountDownLatch peerDisconnected = new CountDownLatch(1);

    CliSession(CliSpec spec, SettingsManager settings, SerialPortManager portOverride) {
        this.spec = spec;
        this.settings = settings;
        this.portOverride = portOverride;
    }

    int run() {
        try {
            port =
                    portOverride != null
                            ? portOverride
                            : new SerialPortManager(
                                    spec.baudRate, spec.dataBits, spec.stopBits, spec.parity);
            manager = new FileSyncManager(port, settings);
            manager.getEventBus().register(this::onEvent);
            manager.setSyncFolder(new File(spec.folder));
            manager.setStrictSyncMode(spec.strict);
            manager.setRespectGitignoreMode(spec.respectGitignore);
            manager.setFastMode(spec.fastMode);
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(this::teardown, "com-file-sync-cli-shutdown"));

            println(
                    "com-file-sync "
                            + AppVersion.get()
                            + " mode="
                            + spec.mode.name().toLowerCase(Locale.ROOT)
                            + " port="
                            + spec.port
                            + " folder="
                            + spec.folder
                            + " baud="
                            + spec.baudRate
                            + " strict="
                            + spec.strict
                            + " gitignore="
                            + spec.respectGitignore
                            + " fast="
                            + spec.fastMode);

            if (!port.open(spec.port)) {
                printErr("Failed to open serial port: " + spec.port);
                return CliMain.EXIT_FAILURE;
            }

            manager.startListening(spec.port);
            // Force-settling the role must happen after startListening: it resets the negotiated
            // flag, and setIsSender marks it settled again. The direction-change frame makes an
            // already-settled peer adopt the complementary role.
            manager.setIsSender(spec.mode != CliSpec.Mode.RECEIVE);
            manager.notifyDirectionChange();

            long waitMs = spec.waitSeconds == 0 ? Long.MAX_VALUE : spec.waitSeconds * 1000L;
            if (!manager.waitForConnection(waitMs)) {
                printErr("Timed out waiting for the peer on " + spec.port);
                return CliMain.EXIT_FAILURE;
            }
            println("Connected; role: " + (manager.isSender() ? "Sender" : "Receiver"));

            if (!awaitTrue(manager::isRoleNegotiated, ROLE_SETTLE_TIMEOUT_MS)) {
                printErr("Role negotiation did not complete");
                return CliMain.EXIT_FAILURE;
            }

            return switch (spec.mode) {
                case PREVIEW -> runPreview();
                case SEND -> runSend();
                case RECEIVE -> runReceive();
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            printErr("Interrupted while waiting for the session to finish");
            return CliMain.EXIT_FAILURE;
        } catch (RuntimeException e) {
            printErr("Unexpected failure: " + e.getMessage());
            return CliMain.EXIT_FAILURE;
        } finally {
            teardown();
        }
    }

    private int runPreview() {
        SyncPreviewPlan plan = buildPlan();
        if (plan == null) {
            return CliMain.EXIT_FAILURE;
        }
        printPlan(plan);
        return CliMain.EXIT_SUCCESS;
    }

    private int runSend() throws InterruptedException {
        SyncPreviewPlan plan = buildPlan();
        if (plan == null) {
            return CliMain.EXIT_FAILURE;
        }
        warnConflicts(plan);
        outcome.begin();
        manager.initiateSync(plan);
        return awaitOutcome();
    }

    private int runReceive() throws InterruptedException {
        println("Waiting for incoming sync session...");
        outcome.begin();
        int code = awaitOutcome();
        if (code == CliMain.EXIT_SUCCESS || code == CliMain.EXIT_PARTIAL) {
            lingerForSenderGoodbye();
        }
        return code;
    }

    /**
     * Wait for the sender to hang up before this receiver tears down its own end. The sender's
     * session ends a few local steps after the receiver's SYNC_COMPLETE — it records the confirmed
     * base and posts its own completion — and a CLI sender disconnects right after that. Hanging up
     * the instant this side completes races that wrap-up: the goodbye frame can reach the sender
     * while it is still finishing, and its one-shot run then reports the lost link as exit 1 for a
     * sync that succeeded. Waiting for the disconnect notification makes the ordering
     * deterministic; a GUI sender that stays connected only delays this exit by the grace bound.
     */
    private void lingerForSenderGoodbye() {
        try {
            peerDisconnected.await(SENDER_GOODBYE_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // The session already succeeded; an interrupt during the post-success linger must not
            // turn the exit code into a failure.
            Thread.currentThread().interrupt();
        }
    }

    /** Builds the preview plan; on failure the reason is printed and null is returned. */
    private SyncPreviewPlan buildPlan() {
        try {
            return manager.previewSync();
        } catch (RuntimeException e) {
            printErr(e.getMessage() != null ? e.getMessage() : e.toString());
            return null;
        }
    }

    private int awaitOutcome() throws InterruptedException {
        long timeoutMs = spec.timeoutSeconds == 0 ? 0 : spec.timeoutSeconds * 1000L;
        int code = outcome.awaitExitCode(timeoutMs);
        if (code < 0) {
            printErr("Timed out waiting for the sync session to finish");
            return CliMain.EXIT_FAILURE;
        }
        if (code == CliMain.EXIT_FAILURE && outcome.failureMessage() != null) {
            printErr("Session failed: " + outcome.failureMessage());
        }
        if (code == CliMain.EXIT_PARTIAL) {
            printErr(
                    "Session completed, but some receiver-side files were locked and not written;"
                            + " they will re-transfer on the next sync");
        }
        return code;
    }

    private void onEvent(SyncEvent event) {
        switch (event.getType()) {
            case LOG -> println(((SyncEvent.LogEvent) event).getMessage());
            case ERROR -> printErr(((SyncEvent.ErrorEvent) event).getMessage());
            case SYNC_COMPLETE -> outcome.complete();
            case SYNC_CANCELLED -> outcome.cancelled();
            case CONNECTION_STATUS -> {
                if (!((SyncEvent.ConnectionEvent) event).isConnected()) {
                    outcome.connectionLost();
                    peerDisconnected.countDown();
                }
            }
            case PENDING_FILE_WRITE -> {
                List<String> paths = ((SyncEvent.PendingWriteEvent) event).getPendingPaths();
                printErr("Files locked by another program (pending): " + String.join(", ", paths));
                outcome.notePendingWrites();
            }
            case FILE_PROGRESS -> {
                SyncEvent.FileProgressEvent progress = (SyncEvent.FileProgressEvent) event;
                println(
                        "File "
                                + progress.getCurrentFile()
                                + "/"
                                + progress.getTotalFiles()
                                + ": "
                                + progress.getFileName());
            }
            case MANIFEST_PROGRESS -> {
                SyncEvent.ManifestProgressEvent progress = (SyncEvent.ManifestProgressEvent) event;
                if (progress.getProcessed() < 0) {
                    println("Waiting for remote manifest...");
                } else {
                    println(
                            "Manifest "
                                    + progress.getProcessed()
                                    + "/"
                                    + progress.getTotal()
                                    + ": "
                                    + progress.getFileName());
                }
            }
            case TRANSFER_PROGRESS -> {
                if (spec.debug) {
                    SyncEvent.TransferProgressEvent progress =
                            (SyncEvent.TransferProgressEvent) event;
                    println(
                            "Transfer "
                                    + progress.getCurrentBlock()
                                    + "/"
                                    + progress.getTotalBlocks()
                                    + " ("
                                    + progress.getBytesTransferred()
                                    + " bytes, "
                                    + (long) progress.getSpeedBytesPerSec()
                                    + " B/s)");
                }
            }
            default -> {
                // Events with no console value for a one-shot run.
            }
        }
    }

    private void warnConflicts(SyncPreviewPlan plan) {
        for (ConflictInfo conflict : plan.getConflicts()) {
            if (!conflict.isResolved()) {
                printErr(
                        "warning: unresolved conflict for "
                                + conflict.getPath()
                                + "; the sender's version will overwrite the receiver's");
            }
        }
    }

    private void printPlan(SyncPreviewPlan plan) {
        Set<String> deltaPaths = plan.getDeltaCandidatePaths();
        Set<String> appendPaths = plan.getAppendResumablePaths();
        List<FileInfo> transfers = plan.getFilesToTransfer();
        println(
                "--- sync plan: "
                        + transfers.size()
                        + " file(s) to transfer, "
                        + plan.getTotalBytesToTransfer()
                        + " bytes ---");
        for (FileInfo file : transfers) {
            String detail = "";
            if (deltaPaths.contains(file.getPath())) {
                detail = ", delta";
            } else if (appendPaths.contains(file.getPath())) {
                detail = ", append-resume";
            }
            println("  transfer " + file.getPath() + " (" + file.getSize() + " B" + detail + ")");
        }
        for (String path : plan.getFilesToDelete()) {
            println("  delete " + path);
        }
        for (FileRename rename : plan.getRenames()) {
            println("  rename " + rename.getFromPath() + " -> " + rename.getToPath());
        }
        for (String dir : plan.getEmptyDirectoriesToCreate()) {
            println("  mkdir " + dir);
        }
        for (String dir : plan.getEmptyDirectoriesToDelete()) {
            println("  rmdir " + dir);
        }
        for (ConflictInfo conflict : plan.getConflicts()) {
            println(
                    "  conflict "
                            + conflict.getPath()
                            + " ("
                            + conflict.getResolution()
                            + "; unresolved defaults to sender wins)");
        }
        println("--- total operations: " + plan.getTotalOperations() + " ---");
    }

    private void teardown() {
        if (!tornDown.compareAndSet(false, true)) {
            return;
        }
        if (manager != null) {
            try {
                manager.disconnect(true);
            } catch (RuntimeException e) {
                // Best-effort teardown; the process is exiting either way.
            }
        }
    }

    private static boolean awaitTrue(BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return condition.getAsBoolean();
    }

    private static void println(String message) {
        System.out.println("[" + LocalTime.now().format(TIMESTAMP) + "] " + message);
    }

    private static void printErr(String message) {
        System.err.println("[" + LocalTime.now().format(TIMESTAMP) + "] " + message);
    }
}
