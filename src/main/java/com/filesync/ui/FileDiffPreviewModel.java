package com.filesync.ui;

import com.filesync.sync.CompressionUtil;
import com.filesync.sync.TextDiffUtil;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Content for a single-file change preview opened from the sync preview table.
 *
 * <p>A preview compares two versions of one file: the <em>source</em> version (what the sync will
 * send) and the <em>base</em> version (what the receiver currently holds). Both sides are optional
 * — a brand-new file has no base, and a file the peer cannot supply has no base bytes available —
 * so the model carries an explicit availability flag plus a human-readable explanation per side
 * instead of leaving the UI to guess.
 *
 * <p>The text/binary decision is content-based (the same heuristic used for hashing and
 * compression) with the file extension as a fallback when no bytes were read, so a renamed or
 * extension-less text file still previews as text.
 */
public final class FileDiffPreviewModel {

    /** Which version of the file a preview side shows. */
    public enum Side {
        /** The version the sync will send (the local file when this device is the sender). */
        SOURCE,
        /** The version currently on the peer (the receiver's file). */
        BASE;
    }

    private final String path;
    private final SyncPreviewOperationType operationType;
    private final byte[] sourceContent;
    private final byte[] baseContent;
    private final boolean baseAvailable;
    private final String sourceUnavailableReason;
    private final String baseUnavailableReason;
    private final boolean text;
    private final boolean truncated;

    private FileDiffPreviewModel(
            String path,
            SyncPreviewOperationType operationType,
            byte[] sourceContent,
            byte[] baseContent,
            boolean baseAvailable,
            String sourceUnavailableReason,
            String baseUnavailableReason,
            boolean text,
            boolean truncated) {
        this.path = path;
        this.operationType = operationType;
        this.sourceContent = sourceContent;
        this.baseContent = baseContent;
        this.baseAvailable = baseAvailable;
        this.sourceUnavailableReason = sourceUnavailableReason;
        this.baseUnavailableReason = baseUnavailableReason;
        this.text = text;
        this.truncated = truncated;
    }

    /**
     * Build a preview model from whatever content the caller managed to obtain.
     *
     * @param path the file's path relative to the sync folder
     * @param operationType the operation the preview was opened for (drives the header text)
     * @param sourceContent the version to be sent, or null when unreadable/too large
     * @param sourceUnavailableReason explanation shown when {@code sourceContent} is null
     * @param baseContent the peer's current version, or null when absent/unavailable
     * @param baseAvailable true when the peer actually holds a version of this file; distinguishes
     *     "new file, nothing to compare" from "existing file whose content could not be fetched"
     * @param baseUnavailableReason explanation shown when {@code baseAvailable} is true but {@code
     *     baseContent} is null
     * @param truncated true when one or both sides were cut short for display
     */
    public static FileDiffPreviewModel of(
            String path,
            SyncPreviewOperationType operationType,
            byte[] sourceContent,
            String sourceUnavailableReason,
            byte[] baseContent,
            boolean baseAvailable,
            String baseUnavailableReason,
            boolean truncated) {
        boolean text = isTextContent(path, sourceContent, baseContent);
        return new FileDiffPreviewModel(
                path,
                operationType,
                sourceContent,
                baseContent,
                baseAvailable,
                sourceUnavailableReason,
                baseUnavailableReason,
                text,
                truncated);
    }

    /** Convenience factory for tests and callers that already know the text/binary verdict. */
    public static FileDiffPreviewModel of(
            String path,
            SyncPreviewOperationType operationType,
            byte[] sourceContent,
            byte[] baseContent,
            boolean baseAvailable,
            boolean text) {
        return new FileDiffPreviewModel(
                path,
                operationType,
                sourceContent,
                baseContent,
                baseAvailable,
                null,
                null,
                text,
                false);
    }

    /**
     * Decide whether a file should be previewed as text. The extension is used as a positive hint
     * first, then any available bytes are inspected with {@link
     * CompressionUtil#isLikelyBinaryContent(byte[])}; with no bytes and no hint the file is treated
     * as binary so the preview shows the placeholder instead of mojibake.
     */
    public static boolean isTextContent(String path, byte[] sourceContent, byte[] baseContent) {
        if (CompressionUtil.isTextExtension(path)) {
            // A known text extension still loses to strongly binary content: a mislabelled or
            // corrupted file must not be rendered as text.
            byte[] sample = firstNonNull(sourceContent, baseContent);
            return sample == null || !CompressionUtil.isLikelyBinaryContent(sample);
        }
        byte[] sample = firstNonNull(sourceContent, baseContent);
        if (sample == null || sample.length == 0) {
            return false;
        }
        return !CompressionUtil.isLikelyBinaryContent(sample);
    }

    private static byte[] firstNonNull(byte[] first, byte[] second) {
        return first != null ? first : second;
    }

