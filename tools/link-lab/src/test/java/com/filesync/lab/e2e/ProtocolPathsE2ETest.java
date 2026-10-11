package com.filesync.lab.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.lab.Trace;
import com.filesync.lab.link.WireModel;
import com.filesync.lab.peer.RemotePeer;
import com.filesync.lab.port.DuplexLink;
import com.filesync.sync.SyncPreviewPlan;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Protocol paths the other two-ended suites do not reach, each against two real managers over the
 * emulated wire: the rename rejection a vanished receiver source forces (and the sender's
 * transfer-plus-delete fallback), shared text sent while no transfer is running (the idle inline
 * frame, and the oversized variant announced as {@code SHARED_TEXT_DATA} and carried by its own
 * XMODEM session), the file-content and combined-log fetches that cross the XMODEM threshold, and a
 * user cancel mid-transfer that must abort the transfer without killing the session.
 */
class ProtocolPathsE2ETest {

    private static final int BAUD = 460_800;
    private static final int AWAIT_MS = 60_000;

    private Trace trace;
    private File root;
    private RemotePeer app;
    private RemotePeer peer;
    private WireModel model;
    private DuplexLink link;

    @BeforeEach
    void setUp() throws Exception {
        trace = new Trace(true, (File) null);
        root = Files.createTempDirectory("link-lab-protocol-paths").toFile();
        model = new WireModel().baud(BAUD).latencyMillis(1).jitterMillis(1);
        link = new DuplexLink(trace, model);
        app = RemotePeer.attach(trace, model, new File(root, "app"), link.sideA(), false);
        peer = RemotePeer.attach(trace, model, new File(root, "peer"), link.sideB(), false);
        await(
                "connection alive and role negotiated on both sides",
                () ->
                        app.isConnected()
                                && peer.isConnected()
                                && app.isRoleNegotiated()
                                && peer.isRoleNegotiated(),
                AWAIT_MS);
    }

    @AfterEach
    void tearDown() {
        closeQuietly(peer);
        closeQuietly(app);
        closeQuietly(link);
        deleteRecursively(root);
    }

    /**
     * A rename whose source vanished on the receiver between the manifest exchange and the move is
     * rejected with {@code RENAME_REJECTED}; the sender then falls back to the plain
     * transfer-plus-delete the rename had replaced, and the session still converges.
     */
    @Test
    @Timeout(240)
    void aRenameWhoseSourceVanishedIsRejectedAndFallsBackToTransfer() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();
        byte[] content =
                "the file that will be moved against a vanished source\n"
                        .getBytes(StandardCharsets.UTF_8);
        writeFile(sender.workspace(), "doomed.txt", content);
        sender.sync();
        awaitFileContent(receiver.workspace(), "doomed.txt", content);
        awaitSyncIdle();

        // bulk.bin keeps the transfer phase busy for seconds, so the source deletion below lands
        // long before the rename is replayed (renames run after all transfers).
        byte[] bulk = randomBytes(600_000);
        writeFile(sender.workspace(), "bulk.bin", bulk);
        assertTrue(
                new File(sender.workspace(), "doomed.txt")
                        .renameTo(new File(sender.workspace(), "moved.txt")),
                "the sender-side move must succeed for the test to mean anything");
        SyncPreviewPlan plan = sender.sync();
        assertEquals(1, plan.getRenames().size(), "the plan must pair doomed.txt -> moved.txt");
        assertTrue(
                Files.deleteIfExists(new File(receiver.workspace(), "doomed.txt").toPath()),
                "the receiver-side source must be removable for the rejection to be forced");

        awaitFileContent(receiver.workspace(), "moved.txt", content);
        awaitFileContent(receiver.workspace(), "bulk.bin", bulk);
        awaitAbsent(receiver.workspace(), "doomed.txt");
        awaitSyncIdle();

