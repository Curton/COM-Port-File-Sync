package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for CompressionUtil smart compression detection. */
class CompressionUtilTest {

    @Test
    void isTextExtensionRecognizesCommonTextExtensions() {
        assertTrue(CompressionUtil.isTextExtension("file.txt"));
        assertTrue(CompressionUtil.isTextExtension("file.java"));
        assertTrue(CompressionUtil.isTextExtension("file.xml"));
        assertTrue(CompressionUtil.isTextExtension("file.json"));
        assertTrue(CompressionUtil.isTextExtension("file.html"));
        assertTrue(CompressionUtil.isTextExtension("file.js"));
        assertTrue(CompressionUtil.isTextExtension("file.py"));
        assertTrue(CompressionUtil.isTextExtension("file.md"));
        assertTrue(CompressionUtil.isTextExtension("file.yaml"));
        assertTrue(CompressionUtil.isTextExtension("file.yml"));
        assertTrue(CompressionUtil.isTextExtension("file.css"));
        assertTrue(CompressionUtil.isTextExtension("file.sql"));
        assertTrue(CompressionUtil.isTextExtension("file.log"));
        assertTrue(CompressionUtil.isTextExtension("file.csv"));
        assertTrue(CompressionUtil.isTextExtension("FILE.TXT"));
        assertTrue(CompressionUtil.isTextExtension("File.Txt"));
    }

    @Test
    void isTextExtensionRejectsNonTextExtensions() {
        assertFalse(CompressionUtil.isTextExtension("file.jpg"));
        assertFalse(CompressionUtil.isTextExtension("file.mp3"));
        assertFalse(CompressionUtil.isTextExtension("file.zip"));
        assertFalse(CompressionUtil.isTextExtension("file.pdf"));
        assertFalse(CompressionUtil.isTextExtension("file.docx"));
        assertFalse(CompressionUtil.isTextExtension("file.xlsx"));
        assertFalse(CompressionUtil.isTextExtension("file.png"));
        assertFalse(CompressionUtil.isTextExtension("file.gif"));
    }

    @Test
    void isTextExtensionHandlesEdgeCases() {
        assertFalse(CompressionUtil.isTextExtension(null));
        assertFalse(CompressionUtil.isTextExtension(""));
        assertFalse(CompressionUtil.isTextExtension("noextension"));
        assertFalse(CompressionUtil.isTextExtension(".hidden"));
        assertFalse(CompressionUtil.isTextExtension("trailing."));
    }

    @Test
    void isCompressedExtensionRecognizesCompressedFormats() {
        assertTrue(CompressionUtil.isCompressedExtension("file.zip"));
        assertTrue(CompressionUtil.isCompressedExtension("file.gz"));
        assertTrue(CompressionUtil.isCompressedExtension("file.bz2"));
        assertTrue(CompressionUtil.isCompressedExtension("file.7z"));
        assertTrue(CompressionUtil.isCompressedExtension("file.rar"));
        assertTrue(CompressionUtil.isCompressedExtension("file.tar"));
    }

    @Test
    void isCompressedExtensionRecognizesMediaFormats() {
        assertTrue(CompressionUtil.isCompressedExtension("file.jpg"));
        assertTrue(CompressionUtil.isCompressedExtension("file.jpeg"));
        assertTrue(CompressionUtil.isCompressedExtension("file.png"));
        assertTrue(CompressionUtil.isCompressedExtension("file.gif"));
        assertTrue(CompressionUtil.isCompressedExtension("file.webp"));
        assertTrue(CompressionUtil.isCompressedExtension("file.mp3"));
        assertTrue(CompressionUtil.isCompressedExtension("file.mp4"));
        assertTrue(CompressionUtil.isCompressedExtension("file.avi"));
        assertTrue(CompressionUtil.isCompressedExtension("file.mkv"));
        assertTrue(CompressionUtil.isCompressedExtension("file.pdf"));
        assertTrue(CompressionUtil.isCompressedExtension("file.docx"));
        assertTrue(CompressionUtil.isCompressedExtension("file.xlsx"));
        assertTrue(CompressionUtil.isCompressedExtension("file.pptx"));
    }

    @Test
    void isCompressedExtensionRejectsNonCompressed() {
        assertFalse(CompressionUtil.isCompressedExtension("file.txt"));
        assertFalse(CompressionUtil.isCompressedExtension("file.java"));
        assertFalse(CompressionUtil.isCompressedExtension("file.md"));
        assertFalse(CompressionUtil.isCompressedExtension("file.csv"));
    }

