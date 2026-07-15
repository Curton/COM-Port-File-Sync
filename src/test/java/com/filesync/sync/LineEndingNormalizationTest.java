package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for line-ending (CRLF/LF) normalization in file hashing and manifest comparison, plus
 * persisted-manifest schema-version validation.
 */
class LineEndingNormalizationTest {

    @TempDir Path tempDir;

    // ========== calculateMD5 normalization ==========

    @Test
    void calculateMd5_treatsCrlfAndLfAsEqual() throws IOException {
        Path lfFile = tempDir.resolve("lf.txt");
        Path crlfFile = tempDir.resolve("crlf.txt");
        Files.write(lfFile, "line1\nline2\nline3\n".getBytes(StandardCharsets.UTF_8));
        Files.write(crlfFile, "line1\r\nline2\r\nline3\r\n".getBytes(StandardCharsets.UTF_8));

        String lfHash = FileChangeDetector.calculateMD5(lfFile.toFile());
        String crlfHash = FileChangeDetector.calculateMD5(crlfFile.toFile());

        assertEquals(lfHash, crlfHash, "CRLF and LF variants of the same text must hash equally");
    }

    @Test
    void calculateMd5_treatsLoneCrAsLf() throws IOException {
        Path lfFile = tempDir.resolve("lf.txt");
        Path crFile = tempDir.resolve("cr.txt");
        Files.write(lfFile, "line1\nline2\n".getBytes(StandardCharsets.UTF_8));
        Files.write(crFile, "line1\rline2\r".getBytes(StandardCharsets.UTF_8));

        assertEquals(
                FileChangeDetector.calculateMD5(lfFile.toFile()),
                FileChangeDetector.calculateMD5(crFile.toFile()),
                "Lone CR (old Mac line ending) must normalize to LF");
    }

    @Test
    void calculateMd5_normalizesCrlfAcrossChunkBoundary() throws IOException {
        // Place a CRLF pair exactly at the 4096-byte binary-detection sample boundary: the CR is
        // the last byte of the sample, the LF is the first byte of the next read. This exercises
        // the pending-CR state machine carried across chunks.
        byte[] crlf = new byte[4096 + 1 + 100];
        java.util.Arrays.fill(crlf, 0, 4095, (byte) 'x'); // indices 0..4094
        crlf[4095] = '\r'; // last byte of the 4096-byte sample
        crlf[4096] = '\n'; // first byte after the sample
        java.util.Arrays.fill(crlf, 4097, crlf.length, (byte) 'y');

        byte[] lf = new byte[4095 + 1 + 100];
        java.util.Arrays.fill(lf, 0, 4095, (byte) 'x');
        lf[4095] = '\n';
        java.util.Arrays.fill(lf, 4096, lf.length, (byte) 'y');

        Path crlfFile = tempDir.resolve("boundary_crlf.txt");
        Path lfFile = tempDir.resolve("boundary_lf.txt");
        Files.write(crlfFile, crlf);
        Files.write(lfFile, lf);

        assertEquals(
                FileChangeDetector.calculateMD5(crlfFile.toFile()),
                FileChangeDetector.calculateMD5(lfFile.toFile()),
                "A CRLF split across a read-chunk boundary must normalize to a single LF");
    }

    @Test
    void calculateMd5_binaryFileUsesRawBytes() throws Exception {
        // Content with >10% non-text bytes -> detected as binary -> hashed without normalization.
        byte[] data = new byte[200];
        for (int i = 0; i < data.length; i++) {
            data[i] = (i % 2 == 0) ? (byte) 0 : (byte) 'A';
        }
        assertTrue(
                CompressionUtil.isLikelyBinaryContent(data), "fixture must be detected as binary");

        Path binFile = tempDir.resolve("blob.bin");
        Files.write(binFile, data);

        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(data);
        StringBuilder expected = new StringBuilder();
        for (byte b : digest) {
            expected.append(String.format("%02x", b));
        }

        assertEquals(
                expected.toString(),
                FileChangeDetector.calculateMD5(binFile.toFile()),
                "Binary files must be hashed using raw bytes (no normalization)");
    }

    // ========== manifest comparison ignores CRLF/LF ==========

