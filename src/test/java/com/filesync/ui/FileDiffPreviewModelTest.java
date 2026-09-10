package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.sync.CompressionUtil;
import com.filesync.sync.TextDiffUtil;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Verifies the file change preview model: text detection, availability reporting, and diffing. */
class FileDiffPreviewModelTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void knownTextExtensionIsPreviewedAsText() {
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "notes.txt",
                        SyncPreviewOperationType.MODIFIED,
                        utf8("hello\n"),
                        null,
                        utf8("hello there\n"),
                        true,
                        null,
                        false);

        assertTrue(model.isText());
        assertEquals("hello there\n", model.getBaseText());
        assertEquals("hello\n", model.getSourceText());
    }

    @Test
    void unknownExtensionWithReadableContentIsPreviewedAsText() {
        // No extension hint at all: the content heuristic must still classify it as text.
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "Makefile",
                        SyncPreviewOperationType.MODIFIED,
                        utf8("all:\n\techo hi\n"),
                        null,
                        utf8("all:\n\techo hi\n\techo bye\n"),
                        true,
                        null,
                        false);

        assertTrue(model.isText());
    }

    @Test
    void binaryContentIsNotPreviewedAsText() {
        byte[] binary = new byte[] {0x00, 0x01, 0x02, (byte) 0xFF, 0x00, 0x10};
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "image.png",
                        SyncPreviewOperationType.MODIFIED,
                        binary,
                        null,
                        binary,
                        true,
                        null,
                        false);

        assertFalse(model.isText());
        assertNull(model.getSourceText());
    }

    @Test
    void binaryContentWithTextExtensionIsStillTreatedAsBinary() {
        // A .txt file full of null bytes must not be rendered as text: content wins over extension.
        byte[] binary = new byte[] {0x00, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0x04};
        assertTrue(CompressionUtil.isLikelyBinaryContent(binary));

        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "corrupt.txt",
                        SyncPreviewOperationType.MODIFIED,
                        binary,
                        null,
                        binary,
                        true,
                        null,
                        false);

        assertFalse(model.isText());
    }

    @Test
    void missingContentWithNoExtensionHintFallsBackToBinary() {
        // Nothing to read and no extension to go on: show the placeholder rather than mojibake.
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "unknownfile",
                        SyncPreviewOperationType.MODIFIED,
                        null,
                        "unreadable",
                        null,
                        true,
                        "peer unreachable",
                        false);

        assertFalse(model.isText());
    }

    @Test
    void textExtensionWithNoContentStillPreviewedAsText() {
        // The extension alone is enough to attempt a text preview even when bytes are missing, so
        // the user sees the "could not be read" explanation instead of a binary placeholder.
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "notes.txt",
                        SyncPreviewOperationType.MODIFIED,
                        null,
                        "locked",
                        null,
                        true,
                        "peer unreachable",
                        false);

        assertTrue(model.isText());
    }

    @Test
    void newFileReportsNoPreviousVersion() {
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "new.txt",
                        SyncPreviewOperationType.NEW,
                        utf8("brand new\n"),
                        null,
                        null,
                        false,
                        null,
                        false);

        assertTrue(model.isSourceAvailable());
        assertFalse(model.isBaseAvailable());
        assertEquals(
                "No previous version - the peer does not have this file yet.",
                model.describeUnavailable(FileDiffPreviewModel.Side.BASE));
        assertNull(model.describeUnavailable(FileDiffPreviewModel.Side.SOURCE));
    }

    @Test
    void existingFileWithFailedFetchReportsFailureNotAbsence() {
        // The peer HAS the file but the content could not be retrieved: this must be
        // distinguishable
        // from a brand-new file.
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "notes.txt",
                        SyncPreviewOperationType.MODIFIED,
                        utf8("local\n"),
                        null,
                        null,
                        true,
                        "timeout waiting for peer",
                        false);

        assertTrue(model.isBaseAvailable());
        assertFalse(model.isBaseContentAvailable());
        String reason = model.describeUnavailable(FileDiffPreviewModel.Side.BASE);
        assertNotNull(reason);
        assertTrue(reason.contains("timeout waiting for peer"), reason);
        assertFalse(reason.contains("does not have this file"), reason);
    }

    @Test
    void unreadableLocalFileExplainsWhy() {
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "notes.txt",
                        SyncPreviewOperationType.MODIFIED,
                        null,
                        "The local file could not be read.",
                        utf8("remote\n"),
                        true,
                        null,
                        false);

        assertEquals(
                "The local file could not be read.",
                model.describeUnavailable(FileDiffPreviewModel.Side.SOURCE));
        assertNull(model.describeUnavailable(FileDiffPreviewModel.Side.BASE));
    }

    @Test
    void diffCountsAddedAndRemovedLines() {
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "code.java",
                        SyncPreviewOperationType.MODIFIED,
                        utf8("line1\nline2-changed\nline3\nline4-added\n"),
                        null,
                        utf8("line1\nline2\nline3\n"),
                        true,
                        null,
                        false);

        TextDiffUtil.DiffResult diff = model.computeDiff();
        assertNotNull(diff);
        assertTrue(diff.hasChanges());
        // "line2-changed" is a modification and "line4-added" a pure insertion.
        assertEquals(2, diff.getAddedCount());
        assertEquals(1, diff.getRemovedCount());
        assertFalse(diff.getHunks().isEmpty());
    }

    @Test
    void identicalTextProducesNoHunksAndFriendlySummary() {
        byte[] same = utf8("alpha\nbeta\n");
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "same.txt",
                        SyncPreviewOperationType.MODIFIED,
                        same,
                        null,
                        same,
                        true,
                        null,
                        false);

        TextDiffUtil.DiffResult diff = model.computeDiff();
        assertNotNull(diff);
        assertFalse(diff.hasChanges());
        assertTrue(model.describeSummary().startsWith("No textual differences"));
    }

    @Test
    void summaryReportsLineCountsForChangedText() {
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "a.txt",
                        SyncPreviewOperationType.MODIFIED,
                        utf8("keep\nold line\n"),
                        null,
                        utf8("keep\nnew line\n"),
                        true,
                        null,
                        false);

        // One line replaced by another: one addition and one removal.
        String summary = model.describeSummary();
        assertTrue(summary.startsWith("+1 -1 lines"), summary);
        assertTrue(summary.contains("change region"), summary);
    }

    @Test
    void binaryPreviewSummarySaysNoLinePreview() {
        byte[] binary = new byte[] {0x00, 0x01, 0x02, 0x03};
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "app.exe",
                        SyncPreviewOperationType.MODIFIED,
                        binary,
                        null,
                        binary,
                        true,
                        null,
                        false);

        assertTrue(
                model.describeSummary().contains("no line-by-line preview available"),
                model.describeSummary());
    }

    @Test
    void truncatedFlagAppearsInSummary() {
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "big.log",
                        SyncPreviewOperationType.MODIFIED,
                        utf8("a\nb\n"),
                        null,
                        utf8("a\n"),
                        true,
                        null,
                        true);

        assertTrue(model.isTruncated());
        assertTrue(model.describeSummary().contains("(truncated)"), model.describeSummary());
    }

    @Test
    void fileNameStripsDirectories() {
        FileDiffPreviewModel model =
                FileDiffPreviewModel.of(
                        "src/main/java/Foo.java",
                        SyncPreviewOperationType.MODIFIED,
                        utf8("x\n"),
                        null,
                        utf8("y\n"),
                        true,
                        null,
                        false);

        assertEquals("Foo.java", model.getFileName());
    }

    @Test
    void nonUtf8BytesAreDecodedWithReplacementCharacters() {
        // Text saved in a legacy encoding must still preview (with visible replacement characters)
        // rather than being rejected outright.
        byte[] latin1 = new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0x41};
        String decoded = FileDiffPreviewModel.decodeText(latin1);
        assertNotNull(decoded);
        assertTrue(decoded.contains("\uFFFD"), decoded);
        assertTrue(decoded.contains("A"), decoded);
    }

    @Test
    void describeSizesDistinguishesAbsentFromUnavailable() {
        FileDiffPreviewModel newFile =
                FileDiffPreviewModel.of(
                        "n.txt",
                        SyncPreviewOperationType.NEW,
                        utf8("abc"),
                        null,
                        null,
                        false,
                        null,
                        false);
        assertTrue(newFile.describeSizes().contains("Previous: absent"), newFile.describeSizes());

        FileDiffPreviewModel existing =
                FileDiffPreviewModel.of(
                        "e.txt",
                        SyncPreviewOperationType.MODIFIED,
                        utf8("abc"),
                        null,
                        null,
                        true,
                        "fetch failed",
                        false);
        assertTrue(
                existing.describeSizes().contains("Previous: unavailable"),
                existing.describeSizes());
    }
}
