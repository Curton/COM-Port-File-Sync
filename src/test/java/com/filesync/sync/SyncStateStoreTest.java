package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SyncStateStore}: confirm/lookup round trips, the no-hash rule, persistence
 * across instances, pruning and removal, and graceful handling of corrupt or incompatible state
 * files.
 */
class SyncStateStoreTest {

    @TempDir Path tempDir;

    private File stateFile() {
        return tempDir.resolve("syncstate-test.json").toFile();
    }

    @Test
    void confirmThenBaseReturnsConfirmedState() {
        SyncStateStore store = new SyncStateStore(stateFile());
        assertNull(store.base("file.txt"), "no record before any confirm");

        store.confirm("file.txt", "md5-a", 42L);

        SyncStateStore.Confirmed base = store.base("file.txt");
        assertNotNull(base, "confirmed path must have a base");
        assertEquals("md5-a", base.md5());
        assertEquals(42L, base.size());
    }

    @Test
    void confirmWithoutMd5IsIgnored() throws IOException {
        SyncStateStore store = new SyncStateStore(stateFile());
        store.confirm("file.txt", null, 42L);
        store.confirm("other.txt", "", 7L);
        store.flush();

        assertNull(store.base("file.txt"), "null md5 must not be recorded");
        assertNull(store.base("other.txt"), "empty md5 must not be recorded");
        assertFalse(stateFile().exists(), "nothing to persist when no entry is recordable");
    }

    @Test
    void confirmOverwritesPreviousBase() {
        SyncStateStore store = new SyncStateStore(stateFile());
        store.confirm("file.txt", "md5-old", 10L);
        store.confirm("file.txt", "md5-new", 20L);

        SyncStateStore.Confirmed base = store.base("file.txt");
        assertEquals("md5-new", base.md5(), "the latest confirmation wins");
        assertEquals(20L, base.size());
    }

    @Test
    void confirmAllRecordsEveryEntry() {
        SyncStateStore store = new SyncStateStore(stateFile());
        Map<String, SyncStateStore.Confirmed> batch = new LinkedHashMap<>();
        batch.put("a.txt", new SyncStateStore.Confirmed("md5-a", 1L));
        batch.put("b.txt", new SyncStateStore.Confirmed("md5-b", 2L));
        batch.put("c.txt", null);

        store.confirmAll(batch);

        assertNotNull(store.base("a.txt"));
        assertNotNull(store.base("b.txt"));
        assertNull(store.base("c.txt"), "a null Confirmed value records nothing");
    }

    @Test
    void persistsAcrossInstances() throws IOException {
        SyncStateStore first = new SyncStateStore(stateFile());
        first.confirm("file.txt", "md5-a", 42L);
        first.flush();
        assertTrue(stateFile().exists(), "flush must write the state file");

        SyncStateStore second = new SyncStateStore(stateFile());
        SyncStateStore.Confirmed base = second.base("file.txt");
        assertNotNull(base, "confirmed state must survive a reload");
        assertEquals("md5-a", base.md5());
        assertEquals(42L, base.size());
    }

    @Test
    void unflushedChangesAreNotVisibleToNewInstance() throws IOException {
        SyncStateStore first = new SyncStateStore(stateFile());
        first.confirm("file.txt", "md5-a", 42L);
        // No flush: a concurrently opened instance must not see the in-memory-only entry.
        assertNull(new SyncStateStore(stateFile()).base("file.txt"));
    }

    @Test
    void pruneDropsOtherPaths() {
        SyncStateStore store = new SyncStateStore(stateFile());
        store.confirm("keep.txt", "md5-keep", 1L);
        store.confirm("gone.txt", "md5-gone", 2L);

        store.prune(java.util.Set.of("keep.txt"));

        assertNotNull(store.base("keep.txt"));
        assertNull(store.base("gone.txt"), "pruned path must not have a base");
    }

    @Test
    void removeDropsSinglePath() {
        SyncStateStore store = new SyncStateStore(stateFile());
        store.confirm("file.txt", "md5-a", 1L);
        store.confirm("other.txt", "md5-b", 2L);

        store.remove("file.txt");

        assertNull(store.base("file.txt"));
        assertNotNull(store.base("other.txt"));
    }

    @Test
    void corruptStateFileStartsEmpty() throws IOException {
        Files.writeString(stateFile().toPath(), "not valid json {{{");
        SyncStateStore store = new SyncStateStore(stateFile());
        assertNull(store.base("file.txt"), "corrupt state must start empty");
        // The store remains usable after starting empty.
        store.confirm("file.txt", "md5-a", 1L);
        store.flush();
        assertEquals("md5-a", new SyncStateStore(stateFile()).base("file.txt").md5());
    }

    @Test
    void incompatibleSchemaVersionStartsEmpty() throws IOException {
        SyncStateStore store = new SyncStateStore(stateFile());
        store.confirm("file.txt", "md5-a", 1L);
        store.flush();
        // Simulate a future schema by rewriting the version field.
        String json = Files.readString(stateFile().toPath());
        Files.writeString(
                stateFile().toPath(), json.replace("\"schemaVersion\":1", "\"schemaVersion\":99"));

        SyncStateStore future = new SyncStateStore(stateFile());
        assertNull(future.base("file.txt"), "state from another schema must be discarded");
    }

    @Test
    void forFolderKeysDistinctFoldersSeparately(@TempDir Path otherDir) {
        CacheLocations.setOverrideForTest(tempDir.toFile());
        try {
            File folderA = tempDir.resolve("folderA").toFile();
            File folderB = tempDir.resolve("folderB").toFile();
            SyncStateStore storeA = SyncStateStore.forFolder(folderA);
            SyncStateStore storeB = SyncStateStore.forFolder(folderB);
            storeA.confirm("file.txt", "md5-a", 1L);
            storeA.flush();

            assertNull(
                    SyncStateStore.forFolder(folderB).base("file.txt"),
                    "each sync folder has its own state file");
            assertNotNull(SyncStateStore.forFolder(folderA).base("file.txt"));
            assertFalse(storeA == storeB);
        } finally {
            CacheLocations.clearOverrideForTest();
        }
    }
}
