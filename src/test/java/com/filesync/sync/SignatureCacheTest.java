package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.delta.SignatureUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SignatureCache}: hit only when the receiver file identity (size, lastModified,
 * md5) matches exactly, persistence across instances, pruning, and graceful handling of corrupt or
 * incompatible cache files.
 */
class SignatureCacheTest {

    @TempDir Path tempDir;

    private File cacheFile() {
        return tempDir.resolve("sigcache-test.json").toFile();
    }

    private FileChangeDetector.FileInfo remote(long size, long mtime, String md5) {
        return new FileChangeDetector.FileInfo("app.log", size, mtime, md5);
    }

    private com.filesync.delta.FileSignatures signatures(byte[] data) throws IOException {
        return SignatureUtil.compute("app.log", data, 64);
    }

    private byte[] randomBytes(int len, long seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }

    @Test
    void storeThenLookupHitsForIdenticalRemoteState() throws IOException {
        SignatureCache cache = new SignatureCache(cacheFile());
        byte[] data = randomBytes(500, 1);
        com.filesync.delta.FileSignatures sigs = signatures(data);

        cache.store("app.log", remote(500, 42L, "md5-value"), sigs);
        cache.flush();

        com.filesync.delta.FileSignatures hit =
                cache.lookup("app.log", remote(500, 42L, "md5-value"));
        assertNotNull(hit, "identical receiver state must hit");
        assertEquals(sigs.getBlockSize(), hit.getBlockSize());
        assertEquals(sigs.getBlockCount(), hit.getBlockCount());
        assertEquals(sigs.getSourceSize(), hit.getSourceSize());
        assertEquals(sigs.getSignatures(), hit.getSignatures());
    }

    @Test
    void lookupMissesOnAnyIdentityChange() throws IOException {
        SignatureCache cache = new SignatureCache(cacheFile());
        cache.store("app.log", remote(500, 42L, "md5-value"), signatures(randomBytes(500, 2)));

        assertNull(cache.lookup("app.log", remote(501, 42L, "md5-value")), "size changed");
        assertNull(cache.lookup("app.log", remote(500, 43L, "md5-value")), "mtime changed");
        assertNull(cache.lookup("app.log", remote(500, 42L, "other-md5")), "md5 changed");
        assertNull(cache.lookup("other.log", remote(500, 42L, "md5-value")), "path changed");
        assertNull(cache.lookup("app.log", null), "no remote metadata");
        assertNull(cache.lookup("app.log", remote(500, 42L, null)), "quick-hash md5 null");
    }

    @Test
    void storeWithoutRemoteMd5IsSkipped() throws IOException {
        SignatureCache cache = new SignatureCache(cacheFile());
        cache.store("app.log", remote(500, 42L, null), signatures(randomBytes(500, 3)));
        cache.flush();

        assertFalse(cacheFile().exists(), "nothing to persist when the entry cannot be validated");
    }

    @Test
    void persistsAcrossInstances() throws IOException {
        byte[] data = randomBytes(500, 4);
        SignatureCache first = new SignatureCache(cacheFile());
        first.store("app.log", remote(500, 42L, "md5-value"), signatures(data));
        first.flush();
        assertTrue(cacheFile().exists(), "flush must write the cache file");

        SignatureCache second = new SignatureCache(cacheFile());
        assertNotNull(second.lookup("app.log", remote(500, 42L, "md5-value")));
        assertNull(second.lookup("app.log", remote(500, 43L, "md5-value")));
    }

    @Test
    void pruneDropsOtherPaths() throws IOException {
        SignatureCache cache = new SignatureCache(cacheFile());
        cache.store("app.log", remote(500, 42L, "md5-value"), signatures(randomBytes(500, 5)));
        cache.store("gone.log", remote(300, 42L, "md5-gone"), signatures(randomBytes(300, 6)));

        cache.prune(Set.of("app.log"));

        assertNotNull(cache.lookup("app.log", remote(500, 42L, "md5-value")));
        assertNull(cache.lookup("gone.log", remote(300, 42L, "md5-gone")));
    }

    @Test
    void corruptCacheFileStartsEmpty() throws IOException {
        Files.writeString(cacheFile().toPath(), "not valid json {{{");
        SignatureCache cache = new SignatureCache(cacheFile());
        assertNull(cache.lookup("app.log", remote(500, 42L, "md5-value")));
        // The cache remains usable after starting empty.
        cache.store("app.log", remote(500, 42L, "md5-value"), signatures(randomBytes(500, 7)));
        cache.flush();
        assertNotNull(
                new SignatureCache(cacheFile()).lookup("app.log", remote(500, 42L, "md5-value")));
    }

