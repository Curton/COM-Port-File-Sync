package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.delta.HashUtil;
import com.filesync.protocol.SyncProtocol;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileChangeDetectorTest {

    @TempDir Path tempDir;

    @Test
    void manifestSkipsLargeTransferStagingFiles() throws IOException {
        Files.writeString(tempDir.resolve(".big.bin" + SyncProtocol.PARTIAL_SUFFIX), "stage bytes");
        Files.writeString(tempDir.resolve("real.txt"), "real");

        FileChangeDetector.FileManifest manifest =
                FileChangeDetector.generateManifest(
                        tempDir.toFile(),
                        FileChangeDetector.ManifestGenerationOptions.builder().build());

        assertNull(
                manifest.getFiles().get(".big.bin" + SyncProtocol.PARTIAL_SUFFIX),
                "A transfer staging file must never be synced as user content");
        assertNotNull(manifest.getFiles().get("real.txt"));
    }

    @Test
    void reusesCachedHashWhenMetadataUnchanged() throws IOException {
        Path filePath = tempDir.resolve("sample.txt");
        Files.writeString(filePath, "hello world");

        CountingHasher countingHasher = new CountingHasher();
        FileChangeDetector.ManifestGenerationOptions options =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withUseQuickHash(false)
                        .withHasher(countingHasher)
                        .build();

        FileChangeDetector.FileManifest first =
                FileChangeDetector.generateManifest(tempDir.toFile(), options);
        assertEquals(1, countingHasher.invocations.get(), "Initial hash should run once");
        String firstHash = first.getFiles().get("sample.txt").getMd5();
        assertNotNull(firstHash, "MD5 should be populated");

        FileChangeDetector.ManifestGenerationOptions cachedOptions =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withUseQuickHash(false)
                        .withHasher(countingHasher)
                        .withPreviousManifest(first)
                        .build();

        FileChangeDetector.FileManifest second =
                FileChangeDetector.generateManifest(tempDir.toFile(), cachedOptions);
        assertEquals(
                1,
                countingHasher.invocations.get(),
                "Hash should be reused when metadata is unchanged");
        assertEquals(
                firstHash,
                second.getFiles().get("sample.txt").getMd5(),
                "Cached hash should be preserved");
    }

    @Test
    void writesPersistedManifestAndReportsProgress() throws IOException {
        Path nestedDir = tempDir.resolve("nested");
        Files.createDirectories(nestedDir);
        Files.writeString(nestedDir.resolve("a.txt"), "a");
        Files.writeString(tempDir.resolve("b.txt"), "b");

        Path manifestFile = tempDir.resolve("manifest-cache.json");
        RecordingProgressCallback callback = new RecordingProgressCallback();

        FileChangeDetector.ManifestGenerationOptions options =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withProgressCallback(callback)
                        .withPersistedManifestFile(manifestFile.toFile())
                        .withUseQuickHash(true)
                        .build();

        FileChangeDetector.FileManifest manifest =
                FileChangeDetector.generateManifest(tempDir.toFile(), options);

        assertTrue(Files.exists(manifestFile), "Manifest should be persisted to disk");
        String persistedJson = Files.readString(manifestFile);
        FileChangeDetector.FileManifest fromDisk =
                FileChangeDetector.manifestFromJson(persistedJson);
        assertEquals(manifest.getFileCount(), fromDisk.getFileCount());
        assertEquals(
                2,
                callback.fileProcessedCount.get(),
                "Progress callback should track processed files");
        assertEquals(
                callback.totalFiles,
                callback.progressTotal,
                "Progress totals should match start callback");
    }

    @Test
    void hashesRunOnThreadPoolWhenEnabled() throws IOException {
        Files.writeString(tempDir.resolve("file1.txt"), "one");
        Files.writeString(tempDir.resolve("file2.txt"), "two");
        Files.writeString(tempDir.resolve("file3.txt"), "three");

        Set<String> hashingThreads = ConcurrentHashMap.newKeySet();
        FileChangeDetector.FileHasher trackingHasher =
                file -> {
                    hashingThreads.add(Thread.currentThread().getName());
                    return FileChangeDetector.calculateMD5(file);
                };

        FileChangeDetector.ManifestGenerationOptions options =
                FileChangeDetector.ManifestGenerationOptions.builder()
                        .withHasher(trackingHasher)
                        .withHashThreadPoolSize(2)
                        .withUseQuickHash(false)
                        .build();

        FileChangeDetector.FileManifest manifest =
                FileChangeDetector.generateManifest(tempDir.toFile(), options);
        assertNotNull(manifest);
        assertTrue(
                hashingThreads.stream().anyMatch(name -> name.contains("pool")),
                "Hashing should occur on worker threads");
    }

    @Test
    void hashFailureDegradesToMetadataOnlyInsteadOfFailing() throws IOException {
        Files.writeString(tempDir.resolve("readable.txt"), "readable");
        Files.writeString(tempDir.resolve("locked.bin"), "locked");

        FileChangeDetector.FileHasher failingHasher =
                file -> {
                    if (file.getName().equals("locked.bin")) {
                        throw new IOException("file locked by another process");
                    }
                    return FileChangeDetector.calculateMD5(file);
                };
        List<String> warnings = new ArrayList<>();
        FileChangeDetector.ManifestProgressCallback callback =
                new FileChangeDetector.ManifestProgressCallback() {
                    @Override
                    public void onWarning(String message) {
                        warnings.add(message);
                    }
                };

        FileChangeDetector.FileManifest manifest =
                FileChangeDetector.generateManifest(
                        tempDir.toFile(),
                        FileChangeDetector.ManifestGenerationOptions.builder()
                                .withUseQuickHash(false)
                                .withHasher(failingHasher)
                                .withProgressCallback(callback)
                                .build());

        FileChangeDetector.FileInfo locked = manifest.getFiles().get("locked.bin");
        assertNotNull(locked, "An unreadable file must stay in the manifest");
        assertNull(locked.getMd5(), "An unreadable file must carry no checksum");
        assertNotNull(
                manifest.getFiles().get("readable.txt").getMd5(),
                "Other files must still be hashed");
        assertEquals(1, warnings.size(), "A summary warning should be reported once");
        assertTrue(warnings.get(0).contains("locked.bin"), "Warning should name the file");
        assertTrue(
                warnings.get(0).contains("file locked by another process"),
                "Warning should include the read-error reason");
    }

    @Test
    void nonIoHashFailureStillFailsGeneration() throws IOException {
        Files.writeString(tempDir.resolve("a.txt"), "a");
        FileChangeDetector.FileHasher brokenHasher =
                file -> {
                    throw new IllegalStateException("hasher bug");
                };

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                FileChangeDetector.generateManifest(
                                        tempDir.toFile(),
                                        FileChangeDetector.ManifestGenerationOptions.builder()
                                                .withUseQuickHash(false)
                                                .withHasher(brokenHasher)
                                                .build()));
        assertTrue(
                thrown.getMessage().contains("Failed to compute file hash"),
                "Non-IO hash failures stay fatal");
        assertTrue(
                thrown.getMessage().contains("hasher bug"),
                "The underlying cause must not be swallowed");
    }

    private static final class CountingHasher implements FileChangeDetector.FileHasher {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String hash(File file) throws IOException {
            invocations.incrementAndGet();
            return FileChangeDetector.calculateMD5(file);
        }
    }

    private static final class RecordingProgressCallback
            implements FileChangeDetector.ManifestProgressCallback {
        private final AtomicInteger fileProcessedCount = new AtomicInteger();
        private int totalFiles;
        private int progressTotal;

        @Override
        public void onStart(int totalFiles) {
            this.totalFiles = totalFiles;
        }

        @Override
        public void onProgress(int processedFiles, int totalFiles) {
            this.progressTotal = totalFiles;
        }

        @Override
        public void onFileProcessed(String fileName) {
            fileProcessedCount.incrementAndGet();
        }
    }

    // ========== Delta method tests ==========

    @Test
    void getChangedFiles_returnsFileOnlyInSource() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("new.txt", new FileChangeDetector.FileInfo("new.txt", 100, 1000L, "abc"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertEquals(1, changed.size());
        assertEquals("new.txt", changed.get(0).getPath());
    }

    @Test
    void getChangedFiles_skipsFileWithSameMd5() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put(
                "file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, "abc123"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put(
                "file.txt", new FileChangeDetector.FileInfo("file.txt", 200, 5000L, "abc123"));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertTrue(changed.isEmpty(), "Same MD5 should mean no change");
    }

    @Test
    void getChangedFiles_detectsDifferentMd5() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, "abc"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 200, 5000L, "def"));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertEquals(1, changed.size());
    }

    @Test
    void getChangedFiles_detectsDifferentMd5WithinMetadataWindow() {
        // Same size, mtime difference within MODIFY_WINDOW_MS, but proven-different MD5s:
        // the checksum must win over the metadata quick check, otherwise a quick post-sync
        // re-edit (or FAT 2-second granularity) is skipped silently.
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, "abc"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 2000L, "def"));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertEquals(
                1,
                changed.size(),
                "Differing MD5s must detect change even when metadata matches within window");
    }

    @Test
    void getChangedFiles_usesMetadataWhenOneSideLacksMd5() {
        // Only one side hashed (e.g. the other file was unreadable): fall back to the
        // metadata window comparison.
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, "abc"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 2000L, null));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertTrue(
                changed.isEmpty(),
                "With a missing checksum, metadata within window should mean unchanged");
    }

    @Test
    void getChangedFiles_skipsSameMetadataInWindow() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, null));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 2000L, null));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertTrue(
                changed.isEmpty(),
                "Within MODIFY_WINDOW_MS (3000) and same size should be unchanged");
    }

    @Test
    void getChangedFiles_detectsMetadataBeyondWindow() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, null));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 5000L, null));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertEquals(1, changed.size(), "Beyond MODIFY_WINDOW_MS should detect change");
    }

    @Test
    void getChangedFiles_detectsDifferentSizeEvenWithinWindow() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 100, 1000L, null));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("file.txt", new FileChangeDetector.FileInfo("file.txt", 200, 1500L, null));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<FileChangeDetector.FileInfo> changed =
                FileChangeDetector.getChangedFiles(source, target);
        assertEquals(1, changed.size(), "Different size should always detect change");
    }

    @Test
    void getFilesToDelete_returnsPathsOnlyInTarget() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("keep.txt", new FileChangeDetector.FileInfo("keep.txt", 50, 0, "x"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("keep.txt", new FileChangeDetector.FileInfo("keep.txt", 50, 0, "x"));
        targetFiles.put(
                "obsolete.txt", new FileChangeDetector.FileInfo("obsolete.txt", 30, 0, "y"));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        List<String> toDelete = FileChangeDetector.getFilesToDelete(source, target);
        assertEquals(1, toDelete.size());
        assertEquals("obsolete.txt", toDelete.get(0));
    }

    @Test
    void getFilesToDelete_returnsEmptyWhenTargetIsSubset() {
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("a.txt", new FileChangeDetector.FileInfo("a.txt", 10, 0, "x"));
        sourceFiles.put("b.txt", new FileChangeDetector.FileInfo("b.txt", 20, 0, "y"));
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put("a.txt", new FileChangeDetector.FileInfo("a.txt", 10, 0, "x"));

        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        assertTrue(FileChangeDetector.getFilesToDelete(source, target).isEmpty());
    }

    @Test
    void getEmptyDirectoriesToCreate_returnsDirsOnlyInSource() {
        Set<String> sourceDirs = new HashSet<>(List.of("newdir", "shared"));
        Set<String> targetDirs = new HashSet<>(List.of("shared"));

        FileChangeDetector.FileManifest source =
                new FileChangeDetector.FileManifest(new HashMap<>(), sourceDirs);
        FileChangeDetector.FileManifest target =
                new FileChangeDetector.FileManifest(new HashMap<>(), targetDirs);

        List<String> toCreate = FileChangeDetector.getEmptyDirectoriesToCreate(source, target);
        assertEquals(1, toCreate.size());
        assertEquals("newdir", toCreate.get(0));
    }

    @Test
    void getEmptyDirectoriesToDelete_returnsDirsOnlyInTargetSortedDeepestFirst() {
        Set<String> sourceDirs = new HashSet<>(List.of("a"));
        Set<String> targetDirs = new HashSet<>(List.of("a", "b/c/d", "b/c", "b"));

        FileChangeDetector.FileManifest source =
                new FileChangeDetector.FileManifest(new HashMap<>(), sourceDirs);
        FileChangeDetector.FileManifest target =
                new FileChangeDetector.FileManifest(new HashMap<>(), targetDirs);

        List<String> toDelete = FileChangeDetector.getEmptyDirectoriesToDelete(source, target);
        assertEquals(3, toDelete.size());
        assertEquals("b/c/d", toDelete.get(0), "Deepest directory should come first");
    }

    @Test
    void getEmptyDirectoriesToDelete_skipsDirThatHoldsFilesOnSource() {
        // Receiver still has an empty foo/ while the sender has put bar.txt inside its foo/. The
        // preview must never pair "transfer foo/bar.txt" with "delete foo/": rmdir is recursive
        // and would wipe the file right after it landed.
        Map<String, FileChangeDetector.FileInfo> sourceFiles = new HashMap<>();
        sourceFiles.put("foo/bar.txt", new FileChangeDetector.FileInfo("foo/bar.txt", 5, 0, "h"));
        FileChangeDetector.FileManifest source = new FileChangeDetector.FileManifest(sourceFiles);
        FileChangeDetector.FileManifest target =
                new FileChangeDetector.FileManifest(new HashMap<>(), new HashSet<>(List.of("foo")));

        assertTrue(
                FileChangeDetector.getEmptyDirectoriesToDelete(source, target).isEmpty(),
                "A directory the sender still populates must not be deleted on the receiver");
    }

    @Test
    void getEmptyDirectoriesToDelete_skipsDirThatHoldsEmptySubdirOnSource() {
        FileChangeDetector.FileManifest source =
                new FileChangeDetector.FileManifest(
                        new HashMap<>(), new HashSet<>(List.of("foo/inner")));
        FileChangeDetector.FileManifest target =
                new FileChangeDetector.FileManifest(new HashMap<>(), new HashSet<>(List.of("foo")));

        assertTrue(
                FileChangeDetector.getEmptyDirectoriesToDelete(source, target).isEmpty(),
                "A directory containing an empty subdirectory on the sender still exists there");
    }

    @Test
    void getEmptyDirectoriesToCreate_skipsDirThatHoldsFilesOnTarget() {
        // Mirror image of the delete case: the sender's foo/ is empty while the receiver's foo/
        // holds files, so the directory already exists there and no CREATE_DIR row belongs in
        // the preview.
        Map<String, FileChangeDetector.FileInfo> targetFiles = new HashMap<>();
        targetFiles.put(
                "foo/existing.txt", new FileChangeDetector.FileInfo("foo/existing.txt", 7, 0, "h"));
        FileChangeDetector.FileManifest source =
                new FileChangeDetector.FileManifest(new HashMap<>(), new HashSet<>(List.of("foo")));
        FileChangeDetector.FileManifest target = new FileChangeDetector.FileManifest(targetFiles);

        assertTrue(
                FileChangeDetector.getEmptyDirectoriesToCreate(source, target).isEmpty(),
                "A directory that exists on the receiver must not be planned for creation");
    }

    @Test
    void fileManifest_noArgConstructorCreatesEmptyManifest() {
        FileChangeDetector.FileManifest manifest = new FileChangeDetector.FileManifest();
        assertTrue(manifest.getFiles().isEmpty());
        assertTrue(manifest.getEmptyDirectories().isEmpty());
        assertEquals(0, manifest.getFileCount());
        assertEquals(0, manifest.getEmptyDirectoryCount());
    }

    @Test
    void fileManifest_singleArgConstructorUsesProvidedMap() {
        Map<String, FileChangeDetector.FileInfo> files = new HashMap<>();
        files.put("a.txt", new FileChangeDetector.FileInfo("a.txt", 10, 0, "hash"));
        FileChangeDetector.FileManifest manifest = new FileChangeDetector.FileManifest(files);
        assertEquals(1, manifest.getFileCount());
        assertTrue(manifest.getEmptyDirectories().isEmpty());
    }

    @Test
    void fileInfo_toStringIncludesPathAndSize() {
        FileChangeDetector.FileInfo info =
                new FileChangeDetector.FileInfo("test.txt", 42, 0, "abc");
        String str = info.toString();
        assertTrue(str.contains("test.txt"));
        assertTrue(str.contains("42"));
    }

    @Test
    void hashFilePrefix_matchesArrayImplementationForTextPrefixes() throws IOException {
        // "\r\na" triples put a CR every 3 bytes, so 4095 and 16383 are CRs — but each is followed by
        // an LF, i.e. a CRLF pair straddling the streaming sample boundary (4095) and the array
        // implementation's chunk boundary (16383), which exercises carrying the pending-CR state
        // across a boundary. Several prefix lengths below also cut right after a CR, covering the
        // trailing-CR flush. Lone CRs (where the flush adds a byte) are covered separately by
        // textHashing_handlesLoneCrAtEveryChunkBoundary.
        byte[] data = "\r\na".repeat(6000).getBytes(StandardCharsets.UTF_8);
        File file = tempDir.resolve("crlf.log").toFile();
        Files.write(file.toPath(), data);

        long[] prefixLengths = {0, 1, 4096, 8191, 8192, 16384, 17999, 18000};
        for (long prefixLength : prefixLengths) {
            FileChangeDetector.PrefixHash hash =
                    FileChangeDetector.hashFilePrefix(file, prefixLength);
            assertEquals(
                    FileChangeDetector.calculateMD5OfPrefix(data, (int) prefixLength),
                    hash.manifestMd5(),
                    "manifest hash must match at prefix length " + prefixLength);
            assertEquals(
                    HashUtil.md5Hex(Arrays.copyOf(data, (int) prefixLength)),
                    hash.rawMd5With(new byte[0]),
                    "raw hash must cover exactly the prefix bytes at length " + prefixLength);
        }
    }

    @Test
    void hashFilePrefix_binaryPrefixIsHashedRaw() throws IOException {
        // Alternating null bytes guarantee the binary classification regardless of the sample.
        byte[] data = new byte[10000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (i % 2 == 0) ? (byte) 0 : (byte) 'a';
        }
        File file = tempDir.resolve("data.bin").toFile();
        Files.write(file.toPath(), data);

        FileChangeDetector.PrefixHash hash = FileChangeDetector.hashFilePrefix(file, 6000);
        assertEquals(HashUtil.md5Hex(Arrays.copyOf(data, 6000)), hash.manifestMd5());
        assertEquals(
                HashUtil.md5Hex(data),
                hash.rawMd5With(Arrays.copyOfRange(data, 6000, data.length)),
                "rawMd5With must continue over the remaining bytes");
    }

    @Test
    void hashFilePrefix_throwsEofWhenFileIsShorterThanPrefix() throws IOException {
        File file = tempDir.resolve("short.log").toFile();
        Files.write(file.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        assertThrows(EOFException.class, () -> FileChangeDetector.hashFilePrefix(file, 6));
    }

    @Test
    void textHashing_handlesLoneCrAtEveryChunkBoundary() throws IOException {
        // A lone CR that sits on a read boundary is flushed as one extra LF ahead of the *next*
        // chunk's output, so normalizing a chunk of HASH_BUFFER_SIZE input bytes can need
        // HASH_BUFFER_SIZE + 1 output bytes. Each offset below parks that lone CR on a boundary of
        // a different hashing path, and is followed by a full chunk:
        //   4095, 12287 -> calculateMD5 / hashFilePrefix (4096-byte sample, then 8192-byte reads)
        //   8191, 16383 -> calculateMD5OfPrefix (8192-byte chunks starting at offset 0)
        // Writing the extra LF at index HASH_BUFFER_SIZE is what produced "Index 8192 out of bounds
        // for length 8192" and aborted manifest generation for the whole folder.
        int length = 24576;
        for (int loneCrOffset : new int[] {4095, 8191, 12287, 16383}) {
            byte[] data = new byte[length];
            Arrays.fill(data, (byte) 'a');
            data[loneCrOffset] = (byte) '\r';
            data[loneCrOffset + 1] = (byte) 'b'; // not LF, so the CR stays lone

            String expected = normalizedMd5Hex(data, length);
            String context = " with a lone CR at offset " + loneCrOffset;
            File file = tempDir.resolve("lone-cr-" + loneCrOffset + ".txt").toFile();
            Files.write(file.toPath(), data);

            assertEquals(expected, FileChangeDetector.calculateMD5(file), "calculateMD5" + context);
            assertEquals(
                    expected,
                    FileChangeDetector.calculateMD5OfPrefix(data, length),
                    "calculateMD5OfPrefix" + context);
            assertEquals(
                    expected,
                    FileChangeDetector.hashFilePrefix(file, length).manifestMd5(),
                    "hashFilePrefix" + context);
        }
    }

    /**
     * Independent oracle for the documented normalization (CRLF and a lone CR both collapse to one
     * LF), deliberately sharing no code with the implementation under test.
     */
    private static String normalizedMd5Hex(byte[] data, int length) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (int i = 0; i < length; i++) {
                if (data[i] == (byte) '\r') {
                    if (i + 1 < length && data[i + 1] == (byte) '\n') {
                        i++;
                    }
                    md.update((byte) '\n');
                } else {
                    md.update(data[i]);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }
    }
}