        assertTrue(
                sender.logLines().stream().anyMatch(l -> l.contains("could not rename doomed.txt")),
                "the sender must log the rejection and its fallback, log was: "
                        + sender.logLines());
        assertArrayEquals(content, readFile(receiver.workspace(), "moved.txt"));
    }

    /**
     * Shared text sent while no transfer is running needs no interleave: a short text rides one
     * inline {@code SHARED_TEXT} frame, and a text too large for the inline budget (base64 longer
     * than the 5-second wire budget at this baud) is announced as {@code SHARED_TEXT_DATA} and
     * carried by its own XMODEM session.
     */
    @Test
    @Timeout(240)
    void sharedTextArrivesIdleInlineAndHugeViaXmodem() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();

        String shortText = "idle hello " + Long.toHexString(System.nanoTime());
        sender.sendSharedText(shortText);
        await(
                "the idle inline text reached the other end",
                () -> receiver.receivedTexts().contains(shortText),
                AWAIT_MS);

        // ~690 KB base64-encoded, far past the ~230 KB inline budget at 460800 baud, so the send
        // switches to the XMODEM variant. The unique marker sits at the very start, within the 200
        // characters the harness keeps of every received event text.
        String marker = "huge-" + Long.toHexString(System.nanoTime()) + " ";
        StringBuilder sb = new StringBuilder(marker);
        while (sb.length() < 500_000) {
            sb.append("the quick brown fox jumps over the lazy dog ");
        }
        receiver.sendSharedText(sb.toString());
        await(
                "the oversized text reached the other end",
                () -> sender.receivedTexts().stream().anyMatch(t -> t.startsWith(marker.trim())),
                AWAIT_MS);
        awaitSyncIdle();
    }

    /**
     * The peer-to-peer fetches the UI backs its dialogs with: file content above the 64 kB inline
     * threshold is announced as {@code FILE_CONTENT_XFER} and carried by an XMODEM session, and so
     * is a combined log that has grown past the same threshold ({@code LOG_XFER}).
     */
    @Test
    @Timeout(240)
    void largeContentAndLogFetchesRideXmodem() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();
        byte[] big = randomBytes(100_000);
        writeFile(sender.workspace(), "big.bin", big);
        sender.sync();
        awaitFileContent(receiver.workspace(), "big.bin", big);
        awaitSyncIdle();

        assertArrayEquals(big, sender.fetchContent("big.bin"), "the fetch returns the bytes");

        // The harness's log provider joins this live list; 2500 lines push it far past the 64 kB
        // threshold so the requester's fetch must switch to LOG_XFER.
        for (int i = 0; i < 2500; i++) {
            receiver.logLines()
                    .add("log line " + i + " padding so the combined log crosses the threshold");
        }
        String remoteLog = sender.fetchAppLog();
        assertNotNull(remoteLog, "the large log fetch must succeed");
        assertTrue(
                remoteLog.length() > 64 * 1024,
                "the log must be large enough that only XMODEM can carry it: "
                        + remoteLog.length());
        assertTrue(remoteLog.contains("log line 2499"), "the fetched log must be the receiver's");
        awaitSyncIdle();
    }

    /**
     * The user's cancel during a live transfer: the sender sends the CAN-prefixed {@code CANCEL},
     * the receiver's parked XMODEM receive aborts, and the session itself survives untouched - both
     * ends stay connected and a follow-up sync delivers the file in full.
     */
    @Test
    @Timeout(240)
    void aCancelledTransferAbortsCleanlyAndTheSessionSurvives() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();
        byte[] payload = randomBytes(600_000);
        writeFile(sender.workspace(), "cancel-me.bin", payload);
        sender.sync();
        // No progress event marks the middle of a batch transfer (the batch XMODEM session carries
        // no progress listener, and TRANSFER_PROGRESS also fires while the manifest itself rides
        // XMODEM - a cancel that early wedges the requester's own exchange). The wire byte counter
        // is unambiguous: the manifest is a few hundred bytes, so tens of kilobytes in mean file
        // payload is crossing, with ~10 s of the 13.5 s batch still to go at this baud.
        long baseline = receiver.inboundStats().offeredBytes();
        await(
                "file payload crossing the wire",
                () -> receiver.inboundStats().offeredBytes() >= baseline + 30_000,
                AWAIT_MS);
        sender.cancelSync();

        await(
                "both sides idle after the cancel",
                () -> !app.isSyncing() && !peer.isSyncing(),
                AWAIT_MS);
        await(
                "both ports transfer-idle after the cancel",
                () -> !app.manager().isTransferBusy() && !peer.manager().isTransferBusy(),
                15_000);
        assertTrue(
                app.isConnected() && peer.isConnected(),
                "a cancelled transfer must not tear down the session");
        awaitSyncIdle();

        // The aborted delivery was never confirmed, so the retry moves the whole file across a
        // session that never needed a reconnect.
        Files.deleteIfExists(new File(receiver.workspace(), "cancel-me.bin").toPath());
        sender.sync();
        awaitFileContent(receiver.workspace(), "cancel-me.bin", payload);
        awaitSyncIdle();
    }

    // ------------------------------------------------------------------------------ helpers

    private RemotePeer sender() {
        return app.isSender() ? app : peer;
    }

    private RemotePeer receiver() {
        return app.isSender() ? peer : app;
    }

    private void awaitSyncIdle() {
        await(
                "sync workers idle",
                () ->
                        !app.isSyncing()
                                && !peer.isSyncing()
                                && app.isConnected()
                                && peer.isConnected(),
                AWAIT_MS);
    }

    private void awaitFileContent(File dir, String relativePath, byte[] expected) {
        await(
                "file content " + relativePath,
                () -> fileContentMatches(dir, relativePath, expected),
                AWAIT_MS);
    }

    private static boolean fileContentMatches(File dir, String relativePath, byte[] expected) {
        try {
            File file = new File(dir, relativePath);
            return file.isFile()
                    && file.length() == expected.length
                    && java.util.Arrays.equals(expected, Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            return false;
        }
    }

    private void awaitAbsent(File dir, String relativePath) {
        await("file gone " + relativePath, () -> !new File(dir, relativePath).exists(), AWAIT_MS);
    }

    private static void await(String what, BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(25);
        }
        throw new AssertionError("timed out after " + timeoutMs + "ms waiting for: " + what);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] data = new byte[length];
        new Random(0xC0FFEE).nextBytes(data);
        return data;
    }

    private static void writeFile(File dir, String relativePath, byte[] data) throws IOException {
        File target = new File(dir, relativePath);
        File parent = target.getParentFile();
        assertTrue(parent != null && (parent.mkdirs() || parent.isDirectory()));
        Files.write(target.toPath(), data);
    }

    private static byte[] readFile(File dir, String relativePath) throws IOException {
        return Files.readAllBytes(new File(dir, relativePath).toPath());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Teardown must not mask a test failure.
        }
    }

    private static void deleteRecursively(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        dir.delete();
    }
}
