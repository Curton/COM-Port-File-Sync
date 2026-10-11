package com.filesync.lab.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.lab.Trace;
import com.filesync.lab.link.WireModel;
import com.filesync.lab.peer.RemotePeer;
import com.filesync.lab.port.DuplexLink;
import com.filesync.sync.ConflictAnalyzer;
import com.filesync.sync.ConflictInfo;
import com.filesync.sync.SyncEventType;
import com.filesync.sync.SyncPreviewPlan;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The sync-feature paths the other two-ended tests do not reach, each driven against two real
 * managers over the emulated wire: rename propagation (the receiver moves instead of
 * retransferring), the append-only fast path and its prefix-shaped-conflict variant, the
 * .gitignore / .filesyncignore filters on both ends, the conflict content fetch the UI's merge
 * flow performs, and the pending-write queue a locked receiver file produces.
 */
class SyncFeaturePathsTest {

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
        root = Files.createTempDirectory("link-lab-sync-features").toFile();
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
     * Renaming a synced file must reach the receiver as a single {@code FILE_RENAME} move: the
     * preview pairs the vanished path with the appeared one by content hash and plans neither a
     * transfer nor a delete for it, and the receiver's copy keeps its bytes through the move.
     */
    @Test
    @Timeout(180)
    void aRenameIsMovedOnTheReceiverInsteadOfRetransferred() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();
        byte[] payload = randomBytes(12_000);
        sender.manager().setStrictSyncMode(true);
        writeFile(sender.workspace(), "report.txt", payload);
        SyncPreviewPlan first = sender.manager().previewSync();
        assertEquals(1, first.getFilesToTransfer().size());
        sender.manager().initiateSync(first);
        awaitFileContent(receiver.workspace(), "report.txt", payload);
        awaitSyncIdle();

        assertTrue(
                new File(sender.workspace(), "report.txt")
                        .renameTo(new File(sender.workspace(), "report-final.txt")),
                "the rename must succeed for the test to mean anything");

        SyncPreviewPlan second = sender.manager().previewSync();
        assertEquals(
                1,
                second.getRenames().size(),
                "same content at a new path pairs into one rename");
        assertEquals("report.txt", second.getRenames().get(0).getFromPath());
        assertEquals("report-final.txt", second.getRenames().get(0).getToPath());
        assertTrue(
                second.getFilesToTransfer().isEmpty(),
                "a rename must not retransfer the payload: " + second.getFilesToTransfer());
        assertTrue(
                second.getFilesToDelete().isEmpty(),
                "the move performs the deletion: " + second.getFilesToDelete());

