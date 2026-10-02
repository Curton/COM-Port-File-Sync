package com.filesync.sync;

import com.filesync.delta.HashUtil;
import com.filesync.util.IoUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared persistence skeleton for the per-folder JSON stores ({@link SignatureCache}, {@link
 * SyncStateStore}): a {@code schemaVersion}-tagged {@code {"entries": {...}}} document, one file
 * per sync folder under the shared cache directory ({@link CacheLocations#cacheDir()}, outside the
 * sync folder so the manifest scan never sees it), written atomically via temp-file + move,
 * reloaded per instance, and silently started empty when corrupt or from an incompatible schema.
 *
 * <p>Subclasses supply the entry type and the domain API. Mutations go through the live {@link
 * #entries()} map plus {@link #markDirty()}; {@link #flush()} is best-effort and never throws.
 *
 * @param <E> the per-path entry type persisted in the {@code entries} object
 */
abstract class AbstractJsonStore<E> {

    private static final Gson GSON = new GsonBuilder().create();

    private final File file;
    private final TypeToken<Map<String, E>> entryMapType;
    private final int schemaVersion;
    private final String tempPrefix;
    private final Map<String, E> entries = new HashMap<>();
    private boolean dirty = false;

    AbstractJsonStore(
            File file,
            TypeToken<Map<String, E>> entryMapType,
            int schemaVersion,
            String tempPrefix) {
        this.file = file;
        this.entryMapType = entryMapType;
        this.schemaVersion = schemaVersion;
        this.tempPrefix = tempPrefix;
        load();
    }

    /**
     * The store file for {@code syncFolder} under the shared cache directory, keyed by the MD5 of
     * the folder's absolute path.
     */
    static File cacheFileFor(File syncFolder, String prefix, String suffix) {
        String folderKey =
                HashUtil.md5Hex(syncFolder.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        return new File(CacheLocations.cacheDir(), prefix + folderKey + suffix);
    }

    /** The live entry map. Mutate only from a synchronized subclass method. */
    protected final Map<String, E> entries() {
        return entries;
    }

    /** Flag the store as changed so the next {@link #flush} persists it. */
    protected final void markDirty() {
        dirty = true;
    }

    /** Drop entries for paths outside {@code keepPaths} (e.g. files deleted). */
    public synchronized void prune(Set<String> keepPaths) {
        if (entries.keySet().retainAll(keepPaths)) {
            dirty = true;
        }
    }

    /**
     * Persist to disk if anything changed since load. Best-effort: IO errors are ignored — for the
     * signature cache a failed write only costs a signature exchange next time, for the base state
     * a failed write only costs re-confirming the same states next sync.
     */
    public synchronized void flush() {
        if (!dirty) {
            return;
        }
        try {
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", schemaVersion);
            root.add("entries", GSON.toJsonTree(entries, entryMapType.getType()));
            // Written beside the target and moved into place by writeStringAtomically: a crash
            // mid-write would otherwise leave a truncated JSON that the next load discards.
            IoUtil.writeStringAtomically(file.toPath(), GSON.toJson(root), tempPrefix);
            dirty = false;
        } catch (IOException e) {
            // Best effort: a failed write only costs re-doing the work next sync.
        }
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        try {
            String json = Files.readString(file.toPath());
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("schemaVersion")
                    || root.get("schemaVersion").getAsInt() != schemaVersion) {
                return; // incompatible or from another version: start fresh
            }
            Map<String, E> loaded = GSON.fromJson(root.get("entries"), entryMapType.getType());
            if (loaded != null) {
                entries.putAll(loaded);
            }
        } catch (RuntimeException | IOException e) {
            // Corrupt store: start empty rather than failing the sync.
        }
    }
}