    @Test
    void calculateEntropyForRandomData() {
        byte[] randomData = new byte[1000];
        for (int i = 0; i < randomData.length; i++) {
            randomData[i] = (byte) (Math.random() * 256);
        }
        double entropy = CompressionUtil.calculateEntropy(randomData);
        assertTrue(entropy > 7.0, "Random data should have high entropy, got: " + entropy);
    }

    @Test
    void calculateEntropyForZeroData() {
        byte[] zeroData = new byte[1000];
        double entropy = CompressionUtil.calculateEntropy(zeroData);
        assertEquals(0.0, entropy, "Zero data should have zero entropy");
    }

    @Test
    void calculateEntropyForSingleByteData() {
        byte[] singleByte = new byte[] {42};
        double entropy = CompressionUtil.calculateEntropy(singleByte);
        assertEquals(0.0, entropy, "Single byte data should have zero entropy");
    }

    @Test
    void calculateEntropyForEmptyData() {
        double entropyEmpty = CompressionUtil.calculateEntropy(new byte[] {});
        double entropyNull = CompressionUtil.calculateEntropy(null);
        assertEquals(0.0, entropyEmpty);
        assertEquals(0.0, entropyNull);
    }

    @Test
    void calculateEntropyForStructuredText() {
        String text = "Hello, World! This is a test string with repeated words. ";
        text = text.repeat(100);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        double entropy = CompressionUtil.calculateEntropy(data);
        assertTrue(entropy < 6.0, "Structured text should have lower entropy, got: " + entropy);
    }

    @Test
    void isLikelyBinaryContentDetectsBinary() {
        byte[] binaryData = new byte[1000];
        for (int i = 0; i < binaryData.length; i++) {
            binaryData[i] = 0;
        }
        assertTrue(
                CompressionUtil.isLikelyBinaryContent(binaryData),
                "Null bytes should indicate binary");

        binaryData = new byte[1000];
        for (int i = 0; i < binaryData.length; i++) {
            binaryData[i] = (byte) 0xFF;
        }
        assertFalse(
                CompressionUtil.isLikelyBinaryContent(binaryData),
                "0xFF bytes are valid Latin-1 text chars, not binary");
    }

    @Test
    void isLikelyBinaryContentAcceptsText() {
        byte[] textData =
                "Hello, World!\nThis is a test.\r\nWith multiple lines."
                        .getBytes(StandardCharsets.UTF_8);
        assertFalse(
                CompressionUtil.isLikelyBinaryContent(textData),
                "Normal text should not be binary");

        byte[] asciiOnly = new byte[1000];
        for (int i = 0; i < asciiOnly.length; i++) {
            asciiOnly[i] = (byte) ('A' + (i % 26));
        }
        assertFalse(
                CompressionUtil.isLikelyBinaryContent(asciiOnly),
                "ASCII letters should not be binary");
    }

    @Test
    void isLikelyBinaryContentHandlesEdgeCases() {
        assertFalse(CompressionUtil.isLikelyBinaryContent(new byte[] {}));
        assertFalse(CompressionUtil.isLikelyBinaryContent(null));
    }

    @Test
    void isCompressedDetectsGzipData() {
        byte[] gzipData = new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00};
        assertTrue(CompressionUtil.isCompressed(gzipData));

        byte[] notGzip = "Not compressed".getBytes(StandardCharsets.UTF_8);
        assertFalse(CompressionUtil.isCompressed(notGzip));