    @Test
    void getChangedFiles_ignoresCrlfOnlyDifferenceInFullMode() throws IOException {
        Path sourceDir = tempDir.resolve("src");
        Path targetDir = tempDir.resolve("tgt");
        Files.createDirectories(sourceDir);
        Files.createDirectories(targetDir);

        byte[] crlf = "alpha\r\nbeta\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] lf = "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8);
        Files.write(sourceDir.resolve("doc.txt"), crlf);
        Files.write(targetDir.resolve("doc.txt"), lf);

        FileChangeDetector.FileManifest source =
                FileChangeDetector.generateManifest(sourceDir.toFile(), false, false);
        FileChangeDetector.FileManifest target =
                FileChangeDetector.generateManifest(targetDir.toFile(), false, false);

        // Stored size is the raw byte count and must differ (CRLF is larger) - this documents that
        // only the hash is normalized, not the stored size used for display/transfer/batching.
        assertNotEquals(
                source.getFiles().get("doc.txt").getSize(),
                target.getFiles().get("doc.txt").getSize(),
                "raw sizes should differ between CRLF and LF");
        assertEquals(
                source.getFiles().get("doc.txt").getMd5(),
                target.getFiles().get("doc.txt").getMd5(),
                "normalized hashes must match across line endings");

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertTrue(changed.isEmpty(), "CRLF-only difference must not be a change in full mode");
    }

    @Test
    void getChangedFiles_ignoresCrlfOnlyDifferenceInQuickMode() throws IOException {
        Path sourceDir = tempDir.resolve("src");
        Path targetDir = tempDir.resolve("tgt");
        Files.createDirectories(sourceDir);
        Files.createDirectories(targetDir);

        Files.write(
                sourceDir.resolve("doc.txt"), "alpha\r\nbeta\r\n".getBytes(StandardCharsets.UTF_8));
        Files.write(targetDir.resolve("doc.txt"), "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8));

        FileChangeDetector.FileManifest source =
                FileChangeDetector.generateManifest(sourceDir.toFile(), false, true);
        FileChangeDetector.FileManifest target =
                FileChangeDetector.generateManifest(targetDir.toFile(), false, true);

        assertEquals(
                source.getFiles().get("doc.txt").getMd5(),
                target.getFiles().get("doc.txt").getMd5(),
                "normalized hashes must match across line endings in quick mode");

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertTrue(changed.isEmpty(), "CRLF-only difference must not be a change in quick mode");
    }

    @Test
    void getChangedFiles_detectsRealContentChangeInFullMode() throws IOException {
        Path sourceDir = tempDir.resolve("src");
        Path targetDir = tempDir.resolve("tgt");
        Files.createDirectories(sourceDir);
        Files.createDirectories(targetDir);

        Files.write(
                sourceDir.resolve("doc.txt"), "line1\nline2\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
                targetDir.resolve("doc.txt"), "line1\nCHANGED\n".getBytes(StandardCharsets.UTF_8));

        FileChangeDetector.FileManifest source =
                FileChangeDetector.generateManifest(sourceDir.toFile(), false, false);
        FileChangeDetector.FileManifest target =
                FileChangeDetector.generateManifest(targetDir.toFile(), false, false);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertEquals(1, changed.size(), "A real content change must still be detected");
    }

    @Test
    void getChangedFiles_detectsRealContentChangeInQuickMode() throws IOException {
        Path sourceDir = tempDir.resolve("src");
        Path targetDir = tempDir.resolve("tgt");
        Files.createDirectories(sourceDir);
        Files.createDirectories(targetDir);

        Files.write(
                sourceDir.resolve("doc.txt"), "line1\nline2\n".getBytes(StandardCharsets.UTF_8));
        Files.write(
                targetDir.resolve("doc.txt"), "line1\nCHANGED\n".getBytes(StandardCharsets.UTF_8));

        FileChangeDetector.FileManifest source =
                FileChangeDetector.generateManifest(sourceDir.toFile(), false, true);
        FileChangeDetector.FileManifest target =
                FileChangeDetector.generateManifest(targetDir.toFile(), false, true);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertEquals(
                1, changed.size(), "A real content change must still be detected in quick mode");
    }

    // ========== persisted manifest schema version ==========

    @Test
    void persistedManifest_currentVersionIsAcceptedAndReused() throws IOException {
        Path contentDir = tempDir.resolve("content");
        Files.createDirectories(contentDir);
        Path file = contentDir.resolve("sample.txt");
        Files.write(file, "hello\n".getBytes(StandardCharsets.UTF_8));
        File fileObj = file.toFile();
        fileObj.setLastModified(1_700_000_000_000L);

        // The cache file lives OUTSIDE the scanned directory so it is not itself hashed.
        Path cacheFile = tempDir.resolve("cache.json");

        CountingHasher hasher = new CountingHasher();
        FileChangeDetector.ManifestGenerationOptions writeOpts =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withUseQuickHash(false)
                        .withHasher(hasher)
                        .withPersistedManifestFile(cacheFile.toFile())
                        .build();
        FileChangeDetector.generateManifest(contentDir.toFile(), writeOpts);
        int countAfterWrite = hasher.invocations.get();
        assertEquals(1, countAfterWrite, "first pass should hash the one text file");

        // Sanity: the persisted cache carries the current schema version.
        FileChangeDetector.FileManifest fromDisk =
                FileChangeDetector.manifestFromJson(Files.readString(cacheFile));
        assertEquals(
                FileChangeDetector.FileManifest.CURRENT_VERSION,
                fromDisk.getSchemaVersion(),
                "persisted manifest should record the current schema version");

        // Second pass: metadata unchanged, so a valid v2 cache must be reused without re-hashing.
        FileChangeDetector.ManifestGenerationOptions reuseOpts =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withUseQuickHash(false)
                        .withHasher(hasher)
                        .withPersistedManifestFile(cacheFile.toFile())
                        .build();
        FileChangeDetector.FileManifest reused =
                FileChangeDetector.generateManifest(contentDir.toFile(), reuseOpts);
        assertEquals(
                countAfterWrite,
                hasher.invocations.get(),
                "a valid v2 cache with unchanged metadata should be reused without re-hashing");
        assertEquals(
                FileChangeDetector.calculateMD5(fileObj),
                reused.getFiles().get("sample.txt").getMd5(),
                "reused hash should equal the real normalized hash");
    }

    @Test
    void persistedManifest_withoutSchemaVersionIsDiscarded() throws IOException {
        assertStaleCacheDiscarded("");
    }

    @Test
    void persistedManifest_withMismatchedVersionIsDiscarded() throws IOException {
        assertStaleCacheDiscarded(",\"schemaVersion\":1");
    }

    /**
     * Writes a stale cache whose metadata matches the file (so {@code canReuseHash} would reuse it
     * if it were loaded) but with an incompatible/missing schema version, then asserts the file is
     * re-hashed instead of reusing the stale hash.
     */
    private void assertStaleCacheDiscarded(String versionFragment) throws IOException {
        Path contentDir = tempDir.resolve("content");
        Files.createDirectories(contentDir);
        Path file = contentDir.resolve("sample.txt");
        Files.write(file, "hello\n".getBytes(StandardCharsets.UTF_8));
        File fileObj = file.toFile();
        fileObj.setLastModified(1_700_000_000_000L);
        long size = fileObj.length();
        long mtime = fileObj.lastModified();

        String staleJson =
                "{\"files\":{\"sample.txt\":{\"path\":\"sample.txt\",\"size\":"
                        + size
                        + ",\"lastModified\":"
                        + mtime
                        + ",\"md5\":\"STALE-HASH\"}},\"emptyDirectories\":[]"
                        + versionFragment
                        + "}";
        Path cacheFile = tempDir.resolve("stale.json"); // outside the scanned dir
        Files.writeString(cacheFile, staleJson);

        CountingHasher hasher = new CountingHasher();
        FileChangeDetector.ManifestGenerationOptions options =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withUseQuickHash(false)
                        .withHasher(hasher)
                        .withPersistedManifestFile(cacheFile.toFile())
                        .build();
        FileChangeDetector.FileManifest manifest =
                FileChangeDetector.generateManifest(contentDir.toFile(), options);

        assertEquals(
                1,
                hasher.invocations.get(),
                "stale cache must be discarded and the file re-hashed");
        String md5 = manifest.getFiles().get("sample.txt").getMd5();
        assertNotEquals("STALE-HASH", md5, "the stale cached hash must not be reused");
        assertEquals(
                FileChangeDetector.calculateMD5(fileObj),
                md5,
                "re-hashed value should be the real normalized hash");
    }

    private static final class CountingHasher implements FileChangeDetector.FileHasher {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String hash(File file) throws IOException {
            invocations.incrementAndGet();
            return FileChangeDetector.calculateMD5(file);
        }
    }
}
