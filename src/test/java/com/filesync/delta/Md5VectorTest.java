package com.filesync.delta;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link Md5}/{@link HashUtil} output to the known-answer test vectors of RFC 1321. Every
 * other test in the tree uses md5Hex as its correctness oracle (manifest hashes, transfer
 * verification), so a broken digest or hex formatter here would make those oracles wrong the same
 * way and still pass; these external constants are the anchor that cannot drift with the
 * implementation.
 */
class Md5VectorTest {

    @Test
    void hexMatchesRfc1321Vectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Md5.hex("".getBytes(US_ASCII)));
        assertEquals("0cc175b9c0f1b6a831c399e269772661", Md5.hex("a".getBytes(US_ASCII)));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Md5.hex("abc".getBytes(US_ASCII)));
        assertEquals(
                "f96b697d7cb7938d525a2f31aaf161d0", Md5.hex("message digest".getBytes(US_ASCII)));
        assertEquals(
                "c3fcd3d76192e4007dfb496cca67e13b",
                Md5.hex("abcdefghijklmnopqrstuvwxyz".getBytes(US_ASCII)));
        assertEquals(
                "d174ab98d277d9f5a5611c2c9f419d9f",
                Md5.hex(
                        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                                .getBytes(US_ASCII)));
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", Md5.hex(eightyDigits()));
    }

    private static byte[] eightyDigits() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append("1234567890");
        }
        return sb.toString().getBytes(US_ASCII);
    }

    @Test
    void toHexFormatsLowercaseWithPadding() {
        assertEquals("", Md5.toHex(new byte[0]));
        assertEquals("00ff10", Md5.toHex(new byte[] {0x00, (byte) 0xff, 0x10}));
    }

    @Test
    void hashUtilByteOverloadAgreesWithMd5() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", HashUtil.md5Hex("abc".getBytes(US_ASCII)));
    }

    @Test
    void fileOverloadStreamsIdenticallyToOneShot(@TempDir Path tempDir) throws IOException {
        // Crosses HashUtil's 8192-byte chunk boundary twice plus a remainder, so the streaming
        // loop must stitch at least three updates into the same digest as the one-shot path.
        int size = 8192 * 2 + 100;
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i * 31 + (i >>> 8));
        }
        Path file = tempDir.resolve("chunk-crossing.bin");
        Files.write(file, data);

        assertEquals(HashUtil.md5Hex(data), HashUtil.md5Hex(file.toFile()));
    }

    @Test
    void fileOverloadHandlesEmptyFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("empty.bin");
        Files.write(file, new byte[0]);

        assertEquals("d41d8cd98f00b204e9800998ecf8427e", HashUtil.md5Hex(file.toFile()));
    }
}
