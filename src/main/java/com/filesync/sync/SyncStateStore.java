package com.filesync.sync;

import com.filesync.delta.HashUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
 * <p>Storage follows the {@link SignatureCache} pattern: one JSON file per sync folder under the
 * shared cache directory ({@link CacheLocations#cacheDir()}, outside the sync folder so the
 * manifest scan never sees it), written atomically via temp-file + move, reloaded per instance, and
 * silently started empty when corrupt or from an incompatible schema. It is deliberately not the
 * manifest cache: that file is rewritten on every preview, and "previewed" is not "synced".
 */
public final class SyncStateStore {

    private static final int SCHEMA_VERSION = 1;
    private static final String STATE_FILE_PREFIX = "syncstate-";
    private static final String STATE_FILE_SUFFIX = ".json";

    private static final Gson GSON = new GsonBuilder().create();
    private static final TypeToken<Map<String, Entry>> ENTRY_MAP_TYPE =
            new TypeToken<Map<String, Entry>>() {};

    /** The confirmed (base) state of one path: its manifest md5 and size at last agreement. */
    public record Confirmed(String md5, long size) {}

    private static final class Entry {
        String md5;
        long size;
    }

    private final File stateFile;
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean dirty = false;

    /** Open (or start) the state store for the given sync folder. */
    public static SyncStateStore forFolder(File syncFolder) {
        String folderKey =
                HashUtil.md5Hex(syncFolder.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        return new SyncStateStore(
                new File(
                        CacheLocations.cacheDir(),
                        STATE_FILE_PREFIX + folderKey + STATE_FILE_SUFFIX));
    }

    /** Open (or start) the store backed by the given file. */
    SyncStateStore(File stateFile) {
        this.stateFile = stateFile;
        load();
    }

    /**
     * The confirmed base of {@code path}, or null when this store has no usable record for it
     * (never synced, deleted, or recorded without a hash).
     */
    public synchronized Confirmed base(String path) {
        Entry entry = entries.get(path);
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
        Entry entry = entries.get(path);
        if (entry == null) {
            entry = new Entry();
            entries.put(path, entry);
        }
        entry.md5 = md5;
        entry.size = size;
        dirty = true;
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
        if (entries.remove(path) != null) {
            dirty = true;
        }
    }

    /** Drop entries for paths outside {@code keepPaths} (e.g. files deleted locally). */
    public synchronized void prune(Set<String> keepPaths) {
        if (entries.keySet().retainAll(keepPaths)) {
            dirty = true;
        }
    }

    /** Persist to disk if anything changed since load. Best-effort: IO errors are ignored. */
    public synchronized void flush() {
        if (!dirty) {
            return;
        }
        Path path = stateFile.toPath();
        Path temp = null;
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", SCHEMA_VERSION);
            root.add("entries", GSON.toJsonTree(entries, ENTRY_MAP_TYPE.getType()));
            // Write beside the target and move it into place: a crash mid-write would otherwise
            // leave a truncated JSON that the next load discards (losing every base at once).
            temp = Files.createTempFile(parent, "syncstate", ".tmp");
            Files.writeString(temp, GSON.toJson(root));
            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems (and some network shares) cannot move atomically.
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            // Best effort: a failed write only costs re-confirming the same states next sync.
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // The move already consumed it, or nothing can be done about it.
                }
            }
        }
    }

    private void load() {
        if (!stateFile.isFile()) {
            return;
        }
        try {
            String json = Files.readString(stateFile.toPath());
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("schemaVersion")
                    || root.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                return; // incompatible or from another version: start fresh
            }
            Map<String, Entry> loaded =
                    GSON.fromJson(root.get("entries"), ENTRY_MAP_TYPE.getType());
            if (loaded != null) {
                entries.putAll(loaded);
            }
        } catch (RuntimeException | IOException e) {
            // Corrupt state: start empty rather than failing the sync. Every differing file
            // becomes a conflict once, then fresh bases are recorded.
        }
    }
}