    /**
     * Decode bytes as UTF-8 text, returning null only when the bytes cannot be decoded at all.
     * Malformed sequences are replaced with U+FFFD rather than failing: text files saved in a
     * legacy single-byte encoding (GBK, Latin-1) are still worth previewing, and the replacement
     * characters make the encoding mismatch visible instead of hiding the file behind an error.
     */
    public static String decodeText(byte[] content) {
        if (content == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(java.nio.ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Compute the line diff between the two available versions. Source is "new", base is "old". */
    public TextDiffUtil.DiffResult computeDiff() {
        String sourceText = getSourceText();
        String baseText = getBaseText();
        if (sourceText == null && baseText == null) {
            return null;
        }
        return TextDiffUtil.computeDiff(
                baseText != null ? baseText : "", sourceText != null ? sourceText : "");
    }

    public String getPath() {
        return path;
    }

    /** File name without any directory part, for dialog titles. */
    public String getFileName() {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    public SyncPreviewOperationType getOperationType() {
        return operationType;
    }

    public byte[] getSourceContent() {
        return sourceContent;
    }

    public byte[] getBaseContent() {
        return baseContent;
    }

    /** True when the side's bytes were successfully obtained and can be rendered. */
    public boolean isSourceAvailable() {
        return sourceContent != null;
    }

    /** True when the base bytes were successfully obtained and can be rendered. */
    public boolean isBaseContentAvailable() {
        return baseContent != null;
    }

    /**
     * True when the peer holds a version of this file. False means there is nothing to compare
     * against — a new file on the receiver, or a delete operation.
     */
    public boolean isBaseAvailable() {
        return baseAvailable;
    }

    public boolean isText() {
        return text;
    }

    public boolean isTruncated() {
        return truncated;
    }

    /** Local/source version as text, or null when unavailable or not decodable as UTF-8. */
    public String getSourceText() {
        return text ? decodeText(sourceContent) : null;
    }

    /** Peer/base version as text, or null when unavailable or not decodable as UTF-8. */
    public String getBaseText() {
        return text ? decodeText(baseContent) : null;
    }

    /**
     * Message shown in place of a side's content when it cannot be rendered. Returns null when the
     * side is renderable, and a short explanation otherwise.
     */
    public String describeUnavailable(Side side) {
        if (side == Side.SOURCE) {
            if (sourceContent != null) {
                return null;
            }
            return sourceUnavailableReason != null
                    ? sourceUnavailableReason
                    : "The local file could not be read.";
        }
        if (baseContent != null) {
            return null;
        }
        if (!baseAvailable) {
            return "No previous version - the peer does not have this file yet.";
        }
        return baseUnavailableReason != null
                ? baseUnavailableReason
                : "The peer's version could not be retrieved.";
    }

    /**
     * One-line summary of the change for the dialog header, e.g. "+12 -3 lines" or a binary-size
     * note. Falls back to an operation description when no diff could be computed.
     */
    public String describeSummary() {
        if (!text) {
            return "Binary file - no line-by-line preview available.";
        }
        TextDiffUtil.DiffResult diff = computeDiff();
        if (diff == null) {
            return "Content unavailable for comparison.";
        }
        if (!diff.hasChanges()) {
            return "No textual differences"
                    + (diff.getUnchangedCount() > 0
                            ? " (" + diff.getUnchangedCount() + " unchanged lines)"
                            : "")
                    + ".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("+").append(diff.getAddedCount()).append(" -").append(diff.getRemovedCount());
        sb.append(" line").append(diff.getChangeCount() == 1 ? "" : "s").append(", ");
        sb.append(diff.getHunks().size())
                .append(" change region")
                .append(diff.getHunks().size() == 1 ? "" : "s");
        if (truncated) {
            sb.append(" (truncated)");
        }
        return sb.append(".").toString();
    }

    /** Short "3.2 KB / 3.4 KB" style description of both sides, for binary placeholders. */
    public String describeSizes() {
        String sourceSize =
                sourceContent != null
                        ? UiFormatting.formatBytes(sourceContent.length)
                        : "unavailable";
        String baseSize =
                baseContent != null
                        ? UiFormatting.formatBytes(baseContent.length)
                        : (baseAvailable ? "unavailable" : "absent");
        return "New: " + sourceSize + "  |  Previous: " + baseSize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FileDiffPreviewModel other)) {
            return false;
        }
        return Objects.equals(path, other.path)
                && operationType == other.operationType
                && java.util.Arrays.equals(sourceContent, other.sourceContent)
                && java.util.Arrays.equals(baseContent, other.baseContent)
                && text == other.text;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                path,
                operationType,
                java.util.Arrays.hashCode(sourceContent),
                java.util.Arrays.hashCode(baseContent),
                text);
    }

    @Override
    public String toString() {
        return "FileDiffPreviewModel{"
                + "path='"
                + path
                + '\''
                + ", operation="
                + operationType
                + ", text="
                + text
                + ", sourceAvailable="
                + isSourceAvailable()
                + ", baseAvailable="
                + baseAvailable
                + '}';
    }
}
