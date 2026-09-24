package com.filesync.lab.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.config.SettingsManager;
import com.filesync.lab.Trace;
import com.filesync.lab.link.TamperRule;
import com.filesync.lab.link.WireModel;
import com.filesync.lab.peer.RemotePeer;
import com.filesync.lab.port.DuplexLink;
import com.filesync.sync.FileSyncManager;
import com.filesync.sync.SyncEventType;
import com.filesync.sync.SyncPreviewPlan;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Two real application instances on one emulated link. Both sides run the production stack against
 * each other over a {@link DuplexLink}, so these tests reach the paths the scripted-port unit tests
 * cannot: the manager's own sync entry points, the drop-file receive chain, the remote-folder
 * exchange, shared text interleaved into a live XMODEM session, link loss and recovery, a payload
 * carried through a wire that really flips bits, and the reconnect-and-retry recovery after a
 * control frame is lost on that wire.
 *
 * <p>Roles are not forced: the real negotiation runs and the test adapts to whichever side won, so
 * the election itself stays under test. The link runs at 460800 baud - fast enough to keep the
 * suite quick, still paced by the same byte schedule as a slow link.
 */
class TwoEndedRegressionTest {

    private static final int BAUD = 460_800;
    private static final int AWAIT_MS = 60_000;
    private static final Set<SyncEventType> PROGRESS_EVENTS =
            Set.of(
                    SyncEventType.FILE_PROGRESS,
                    SyncEventType.TRANSFER_PROGRESS,
                    SyncEventType.MANIFEST_PROGRESS);

    private Trace trace;
    private File root;
    private RemotePeer app;
    private RemotePeer peer;

    private final List<SyncEventType> appEvents = new CopyOnWriteArrayList<>();
    private final List<SyncEventType> peerEvents = new CopyOnWriteArrayList<>();
    private final AtomicInteger senderProgressEvents = new AtomicInteger();
    private WireModel model;
    private DuplexLink link;

    /**
     * Samples both managers' liveness flags so a stalled recovery can be told apart from a
     * negotiated one: only transitions are traced, which keeps the log small.
     */
    private volatile boolean sampleStates;

    private Thread stateSampler;

    @BeforeEach
    void setUp() throws Exception {
        trace = new Trace(true, (File) null);
        root = Files.createTempDirectory("link-lab-e2e").toFile();
        model = new WireModel().baud(BAUD).latencyMillis(1).jitterMillis(1);
        link = new DuplexLink(trace, model);
        app = RemotePeer.attach(trace, model, new File(root, "app"), link.sideA(), false);
        peer = RemotePeer.attach(trace, model, new File(root, "peer"), link.sideB(), false);
        app.manager()
                .getEventBus()
                .register(
                        event -> {
                            appEvents.add(event.getType());
                            if (app.isSender() && PROGRESS_EVENTS.contains(event.getType())) {
                                senderProgressEvents.incrementAndGet();
                            }
                        });
        peer.manager()
                .getEventBus()
                .register(
                        event -> {
                            peerEvents.add(event.getType());
                            if (peer.isSender() && PROGRESS_EVENTS.contains(event.getType())) {
                                senderProgressEvents.incrementAndGet();
                            }
                        });
        awaitSessionAlive();
        startStateSampler();
    }

    @AfterEach
    void tearDown() {
        sampleStates = false;
        closeQuietly(peer);
        closeQuietly(app);
        closeQuietly(link);
        deleteRecursively(root);
    }

    @Test
    @Timeout(180)
    void fullSyncTransfersFilesAndDeletions() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();
        byte[] text =
                "hello from the other side of the emulated link\n".getBytes(StandardCharsets.UTF_8);
        byte[] blob = randomBytes(20_000);
        writeFile(sender.workspace(), "doc.txt", text);
        writeFile(sender.workspace(), "sub/nested.bin", blob);

        SyncPreviewPlan plan = sender.sync();
        assertEquals(2, plan.getFilesToTransfer().size());
        assertTrue(plan.getTotalBytesToTransfer() >= blob.length);
        awaitFileContent(receiver.workspace(), "doc.txt", text);
        awaitFileContent(receiver.workspace(), "sub/nested.bin", blob);
        awaitSyncIdle();

