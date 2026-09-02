package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the persisted-manifest checksum cache that sync previews rely on: a second generation
 * of an unchanged folder must not re-read any file, and only visibly changed files may be
 * re-hashed.
 */
class FileChangeDetectorManifestCacheTest {

    @TempDir Path syncFolder;

    @TempDir Path cacheDir;

    /** Hasher that records which files it actually read. */
    private static final class CountingHasher implements FileChangeDetector.FileHasher {
        private final AtomicInteger invocations = new AtomicInteger();
        private final ConcurrentLinkedQueue<String> hashedNames = new ConcurrentLinkedQueue<>();

        @Override
        public String hash(File file) throws IOException {
            invocations.incrementAndGet();
            hashedNames.add(file.getName());
            return FileChangeDetector.calculateMD5(file);
        }

        Set<String> hashedNameSet() {
            return new TreeSet<>(hashedNames);
        }
    }

    private FileChangeDetector.FileManifest generateWithHasher(
            boolean quickHash, CountingHasher hasher, File cacheFile) throws IOException {
        return FileChangeDetector.generateManifest(
                syncFolder.toFile(),
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withUseQuickHash(quickHash)
                        .withHasher(hasher)
                        .withPersistedManifestFile(cacheFile)
                        .build());
    }

    @Test
    void persistedCacheSkipsRehashingUnchangedFiles() throws IOException {
        Files.writeString(syncFolder.resolve("a.txt"), "alpha");
        Files.writeString(syncFolder.resolve("b.txt"), "beta");
        File cacheFile = newCacheFile();

        CountingHasher firstHasher = new CountingHasher();
        FileChangeDetector.FileManifest first = generateWithHasher(false, firstHasher, cacheFile);
        assertEquals(2, firstHasher.invocations.get(), "First generation hashes every file");
        assertTrue(cacheFile.isFile(), "Manifest cache should be persisted");

        CountingHasher secondHasher = new CountingHasher();
        FileChangeDetector.FileManifest second = generateWithHasher(false, secondHasher, cacheFile);
        assertEquals(0, secondHasher.invocations.get(), "Unchanged files must reuse cached MD5");

        assertEquals(
                first.getFiles().get("a.txt").getMd5(),
                second.getFiles().get("a.txt").getMd5(),
                "Cached hash should be preserved");
        assertEquals(
                first.getFiles().get("b.txt").getMd5(),
                second.getFiles().get("b.txt").getMd5(),
                "Cached hash should be preserved");
    }

    @Test
    void persistedCacheRehashesOnlyChangedAndNewFiles() throws IOException {
        Path unchanged = syncFolder.resolve("a.txt");
        Path rewritten = syncFolder.resolve("b.txt");
        Files.writeString(unchanged, "aaa");
        Files.writeString(rewritten, "bbb");
        File cacheFile = newCacheFile();

        CountingHasher firstHasher = new CountingHasher();
        generateWithHasher(false, firstHasher, cacheFile);
        String unchangedHashBefore =
                generateWithHasher(false, new CountingHasher(), cacheFile)
                        .getFiles()
                        .get("a.txt")
                        .getMd5();

        // Size change guarantees a cache miss even where the filesystem rounds mtime.
        Files.writeString(rewritten, "b-changed-and-longer");
        Files.writeString(syncFolder.resolve("c.txt"), "new file");

        CountingHasher secondHasher = new CountingHasher();
        FileChangeDetector.FileManifest second = generateWithHasher(false, secondHasher, cacheFile);

        assertEquals(
                Set.of("b.txt", "c.txt"),
                secondHasher.hashedNameSet(),
                "Only the changed and the new file may be re-read");
        assertEquals(
                unchangedHashBefore,
                second.getFiles().get("a.txt").getMd5(),
                "Unchanged file keeps its cached hash");
        assertNotEquals(
                "bbb",
                second.getFiles().get("b.txt").getMd5(),
                "Sanity: md5 is a hex digest, not the content");
        assertNotNull(second.getFiles().get("c.txt").getMd5(), "New file gets a hash");
    }

