package com.filesync.delta;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The streaming delta paths: a source larger than a literal chunk, encoded straight from a stream
 * and decoded straight to disk, plus the failure modes of a stream that ends before its announced
 * size. These complement the in-memory round-trips in {@link DeltaEncoderDecoderTest}.
 */
class DeltaStreamTransferTest {

    private static final int BLOCK = 64;
    private static final int LITERAL_CHUNK = 64 * 1024;

    @TempDir Path tempDir;

    private byte[] randomBytes(int len, long seed) {
        Random rng = new Random(seed);
        byte[] b = new byte[len];
        rng.nextBytes(b);
        return b;
    }

    /** Write {@code bytes} to a fresh file in the temp dir and return it. */
    private Path file(String name, byte[] bytes) throws IOException {
        Path p = tempDir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    @Test
    void streamEncodeBulkLiteralPathEmitsMultipleChunks() throws IOException {
        byte[] source = randomBytes(LITERAL_CHUNK + 5000, 71);
        // blockSize 0 disables matching, so the whole source pumps through the bulk literal path
        // and must be emitted as two LITERAL tokens (one full 64-KiB chunk, one remainder).
        FileSignatures sigs =
                new FileSignatures(
                        "x",
                        0,
                        1,
                        source.length,
                        List.of(
                                new BlockSignature(
                                        0, 0, new byte[BlockSignature.STRONG_HASH_LENGTH])));
        byte[] delta;
        try (InputStream in = new ByteArrayInputStream(source)) {
            delta = DeltaEncoder.encode(in, source.length, sigs);
        }
        // Header (17) + LITERAL(5+chunk) + LITERAL(5+remainder): the encoder chunks at 64 KiB.
        assertEquals(
                17 + (5 + LITERAL_CHUNK) + (5 + (source.length - LITERAL_CHUNK)), delta.length);
        // Patch in a decodable blockSize (the real one is 0, which the decoder rejects) and
        // confirm the literal payload reproduces the source byte-for-byte.
        byte[] decodable = delta.clone();
        decodable[5] = 0;
        decodable[6] = 0;
        decodable[7] = 0;
        decodable[8] = 64;
        assertArrayEquals(source, DeltaDecoder.decode(new byte[0], decodable));
    }

    @Test
    void streamEncodeWithLargeLiteralRunDecodesInOrder() throws IOException {
        byte[] base = randomBytes(BLOCK * 2, 21);
        FileSignatures sigs = SignatureUtil.compute("x", base, BLOCK);
        // An unmatchable tail far larger than one literal chunk: the run must be emitted as
        // consecutive LITERAL tokens and still reconstruct byte-for-byte.
        byte[] tail = randomBytes(LITERAL_CHUNK * 2 + 12345, 22);
        byte[] source = new byte[base.length + tail.length];
        System.arraycopy(base, 0, source, 0, base.length);
        System.arraycopy(tail, 0, source, base.length, tail.length);

        byte[] delta;
        try (InputStream in = new ByteArrayInputStream(source)) {
            delta = DeltaEncoder.encode(in, source.length, sigs);
        }
        assertArrayEquals(source, DeltaDecoder.decode(base, delta));
    }

    @Test
    void fileToFileStreamRoundTripReproducesSourceAndMd5() throws IOException {
        byte[] baseBytes = randomBytes(512 * 1024, 31);
        byte[] sourceBytes = baseBytes.clone();
        Random rng = new Random(32);
        // Mutate scattered single bytes: mostly COPY runs with literal splits between them.
        for (int k = 0; k < 64; k++) {
            sourceBytes[rng.nextInt(sourceBytes.length)] = (byte) rng.nextInt();
        }
        Path baseFile = file("base.bin", baseBytes);
        Path sourceFile = file("source.bin", sourceBytes);
        Path deltaFile = tempDir.resolve("delta.bin");
        Path rebuiltFile = tempDir.resolve("rebuilt.bin");

        FileSignatures sigs = SignatureUtil.compute("x", baseBytes, BLOCK);
        byte[] delta;
        try (InputStream in = new BufferedInputStream(new FileInputStream(sourceFile.toFile()))) {
            delta = DeltaEncoder.encode(in, Files.size(sourceFile), sigs);
        }
        Files.write(deltaFile, delta);

        long written;
        java.security.MessageDigest md5 = Md5.newDigest();
        try (RandomAccessFile base = new RandomAccessFile(baseFile.toFile(), "r");
                DataInputStream deltaIn =
                        new DataInputStream(
                                new BufferedInputStream(new FileInputStream(deltaFile.toFile())));
                DigestOutputStream rebuilt =
                        new DigestOutputStream(
                                new BufferedOutputStream(
                                        new FileOutputStream(rebuiltFile.toFile())),
                                md5)) {
            written = DeltaDecoder.decodeInto(base, deltaIn, rebuilt);
        }
        assertEquals(sourceBytes.length, written);
        assertArrayEquals(sourceBytes, Files.readAllBytes(rebuiltFile));
        // The streamed digest matches the file hash: the production receiver verifies exactly so.
        assertEquals(HashUtil.md5Hex(sourceFile.toFile()), Md5.toHex(md5.digest()));
    }

    @Test
    void truncatedStreamFailsWithAnnouncedSize() throws IOException {
        byte[] base = randomBytes(BLOCK * 2, 41);
        FileSignatures sigs = SignatureUtil.compute("x", base, BLOCK);
        // The stream delivers fewer bytes than announced: the window-initialising read fails.
        InputStream shortInit = new ByteArrayInputStream(new byte[10]);
        assertThrows(IOException.class, () -> DeltaEncoder.encode(shortInit, 1000, sigs));

        // Enough bytes for the initial window but not the announced total: a mid-scan slide
        // runs off the end of the stream.
        InputStream shortSlide = new ByteArrayInputStream(randomBytes(BLOCK + 5, 42));
        assertThrows(IOException.class, () -> DeltaEncoder.encode(shortSlide, BLOCK + 10, sigs));
    }

    @Test
    void decodeIntoRejectsAnEmptyStream() throws IOException {
        Path baseFile = file("base.bin", randomBytes(BLOCK * 2, 51));
        try (RandomAccessFile base = new RandomAccessFile(baseFile.toFile(), "r");
                DataInputStream empty =
                        new DataInputStream(new ByteArrayInputStream(new byte[0]))) {
            IOException e =
                    assertThrows(
                            IOException.class,
                            () ->
                                    DeltaDecoder.decodeInto(
                                            base, empty, OutputStream.nullOutputStream()));
            assertEquals("Empty delta stream", e.getMessage());
        }
    }

    @Test
    void decodeIntoReportsMalformedDeltaFromTheWire() throws IOException {
        byte[] base = randomBytes(BLOCK * 2, 61);
        Path baseFile = file("base.bin", base);
        // A delta whose header lies about the version must fail even streamed from a file.
        byte[] delta = DeltaEncoder.encode(base, SignatureUtil.compute("x", base, BLOCK));
        delta[4] = 99;
        Path deltaFile = file("bad.bin", delta);
        try (RandomAccessFile baseRa = new RandomAccessFile(baseFile.toFile(), "r");
                DataInputStream in =
                        new DataInputStream(
                                new BufferedInputStream(new FileInputStream(deltaFile.toFile())))) {
            assertThrows(
                    IOException.class,
                    () -> DeltaDecoder.decodeInto(baseRa, in, OutputStream.nullOutputStream()));
        }
    }
}
