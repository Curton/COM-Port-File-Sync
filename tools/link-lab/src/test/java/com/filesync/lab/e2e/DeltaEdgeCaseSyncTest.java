package com.filesync.lab.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.lab.Trace;
import com.filesync.lab.link.WireModel;
import com.filesync.lab.peer.RemotePeer;
import com.filesync.lab.port.DuplexLink;
import com.filesync.sync.SyncPreviewPlan;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Delta-encoder edge shapes the happy-path delta tests never reach: a receiver base smaller than
 * one signature block (all-literal delta and its savings fallback), a divergent tail that slides
 * the final rolling window as literals, a receiver block duplicated at a non-block-aligned offset
 * (strong-hash over the wrapped ring window), and a large edit whose coalesced COPY run exceeds the
 * decoder's 64 KiB scratch chunk while the literal run exceeds the encoder's flush threshold.
 *
 * <p>Every scenario establishes the baseline with a first full sync (so the receiver's copy is a
 * confirmed synced state, not a conflict), then edits only the sender's copy. Binary payloads
 * throughout: line-ending normalization must not silently reshape the content a delta is encoded
 * against.
 */
class DeltaEdgeCaseSyncTest {

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
        root = Files.createTempDirectory("link-lab-delta-edge").toFile();
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

    @Test
    @Timeout(240)
    void tinyReceiverBaseEncodesAllLiteralDeltaThenFallsBackToBatch() throws Exception {
        // 200 bytes: below one 512-byte signature block, so the receiver's signatures carry zero
        // blocks and the encoder can only emit literals. The junk content is deliberately not a
        // prefix of the sender's file, so the append fast path rejects it and the signature
        // exchange still runs. 70 KiB keeps the pumped literal run past the encoder's 64 KiB
        // flush threshold.
        runDeltaScenario("tiny-base/only.bin", randomBytes(200, 1), randomBytes(70 * 1024, 2));

        List<String> log = senderLog();
        assertTrue(
                log.stream().anyMatch(l -> l.contains("Requesting block signatures for 1")),
                "the signature exchange must be requested, log was: " + log);
        assertTrue(
                log.stream().anyMatch(l -> l.contains("Block signatures received for 1")),
                "the receiver must have signed its tiny file, log was: " + log);
        assertTrue(
                log.stream()
                        .anyMatch(l -> l.contains("Delta saving for") && l.contains("too small")),
                "an all-literal delta cannot beat the full transfer; the savings gate must fall"
                        + " back to the batch path, log was: "
                        + log);
    }

    @Test
    @Timeout(180)
    void divergentTailSlidesTheFinalWindowAsLiterals() throws Exception {
        // Same length, so the append fast path is out; the last full window contains the edited
        // bytes and never matches, so the encoder slides the final block-sized window to the end
        // of the stream with no refill byte available.
        byte[] base = randomBytes(16 * 1024, 3);
        byte[] next = base.clone();
        byte[] tail = randomBytes(300, 4);
        System.arraycopy(tail, 0, next, next.length - tail.length, tail.length);
        runDeltaScenario("tail-diverge/blob.bin", base, next);
        assertDeltaTransferred("tail-diverge/blob.bin");
    }

    @Test
    @Timeout(180)
    void duplicateBlockAtUnalignedOffsetMatchesThroughTheWrappedWindow() throws Exception {
        // The inserted copy of the base's first block starts at offset 300, so the weak-hash hit
        // lands with the ring window wrapped (ringPos != 0) and the strong hash must be fed as
        // two contiguous spans of the ring. The rest of the file realigns afterwards.
        byte[] base = randomBytes(16 * 1024, 5);
        byte[] next = new byte[base.length + 512];
        System.arraycopy(base, 0, next, 0, 300);
        System.arraycopy(base, 0, next, 300, 512);
        System.arraycopy(base, 300, next, 812, base.length - 300);
        runDeltaScenario("unaligned-dup/blob.bin", base, next);
        assertDeltaTransferred("unaligned-dup/blob.bin");
    }

    @Test
    @Timeout(300)
    void largeEditUsesChunkedCopyRunAndFlushesBigLiteralRun() throws Exception {
        // 100 KiB unchanged prefix coalesces into one COPY run past the decoder's 64 KiB scratch
        // chunk; the 80 KiB edited middle is traversed byte-by-byte as literals, crossing the
        // encoder's 64 KiB literal flush threshold.
        byte[] base = randomBytes(200 * 1024, 6);
        byte[] next = base.clone();
        byte[] edit = randomBytes(80 * 1024, 7);
        System.arraycopy(edit, 0, next, 100 * 1024, edit.length);
        runDeltaScenario("large-edit/blob.bin", base, next);
        assertDeltaTransferred("large-edit/blob.bin");
    }

    /** Syncs {@code base} once, then re-syncs with {@code next} in the sender's copy. */
    private void runDeltaScenario(String path, byte[] base, byte[] next) throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();

        writeFile(sender.workspace(), path, base);
        SyncPreviewPlan first = sender.sync();
        assertEquals(1, first.getFilesToTransfer().size(), "the first sync carries the base");
        awaitFileContent(receiver.workspace(), path, base);
        awaitSyncIdle();

        writeFile(sender.workspace(), path, next);
        SyncPreviewPlan second = sender.sync();
        assertTrue(
                second.getDeltaCandidatePaths().contains(path),
                "the edited file must be a delta candidate, got "
                        + second.getDeltaCandidatePaths());
        awaitFileContent(receiver.workspace(), path, next);
        awaitSyncIdle();
    }

    private void assertDeltaTransferred(String path) {
        List<String> log = senderLog();
        assertTrue(
                log.stream().anyMatch(l -> l.contains("Delta syncing") && l.contains(path)),
                "the file must have gone out as a delta, log was: " + log);
        assertTrue(
                log.stream().anyMatch(l -> l.contains("(saved ")),
                "the delta must have reported its savings, log was: " + log);
    }

    private List<String> senderLog() {
        return sender().logLines();
    }

    private byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

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
                () -> {
                    try {
                        File file = new File(dir, relativePath);
                        return file.isFile()
                                && file.length() == expected.length
                                && java.util.Arrays.equals(
                                        expected, Files.readAllBytes(file.toPath()));
                    } catch (IOException e) {
                        return false;
                    }
                },
                AWAIT_MS);
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

    private static void writeFile(File dir, String relativePath, byte[] data) throws IOException {
        File target = new File(dir, relativePath);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            assertTrue(parent.mkdirs(), "must create " + parent);
        }
        Files.write(target.toPath(), data);
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Exception ignored) {
            // teardown is best-effort
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
