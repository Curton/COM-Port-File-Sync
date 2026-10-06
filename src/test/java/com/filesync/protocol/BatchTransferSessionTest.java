package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.filesync.sync.FileChangeDetector;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Tests for BatchTransferSession binary batch encoding and decoding. */
class BatchTransferSessionTest {

    @TempDir Path tempDir;

    @Test
    void buildBatchWithEmptyListReturnsValidHeader() throws IOException {
        byte[] batch = BatchTransferSession.buildBatch(List.of(), 65536);

        assertTrue(
                batch.length >= 9,
                "Batch should have at least magic(4) + version(1) + count(4) bytes");
        assertEquals(0x42, batch[0], "First magic byte should be 'B'");
        assertEquals(0x54, batch[1], "Second magic byte should be 'T'");
        assertEquals(0x48, batch[2], "Third magic byte should be 'H'");
        assertEquals(0x00, batch[3], "Fourth magic byte should be 0");
        assertEquals(2, batch[4], "Version should be 2");

        int count =
                ((batch[5] & 0xFF) << 24)
                        | ((batch[6] & 0xFF) << 16)
                        | ((batch[7] & 0xFF) << 8)
                        | (batch[8] & 0xFF);
        assertEquals(0, count, "Entry count should be 0 for empty list");
    }

