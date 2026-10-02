package com.filesync.sync;

import com.filesync.delta.FileSignatures;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * Sender-side persistent cache of block signatures received from the peer, keyed by the receiver
 * file state they describe (path + size + md5 from the receiver's manifest).
 *
 * <p>The signature exchange is the dominant serial-link cost of the rsync-style delta path
 * (incompressible hash bytes for every block). When the receiver's file is unchanged between two
 * syncs, its block signatures are unchanged too, so a cached copy lets the sender skip the whole
 * round trip and encode the delta directly.
 *
 * <p>Correctness: a cached entry is only returned when the receiver's current manifest describes
 * exactly the file state the signatures were computed for; any mismatch is a miss. A stale or
 * corrupt entry therefore costs at most a wasted delta that the receiver's full-file MD5
 * verification rejects before writing. On that rejection the receiver sends a BASE_STALE
 * notification naming its current file state, which is recorded here as a rejection memo: lookups
 * treat the entry as a miss and the append gate skips the same state, so the next sync exchanges
 * fresh signatures instead of repeating the rejected transfer. The memo is dropped automatically
 * once the receiver's file changes or a later signature exchange overwrites the entry.
 *
 * <p>The receiver's lastModified is deliberately not part of the identity: the manifest md5 already
 * proves the content, and a timestamp change with the same hash means the bytes are identical — the
 * cached signatures still describe them.
 *
 * <p>Cache files live under the shared cache directory ({@link CacheLocations#cacheDir()}, outside
 * the sync folder so the manifest scan never sees them), one JSON file per sync folder. Storage is
 * the {@link AbstractJsonStore} temp-file + move scheme shared with {@link SyncStateStore}.
 */
public final class SignatureCache extends AbstractJsonStore<SignatureCache.CacheEntry> {

    private static final int SCHEMA_VERSION = 1;
    private static final String CACHE_FILE_PREFIX = "sigcache-";
    private static final String CACHE_FILE_SUFFIX = ".json";

    private static final TypeToken<Map<String, CacheEntry>> ENTRY_MAP_TYPE =
            new TypeToken<Map<String, CacheEntry>>() {};

    /**
     * Serialized receiver-file identity plus the Base64 block-signature payload it describes. A
     * {@code rejected} entry (a BASE_STALE notification arrived for exactly this identity) carries
     * no payload when it was created by {@link #markRejected} rather than {@link #store}.
     */
    /** Package-private so it can name the {@link AbstractJsonStore} supertype's type argument. */
    static final class CacheEntry {
        long remoteSize;
        String remoteMd5;
        String signaturesBase64;
        boolean rejected;
    }

    /** Open (or start) the cache for the given sync folder. */
    public static SignatureCache forFolder(File syncFolder) {
        return new SignatureCache(cacheFileFor(syncFolder, CACHE_FILE_PREFIX, CACHE_FILE_SUFFIX));
    }

    /** Open (or start) the cache backed by the given file. */
    SignatureCache(File cacheFile) {
        super(cacheFile, ENTRY_MAP_TYPE, SCHEMA_VERSION, "signatures");
    }

    /**
     * Return the cached signatures for {@code path} when they provably describe the receiver file
     * recorded in {@code remote}, or null on any mismatch (including a null md5, which cannot be
     * validated). A rejected entry is a miss: the receiver already refused a transfer against this
     * exact state, so fresh signatures must be exchanged.
     */
    public synchronized FileSignatures lookup(String path, FileChangeDetector.FileInfo remote) {
        if (remote == null || remote.getMd5() == null || remote.getMd5().isEmpty()) {
            return null;
        }
        CacheEntry entry = entries().get(path);
        if (entry == null || entry.signaturesBase64 == null || entry.rejected) {
            return null;
        }
        if (!identityMatches(entry, remote.getSize(), remote.getMd5())) {
            return null;
        }
        try {
            return FileSignatures.fromBytes(Base64.getDecoder().decode(entry.signaturesBase64));
        } catch (IOException | IllegalArgumentException e) {
            return null; // corrupt payload: treat as a miss
        }
    }

    /** Record the signatures exchanged for {@code path} alongside the receiver state they match. */
    public synchronized void store(
            String path, FileChangeDetector.FileInfo remote, FileSignatures signatures)
            throws IOException {
        if (remote == null || remote.getMd5() == null || remote.getMd5().isEmpty()) {
            return; // without an md5 the entry could never be validated later
        }
        CacheEntry entry = new CacheEntry();
        entry.remoteSize = remote.getSize();
        entry.remoteMd5 = remote.getMd5();
        entry.signaturesBase64 = Base64.getEncoder().encodeToString(signatures.toBytes());
        entries().put(path, entry);
        markDirty();
    }

    /**
     * Record that the receiver rejected a delta/append against the named receiver state (its
     * current file is not what the sender diffed against). Lookups and the append gate treat the
     * state as unusable until the receiver's file changes or a later successful signature exchange
     * overwrites the entry.
     */
    public synchronized void markRejected(String path, long remoteSize, String remoteMd5) {
        CacheEntry entry = entries().get(path);
        if (entry == null) {
            entry = new CacheEntry();
            entries().put(path, entry);
        }
        entry.remoteSize = remoteSize;
        entry.remoteMd5 = remoteMd5;
        entry.rejected = true;
        markDirty();
    }

    /**
     * Whether the receiver rejected a transfer against exactly the state {@code remote} describes.
     */
    public synchronized boolean isRejected(String path, FileChangeDetector.FileInfo remote) {
        if (remote == null || remote.getMd5() == null || remote.getMd5().isEmpty()) {
            return false;
        }
        CacheEntry entry = entries().get(path);
        if (entry == null || !entry.rejected) {
            return false;
        }
        return identityMatches(entry, remote.getSize(), remote.getMd5());
    }

    /**
     * Null-safe identity comparison so a legacy entry without an md5 never validates. The size and
     * the content hash both have to match: the entry describes exactly one receiver file state. The
     * lastModified is not consulted — identical bytes with a newer timestamp are still the same
     * state as far as block signatures are concerned.
     */
    private static boolean identityMatches(CacheEntry entry, long remoteSize, String remoteMd5) {
        return entry.remoteMd5 != null
                && entry.remoteSize == remoteSize
                && entry.remoteMd5.equals(remoteMd5);
    }
}