        sender.manager().initiateSync(second);
        await("receiver moved the file", () -> absent(receiver.workspace(), "report.txt"), AWAIT_MS);
        awaitFileContent(receiver.workspace(), "report-final.txt", payload);
        awaitSyncIdle();
    }

    /**
     * An actively-grown file (the log-file shape: the receiver's copy is a strict prefix of the
     * sender's) must ride the append fast path: no signature exchange, only the tail crosses the
     * wire, and the receiver reconstructs and verifies the whole file before replacing its copy.
     */
    @Test
    @Timeout(180)
    void anAppendOnlyFileSendsOnlyTheTail() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();
        byte[] base = randomBytes(20_000);
        writeFile(sender.workspace(), "app.log", base);
        sender.sync();
        awaitFileContent(receiver.workspace(), "app.log", base);
        awaitSyncIdle();

        byte[] tail = "appended line one\nappended line two\n".getBytes(StandardCharsets.UTF_8);
        byte[] grown = concat(base, tail);
        writeFile(sender.workspace(), "app.log", grown);

        SyncPreviewPlan plan = sender.sync();
        assertTrue(
                plan.getDeltaCandidatePaths().contains("app.log"),
                "an appended file past the delta floor is a delta candidate");
        awaitFileContent(receiver.workspace(), "app.log", grown);
        await(
                "the sender logs the append fast path",
                () -> sender.logLines().stream().anyMatch(l -> l.contains("Append-only syncing")),
                AWAIT_MS);
        assertFalse(
                sender.logLines().stream().anyMatch(l -> l.contains("Requesting block signatures")),
                "the append path must skip the signature exchange entirely");
        awaitSyncIdle();
    }

    /**
     * The receiver's copy drifted to a strict byte-prefix of the sender's file - the shape of an
     * archive that arrived halfway through some outside-the-sync channel. The manifests alone
     * classify it as a conflict, but a receiver-modified file it is not: the preview exempts it
     * and only the missing tail is transferred, converging both sides without user intervention.
     */
    @Test
    @Timeout(180)
    void aReceiverPrefixConflictSendsOnlyTheMissingTail() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();
        byte[] original = randomBytes(24_000);
        byte[] tail = randomBytes(3_000);
        writeFile(sender.workspace(), "archive.bin", original);
        sender.sync();
        awaitFileContent(receiver.workspace(), "archive.bin", original);
        awaitSyncIdle();

        // The receiver holds the first 9 kB (diverged from the base), the sender appended to the
        // original: a strictly shorter prefix of the sender's new content.
        byte[] truncated = Arrays.copyOf(original, 9_000);
        writeFile(receiver.workspace(), "archive.bin", truncated);
        byte[] full = concat(original, tail);
        writeFile(sender.workspace(), "archive.bin", full);

        SyncPreviewPlan plan = sender.sync();
        assertEquals(
                0,
                plan.getConflicts().size(),
                "the prefix shape is exempted from the conflict path");
        assertTrue(
                plan.getAppendResumablePaths().contains("archive.bin"),
                "the exempted file is labelled append-resumable");
        awaitFileContent(receiver.workspace(), "archive.bin", full);
        awaitSyncIdle();
    }

    /**
     * The ignore filters decide what a sync sees. .gitignore patterns (star, directory-only,
     * rooted, negation, nested files) exclude paths from the manifest while respectGitignore is
     * on; the root .filesyncignore always does - including exempting receiver-held paths from
     * strict-mode deletion; and the receiver's own .filesyncignore hides its local files so they
     * are neither overwritten nor deleted. Turning the .gitignore toggle back off lets the
     * scanner see the previously hidden files.
     */
    @Test
    @Timeout(180)
    void ignoreFilesShapeTheManifestOnBothEnds() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();
        sender.manager().setRespectGitignoreMode(true);
        sender.manager().setStrictSyncMode(true);

        writeFile(sender.workspace(), ".gitignore", "# e2e\n\n*.log\n!keep.log\nbuild/\n"
                + "/top-secret.txt\n");
        writeFile(sender.workspace(), ".filesyncignore", "secret.key\n");
        writeFile(sender.workspace(), "keep.txt", "kept".getBytes(StandardCharsets.UTF_8));
        writeFile(sender.workspace(), "notes.log", "ignored".getBytes(StandardCharsets.UTF_8));
        writeFile(sender.workspace(), "keep.log", "re-included".getBytes(StandardCharsets.UTF_8));
        writeFile(sender.workspace(), "build/out.bin", randomBytes(1_000));
        writeFile(sender.workspace(), "top-secret.txt", "root secret".getBytes(StandardCharsets.UTF_8));
        writeFile(sender.workspace(), "sub/top-secret.txt", "nested is fine".getBytes(StandardCharsets.UTF_8));
        writeFile(sender.workspace(), "sub/.gitignore", "inner.txt\n");
        writeFile(sender.workspace(), "sub/inner.txt", "ignored".getBytes(StandardCharsets.UTF_8));
        writeFile(sender.workspace(), "secret.key", "key material".getBytes(StandardCharsets.UTF_8));

        // Receiver-side state the sync must respect: a path the receiver's own .filesyncignore
        // hides (its manifest does not carry it, so the sender's copy is not transferred over
        // it), and a stale secret.key the sender's .filesyncignore must exempt from the strict
        // delete even though the sender no longer manages the path.
        byte[] receiverSecret = "receiver's key material".getBytes(StandardCharsets.UTF_8);
        writeFile(receiver.workspace(), ".filesyncignore", "local-only.txt\n");
        writeFile(receiver.workspace(), "local-only.txt", "receiver's own notes".getBytes(StandardCharsets.UTF_8));
        writeFile(receiver.workspace(), "secret.key", receiverSecret);

        writeFile(sender.workspace(), "local-only.txt", "sender's notes".getBytes(StandardCharsets.UTF_8));

        SyncPreviewPlan plan = sender.sync();
        assertEquals(
                Set.of("keep.txt", "keep.log", "sub/top-secret.txt"),
                pathsOf(plan.getFilesToTransfer()),
                "only the unignored files may transfer");
        assertTrue(plan.getFilesToDelete().isEmpty(), "ignored paths are exempt from deletion");

        awaitFileContent(receiver.workspace(), "keep.txt", "kept".getBytes(StandardCharsets.UTF_8));
        awaitFileContent(
                receiver.workspace(), "keep.log", "re-included".getBytes(StandardCharsets.UTF_8));
        awaitFileContent(
                receiver.workspace(),
                "sub/top-secret.txt",
                "nested is fine".getBytes(StandardCharsets.UTF_8));
        awaitSyncIdle();
        assertFalse(receiver.workspaceHas("notes.log"), "*.log is ignored by the sender");
        assertFalse(receiver.workspaceHas("build/out.bin"), "build/ is ignored by the sender");
        assertFalse(receiver.workspaceHas("top-secret.txt"), "the rooted pattern hits the root only");
        assertFalse(receiver.workspaceHas("sub/inner.txt"), "the nested .gitignore applies");
        assertArrayEquals(
                receiverSecret,
                Files.readAllBytes(new File(receiver.workspace(), "secret.key").toPath()),
                "the receiver's stale secret.key survives strict mode (sender ignores it)");
        assertEquals(
                "receiver's own notes",
                Files.readString(new File(receiver.workspace(), "local-only.txt").toPath()),
                "the receiver's hidden local file is neither overwritten nor deleted");

        // With the .gitignore toggle off the scanner sees the previously hidden files, while the
        // .filesyncignore keeps doing its job on both ends.
        sender.manager().setRespectGitignoreMode(false);
        SyncPreviewPlan second = sender.sync();
        assertTrue(
                pathsOf(second.getFilesToTransfer()).containsAll(
                        Set.of("notes.log", "build/out.bin", "top-secret.txt", "sub/inner.txt")),
                "disabling the gitignore filter reveals the hidden files: "
                        + pathsOf(second.getFilesToTransfer()));
        awaitFileContent(receiver.workspace(), "notes.log", "ignored".getBytes(StandardCharsets.UTF_8));
        awaitSyncIdle();
        assertEquals(
                "receiver's own notes",
                Files.readString(new File(receiver.workspace(), "local-only.txt").toPath()),
                "the receiver's .filesyncignore keeps hiding its local file");
    }

    /**
     * The conflict content path the UI's merge flow runs, headless: the preview's conflicts are
     * enriched with the remote bytes fetched over the production fetch exchange, the diff is
     * computed on both sides' texts, and a conflict whose differences are pure whitespace is
     * pre-resolved to KEEP_LOCAL so it never earns a merge page.
     */
    @Test
    @Timeout(180)
    void conflictContentFetchComputesTheTextDiffLikeTheUi() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();
        byte[] senderMeaningful =
                "line one\nline two changed on the sender\nline three\n"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] receiverMeaningful =
                "line one\nline two rewritten on the receiver\nline three\n"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] senderTrivial = "alpha\nbeta\n\ngamma\n".getBytes(StandardCharsets.UTF_8);
        byte[] receiverTrivial = "alpha\nbeta\n\n\ngamma\n".getBytes(StandardCharsets.UTF_8);
        writeFile(sender.workspace(), "meaningful.txt", senderMeaningful);
        writeFile(receiver.workspace(), "meaningful.txt", receiverMeaningful);
        writeFile(sender.workspace(), "trivial.txt", senderTrivial);
        writeFile(receiver.workspace(), "trivial.txt", receiverTrivial);

        // No sync history exists, so a differing file counts as diverged: both files conflict.
        SyncPreviewPlan plan = sender.manager().previewSync();
        List<ConflictInfo> conflicts = new ArrayList<>(plan.getConflicts());
        assertEquals(2, conflicts.size(), "both differing files conflict with no base recorded");

        for (ConflictInfo conflict : conflicts) {
            byte[] remote = sender.fetchContent(conflict.getPath());
            assertNotNull(remote, "the fetch exchange must return the receiver's bytes");
            conflict.setRemoteContent(remote);
        }
        ConflictAnalyzer.filterTrivialConflicts(conflicts);

        ConflictInfo meaningful = conflictOf(conflicts, "meaningful.txt");
        assertTrue(
                meaningful.hasMeaningfulDifferences(),
                "a rewritten line is a real difference");
        assertEquals(
                ConflictInfo.Resolution.UNRESOLVED,
                meaningful.getResolution(),
                "a meaningful conflict stays in the queue");
        assertArrayEquals(
                receiverMeaningful,
                meaningful.getRemoteContent(),
                "the fetched bytes are the receiver's");
        assertEquals(
                new String(senderMeaningful, StandardCharsets.UTF_8),
                meaningful.getLocalContentAsString(),
                "the lazy local content resolves to the sender's bytes");

        ConflictInfo trivial = conflictOf(conflicts, "trivial.txt");
        assertFalse(
                trivial.hasMeaningfulDifferences(),
                "an added blank line is whitespace-only");
        assertEquals(
                ConflictInfo.Resolution.KEEP_LOCAL,
                trivial.getResolution(),
                "the trivial conflict is pre-resolved for the sender");

        // Applying the plan converges both files to the sender's versions (the default local
        // -wins the headless harness shares with the UI's fallback).
        sender.manager().initiateSync(plan);
        awaitFileContent(receiver.workspace(), "meaningful.txt", senderMeaningful);
        awaitFileContent(receiver.workspace(), "trivial.txt", senderTrivial);
        awaitSyncIdle();
    }

    /**
     * A receiver file another program holds locked (a read-only file here - the same IOException
     * surface) must not drop or corrupt the received bytes: they are queued as a pending write,
     * the sender withdraws its optimistic record for the path, and the user's Retry lands the
     * bytes once the lock is gone (Skip leaves them queued out and a later sync retries).
     */
    @Test
    @Timeout(240)
    void aLockedReceiverFileIsQueuedForARetryDecision() throws Exception {
        RemotePeer sender = sender();
        RemotePeer receiver = receiver();
        List<String> pendingPaths = new CopyOnWriteArrayList<>();
        receiver.manager()
                .getEventBus()
                .register(
                        event -> {
                            if (event.getType() == SyncEventType.PENDING_FILE_WRITE) {
                                pendingPaths.addAll(pendingPathsOf(event));
                            }
                        });

        byte[] first = "round one\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "round two, blocked by the lock\n".getBytes(StandardCharsets.UTF_8);
        writeFile(sender.workspace(), "open-in-editor.txt", first);
        sender.sync();
        awaitFileContent(receiver.workspace(), "open-in-editor.txt", first);
        awaitSyncIdle();

        File receiverFile = new File(receiver.workspace(), "open-in-editor.txt");
        assertTrue(receiverFile.setWritable(false), "the lock must be in place");

        writeFile(sender.workspace(), "open-in-editor.txt", second);
        sender.sync();
        await(
                "the receiver queues the unwritable file",
                () -> pendingPaths.contains("open-in-editor.txt"),
                AWAIT_MS);
        assertArrayEquals(
                first,
                Files.readAllBytes(receiverFile.toPath()),
                "nothing may overwrite the locked file's bytes");
        awaitSyncIdle();

        // Retry: the lock is gone, the queued bytes land.
        assertTrue(receiverFile.setWritable(true), "releasing the lock must succeed");
        receiver.manager().retryPendingWrites(List.of("open-in-editor.txt"));
        awaitFileContent(receiver.workspace(), "open-in-editor.txt", second);
        awaitSyncIdle();

        // Skip All: the bytes stay queued out, and because the sender's base was withdrawn the
        // next sync offers the file again - which lands it once the lock is released.
        byte[] third = "round three, skipped then retried\n".getBytes(StandardCharsets.UTF_8);
        assertTrue(receiverFile.setWritable(false), "the lock must be back");
        writeFile(sender.workspace(), "open-in-editor.txt", third);
        sender.sync();
        await(
                "the second blocked write is queued",
                () -> pendingPaths.size() >= 2,
                AWAIT_MS);
        assertArrayEquals(second, Files.readAllBytes(receiverFile.toPath()));
        receiver.manager().skipAllPendingWrites();
        awaitSyncIdle();
        assertTrue(receiverFile.setWritable(true), "releasing the lock must succeed");
        sender.sync();
        awaitFileContent(receiver.workspace(), "open-in-editor.txt", third);
        awaitSyncIdle();
    }

    // ------------------------------------------------------------------------------ helpers

    private static Set<String> pathsOf(List<com.filesync.sync.FileChangeDetector.FileInfo> files) {
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (com.filesync.sync.FileChangeDetector.FileInfo fi : files) {
            paths.add(fi.getPath());
        }
        return paths;
    }

    private static ConflictInfo conflictOf(List<ConflictInfo> conflicts, String path) {
        for (ConflictInfo conflict : conflicts) {
            if (conflict.getPath().equals(path)) {
                return conflict;
            }
        }
        throw new AssertionError("no conflict for " + path);
    }

    /** Reads the pending paths of a package-private event via its public accessor. */
    private static List<String> pendingPathsOf(com.filesync.sync.SyncEvent event) {
        try {
            Method accessor = event.getClass().getMethod("getPendingPaths");
            @SuppressWarnings("unchecked")
            List<String> paths = (List<String>) accessor.invoke(event);
            return paths;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("not a pending-write event: " + event.getType(), e);
        }
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] both = new byte[head.length + tail.length];
        System.arraycopy(head, 0, both, 0, head.length);
        System.arraycopy(tail, 0, both, head.length, tail.length);
        return both;
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

    private static boolean absent(File dir, String relativePath) {
        return !new File(dir, relativePath).exists();
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

    private static void writeFile(File dir, String relativePath, String text) throws IOException {
        writeFile(dir, relativePath, text.getBytes(StandardCharsets.UTF_8));
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
