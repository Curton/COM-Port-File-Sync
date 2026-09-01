package com.filesync.delta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Round-trip serialization of {@link FileSignatures} and {@link SignatureSet}. */
class SignatureSerializationTest {

    @Test
    void fileSignaturesRoundTrip() throws IOException {
        BlockSignature a = new BlockSignature(0, 0x1234, md5(1));
        BlockSignature b = new BlockSignature(1, 0x5678, md5(2));
        FileSignatures fs = new FileSignatures("dir/file.bin", 1024, 2, 2048L, List.of(a, b));

        byte[] bytes = fs.toBytes();
        FileSignatures back = FileSignatures.fromBytes(bytes);

        assertEquals(fs.getPath(), back.getPath());
        assertEquals(fs.getBlockSize(), back.getBlockSize());
        assertEquals(fs.getBlockCount(), back.getBlockCount());
        assertEquals(fs.getSourceSize(), back.getSourceSize());
        assertEquals(fs.getSignatures(), back.getSignatures());
        assertEquals(0, back.getSignatures().get(0).getBlockIndex());
        assertEquals(1, back.getSignatures().get(1).getBlockIndex());
    }

    @Test
    void signatureSetRoundTrip() throws IOException {
        FileSignatures fs1 =
                new FileSignatures(
                        "a.bin", 2048, 1, 2048L, List.of(new BlockSignature(0, 11, md5(7))));
        FileSignatures fs2 =
                new FileSignatures(
                        "b.bin",
                        2048,
                        3,
                        6144L,
                        List.of(
                                new BlockSignature(0, 21, md5(8)),
                                new BlockSignature(1, 22, md5(9)),
                                new BlockSignature(2, 23, md5(10))));
        SignatureSet set = new SignatureSet(List.of(fs1, fs2));

        byte[] bytes = set.toBytes();
        SignatureSet back = SignatureSet.fromBytes(bytes);

        assertEquals(2, back.size());
        assertNotNull(back.get("a.bin"));
        assertNotNull(back.get("b.bin"));
        assertEquals(fs1.getSignatures(), back.get("a.bin").getSignatures());
        assertEquals(fs2.getSignatures(), back.get("b.bin").getSignatures());
    }

    @Test
    void emptySetRoundTrip() throws IOException {
        SignatureSet set = SignatureSet.empty();
        SignatureSet back = SignatureSet.fromBytes(set.toBytes());
        assertEquals(0, back.size());
        assertNull(back.get("missing"));
    }

    @Test
    void rejectsBadMagic() {
        byte[] bad = new byte[] {0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00};
        assertThrows(IOException.class, () -> SignatureSet.fromBytes(bad));
    }

    @Test
    void rejectsWrongContainerVersion() throws IOException {
        SignatureSet set =
                new SignatureSet(
                        List.of(
                                new FileSignatures(
                                        "a.bin",
                                        2048,
                                        1,
                                        2048L,
                                        List.of(new BlockSignature(0, 11, md5(7))))));
        byte[] bytes = set.toBytes();
        bytes[4] = 0x01; // overwrite the version byte with an older version
        assertThrows(IOException.class, () -> SignatureSet.fromBytes(bytes));
    }

    private static byte[] md5(int seed) {
        byte[] b = new byte[BlockSignature.STRONG_HASH_LENGTH];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (seed + i);
        }
        return b;
    }
}
