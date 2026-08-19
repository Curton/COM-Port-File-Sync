package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PendingFileWriteService}: received files whose write failed (target locked by
 * another program) wait in memory for a user decision between retry and skip.
 */
class PendingFileWriteServiceTest {

    @TempDir Path tempDir;

    private SimpleSyncEventBus eventBus;
    private PendingFileWriteService service;
    private final List<SyncEvent.LogEvent> logEvents = new CopyOnWriteArrayList<>();
    private final List<SyncEvent.PendingWriteEvent> pendingEvents = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        eventBus = new SimpleSyncEventBus();
        logEvents.clear();
        pendingEvents.clear();
        eventBus.register(
                event -> {
                    if (event instanceof SyncEvent.LogEvent logEvent) {
                        logEvents.add(logEvent);
                    } else if (event instanceof SyncEvent.PendingWriteEvent pendingEvent) {
                        pendingEvents.add(pendingEvent);
                    }
                });
        service = new PendingFileWriteService(eventBus);
    }

    @Test
    void enqueue_postsPendingWriteEventAndLog() {
        service.enqueue(tempDir.toFile(), "locked.txt", "data".getBytes(StandardCharsets.UTF_8), 0L, "being used");

        assertEquals(List.of("locked.txt"), service.getPendingPaths());
        assertEquals(List.of("locked.txt"), lastPendingPaths(), "enqueue must notify the UI");
        assertTrue(
                logEvents.stream()
                        .anyMatch(e -> e.getMessage().contains("waiting for your decision")),
                "enqueue must log that the file is waiting for a user decision");
    }

    @Test
    void retry_failureKeepsEntry_thenRetrySucceedsAfterBlockerRemoved() throws Exception {
        File baseDir = tempDir.toFile();
        File target = new File(baseDir, "a.txt");
        target.mkdirs(); // lock the write: FileOutputStream cannot open a directory

        service.enqueue(baseDir, "a.txt", "hello".getBytes(StandardCharsets.UTF_8), 0L, "locked");
        service.retry(List.of("a.txt"));

        waitUntil(() -> logContains("Retry failed"), Duration.ofSeconds(5));
        assertEquals(
                List.of("a.txt"),
                service.getPendingPaths(),
                "A failed retry must keep the entry for another attempt");

        target.delete(); // user released the lock, e.g. closed the Office document
        service.retry(List.of("a.txt"));

        waitUntil(() -> logContains("File written after retry"), Duration.ofSeconds(5));
        assertTrue(service.getPendingPaths().isEmpty(), "Entry must be removed after success");
        assertEquals("hello", Files.readString(target.toPath()), "Content must be written on retry");
        assertTrue(logContains("File written after retry: a.txt"));
    }

    @Test
    void retry_writesFileAndRestoresSenderLastModified() throws Exception {
        long senderModified = 1_600_000_000_000L;
        service.enqueue(tempDir.toFile(), "a.txt", "data".getBytes(StandardCharsets.UTF_8), senderModified, "locked");

        service.retry(List.of("a.txt"));

        // Wait for the sender timestamp to be restored, not just for the file to exist: the write
        // and setLastModified happen in sequence, so checking only existence can race and observe
        // the write time instead of the sender's timestamp.
        waitUntil(
                () -> {
                    File f = new File(tempDir.toFile(), "a.txt");
                    return f.exists() && f.lastModified() == senderModified;
                },
                Duration.ofSeconds(5));
        assertEquals(
                senderModified,
                new File(tempDir.toFile(), "a.txt").lastModified(),
                "Sender timestamp must be restored so the next manifest comparison matches");
    }

    @Test
    void samePathEnqueue_replacesData_newerVersionIsWritten() throws Exception {
        service.enqueue(tempDir.toFile(), "a.txt", "v1".getBytes(StandardCharsets.UTF_8), 0L, "locked");
        service.enqueue(tempDir.toFile(), "a.txt", "v2".getBytes(StandardCharsets.UTF_8), 0L, "locked");

        assertEquals(1, service.getPendingPaths().size(), "Same path must hold a single entry");
        service.retry(List.of("a.txt"));

        waitUntil(() -> new File(tempDir.toFile(), "a.txt").exists(), Duration.ofSeconds(5));
        assertEquals("v2", Files.readString(tempDir.resolve("a.txt")), "Newer data must win");
    }

    @Test
    void markWritten_clearsPendingEntry_soStaleRetryCannotOverwriteNewerFile() throws Exception {
        File target = new File(tempDir.toFile(), "a.txt");
        Files.writeString(target.toPath(), "newer version");
        // v1 was queued while locked; then a newer version arrived and was written by a batch.
        service.enqueue(tempDir.toFile(), "a.txt", "stale v1".getBytes(StandardCharsets.UTF_8), 0L, "locked");

        service.markWritten("a.txt");
        assertTrue(service.getPendingPaths().isEmpty(), "markWritten must clear the stale entry");

        service.retry(List.of("a.txt"));
        // The retry completes (posting a refresh event) without touching the file.
        waitUntil(() -> pendingEvents.size() >= 3, Duration.ofSeconds(5));
        // A retry of a cleared entry must not write stale data over the newer file.
        assertEquals("newer version", Files.readString(target.toPath()));
    }

    @Test
    void skip_removesOnlyRequestedPaths() {
        service.enqueue(tempDir.toFile(), "a.txt", new byte[0], 0L, "locked");
        service.enqueue(tempDir.toFile(), "b.txt", new byte[0], 0L, "locked");
        service.enqueue(tempDir.toFile(), "c.txt", new byte[0], 0L, "locked");

        service.skip(List.of("b.txt"));

        assertEquals(
                List.of("a.txt", "c.txt"),
                service.getPendingPaths(),
                "Only the skipped path must be removed");
        assertTrue(logContains("Skipped file: b.txt"));
        assertTrue(logContains("will be re-synced on next sync"));
    }

    @Test
    void skipAll_clearsEveryPendingEntryAndPostsEmptyEvent() {
        service.enqueue(tempDir.toFile(), "a.txt", new byte[0], 0L, "locked");
        service.enqueue(tempDir.toFile(), "b.txt", new byte[0], 0L, "locked");

        service.skipAll();

        assertTrue(service.getPendingPaths().isEmpty(), "skipAll must clear every entry");
        assertTrue(lastPendingPaths().isEmpty(), "UI must be notified that nothing is pending");
        assertTrue(logContains("Skipped 2 file(s)"));
    }

    private boolean logContains(String fragment) {
        return logEvents.stream().anyMatch(e -> e.getMessage().contains(fragment));
    }

    private List<String> lastPendingPaths() {
        if (pendingEvents.isEmpty()) {
            return List.of();
        }
        return pendingEvents.get(pendingEvents.size() - 1).getPendingPaths();
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for condition");
    }
}
