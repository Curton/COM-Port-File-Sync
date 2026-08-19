package com.filesync.sync;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Holds received files that could not be written to disk because the target file is locked by
 * another program (e.g. an open Word document). The UI is notified through a
 * {@link SyncEvent.PendingWriteEvent} so the user can decide between retry and skip; entries are
 * held in memory indefinitely until the user retries successfully or skips them. Nothing is
 * silently dropped.
 */
public class PendingFileWriteService {

    /** Above this many pending entries an error is logged to prompt the user (entries are kept). */
    private static final int MAX_PENDING_ENTRIES = 100;

    private final SyncEventBus eventBus;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "PendingFileWrite");
                        t.setDaemon(true);
                        return t;
                    });
    private final Map<String, PendingEntry> pending = new ConcurrentHashMap<>();

    public PendingFileWriteService(SyncEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /** Payload of a received file that could not be written yet. */
    public record PendingEntry(
            File baseDir,
            String relativePath,
            byte[] data,
            long lastModified,
            String errorMessage) {}

    /**
     * Queue a received file whose write failed. If the same path is already pending, the entry is
     * replaced with the newer version so a stale payload is never written over newer data.
     */
    public void enqueue(
            File baseDir, String relativePath, byte[] data, long lastModified, String errorMessage) {
        PendingEntry entry =
                new PendingEntry(baseDir, relativePath, data, lastModified, errorMessage);
        boolean replaced = pending.put(relativePath, entry) != null;
        if (pending.size() > MAX_PENDING_ENTRIES) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            pending.size()
                                    + " files are waiting to be written; please close the programs"
                                    + " that are using them (latest: "
                                    + relativePath
                                    + ")"));
        }
        eventBus.post(
                new SyncEvent.LogEvent(
                        "File is in use by another program, waiting for your decision: "
                                + relativePath
                                + (replaced ? " (newer version queued)" : "")
                                + " ["
                                + errorMessage
                                + "]"));
        postPendingEvent();
    }

    /**
     * Called when a batch or single-file receive writes this path successfully, clearing any stale
     * pending entry so an older retry cannot overwrite the newer file.
     */
    public void markWritten(String relativePath) {
        if (pending.remove(relativePath) != null) {
            eventBus.post(
                    new SyncEvent.LogEvent(
                            "File written successfully, pending entry cleared: " + relativePath));
            postPendingEvent();
        }
    }

    /** Retry writing the given paths, triggered only by the user's "Retry" action. */
    public void retry(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        executor.submit(() -> retryInternal(paths));
    }

    private void retryInternal(List<String> paths) {
        for (String path : paths) {
            PendingEntry entry = pending.get(path);
            if (entry == null) {
                continue; // already resolved by a concurrent write
            }
            try {
                writeEntry(entry);
                if (pending.remove(path, entry)) {
                    eventBus.post(new SyncEvent.LogEvent("File written after retry: " + path));
                } else {
                    // A newer version was queued while we were writing; retry it as well.
                    executor.submit(() -> retryInternal(List.of(path)));
                }
            } catch (IOException e) {
                // Keep the entry; the dialog stays open so the user can retry or skip.
                eventBus.post(
                        new SyncEvent.LogEvent(
                                "Retry failed, file still in use: "
                                        + path
                                        + " ("
                                        + e.getMessage()
                                        + ")"));
            }
        }
        postPendingEvent();
    }

    /** Drop the given paths from the queue (user chose "Skip"). */
    public void skip(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        boolean removed = false;
        for (String path : paths) {
            if (pending.remove(path) != null) {
                eventBus.post(
                        new SyncEvent.LogEvent(
                                "Skipped file: " + path + " (will be re-synced on next sync)"));
                removed = true;
            }
        }
        if (removed) {
            postPendingEvent();
        }
    }

    /** Drop every pending entry at once (user chose "Skip All"). */
    public void skipAll() {
        if (pending.isEmpty()) {
            return;
        }
        int count = pending.size();
        pending.clear();
        eventBus.post(
                new SyncEvent.LogEvent(
                        "Skipped "
                                + count
                                + " file(s) (will be re-synced on next sync)"));
        postPendingEvent();
    }

    /** Paths currently waiting for a user decision, sorted for a stable dialog list. */
    public List<String> getPendingPaths() {
        List<String> paths = new ArrayList<>(pending.keySet());
        Collections.sort(paths);
        return paths;
    }

    public int getPendingCount() {
        return pending.size();
    }

    private void postPendingEvent() {
        eventBus.post(new SyncEvent.PendingWriteEvent(getPendingPaths()));
    }

    private static void writeEntry(PendingEntry entry) throws IOException {
        File targetFile = new File(entry.baseDir(), entry.relativePath());
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create directory: " + parentDir);
        }
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(entry.data());
        }
        // Preserve sender timestamp so subsequent manifest comparisons match
        if (entry.lastModified() > 0) {
            targetFile.setLastModified(entry.lastModified());
        }
    }
}
