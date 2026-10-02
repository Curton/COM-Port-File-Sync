package com.filesync.sync;

import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.util.Map;

/**
 * The last-successfully-synced ("base") state of each file in a sync folder: the manifest md5 and
 * size a path had when both sides last agreed on its content.
 *
 * <p>Conflict arbitration compares three states per path: local (L), remote (R) and this base (B).
 * R == B means only the sender changed the file — a normal transfer; anything else (with L != R)
 * means the receiver changed it too, which needs the conflict dialog. Without a base the history is
 * unknown and the path is treated conservatively as a conflict, which is what a first pairing (or a
 * wiped cache) degrades to: everything that differs goes through the dialog once, then the session
 * records fresh bases and subsequent syncs arbitrate silently.
 *
 * <p>Bases advance only on confirmed success. The receiver confirms a path as it verifies and
 * writes it; the sender confirms optimistically after the session completes and then subtracts the
 * paths the receiver reports as failed ({@code CMD_WRITE_FAILURES}), so a locked or corrupt write
 * never masquerades as synced content. Salvage paths (interrupted transfers that leave a prefix on
 * disk) must not confirm: a partial file is not a synced file.
 *
 * <p>Entries carry the manifest-normalized md5. A path without a hash (fast mode leaves binaries
 * unhashed) is never recorded — arbitration falls back to timestamps there, and a base that cannot
 * be compared against a manifest would be dead weight.
 *
 * <p>Storage is the {@link AbstractJsonStore} scheme shared with {@link SignatureCache}: one JSON
 * file per sync folder under the shared cache directory ({@link CacheLocations#cacheDir()}, outside
 * the sync folder so the manifest scan never sees it), written atomically via temp-file + move,
 * reloaded per instance, and silently started empty when corrupt or from an incompatible schema. It
 * is deliberately not the manifest cache: that file is rewritten on every preview, and "previewed"
 * is not "synced".
 */
public final class SyncStateStore extends AbstractJsonStore<SyncStateStore.Entry> {

    private static final int SCHEMA_VERSION = 1;
    private static final String STATE_FILE_PREFIX = "syncstate-";
    private static final String STATE_FILE_SUFFIX = ".json";

    private static final TypeToken<Map<String, Entry>> ENTRY_MAP_TYPE =
            new TypeToken<Map<String, Entry>>() {};

    /** The confirmed (base) state of one path: its manifest md5 and size at last agreement. */
    public record Confirmed(String md5, long size) {}

    /** Package-private so it can name the {@link AbstractJsonStore} supertype's type argument. */
    static final class Entry {
        String md5;
        long size;
    }

    /** Open (or start) the state store for the given sync folder. */
    public static SyncStateStore forFolder(File syncFolder) {
        return new SyncStateStore(cacheFileFor(syncFolder, STATE_FILE_PREFIX, STATE_FILE_SUFFIX));
    }

    /** Open (or start) the store backed by the given file. */
    SyncStateStore(File stateFile) {
        super(stateFile, ENTRY_MAP_TYPE, SCHEMA_VERSION, "syncstate");
    }

    /**
     * The confirmed base of {@code path}, or null when this store has no usable record for it
     * (never synced, deleted, or recorded without a hash).
     */
    public synchronized Confirmed base(String path) {
        Entry entry = entries().get(path);
        if (entry == null || entry.md5 == null || entry.md5.isEmpty()) {
            return null;
        }
        return new Confirmed(entry.md5, entry.size);
    }

    /**
     * Record the confirmed state of {@code path}. A null or empty md5 is ignored: without a hash
     * the entry could never be compared against a manifest.
     */
    public synchronized void confirm(String path, String md5, long size) {
        if (md5 == null || md5.isEmpty()) {
            return;
        }
        Entry entry = entries().get(path);
        if (entry == null) {
            entry = new Entry();
            entries().put(path, entry);
        }
        entry.md5 = md5;
        entry.size = size;
        markDirty();
    }

    /** Record confirmed states for many paths at once (see {@link #confirm}). */
    public synchronized void confirmAll(Map<String, Confirmed> confirmations) {
        if (confirmations == null) {
            return;
        }
        for (Map.Entry<String, Confirmed> c : confirmations.entrySet()) {
            Confirmed confirmed = c.getValue();
            if (confirmed != null) {
                confirm(c.getKey(), confirmed.md5(), confirmed.size());
            }
        }
    }

    /** Forget the record for {@code path} (e.g. the file was deleted on this side). */
    public synchronized void remove(String path) {
        if (entries().remove(path) != null) {
            markDirty();
        }
    }
}
