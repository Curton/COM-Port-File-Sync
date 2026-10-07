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
 * <p>Alongside the base, each entry can carry a <em>delivered</em> state: the content this side
 * last pushed to the peer, recorded when the sender's own transfer layer reported that transfer
 * complete. It is deliberately weaker than a base — a delivered transfer is not proof the peer
 * still holds the bytes (a later write on the peer may have failed, and the sender only learns that
 * from the end-of-session failure report, which a torn-down session never delivers) — so it never
 * advances the base. It exists so arbitration can tell "the peer holds content this side pushed"
 * from "the peer changed the file itself": a session that delivers content but dies before its
 * end-of-session bookkeeping leaves the base stale while the peer holds the pushed version, and
 * without this marker the next preview reads that as receiver-side divergence and raises a conflict
 * for a file only the sender ever modified. A hand edit on the peer always moves R away from the
 * delivered md5, so it still surfaces as a conflict.
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

        /**
         * The content this side last pushed to the peer (manifest md5 + size), or null when the
         * last delivery is not worth remembering (nothing pushed yet, withdrawn as a write failure,
         * or superseded by a confirmed base with the same hash — see {@link
         * SyncStateStore#recordDelivered}).
         */
        String deliveredMd5;

        long deliveredSize;
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
     * the entry could never be compared against a manifest. Confirming the content this side last
     * delivered also drops that path's delivered marker — the base is the stronger claim.
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
        if (md5.equals(entry.deliveredMd5)) {
            // The base now carries the truth this marker stood in for, so it is dead weight.
            entry.deliveredMd5 = null;
            entry.deliveredSize = 0L;
        }
        markDirty();
    }

    /**
     * The content this side last pushed to the peer for {@code path}, or null when nothing is
     * remembered (never pushed here, withdrawn as a write failure, or already confirmed as the
     * base). Only the sender records this; a delivered transfer is not a confirmed one, so
     * arbitration treats it as evidence about what the peer holds, never as an agreement.
     */
    public synchronized Confirmed delivered(String path) {
        Entry entry = entries().get(path);
        if (entry == null || entry.deliveredMd5 == null || entry.deliveredMd5.isEmpty()) {
            return null;
        }
        return new Confirmed(entry.deliveredMd5, entry.deliveredSize);
    }

    /**
     * Remember that this side pushed {@code md5} to the peer for {@code path} and the transfer
     * reported success. A null or empty md5 is ignored for the same reason as {@link #confirm}: an
     * entry no manifest comparison can use is dead weight. Recording an md5 the base already holds
     * is skipped — the base is the stronger claim and needs no marker.
     */
    public synchronized void recordDelivered(String path, String md5, long size) {
        if (md5 == null || md5.isEmpty()) {
            return;
        }
        Entry entry = entries().get(path);
        if (entry != null && md5.equals(entry.md5)) {
            return; // already the confirmed base
        }
        if (entry == null) {
            entry = new Entry();
            entries().put(path, entry);
        }
        entry.deliveredMd5 = md5;
        entry.deliveredSize = size;
        markDirty();
    }

    /**
     * Forget the delivered state of {@code path}: the peer reported it could not write the pushed
     * content, so what it holds is provably not this side's delivery and the marker must not
     * suppress a future conflict.
     */
    public synchronized void forgetDelivered(String path) {
        Entry entry = entries().get(path);
        if (entry == null || entry.deliveredMd5 == null) {
            return;
        }
        entry.deliveredMd5 = null;
        entry.deliveredSize = 0L;
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
