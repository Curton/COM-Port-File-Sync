package com.filesync.lab.e2e;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression shape from the field (v1.6.48, two machines syncing the application's own source
 * tree): a sync whose delta path carries many candidates — 30 files whose block signatures are
 * requested in one {@code DELTA_SIG_REQ} exchange. The single-file delta tests cannot see what a
 * 30-path exchange does; this test reproduces the exact shape over the emulated wire.
 */
class MultiFileDeltaSyncTest {

    private static final int BAUD = 460_800;
    private static final int AWAIT_MS = 60_000;
    private static final int FILE_COUNT = 35;
    private static final int MODIFIED_COUNT = 30;

    private Trace trace;
    private File root;
    private RemotePeer app;
    private RemotePeer peer;
    private WireModel model;
    private DuplexLink link;

    @BeforeEach
    void setUp() throws Exception {
        trace = new Trace(true, (File) null);
        root = Files.createTempDirectory("link-lab-multifile-delta").toFile();
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
    void manyDeltaCandidatesSyncThroughOneSignatureExchange() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();

        // Seed the tree: deep .java-ish paths, sizes in the range of the real source files
        // (16-47 KB, all past the 8 KB delta-eligibility floor).
        Map<String, byte[]> original = new LinkedHashMap<>();
        for (int i = 0; i < FILE_COUNT; i++) {
            original.put(path(i), numberedText(300 + 12 * i));
        }
        for (Map.Entry<String, byte[]> e : original.entrySet()) {
            writeFile(sender.workspace(), e.getKey(), e.getValue());
        }
        SyncPreviewPlan first = sender.sync();
        assertTrue(
                first.getFilesToTransfer().size() >= FILE_COUNT,
                "the first sync carries every file");
        for (Map.Entry<String, byte[]> e : original.entrySet()) {
            awaitFileContent(receiver.workspace(), e.getKey(), e.getValue());
        }
        awaitSyncIdle();

        // Edit 30 of them in the middle — the second sync sees 30 delta candidates and requests
        // their block signatures in a single exchange, exactly like the field session.
        Map<String, byte[]> edited = new LinkedHashMap<>();
        for (int i = 0; i < MODIFIED_COUNT; i++) {
            byte[] second = withMiddleReplaced(original.get(path(i)), 2_000, 4_000);
            edited.put(path(i), second);
            writeFile(sender.workspace(), path(i), second);
        }
        SyncPreviewPlan second = sender.sync();
        assertTrue(
                second.getDeltaCandidatePaths().size() == MODIFIED_COUNT,
                "the edited files must be delta candidates, got "
                        + second.getDeltaCandidatePaths().size());
        for (Map.Entry<String, byte[]> e : edited.entrySet()) {
            awaitFileContent(receiver.workspace(), e.getKey(), e.getValue());
        }
        awaitSyncIdle();

        List<String> senderLog = new ArrayList<>(sender.logLines());
        assertTrue(
                senderLog.stream()
                        .anyMatch(l -> l.contains("Requesting block signatures for " + MODIFIED_COUNT)),
                "the exchange must have been requested, log was: " + senderLog);
        assertTrue(
                senderLog.stream()
                        .anyMatch(l -> l.contains("Block signatures received for " + MODIFIED_COUNT)),
                "the signature exchange must complete, log was: " + senderLog);
    }

    private static String path(int i) {
        return String.format("main/java/com/filesync/sync/Service%02d.java", i);
    }

    /** A large compressible text body: numbered lines with a fixed repeating tail. */
    private static byte[] numberedText(int lineCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            sb.append(String.format("line %06d the quick brown fox jumps over the lazy dog\n", i));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A copy of {@code original} with a contiguous middle region overwritten by repeating text. */
    private static byte[] withMiddleReplaced(byte[] original, int start, int length) {
        byte[] edited = original.clone();
        byte[] filler = "REPLACED REGION ".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < length; i++) {
            edited[start + i] = filler[i % filler.length];
        }
        return edited;
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
        try {
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
        } catch (AssertionError e) {
            dumpAllThreads(e.getMessage());
            throw e;
        }
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

    /** Prints every thread's stack so a wedged two-ended test shows where each side is parked. */
    private void dumpAllThreads(String reason) {
        System.out.println("=== THREAD DUMP: " + reason + " ===");
        for (Map.Entry<Thread, StackTraceElement[]> e :
                Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            if (t.getName().startsWith("JUnit") || t.getName().startsWith("Surefire")) {
                continue;
            }
            System.out.println("--- " + t.getName() + " (" + t.getState() + ")");
            for (StackTraceElement el : e.getValue()) {
                System.out.println("    " + el);
            }
        }
        System.out.println("=== END THREAD DUMP ===");
        dumpWireState("app", app);
        dumpWireState("peer", peer);
        System.out.println("--- app log lines:");
        app.logLines().forEach(l -> System.out.println("    " + l));
        System.out.println("--- peer log lines:");
        peer.logLines().forEach(l -> System.out.println("    " + l));
        System.out.flush();
    }

    /** Reflectively prints one side's wire channels: pending schedule bytes, ready bytes, stats. */
    private static void dumpWireState(String label, RemotePeer side) {
        try {
            Object port = fieldOf(side.manager(), "serialPort");
            System.out.println(
                    label + " port class=" + port.getClass().getName() + " @" + System.identityHashCode(port));
            for (String ch : new String[] {"inbound", "outbound"}) {
                Object channel = fieldOf(port, ch);
                Object schedule = fieldOf(channel, "schedule");
                int pending = (int) fieldOf(schedule, "count");
                int ready = (int) fieldOf(channel, "readyCount");
                Object stats = fieldOf(channel, "stats");
                System.out.printf(
                        "%s %s: ch@%x stats@%x schedulePending=%d ready=%d offered=%d released=%d dropped=%d%n",
                        label,
                        ch,
                        System.identityHashCode(channel),
                        System.identityHashCode(stats),
                        pending,
                        ready,
                        longOf(fieldOf(stats, "offeredBytes")),
                        longOf(fieldOf(stats, "releasedBytes")),
                        longOf(fieldOf(stats, "droppedBytes")));
            }
            for (String st : new String[] {"inboundStats", "outboundStats"}) {
                Object stats = fieldOf(port, st);
                System.out.printf(
                        "%s %s: stats@%x offered=%d released=%d%n",
                        label,
                        st,
                        System.identityHashCode(stats),
                        longOf(fieldOf(stats, "offeredBytes")),
                        longOf(fieldOf(stats, "releasedBytes")));
            }
        } catch (RuntimeException e) {
            System.out.println(label + " wire state unavailable: " + e);
        }
    }

    private static Object fieldOf(Object target, String name) {
        Object base = target;
        for (Class<?> c = base.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(base);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (IllegalAccessException | RuntimeException e) {
                throw new IllegalStateException("cannot read " + name, e);
            }
        }
        throw new IllegalStateException("no field " + name + " on " + base.getClass());
    }

    private static long longOf(Object atomic) {
        return ((java.util.concurrent.atomic.AtomicLong) atomic).get();
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