    @Test
    void incompatibleSchemaVersionStartsEmpty() throws IOException {
        Files.writeString(
                cacheFile().toPath(),
                "{\"schemaVersion\":99,\"entries\":{"
                        + "\"app.log\":{\"remoteSize\":500,\"remoteLastModified\":42,"
                        + "\"remoteMd5\":\"md5-value\",\"signaturesBase64\":\"AAAA\"}}}");
        SignatureCache cache = new SignatureCache(cacheFile());
        assertNull(cache.lookup("app.log", remote(500, 42L, "md5-value")));
    }

    @Test
    void corruptBase64PayloadIsAMiss() throws IOException {
        SignatureCache cache = new SignatureCache(cacheFile());
        cache.store("app.log", remote(500, 42L, "md5-value"), signatures(randomBytes(500, 8)));
        cache.flush();
        // Corrupt the stored payload: lookup must treat it as a miss, not an error.
        String json = Files.readString(cacheFile().toPath());
        Files.writeString(
                cacheFile().toPath(),
                json.replaceFirst(
                        "\"signaturesBase64\":\"[^\"]*\"", "\"signaturesBase64\":\"!!!\""));
        assertNull(
                new SignatureCache(cacheFile()).lookup("app.log", remote(500, 42L, "md5-value")));
    }

    // ========== rejection memo (BASE_STALE) ==========

    @Test
    void markRejectedMakesLookupMissAndIsRejectedTrue() throws IOException {
        SignatureCache cache = new SignatureCache(cacheFile());
        cache.store("app.log", remote(500, 42L, "md5-value"), signatures(randomBytes(500, 9)));

        cache.markRejected("app.log", 500, 42L, "md5-value");

        assertTrue(cache.isRejected("app.log", remote(500, 42L, "md5-value")));
        assertNull(
                cache.lookup("app.log", remote(500, 42L, "md5-value")),
                "a rejected receiver state must be a miss so fresh signatures are exchanged");
    }

    @Test
    void markRejectedCreatesEntryWithoutPriorStore() throws IOException {
        SignatureCache cache = new SignatureCache(cacheFile());

        cache.markRejected("app.log", 500, 42L, "md5-value");

        assertTrue(cache.isRejected("app.log", remote(500, 42L, "md5-value")));
        assertNull(cache.lookup("app.log", remote(500, 42L, "md5-value")));
    }

    @Test
    void isRejectedOnlyMatchesTheNamedReceiverState() throws IOException {
        SignatureCache cache = new SignatureCache(cacheFile());
        cache.markRejected("app.log", 500, 42L, "md5-value");

        assertFalse(cache.isRejected("app.log", remote(501, 42L, "md5-value")), "size changed");
        assertFalse(cache.isRejected("app.log", remote(500, 43L, "md5-value")), "mtime changed");
        assertFalse(cache.isRejected("app.log", remote(500, 42L, "other-md5")), "md5 changed");
        assertFalse(cache.isRejected("other.log", remote(500, 42L, "md5-value")), "path changed");
        assertFalse(cache.isRejected("app.log", null), "no remote metadata");
        assertFalse(cache.isRejected("app.log", remote(500, 42L, null)), "quick-hash md5 null");
        assertFalse(cache.isRejected("app.log", remote(500, 42L, "")), "empty md5");
    }

    @Test
    void rejectionPersistsAcrossInstances() throws IOException {
        SignatureCache first = new SignatureCache(cacheFile());
        first.markRejected("app.log", 500, 42L, "md5-value");
        first.flush();

        SignatureCache second = new SignatureCache(cacheFile());
        assertTrue(second.isRejected("app.log", remote(500, 42L, "md5-value")));
        assertNull(second.lookup("app.log", remote(500, 42L, "md5-value")));
    }

    @Test
    void storeOverwritesRejectionForTheSamePath() throws IOException {
        SignatureCache cache = new SignatureCache(cacheFile());
        cache.markRejected("app.log", 500, 42L, "md5-value");

        // A later successful signature exchange for the same state heals the entry.
        cache.store("app.log", remote(500, 42L, "md5-value"), signatures(randomBytes(500, 10)));

        assertFalse(cache.isRejected("app.log", remote(500, 42L, "md5-value")));
        assertNotNull(cache.lookup("app.log", remote(500, 42L, "md5-value")));
    }

    @Test
    void markRejectedOnEntryWithDifferentIdentityMovesTheMemo() throws IOException {
        SignatureCache cache = new SignatureCache(cacheFile());
        cache.store("app.log", remote(500, 42L, "md5-old"), signatures(randomBytes(500, 11)));

        // The receiver's file changed since the stored signatures; the memo follows the newest
        // state, and the stale payload is never validated against either identity.
        cache.markRejected("app.log", 600, 43L, "md5-new");

        assertTrue(cache.isRejected("app.log", remote(600, 43L, "md5-new")));
        assertNull(cache.lookup("app.log", remote(500, 42L, "md5-old")));
        assertNull(cache.lookup("app.log", remote(600, 43L, "md5-new")));
    }
}