        // Removing a file must propagate as a deletion on the next sync, without re-sending the
        // file that did not change. Deletions only ride along in strict sync mode.
        sender.manager().setStrictSyncMode(true);
        assertTrue(new File(sender.workspace(), "doc.txt").delete());
        SyncPreviewPlan second = sender.sync();
        assertTrue(second.getFilesToTransfer().isEmpty());
        assertEquals(List.of("doc.txt"), second.getFilesToDelete());
        awaitAbsent(receiver.workspace(), "doc.txt");
        awaitSyncIdle();
        assertArrayEquals(blob, readFile(receiver.workspace(), "sub/nested.bin"));
    }

    @Test
    @Timeout(180)
    void dropFileIsReceivedAndSaved() throws Exception {
        // Unique per run: the receiving side renames on collision, and a leftover from an earlier
        // run must not decide the file name this run asserts on.
        String fileName = "link-lab-drop-" + Long.toHexString(System.nanoTime()) + ".bin";
        byte[] payload = randomBytes(9_000);
        File dropped = new File(peer.workspace(), fileName);
        Files.write(dropped.toPath(), payload);

        peer.push(dropped);
        await("drop file saved on the app side", () -> !app.receivedFiles().isEmpty(), AWAIT_MS);
        File saved = new File(app.receivedFiles().get(0));
        try {
            assertTrue(saved.isFile(), "expected a real file at " + saved);
            assertTrue(saved.isAbsolute(), "the drop path must be absolute: " + saved);
            assertEquals(fileName, saved.getName());
            assertArrayEquals(payload, Files.readAllBytes(saved.toPath()));
        } finally {
            Files.deleteIfExists(saved.toPath());
        }
    }

    @Test
    @Timeout(180)
    void remoteFolderContextAndChangeNotification() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();

        String folder = sender.requestFolderContext();
        assertEquals(
                SettingsManager.normalizeFolderPath(receiver.workspace().getAbsolutePath()),
                SettingsManager.normalizeFolderPath(folder));

        File switched = new File(root, "switched-receiver-folder");
        assertTrue(switched.mkdirs());
        // The mapping lives in the sender's SettingsManager, which FileSyncManager keeps private;
        // SyncController writes the same entry when the user picks a folder pair.
        settingsOf(sender.manager())
                .setRememberedFolderMapping(
                        sender.connectedPort(),
                        sender.workspace().getAbsolutePath(),
                        switched.getAbsolutePath());
        sender.manager().notifyFolderChange(sender.workspace().getAbsolutePath());

        await(
                "receiver applies the folder change",
                () -> eventsOf(receiver).contains(SyncEventType.REMOTE_FOLDER_CHANGED),
                AWAIT_MS);
        assertEquals(
                SettingsManager.normalizeFolderPath(switched.getAbsolutePath()),
                SettingsManager.normalizeFolderPath(
                        receiver.manager().getSyncFolder().getAbsolutePath()));
    }

    @Test
    @Timeout(240)
    void sharedTextIsInterleavedIntoALiveFileTransfer() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();
        byte[] payload = randomBytes(300_000);
        writeFile(sender.workspace(), "big.bin", payload);
        senderProgressEvents.set(0);

        sender.sync();
        // Queue the text only once blocks are actually flying: the peer's listener is parked inside
        // the XMODEM session for the whole transfer, so the only way this text can get through is
        // as
        // a frame interleaved between two data blocks of the sender's stream.
        await("transfer in flight", () -> senderProgressEvents.get() > 0, AWAIT_MS);
        sender.sendSharedText("interleaved while blocks are flying");

        // The moment the text lands must still be inside the transfer: the destination file is not
        // complete yet, so the frame could only have been dispatched by the interleaved-frame path
        // -
        // the ordinary listener is parked inside the XMODEM receive for the whole session.
        await(
                "shared text delivered",
                () -> receiver.receivedTexts().contains("interleaved while blocks are flying"),
                AWAIT_MS);
        assertFalse(
                fileContentMatches(receiver.workspace(), "big.bin", payload),
                "the file was already complete when the interleaved text arrived");

        awaitSyncIdle();
        awaitFileContent(receiver.workspace(), "big.bin", payload);
    }

    @Test
    @Timeout(240)
    void linkCycleLosesAndRecoversTheSession() throws Exception {
        RemotePeer firstReceiver = receiver();
        byte[] before = randomBytes(4_000);
        writeFile(sender().workspace(), "before.bin", before);
        sender().sync();
        awaitFileContent(firstReceiver.workspace(), "before.bin", before);
        awaitSyncIdle();

        link.unplug();
        await(
                "both sides notice the dead link",
                () ->
                        !app.isConnected()
                                && !peer.isConnected()
                                && !app.manager().isRunning()
                                && !peer.manager().isRunning(),
                AWAIT_MS);

        link.replug();
        app.restart();
        peer.restart();
        awaitSessionAlive();

        // Roles are re-negotiated from scratch after a link cycle, so pick the new sender.
        byte[] after = randomBytes(5_000);
        RemotePeer newSender = sender();
        writeFile(newSender.workspace(), "after.bin", after);
        newSender.sync();
        awaitFileContent(receiver().workspace(), "after.bin", after);
        awaitSyncIdle();
        // The pre-outage transfer is untouched by the recovery.
        awaitFileContent(firstReceiver.workspace(), "before.bin", before);
    }

    @Test
    @Timeout(180)
    void syncSurvivesALossyWire() throws Exception {
        // A noisy line: flipped bits, which is what line noise does to a byte that still arrives.
        // The model's rates are per channel and every byte crosses two channels (the writer's
        // outbound and the reader's inbound), so the effective rate is roughly double the knob;
        // the value below flips a noticeable share of the payload's blocks while leaving XMODEM's
        // per-block CRC and ACK/NAK retries able to absorb every one of them. Silent byte loss is
        // deliberately not modelled here: a dropped byte desynchronises the XMODEM stream itself
        // and no retry can resynchronise it, so the transfer-layer fault that a real line produces
        // is bit errors (covered here) and lost frames (covered by the dropped-ACK test below).
        // When a control frame does get hit, the exchange waits out its protocol timeout and the
        // heartbeat teardown follows, so the sync is retried the way a user would: disconnect,
        // reconnect, sync again.
        model.corruptPercent(0.02);
        byte[] payload = randomBytes(40_000);

        String lastFailure = "none";
        boolean delivered = false;
        for (int attempt = 1; attempt <= 4 && !delivered; attempt++) {
            if (attempt > 1) {
                rebuildSession();
                awaitSessionAlive();
            }
            // The payload starts on the sender and must show up on the receiver: the receiver's
            // copy
            // is removed first, so only the wire can put it back. Roles are re-negotiated after
            // every rebuild, so which side is which is read fresh each time.
            writeFile(sender().workspace(), "noisy.bin", payload);
            Files.deleteIfExists(new File(receiver().workspace(), "noisy.bin").toPath());
            try {
                sender().sync();
            } catch (RuntimeException e) {
                lastFailure = e.getMessage();
                trace.log(Trace.Dir.PEER, "sync attempt " + attempt + " failed: " + lastFailure);
            }
            // initiateSync is fire-and-forget while blocks are still flying, and a wedged exchange
            // keeps the workers busy for a full protocol timeout: wait for the file, and only call
            // the attempt settled once both sides have been idle for a moment.
            delivered = awaitDeliveryOrQuiet(receiver(), payload);
        }
        assertTrue(
                delivered, "the noisy wire never delivered the file; last failure: " + lastFailure);
        await("sync workers idle", () -> !app.isSyncing() && !peer.isSyncing(), AWAIT_MS);

        // The wire really was faulty - otherwise the payload could have crossed a clean line. Both
        // directions inject faults, and the counters survive the re-plugs above.
        var stats = app.inboundStats();
        var other = peer.inboundStats();
        assertTrue(
                stats.corruptedBytesPlus(other) > 0,
                "no bytes were corrupted: " + app.inboundStats().summary());
    }

    /**
     * A control frame lost on the wire wedges the exchange that waits for it: the responder sits in
     * its protocol timeout while the requester's handshake window expires and it drops the session.
     * The recovery is the user's one - disconnect both ends, reconnect, sync again - and halting
     * the wedged responder interrupts the wait instead of letting it run its full timeout. The
     * re-plug builds fresh channels, and tamper rules live on channels, so the retry runs clean.
     */
    @Test
    @Timeout(180)
    void aDroppedControlFrameIsRecoveredByReconnectingAndRetrying() throws Exception {
        RemotePeer initialSender = sender();
        RemotePeer initialReceiver = receiver();
        byte[] payload = randomBytes(8_000);
        writeFile(initialSender.workspace(), "dropped-ack.bin", payload);
        // Whichever side wins the election writes the first ACK of the manifest exchange; the rule
        // is installed on both directions so the test does not depend on the outcome.
        app.addTamperRule(new TamperRule("ACK", 1, TamperRule.Action.DROP, 0, null), true);
        peer.addTamperRule(new TamperRule("ACK", 1, TamperRule.Action.DROP, 0, null), true);

        RuntimeException failure = null;
        try {
            initialSender.sync();
        } catch (RuntimeException e) {
            failure = e;
            trace.log(Trace.Dir.PEER, "sync with a dropped ACK failed: " + e.getMessage());
        }
        assertTrue(failure != null, "the sync should not complete while its ACK is dropped");
        assertFalse(
                fileContentMatches(initialReceiver.workspace(), "dropped-ack.bin", payload),
                "no part of the payload may arrive before the retry");

        rebuildSession();
        awaitSessionAlive();
        RemotePeer retrySender = sender();
        RemotePeer retryReceiver = receiver();
        writeFile(retrySender.workspace(), "dropped-ack.bin", payload);
        Files.deleteIfExists(new File(retryReceiver.workspace(), "dropped-ack.bin").toPath());
        retrySender.sync();
        awaitFileContent(retryReceiver.workspace(), "dropped-ack.bin", payload);
        awaitSyncIdle();
    }

    /**
     * Waits up to {@link #AWAIT_MS} for the receiver to hold the payload. Returns false when the
     * transfer settled without delivering it - both workers idle for a moment, which covers the gap
     * between the sender's preview returning and its transfer starting.
     */
    private boolean awaitDeliveryOrQuiet(RemotePeer receiver, byte[] payload) {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        long idleSince = -1;
        while (System.currentTimeMillis() < deadline) {
            if (fileContentMatches(receiver.workspace(), "noisy.bin", payload)) {
                return true;
            }
            if (!app.isSyncing() && !peer.isSyncing()) {
                if (idleSince < 0) {
                    idleSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - idleSince >= 500) {
                    return false;
                }
            } else {
                idleSince = -1;
            }
            sleep(25);
        }
        return false;
    }

    /**
     * Rebuilds both ends of the link from scratch: stop both listeners (interrupting whatever
     * exchange a corrupted frame left parked), re-plug the cable, start both listeners again. The
     * session that comes up is negotiated from scratch, so roles may flip.
     */
    private void rebuildSession() {
        app.halt();
        peer.halt();
        link.replug();
        app.restart();
        peer.restart();
    }

    // ------------------------------------------------------------------------------ helpers

    private void startStateSampler() {
        sampleStates = true;
        stateSampler =
                new Thread(
                        () -> {
                            String previous = "";
                            while (sampleStates) {
                                String now =
                                        "conn="
                                                + app.isConnected()
                                                + "/"
                                                + peer.isConnected()
                                                + " neg="
                                                + app.isRoleNegotiated()
                                                + "/"
                                                + peer.isRoleNegotiated()
                                                + " run="
                                                + app.isRunning()
                                                + "/"
                                                + peer.isRunning()
                                                + " snd="
                                                + app.isSender()
                                                + "/"
                                                + peer.isSender()
                                                + " sync="
                                                + app.isSyncing()
                                                + "/"
                                                + peer.isSyncing()
                                                + " xm="
                                                + flagOf(app, "protocol", "xmodemInProgress")
                                                + "/"
                                                + flagOf(peer, "protocol", "xmodemInProgress")
                                                + " aw="
                                                + flagOf(app, "protocol", "awaitingCommand")
                                                + "/"
                                                + flagOf(peer, "protocol", "awaitingCommand")
                                                + " blk="
                                                + flagOf(app, "senderBlockingProtocolExchange")
                                                + "/"
                                                + flagOf(peer, "senderBlockingProtocolExchange");
                                if (!now.equals(previous)) {
                                    trace.log(Trace.Dir.PEER, "STATE " + now);
                                    previous = now;
                                }
                                sleep(20);
                            }
                        });
        stateSampler.setDaemon(true);
        stateSampler.setName("state-sampler");
        stateSampler.start();
    }

    /** Reads a boolean field of the peer's manager (or of its protocol when named). */
    private static boolean flagOf(RemotePeer side, String... path) {
        Object target = side.manager();
        for (int i = 0; i < path.length - 1; i++) {
            target = rawField(target, path[i]);
            if (target == null) {
                return false;
            }
        }
        Object value = rawField(target, path[path.length - 1]);
        return value instanceof Boolean b ? b : false;
    }

    private static Object rawField(Object target, String name) {
        try {
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A missing field is reported as "false" by the caller.
        }
        return null;
    }

    private RemotePeer sender() {
        return app.isSender() ? app : peer;
    }

    private RemotePeer receiver() {
        return app.isSender() ? peer : app;
    }

    private List<SyncEventType> eventsOf(RemotePeer side) {
        return side == app ? appEvents : peerEvents;
    }

    private void awaitSessionAlive() {
        await(
                "connection alive and role negotiated on both sides",
                () ->
                        app.isConnected()
                                && peer.isConnected()
                                && app.isRoleNegotiated()
                                && peer.isRoleNegotiated(),
                AWAIT_MS);
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
                    && Arrays.equals(expected, Files.readAllBytes(file.toPath()));
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

    /** Reads the manager's private SettingsManager (the mapping only SyncController writes). */
    private static SettingsManager settingsOf(FileSyncManager manager) {
        try {
            Field field = FileSyncManager.class.getDeclaredField("settings");
            field.setAccessible(true);
            return (SettingsManager) field.get(manager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read the manager's settings: " + e);
        }
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
