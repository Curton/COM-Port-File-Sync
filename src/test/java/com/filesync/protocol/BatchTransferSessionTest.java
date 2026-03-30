package com.filesync.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertEquals(1, batch[4], "Version should be 1");

        int count =
                ((batch[5] & 0xFF) << 24)
                        | ((batch[6] & 0xFF) << 16)
                        | ((batch[7] & 0xFF) << 8)
                        | (batch[8] & 0xFF);
        assertEquals(0, count, "Entry count should be 0 for empty list");
    }

    @Test
    void buildBatchSingleFileRoundTrip() throws IOException {
        File testFile = tempDir.resolve("example.txt").toFile();
        Files.writeString(testFile.toPath(), "Hello, World!");

        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] {testFile, "example.txt"});
        byte[] batch = BatchTransferSession.buildBatch(files, 65536);

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null);
        assertEquals(1, written, "Should have written 1 file");

        File extracted = new File(extractDir, "example.txt");
        assertTrue(extracted.exists(), "Extracted file should exist");
        assertEquals("Hello, World!", Files.readString(extracted.toPath()), "Content should match");
        assertEquals(
                testFile.lastModified(),
                extracted.lastModified(),
                "Last modified should be preserved");
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

    @Test
    void decodeBatchCreatesMissingParentDirectories() throws IOException {
        File dir = tempDir.resolve("input2").toFile();
        dir.mkdirs();

        File nestedFile = new File(dir, "subdir/nested/deep.txt");
        nestedFile.getParentFile().mkdirs();
        Files.writeString(nestedFile.toPath(), "deep content");

        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] {nestedFile, "subdir/nested/deep.txt"});
        byte[] batch = BatchTransferSession.buildBatch(files, 65536);

        File extractDir = tempDir.resolve("extracted2").toFile();
        // Do NOT call extractDir.mkdirs() here - parent directories should not exist
        // so that decodeAndWriteBatch must create them via mkdirs()

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null);
        assertEquals(1, written, "Should have written 1 file");

        File extracted = new File(extractDir, "subdir/nested/deep.txt");
        assertTrue(extracted.exists(), "Nested file should exist after decode");
        assertTrue(
                extracted.getParentFile().exists(), "Parent directories should have been created");
        assertEquals("deep content", Files.readString(extracted.toPath()));
    }

    @Test
    void buildBatchWithLargeFile() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("Line ").append(i).append(": Some test content for large file\n");
        }
        String largeContent = sb.toString();

        File largeFile = tempDir.resolve("large.bin").toFile();
        Files.writeString(largeFile.toPath(), largeContent);

        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] {largeFile, "large.bin"});
        byte[] batch = BatchTransferSession.buildBatch(files, 65536);

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null);
        assertEquals(1, written, "Should have written 1 file");

        File extracted = new File(extractDir, "large.bin");
        assertEquals(
                largeContent,
                Files.readString(extracted.toPath()),
                "Large file content should match");
    }

    @Test
    void buildBatchWithBinaryContent() throws IOException {
        byte[] binaryData = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryData[i] = (byte) i;
        }

        File binaryFile = tempDir.resolve("binary.bin").toFile();
        Files.write(binaryFile.toPath(), binaryData);

        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] {binaryFile, "binary.bin"});
        byte[] batch = BatchTransferSession.buildBatch(files, 65536);

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null);
        assertEquals(1, written, "Should have written 1 file");

        File extracted = new File(extractDir, "binary.bin");
        byte[] recovered = Files.readAllBytes(extracted.toPath());
        assertEquals(256, recovered.length, "Binary file size should be preserved");
        for (int i = 0; i < 256; i++) {
            assertEquals((byte) i, recovered[i], "Binary byte at index " + i + " should match");
        }
    }

    @Test
    void decodeBatchInvalidMagicThrowsException() {
        byte[] badBatch = new byte[] {0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01};

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                BatchTransferSession.decodeAndWriteBatch(
                                        extractDir, badBatch, 0, null));
        assertTrue(thrown.getMessage().contains("bad magic"), "Should report bad magic bytes");
    }

    @Test
    void decodeBatchUnsupportedVersionThrowsException() {
        byte[] badBatch = new byte[] {0x42, 0x54, 0x48, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00};

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () ->
                                BatchTransferSession.decodeAndWriteBatch(
                                        extractDir, badBatch, 0, null));
        assertTrue(
                thrown.getMessage().contains("Unsupported batch version"),
                "Should report unsupported version");
    }

    @Test
    void buildBatchWithZeroMaxBatchSizeUsesDefault() throws IOException {
        File testFile = tempDir.resolve("test.txt").toFile();
        Files.writeString(testFile.toPath(), "content");

        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] {testFile, "test.txt"});
        byte[] batch = BatchTransferSession.buildBatch(files, 0);

        assertNotNull(batch, "Should return a valid batch even with 0 maxBatchSize");
        assertTrue(batch.length > 0, "Batch should contain data");

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null);
        assertEquals(1, written, "Should have written 1 file");
    }

    @Test
    void buildBatchEnforcesMaxEntriesLimit() throws IOException {
        File dir = tempDir.resolve("input").toFile();
        dir.mkdirs();

        List<Object[]> files = new ArrayList<>();
        for (int i = 0; i < 72; i++) {
            File f = new File(dir, "file_" + i + ".txt");
            Files.writeString(f.toPath(), "content " + i);
            files.add(new Object[] {f, "file_" + i + ".txt"});
        }

        byte[] batch = BatchTransferSession.buildBatch(files, 65536);

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null);
        assertEquals(64, written, "Should be limited to MAX_ENTRIES_PER_BATCH (64)");
    }

    @Test
    void buildBatchWithUnicodeFilenames() throws IOException {
        File dir = tempDir.resolve("input").toFile();
        dir.mkdirs();

        String[] unicodeNames = {"文件.txt", "файл.txt", "αρχείο.txt", "emoji_\uD83D\uDE00.txt"};
        File testFile = new File(dir, unicodeNames[0]);
        Files.writeString(testFile.toPath(), "unicode content");

        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] {testFile, unicodeNames[0]});
        byte[] batch = BatchTransferSession.buildBatch(files, 65536);

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null);
        assertEquals(1, written, "Should have written 1 file");

        File extracted = new File(extractDir, unicodeNames[0]);
        assertTrue(extracted.exists(), "File with unicode name should exist");
        assertEquals("unicode content", Files.readString(extracted.toPath()));
    }

    @Test
    void buildBatchWithSpecialCharactersInPath() throws IOException {
        File dir = tempDir.resolve("input").toFile();
        dir.mkdirs();

        File testFile = new File(dir, "path with spaces/file&special#chars.txt");
        testFile.getParentFile().mkdirs();
        Files.writeString(testFile.toPath(), "special path content");

        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] {testFile, "path with spaces/file&special#chars.txt"});
        byte[] batch = BatchTransferSession.buildBatch(files, 65536);

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null);
        assertEquals(1, written, "Should have written 1 file");

        File extracted = new File(extractDir, "path with spaces/file&special#chars.txt");
        assertTrue(extracted.exists(), "File with special chars in path should exist");
        assertEquals("special path content", Files.readString(extracted.toPath()));
    }

    @Test
    void buildBatchWithEmptyFile() throws IOException {
        File emptyFile = tempDir.resolve("empty.txt").toFile();
        Files.writeString(emptyFile.toPath(), "");

        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] {emptyFile, "empty.txt"});
        byte[] batch = BatchTransferSession.buildBatch(files, 65536);

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        int written = BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null);
        assertEquals(1, written, "Should have written 1 file");

        File extracted = new File(extractDir, "empty.txt");
        assertTrue(extracted.exists(), "Empty file should exist");
        assertEquals(0, extracted.length(), "Empty file should have 0 length");
    }

    @Test
    void decodeBatchWithTruncatedDataThrowsException() {
        byte[] batch = new byte[] {0x42, 0x54, 0x48, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01};

        File extractDir = tempDir.resolve("extracted").toFile();
        extractDir.mkdirs();

        IOException thrown =
                assertThrows(
                        IOException.class,
                        () -> BatchTransferSession.decodeAndWriteBatch(extractDir, batch, 0, null));
        assertTrue(
                thrown.getMessage().contains("Unexpected end of batch stream"),
                "Should report truncated data");
    }
}