        assertFalse(CompressionUtil.isCompressed(null));
        assertFalse(CompressionUtil.isCompressed(new byte[] {0x1f}));
        assertFalse(CompressionUtil.isCompressed(new byte[] {}));
    }

    @Test
    void compressAndDecompressRoundTrip() throws IOException {
        String original = "This is some test content that should compress well.";
        byte[] data = original.getBytes(StandardCharsets.UTF_8);

        byte[] compressed = CompressionUtil.compress(data);
        assertNotEquals(
                data.length, compressed.length, "Compressed data should be different length");

        byte[] decompressed = CompressionUtil.decompress(compressed);
        assertEquals(original, new String(decompressed, StandardCharsets.UTF_8));
    }

    @Test
    void compressNullDataReturnsNull() throws IOException {
        byte[] result = CompressionUtil.compress(null);
        assertNull(result);
    }

    @Test
    void compressEmptyDataReturnsEmpty() throws IOException {
        byte[] result = CompressionUtil.compress(new byte[] {});
        assertArrayEquals(new byte[] {}, result);
    }

    @Test
    void decompressNullDataReturnsNull() throws IOException {
        byte[] result = CompressionUtil.decompress(null);
        assertNull(result);
    }

    @Test
    void decompressEmptyDataReturnsEmpty() throws IOException {
        byte[] result = CompressionUtil.decompress(new byte[] {});
        assertArrayEquals(new byte[] {}, result);
    }

    @Test
    void decompressIfNeededOnlyDecompressesWhenFlagSet() throws IOException {
        String original = "Test content for decompression check";
        byte[] data = original.getBytes(StandardCharsets.UTF_8);
        byte[] compressed = CompressionUtil.compress(data);

        byte[] decompressed = CompressionUtil.decompressIfNeeded(compressed, true);
        assertEquals(original, new String(decompressed, StandardCharsets.UTF_8));

        byte[] notDecompressed = CompressionUtil.decompressIfNeeded(compressed, false);
        assertArrayEquals(compressed, notDecompressed, "Should not decompress when flag is false");
    }

    @Test
    void compressIfBeneficialForCompressedExtension() throws IOException {
        byte[] data = "Some test content".getBytes(StandardCharsets.UTF_8);

        CompressionUtil.CompressedData result =
                CompressionUtil.compressIfBeneficial("file.zip", data);
        assertFalse(result.isCompressed(), "Should not compress already compressed formats");

        result = CompressionUtil.compressIfBeneficial("file.jpg", data);
        assertFalse(result.isCompressed(), "Should not compress JPG files");

        result = CompressionUtil.compressIfBeneficial("file.mp3", data);
        assertFalse(result.isCompressed(), "Should not compress MP3 files");

        result = CompressionUtil.compressIfBeneficial("file.pdf", data);
        assertFalse(result.isCompressed(), "Should not compress PDF files");
    }

    @Test
    void compressIfBeneficialForTextExtension() throws IOException {
        byte[] data =
                "This is a long test string with repetitive content. "
                        .repeat(50)
                        .getBytes(StandardCharsets.UTF_8);

        CompressionUtil.CompressedData result =
                CompressionUtil.compressIfBeneficial("file.txt", data);
        assertTrue(
                result.isCompressed(), "Text files with repetitive content should be compressed");
        assertTrue(result.getData().length < data.length, "Compressed data should be smaller");

        byte[] randomishData = new byte[1000];
        for (int i = 0; i < randomishData.length; i++) {
            randomishData[i] = (byte) (Math.random() * 256);
        }
        result = CompressionUtil.compressIfBeneficial("file.java", randomishData);
    }

    @Test
    void compressIfBeneficialSkipsSmallFiles() throws IOException {
        byte[] smallData = "Hi".getBytes(StandardCharsets.UTF_8);

        CompressionUtil.CompressedData result =
                CompressionUtil.compressIfBeneficial("file.txt", smallData);
        assertFalse(
                result.isCompressed(),
                "Small files under trial threshold should not be trial-compressed");
    }

    @Test
    void compressIfBeneficialHandlesNullInput() throws IOException {
        CompressionUtil.CompressedData result = CompressionUtil.compressIfBeneficial(null, null);
        assertFalse(result.isCompressed());
        assertNull(result.getData());

        result = CompressionUtil.compressIfBeneficial("file.txt", null);
        assertFalse(result.isCompressed());
        assertNull(result.getData());
    }

    @Test
    void compressIfBeneficialHandlesEmptyInput() throws IOException {
        CompressionUtil.CompressedData result =
                CompressionUtil.compressIfBeneficial("file.txt", new byte[] {});
        assertFalse(result.isCompressed());
        assertArrayEquals(new byte[] {}, result.getData());
    }

    @Test
    void hasHighCompressionPotentialRejectsCompressedFormats() {
        byte[] data = new byte[1000];
        assertFalse(CompressionUtil.hasHighCompressionPotential("file.zip", data));
        assertFalse(CompressionUtil.hasHighCompressionPotential("file.gz", data));
        assertFalse(CompressionUtil.hasHighCompressionPotential("file.jpg", data));
        assertFalse(CompressionUtil.hasHighCompressionPotential("file.png", data));
        assertFalse(CompressionUtil.hasHighCompressionPotential("file.mp3", data));
        assertFalse(CompressionUtil.hasHighCompressionPotential("file.mp4", data));
        assertFalse(CompressionUtil.hasHighCompressionPotential("file.pdf", data));
    }

    @Test
    void hasHighCompressionPotentialRejectsHighEntropyBinary() {
        byte[] highEntropy = new byte[2000];
        for (int i = 0; i < highEntropy.length; i++) {
            highEntropy[i] = (byte) (Math.random() * 256);
        }
        assertFalse(CompressionUtil.hasHighCompressionPotential("file.bin", highEntropy));
        assertFalse(CompressionUtil.hasHighCompressionPotential("noextension", highEntropy));
    }

    @Test
    void hasHighCompressionPotentialAcceptsTextFiles() {
        String text = "Hello World\nThis is a test file.\nWith some content.\n";
        byte[] data = text.repeat(100).getBytes(StandardCharsets.UTF_8);
        assertTrue(CompressionUtil.hasHighCompressionPotential("file.txt", data));
        assertTrue(CompressionUtil.hasHighCompressionPotential("file.java", data));
        assertTrue(CompressionUtil.hasHighCompressionPotential("file.xml", data));
        assertTrue(CompressionUtil.hasHighCompressionPotential("file.json", data));
    }

    @Test
    void hasHighCompressionPotentialRejectsSmallData() {
        byte[] smallData = "Small".getBytes(StandardCharsets.UTF_8);
        assertFalse(
                CompressionUtil.hasHighCompressionPotential("file.unknown", smallData),
                "Unknown extension with small data should be rejected");
    }

    @Test
    void hasHighCompressionPotentialHandlesEdgeCases() {
        assertFalse(CompressionUtil.hasHighCompressionPotential(null, null));
        assertFalse(CompressionUtil.hasHighCompressionPotential("file.unknown", null));
        assertFalse(CompressionUtil.hasHighCompressionPotential("file.unknown", new byte[] {}));
        assertFalse(CompressionUtil.hasHighCompressionPotential(null, new byte[] {1, 2, 3, 4, 5}));
    }

    @Test
    void hasHighCompressionPotentialForUnknownExtensionAnalyzesContent() {
        byte[] repetitiveData = "AAAA".repeat(500).getBytes(StandardCharsets.UTF_8);
        assertTrue(CompressionUtil.hasHighCompressionPotential("file.unk", repetitiveData));

        byte[] randomData = new byte[2000];
        for (int i = 0; i < randomData.length; i++) {
            randomData[i] = (byte) (Math.random() * 256);
        }
        assertFalse(CompressionUtil.hasHighCompressionPotential("file.unk", randomData));
    }

    @Test
    void estimateCompressionRatioForRepetitiveData() {
        byte[] repetitive = "AAAA".repeat(1000).getBytes(StandardCharsets.UTF_8);
        double ratio = CompressionUtil.estimateCompressionRatio(repetitive);
        assertTrue(ratio < 0.5, "Repetitive data should compress well, ratio: " + ratio);
    }

    @Test
    void estimateCompressionRatioForRandomData() {
        byte[] random = new byte[1000];
        for (int i = 0; i < random.length; i++) {
            random[i] = (byte) (Math.random() * 256);
        }
        double ratio = CompressionUtil.estimateCompressionRatio(random);
        assertTrue(ratio > 0.9, "Random data should not compress well, ratio: " + ratio);
    }

    @Test
    void estimateCompressionRatioHandlesEdgeCases() {
        assertEquals(1.0, CompressionUtil.estimateCompressionRatio(null));
        assertEquals(1.0, CompressionUtil.estimateCompressionRatio(new byte[] {}));
    }

    @Test
    void textExtensionsCanBeAddedAndRemoved() {
        assertFalse(CompressionUtil.isTextExtension("file.myext"));

        CompressionUtil.addTextExtension("myext");
        assertTrue(CompressionUtil.isTextExtension("file.myext"));
        assertTrue(CompressionUtil.isTextExtension("FILE.MYEXT"));

        CompressionUtil.removeTextExtension("myext");
        assertFalse(CompressionUtil.isTextExtension("file.myext"));
    }

    @Test
    void getTextExtensionsReturnsCopy() {
        Set<String> extensions = CompressionUtil.getTextExtensions();
        assertNotNull(extensions);
        assertTrue(extensions.size() > 0);

        extensions.add("phantom");
        assertFalse(CompressionUtil.isTextExtension("file.phantom"));
    }

    @Test
    void compressedDataContainerWorks() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        CompressionUtil.CompressedData cd = new CompressionUtil.CompressedData(data, true);

        assertArrayEquals(data, cd.getData());
        assertTrue(cd.isCompressed());

        CompressionUtil.CompressedData cdFalse = new CompressionUtil.CompressedData(data, false);
        assertFalse(cdFalse.isCompressed());
    }
}
