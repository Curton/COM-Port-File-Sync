package com.filesync.sync;

import com.filesync.delta.FileSignatures;
import com.filesync.delta.HashUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Sender-side persistent cache of block signatures received from the peer, keyed by the receiver
 * file state they describe (path + size + lastModified + md5 from the receiver's manifest).
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
 * once the receiver's file visibly changes or a later signature exchange overwrites the entry.
 *
 * <p>Cache files live under the shared cache directory ({@link CacheLocations#cacheDir()}, outside
 * the sync folder so the manifest scan never sees them), one JSON file per sync folder.
 */
public final class SignatureCache {

    private static final int SCHEMA_VERSION = 1;
    private static final String CACHE_FILE_PREFIX = "sigcache-";
    private static final String CACHE_FILE_SUFFIX = ".json";

    private static final Gson GSON = new GsonBuilder().create();
    private static final TypeToken<Map<String, CacheEntry>> ENTRY_MAP_TYPE =
            new TypeToken<Map<String, CacheEntry>>() {};

    /**
     * Serialized receiver-file identity plus the Base64 block-signature payload it describes. A
     * {@code rejected} entry (a BASE_STALE notification arrived for exactly this identity) carries
     * no payload when it was created by {@link #markRejected} rather than {@link #store}.
     */
    private static final class CacheEntry {
        long remoteSize;
        long remoteLastModified;
        String remoteMd5;
        String signaturesBase64;
        boolean rejected;
    }

    private final File cacheFile;
    private final Map<String, CacheEntry> entries = new HashMap<>();
    private boolean dirty = false;

    /** Open (or start) the cache for the given sync folder. */
    public static SignatureCache forFolder(File syncFolder) {
        String folderKey =
                HashUtil.md5Hex(syncFolder.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        return new SignatureCache(
                new File(
                        CacheLocations.cacheDir(),
                        CACHE_FILE_PREFIX + folderKey + CACHE_FILE_SUFFIX));
    }

    /** Open (or start) the cache backed by the given file. */
    SignatureCache(File cacheFile) {
        this.cacheFile = cacheFile;
        load();
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
        CacheEntry entry = entries.get(path);
        if (entry == null || entry.signaturesBase64 == null || entry.rejected) {
            return null;
        }
        if (!identityMatches(entry, remote.getSize(), remote.getLastModified(), remote.getMd5())) {
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
        entry.remoteLastModified = remote.getLastModified();
        entry.remoteMd5 = remote.getMd5();
        entry.signaturesBase64 = Base64.getEncoder().encodeToString(signatures.toBytes());
        entries.put(path, entry);
        dirty = true;
    }

    /** Drop entries for paths outside {@code keepPaths} (e.g. files deleted on the receiver). */
    public synchronized void prune(Set<String> keepPaths) {
        if (entries.keySet().retainAll(keepPaths)) {
            dirty = true;
        }
    }

    /**
     * Record that the receiver rejected a delta/append against the named receiver state (its
     * current file is not what the sender diffed against). Lookups and the append gate treat the
     * state as unusable until the receiver's file visibly changes or a later successful signature
     * exchange overwrites the entry.
     */
    public synchronized void markRejected(
            String path, long remoteSize, long remoteLastModified, String remoteMd5) {
        CacheEntry entry = entries.get(path);
        if (entry == null) {
            entry = new CacheEntry();
            entries.put(path, entry);
        }
        entry.remoteSize = remoteSize;
        entry.remoteLastModified = remoteLastModified;
        entry.remoteMd5 = remoteMd5;
        entry.rejected = true;
        dirty = true;
    }

    /**
     * Whether the receiver rejected a transfer against exactly the state {@code remote} describes.
     */
    public synchronized boolean isRejected(String path, FileChangeDetector.FileInfo remote) {
        if (remote == null || remote.getMd5() == null || remote.getMd5().isEmpty()) {
            return false;
        }
        CacheEntry entry = entries.get(path);
        if (entry == null || !entry.rejected) {
            return false;
        }
        return identityMatches(entry, remote.getSize(), remote.getLastModified(), remote.getMd5());
    }

    /** Persist to disk if anything changed since load. Best-effort: IO errors are ignored. */
    public synchronized void flush() {
        if (!dirty) {
            return;
        }
        try {
            File parent = cacheFile.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", SCHEMA_VERSION);
            root.add("entries", GSON.toJsonTree(entries, ENTRY_MAP_TYPE.getType()));
            Files.writeString(cacheFile.toPath(), GSON.toJson(root));
            dirty = false;
        } catch (IOException e) {
            // Best effort: a failed cache write only costs a signature exchange next time.
        }
    }

    private void load() {
        if (!cacheFile.isFile()) {
            return;
        }
        try {
            String json = Files.readString(cacheFile.toPath());
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("schemaVersion")
                    || root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                return; // incompatible or from another version: start fresh
            }
            Map<String, CacheEntry> loaded =
                    GSON.fromJson(root.get("entries"), ENTRY_MAP_TYPE.getType());
            if (loaded != null) {
                entries.putAll(loaded);
            }
        } catch (RuntimeException | IOException e) {
            // Corrupt cache: start empty rather than failing the sync.
        }
    }

    /**
     * Null-safe identity comparison so a legacy entry without an md5 never validates. All three
     * fields must match: the entry describes exactly one receiver file state.
     */
    private static boolean identityMatches(
            CacheEntry entry, long remoteSize, long remoteLastModified, String remoteMd5) {
        return entry.remoteMd5 != null
                && entry.remoteSize == remoteSize
                && entry.remoteLastModified == remoteLastModified
                && entry.remoteMd5.equals(remoteMd5);
    }
}