    @ParameterizedTest
    @MethodSource("singleFileBatchCases")
    void singleFileBatchRoundTrips(
            String relPath, byte[] content, int maxBatchSize, boolean assertLastModified)
            throws IOException {
        File srcFile = tempDir.resolve("src").toFile();
        Files.write(srcFile.toPath(), content);

        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] {srcFile, relPath});
        byte[] batch = BatchTransferSession.buildBatch(files, maxBatchSize);

        // The extract dir is not pre-created: the decoder must create missing parent directories
        // (including the extract dir itself) via mkdirs().
        File extractDir = tempDir.resolve("extracted").toFile();

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null);
        assertEquals(1, written, "Should have written 1 file");

        File extracted = new File(extractDir, relPath);
        assertTrue(extracted.exists(), relPath + " should exist after decode");
        assertArrayEquals(
                content, Files.readAllBytes(extracted.toPath()), relPath + " content should match");
        if (assertLastModified) {
            assertEquals(
                    srcFile.lastModified(),
                    extracted.lastModified(),
                    "Last modified should be preserved");
        }
    }

    private static Stream<Arguments> singleFileBatchCases() {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            large.append("Line ").append(i).append(": Some test content for large file\n");
        }
        byte[] binary = new byte[256];
        for (int i = 0; i < 256; i++) {
            binary[i] = (byte) i;
        }
        return Stream.of(
                // buildBatchSingleFileRoundTrip: also asserted lastModified preservation.
                arguments(
                        "example.txt",
                        "Hello, World!".getBytes(StandardCharsets.UTF_8),
                        65536,
                        true),
                // decodeBatchCreatesMissingParentDirectories
                arguments(
                        "subdir/nested/deep.txt",
                        "deep content".getBytes(StandardCharsets.UTF_8),
                        65536,
                        false),
                // buildBatchWithLargeFile
                arguments(
                        "large.bin",
                        large.toString().getBytes(StandardCharsets.UTF_8),
                        65536,
                        false),
                // buildBatchWithBinaryContent
                arguments("binary.bin", binary, 65536, false),
                // buildBatchWithZeroMaxBatchSizeUsesDefault
                arguments("test.txt", "content".getBytes(StandardCharsets.UTF_8), 0, false),
                // buildBatchWithUnicodeFilenames
                arguments(
                        "文件.txt", "unicode content".getBytes(StandardCharsets.UTF_8), 65536, false),
                // buildBatchWithSpecialCharactersInPath
                arguments(
                        "path with spaces/file&special#chars.txt",
                        "special path content".getBytes(StandardCharsets.UTF_8),
                        65536,
                        false),
                // buildBatchWithEmptyFile
                arguments("empty.txt", new byte[0], 65536, false));
    }

    @Test
    void buildBatchMultipleFilesRoundTrip() throws IOException {
        File dir = tempDir.resolve("input").toFile();
        dir.mkdirs();

        List<Object[]> files = new ArrayList<>();
        String[] names = {"a.txt", "b.txt", "c.txt"};
        String[] contents = {"content A", "content B", "content C"};

        for (int i = 0; i < names.length; i++) {
            File f = new File(dir, names[i]);
            Files.writeString(f.toPath(), contents[i]);
            files.add(new Object[] {f, names[i]});
        }

        byte[] batch = BatchTransferSession.buildBatch(files, 65536);

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        int[] progress = new int[1];
        BatchTransferSession.BatchProgressCallback callback =
                (idx, total, relPath) -> progress[0]++;

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 3, callback);
        assertEquals(3, written, "Should have written 3 files");
        assertEquals(3, progress[0], "Callback should have been called 3 times");

        for (int i = 0; i < names.length; i++) {
            File extracted = new File(extractDir, names[i]);
            assertTrue(extracted.exists(), names[i] + " should exist");
            assertEquals(
                    contents[i],
                    Files.readString(extracted.toPath()),
                    names[i] + " content should match");
        }
    }

    @ParameterizedTest
    @MethodSource("malformedBatchCases")
    void decodeBatchRejectsMalformedBatches(byte[] batch, String expectedMessageFragment) {
        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null));
        assertTrue(
                thrown.getMessage().contains(expectedMessageFragment),
                "Should report: " + expectedMessageFragment + ", got: " + thrown.getMessage());
    }

    private static Stream<Arguments> malformedBatchCases() {
        return Stream.of(
                // decodeBatchInvalidMagicThrowsException
                arguments(
                        new byte[] {0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x01},
                        "bad magic"),
                // decodeBatchUnsupportedVersionThrowsException
                arguments(
                        new byte[] {0x42, 0x54, 0x48, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00},
                        "Unsupported batch version"),
                // decodeBatchWithTruncatedDataThrowsException
                arguments(
                        new byte[] {0x42, 0x54, 0x48, 0x00, 0x02, 0x00, 0x00, 0x00, 0x01},
                        "Unexpected end of batch stream"),
                // decodeBatchRejectsHugeEntryDataLength: LEN = Integer.MAX_VALUE
                arguments(
                        new byte[] {
                            0x42,
                            0x54,
                            0x48,
                            0x00, // magic
                            0x02, // version
                            0x00,
                            0x00,
                            0x00,
                            0x01, // count = 1
                            0x00,
                            0x01, // path len = 1
                            (byte) 'a', // path
                            0x00,
                            0x00,
                            0x00,
                            0x00,
                            0x00,
                            0x00,
                            0x00,
                            0x00, // lastModified
                            0x00, // flags
                            (byte) 0x7F,
                            (byte) 0xFF,
                            (byte) 0xFF,
                            (byte) 0xFF // dataLen
                        },
                        "Invalid data length"),
                // decodeBatchRejectsNegativeEntryDataLength: LEN = Integer.MIN_VALUE
                arguments(
                        new byte[] {
                            0x42,
                            0x54,
                            0x48,
                            0x00, // magic
                            0x02, // version
                            0x00,
                            0x00,
                            0x00,
                            0x01, // count = 1
                            0x00,
                            0x01, // path len = 1
                            (byte) 'a', // path
                            0x00,
                            0x00,
                            0x00,
                            0x00,
                            0x00,
                            0x00,
                            0x00,
                            0x00, // lastModified
                            0x00, // flags
                            (byte) 0x80,
                            0x00,
                            0x00,
                            0x00 // dataLen
                        },
                        "Invalid data length"),
                // decodeBatchRejectsEntryCountOverMax: count = 257 (max is 256)
                arguments(
                        new byte[] {
                            0x42,
                            0x54,
                            0x48,
                            0x00, // magic
                            0x02, // version
                            0x00,
                            0x00,
                            0x01,
                            0x01 // count = 257
                        },
                        "Invalid batch entry count"),
                // decodeBatchRejectsNegativeEntryCount: count = -1
                arguments(
                        new byte[] {
                            0x42,
                            0x54,
                            0x48,
                            0x00, // magic
                            0x02, // version
                            (byte) 0xFF,
                            (byte) 0xFF,
                            (byte) 0xFF,
                            (byte) 0xFF // count = -1
                        },
                        "Invalid batch entry count"),
                // decodeBatchRejectsHugePathLength: pathLen = 65535, no path bytes follow
                arguments(
                        new byte[] {
                            0x42,
                            0x54,
                            0x48,
                            0x00, // magic
                            0x02, // version
                            0x00,
                            0x00,
                            0x00,
                            0x01, // count = 1
                            (byte) 0xFF,
                            (byte) 0xFF // path len = 65535
                        },
                        "Invalid path length"));
    }

    @Test
    void buildBatchRejectsOverMaxEntries() {
        File dir = tempDir.resolve("input").toFile();
        dir.mkdirs();

        List<Object[]> files = new ArrayList<>();
        for (int i = 0; i < 260; i++) {
            File f = new File(dir, "file_" + i + ".txt");
            try {
                Files.writeString(f.toPath(), "content " + i);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            files.add(new Object[] {f, "file_" + i + ".txt"});
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> BatchTransferSession.buildBatch(files, 65536));
    }

    // ========== Path containment ==========

    /**
     * A substring test for {@code ".."} rejects legitimate names such as {@code notes..txt}, which
     * aborts the whole batch transfer and fails the sync for a file that is perfectly safe.
     * Backslash-separated paths are what the manifest walk produces on Windows, so the containment
     * check must normalize separators before rejecting anything.
     */
    @Test
    void decodeBatchAcceptsDoubleDotNamesAndBackslashSeparatedPaths() throws IOException {
        byte[] batch =
                buildBatch(
                        new String[] {"notes..txt", "dir..name/data.txt", "sub\\nested\\file.txt"},
                        new String[] {"A", "B", "A"});

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 3, null);
        assertEquals(
                3,
                written,
                "A '..' inside a name is not a traversal and Windows-style relative paths must be accepted");
        assertEquals("A", Files.readString(new File(extractDir, "notes..txt").toPath()));
        assertEquals("B", Files.readString(new File(extractDir, "dir..name/data.txt").toPath()));
        assertEquals("A", Files.readString(new File(extractDir, "sub\\nested\\file.txt").toPath()));
    }

    @Test
    void decodeBatchRejectsPathsThatDoNotResolveInsideTheBaseDirectory() throws IOException {
        byte[] batch = buildBatch(new String[] {"\\evil.txt"}, new String[] {"A"});

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null));
        assertTrue(
                thrown.getMessage().contains("Path traversal rejected"),
                "Should report a traversal rejection, got: " + thrown.getMessage());
        assertFalse(
                new File(extractDir, "evil.txt").exists(),
                "Nothing may be written for a rejected path");
    }

    // ========== Per-entry write failures (locked files) ==========

    /**
     * Build a batch with the given (relativePath, content) pairs. Callers keep the pair order; no
     * sorting is applied here.
     */
    private byte[] buildBatch(String[] paths, String[] contents) throws IOException {
        File dir = tempDir.resolve("batch-input").toFile();
        dir.mkdirs();
        List<Object[]> files = new ArrayList<>();
        for (int i = 0; i < paths.length; i++) {
            File f = new File(dir, "src_" + i);
            Files.writeString(f.toPath(), contents[i]);
            files.add(new Object[] {f, paths[i]});
        }
        return BatchTransferSession.buildBatch(files, 65536);
    }

    @Test
    void decodeBatchWithWriteFailure_continuesWithRemainingEntriesAndReportsFailure()
            throws IOException {
        byte[] batch =
                buildBatch(
                        new String[] {"a.txt", "b.txt"}, new String[] {"content A", "content B"});

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();
        // A directory at the target path makes FileOutputStream fail on every platform, which is
        // how a file locked by another program surfaces on Windows.
        new File(extractDir, "a.txt").mkdirs();

        List<String> failedPaths = new ArrayList<>();
        List<String> failedContents = new ArrayList<>();
        int written =
                BatchTransferSession.decodeAndWriteBatch(
                        extractDir,
                        batch,
                        2,
                        null,
                        (path, data, lastModified, message, cause) -> {
                            failedPaths.add(path);
                            failedContents.add(new String(data, StandardCharsets.UTF_8));
                        });

        assertEquals(1, written, "Only the writable entry should be written");
        assertEquals(List.of("a.txt"), failedPaths, "The locked entry must be reported");
        assertEquals(
                List.of("content A"),
                failedContents,
                "The handler must receive the decoded payload of the locked entry");
        File extractedB = new File(extractDir, "b.txt");
        assertTrue(extractedB.exists(), "Remaining entries must still be written");
        assertEquals("content B", Files.readString(extractedB.toPath()));
    }

    @Test
    void decodeBatchWithMultipleWriteFailures_reportsEachOneAndWritesTheRest() throws IOException {
        byte[] batch =
                buildBatch(new String[] {"a.txt", "b.txt", "c.txt"}, new String[] {"A", "B", "C"});

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();
        new File(extractDir, "a.txt").mkdirs();
        new File(extractDir, "c.txt").mkdirs();

        List<String> failedPaths = new ArrayList<>();
        int written =
                BatchTransferSession.decodeAndWriteBatch(
                        extractDir,
                        batch,
                        3,
                        null,
                        (path, data, lastModified, message, cause) -> failedPaths.add(path));

        assertEquals(1, written, "Only the writable entry should be written");
        assertEquals(List.of("a.txt", "c.txt"), failedPaths, "Each locked entry must be reported");
        assertTrue(
                new File(extractDir, "b.txt").exists(), "Writable entries must still be written");
    }

    @Test
    void decodeBatchWithWriteFailure_progressCallbackOnlyFiresForSuccessfulEntries()
            throws IOException {
        byte[] batch = buildBatch(new String[] {"a.txt", "b.txt"}, new String[] {"A", "B"});

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();
        new File(extractDir, "a.txt").mkdirs();

        int[] progress = new int[1];
        BatchTransferSession.BatchProgressCallback callback =
                (idx, total, relPath) -> progress[0]++;

        int written =
                BatchTransferSession.decodeAndWriteBatch(
                        extractDir,
                        batch,
                        2,
                        callback,
                        (path, data, lastModified, message, cause) -> {});

        assertEquals(1, written, "Should have written 1 file");
        assertEquals(1, progress[0], "Progress callback should only fire for written entries");
    }

    @Test
    void decodeBatchWithWriteFailure_noHandlerStillThrows() throws IOException {
        byte[] batch = buildBatch(new String[] {"a.txt", "b.txt"}, new String[] {"A", "B"});

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();
        new File(extractDir, "a.txt").mkdirs();

        // The 4-arg overload (no failure handler) must preserve the legacy abort-on-failure
        // behavior.
        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null));
        assertTrue(thrown.getMessage() != null, "Write failure should carry the OS error message");
    }

    // ========== Per-entry manifest md5 (version 2 envelope) ==========

    @Test
    void buildBatchWithMd5_writesVerifiedEntryAndConfirmsIt() throws IOException {
        String content = "Hello, World!";
        File f = tempDir.resolve("example.txt").toFile();
        Files.writeString(f.toPath(), content);
        String md5 = FileChangeDetector.manifestMd5(content.getBytes(StandardCharsets.UTF_8));

        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] {f, "example.txt", md5});
        byte[] batch = BatchTransferSession.buildBatch(files, 65536);

        // The version-2 entry inserts 16 raw md5 bytes between LAST_MODIFIED and FLAGS, so the
        // envelope is exactly 16 bytes longer than a hash-less entry of the same content.
        byte[] hashless =
                BatchTransferSession.buildBatch(
                        List.<Object[]>of(new Object[] {f, "example.txt"}), 65536);
        assertEquals(16, batch.length - hashless.length, "16 raw MD5 bytes must be inserted");

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();
        List<String> confirmed = new ArrayList<>();
        long[] confirmedSize = new long[1];
        int written =
                BatchTransferSession.decodeAndWriteBatch(
                        extractDir,
                        batch,
                        0,
                        null,
                        null,
                        (path, entryMd5, size) -> {
                            confirmed.add(path + ":" + entryMd5);
                            confirmedSize[0] = size;
                        });

        assertEquals(1, written);
        assertEquals(content, Files.readString(new File(extractDir, "example.txt").toPath()));
        assertEquals(
                List.of("example.txt:" + md5),
                confirmed,
                "a verified entry must be confirmed with its md5 hex");
        assertEquals(
                content.length(),
                confirmedSize[0],
                "the confirmed size is the decoded entry length");
    }

    @Test
    void decodeBatchRejectsEntryWhoseMd5DoesNotMatch() throws IOException {
        File f = tempDir.resolve("example.txt").toFile();
        Files.writeString(f.toPath(), "Hello, World!");
        // Correct for some other content: the decoded entry must fail verification.
        String wrongMd5 = FileChangeDetector.manifestMd5("other".getBytes(StandardCharsets.UTF_8));

        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] {f, "example.txt", wrongMd5});
        byte[] batch = BatchTransferSession.buildBatch(files, 65536);

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();
        List<String> failedPaths = new ArrayList<>();
        List<BatchTransferSession.WriteFailureCause> causes = new ArrayList<>();
        List<String> confirmed = new ArrayList<>();
        int written =
                BatchTransferSession.decodeAndWriteBatch(
                        extractDir,
                        batch,
                        0,
                        null,
                        (path, data, lastModified, message, cause) -> {
                            failedPaths.add(path);
                            causes.add(cause);
                        },
                        (path, entryMd5, size) -> confirmed.add(path));

        assertEquals(0, written, "a mismatched entry must not be written");
        assertFalse(
                new File(extractDir, "example.txt").exists(),
                "corrupt content must never reach the disk");
        assertEquals(List.of("example.txt"), failedPaths, "the mismatch must be reported");
        assertEquals(
                List.of(BatchTransferSession.WriteFailureCause.HASH_MISMATCH),
                causes,
                "a mismatched entry must be reported as HASH_MISMATCH (never retry these bytes)");
        assertTrue(confirmed.isEmpty(), "nothing to confirm for a rejected entry");
    }

    @Test
    void decodeBatchWithoutMd5_confirmsEntryWithNullHash() throws IOException {
        byte[] batch = buildBatch(new String[] {"a.txt"}, new String[] {"A"});

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();
        List<String> confirmedMd5 = new ArrayList<>();
        int written =
                BatchTransferSession.decodeAndWriteBatch(
                        extractDir,
                        batch,
                        0,
                        null,
                        null,
                        (path, entryMd5, size) -> confirmedMd5.add(String.valueOf(entryMd5)));

        assertEquals(1, written, "a hash-less entry (fast mode) is written unverified");
        assertEquals(List.of("null"), confirmedMd5, "no announced md5 means a null hash");
    }
}