    @Test
    void quickModeBinaryEntryIsRehashedInFullMode() throws IOException {
        Files.writeString(syncFolder.resolve("note.txt"), "note");
        Files.write(syncFolder.resolve("blob.bin"), new byte[] {0x00, 0x01, 0x02, (byte) 0xFF});
        File cacheFile = newCacheFile();

        CountingHasher quickHasher = new CountingHasher();
        FileChangeDetector.FileManifest quick = generateWithHasher(true, quickHasher, cacheFile);
        assertEquals(
                Set.of("note.txt"),
                quickHasher.hashedNameSet(),
                "Quick mode hashes only text-extension files");
        assertNull(
                quick.getFiles().get("blob.bin").getMd5(),
                "Quick mode leaves binary entries without a checksum");

        CountingHasher fullHasher = new CountingHasher();
        FileChangeDetector.FileManifest full = generateWithHasher(false, fullHasher, cacheFile);
        assertEquals(
                Set.of("blob.bin"),
                fullHasher.hashedNameSet(),
                "Full mode must re-hash the checksum-less binary entry");
        assertEquals(
                quick.getFiles().get("note.txt").getMd5(),
                full.getFiles().get("note.txt").getMd5(),
                "Text hash from quick mode is reusable in full mode");
        assertNotNull(full.getFiles().get("blob.bin").getMd5(), "Binary entry gets its checksum");
    }

    @Test
    void generateManifestWithCacheMatchesUncachedResult() throws IOException {
        Files.writeString(syncFolder.resolve("root.txt"), "root");
        Path nested = syncFolder.resolve("nested");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("leaf.txt"), "leaf");
        File cacheFile = newCacheFile();

        FileChangeDetector.FileManifest cached =
                FileChangeDetector.generateManifestWithCache(
                        syncFolder.toFile(), false, false, cacheFile);
        FileChangeDetector.FileManifest plain =
                FileChangeDetector.generateManifest(syncFolder.toFile(), false, false);

        assertEquals(
                plain.getFileCount(),
                cached.getFileCount(),
                "Cached generation sees the same files");
        for (String path : plain.getFiles().keySet()) {
            FileChangeDetector.FileInfo plainInfo = plain.getFiles().get(path);
            FileChangeDetector.FileInfo cachedInfo = cached.getFiles().get(path);
            assertNotNull(cachedInfo, "Missing entry for " + path);
            assertEquals(plainInfo.getSize(), cachedInfo.getSize(), path);
            assertEquals(plainInfo.getLastModified(), cachedInfo.getLastModified(), path);
            assertEquals(plainInfo.getMd5(), cachedInfo.getMd5(), path);
        }

        // A second cached generation loads the persisted file and must stay identical.
        FileChangeDetector.FileManifest reloaded =
                FileChangeDetector.generateManifestWithCache(
                        syncFolder.toFile(), false, false, cacheFile);
        assertEquals(
                cached.getFiles().get("nested/leaf.txt").getMd5(),
                reloaded.getFiles().get("nested/leaf.txt").getMd5(),
                "Reloaded cache keeps the same checksum");
        assertTrue(
                Files.readString(cacheFile.toPath())
                        .contains(
                                "\"schemaVersion\":"
                                        + FileChangeDetector.FileManifest.CURRENT_VERSION),
                "Persisted manifest carries the schema version");
    }

    @Test
    void persistedManifestFileForIsPerFolderUnderUserHome() {
        File folderA = new File("C:/sync/a");
        File folderB = new File("C:/sync/b");

        File cacheA = FileChangeDetector.persistedManifestFileFor(folderA);
        assertEquals(
                ".filesync",
                cacheA.getParentFile().getName(),
                "Cache lives under the .filesync directory");
        assertEquals(
                new File(System.getProperty("user.home"), ".filesync"),
                cacheA.getParentFile(),
                "Cache directory is under the user home");
        assertTrue(
                cacheA.getName().startsWith("manifest-cache-")
                        && cacheA.getName().endsWith(".json"),
                "Cache file name follows the manifest-cache-<key>.json convention");

        assertEquals(
                cacheA, FileChangeDetector.persistedManifestFileFor(folderA), "Stable per folder");
        assertNotEquals(
                cacheA,
                FileChangeDetector.persistedManifestFileFor(folderB),
                "Different folders get different cache files");
    }

    @Test
    void manifestReportsExplicitSizeAndLastModified() throws IOException {
        Path file = syncFolder.resolve("pinned.txt");
        Files.writeString(file, "12345", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(12345000L));

        FileChangeDetector.FileManifest manifest =
                FileChangeDetector.generateManifestWithCache(
                        syncFolder.toFile(), false, false, newCacheFile());

        FileChangeDetector.FileInfo info = manifest.getFiles().get("pinned.txt");
        assertNotNull(info, "Manifest contains the file");
        assertEquals(5L, info.getSize(), "Size comes from the walk attributes");
        assertEquals(
                12345000L, info.getLastModified(), "LastModified comes from the walk attributes");
    }

    private File newCacheFile() {
        return cacheDir.resolve("manifest-cache.json").toFile();
    }
}
