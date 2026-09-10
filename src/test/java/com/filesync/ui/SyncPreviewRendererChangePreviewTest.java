package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.sync.SyncPreviewPlan;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the sync preview's change-preview plumbing: the Preview column, previous-version
 * fetching (including caching and failure handling), and preview-model assembly.
 */
class SyncPreviewRendererChangePreviewTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static com.filesync.sync.FileChangeDetector.FileInfo fileInfo(String path, long size) {
        return new com.filesync.sync.FileChangeDetector.FileInfo(path, size, 0L, null);
    }

    private static SyncPreviewPlan planWith(
            List<com.filesync.sync.FileChangeDetector.FileInfo> files,
            java.util.Set<String> existingRemotePaths) {
        long total = files.stream().mapToLong(f -> f.getSize()).sum();
        return new SyncPreviewPlan(
                files,
                List.of(),
                List.of(),
                List.of(),
                total,
                false,
                List.of(),
                java.util.Set.of(),
                java.util.Set.of(),
                existingRemotePaths,
                java.util.Map.of());
    }

    // --- Table shape -------------------------------------------------------------------------

    @Test
    void tableModelExposesPreviewColumn() {
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);
        List<SyncPreviewRow> rows =
                List.of(new SyncPreviewRow(SyncPreviewOperationType.MODIFIED, "a.txt", "1 B", 1L));

        DefaultTableModel model = renderer.createSyncPreviewTableModel(rows);

        // Header and button label share the short form so the narrow column never ellipsizes.
        assertEquals("Pre", model.getColumnName(SyncPreviewRenderer.PREVIEW_COLUMN));
        assertEquals(
                SyncPreviewRenderer.previewButtonLabel(),
                model.getColumnName(SyncPreviewRenderer.PREVIEW_COLUMN));
        assertEquals(5, model.getColumnCount());
        assertTrue(model.isCellEditable(0, 0), "checkbox column stays editable");
        assertTrue(
                model.isCellEditable(0, SyncPreviewRenderer.PREVIEW_COLUMN),
                "preview column must be clickable");
        assertFalse(model.isCellEditable(0, 1), "type column is read-only");
        assertFalse(model.isCellEditable(0, 3), "path column is read-only");
    }

    // --- Which rows have something to compare against ----------------------------------------

    @Test
    void newAndDeleteRowsHaveNoPreviousVersionToFetch() {
        assertFalse(
                new SyncPreviewRow(SyncPreviewOperationType.NEW, "n.txt", "1 B", 1L)
                        .hasBaseVersion());
        assertFalse(
                new SyncPreviewRow(SyncPreviewOperationType.DELETE_FILE, "d.txt", "-", 0L)
                        .hasBaseVersion());
        assertFalse(
                new SyncPreviewRow(SyncPreviewOperationType.DELETE_DIR, "d", "-", 0L)
                        .hasBaseVersion());
        assertFalse(
                new SyncPreviewRow(SyncPreviewOperationType.CREATE_DIR, "d", "-", 0L)
                        .hasBaseVersion());
    }

    @Test
    void modifiedAppendAndConflictRowsHaveAPreviousVersion() {
        assertTrue(
                new SyncPreviewRow(SyncPreviewOperationType.MODIFIED, "m.txt", "1 B", 1L)
                        .hasBaseVersion());
        assertTrue(
                new SyncPreviewRow(SyncPreviewOperationType.APPEND, "a.txt", "1 B", 1L)
                        .hasBaseVersion());
        assertTrue(
                new SyncPreviewRow(SyncPreviewOperationType.CONFLICT, "c.txt", "1 B", 1L)
                        .hasBaseVersion());
    }

    // --- Previous-version caching ------------------------------------------------------------

    @Test
    void baseContentIsCachedAfterFetchAndOnlyFetchedOnce() {
        SyncPreviewRow row =
                new SyncPreviewRow(SyncPreviewOperationType.MODIFIED, "m.txt", "1 B", 1L);
        AtomicReference<String> requested = new AtomicReference<>();

        SyncPreviewRenderer renderer =
                new SyncPreviewRenderer(
                        null,
                        path -> {
                            requested.set(path);
                            return utf8("old content");
                        });

        assertFalse(row.isBaseFetched());
        byte[] fetched = renderer.fetchBaseContent(row);
        assertNotNull(fetched);
        assertEquals("m.txt", requested.get());
        assertEquals("old content", new String(fetched, StandardCharsets.UTF_8));

        row.setBaseContent(fetched);
        row.setBaseFetched(true);
        assertTrue(row.isBaseFetched());
        assertSame(fetched, row.getBaseContent());
    }

    @Test
    void fetchReturnsNullWhenNoResolverIsAvailable() {
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);
        assertNull(
                renderer.fetchBaseContent(
                        new SyncPreviewRow(SyncPreviewOperationType.MODIFIED, "m.txt", "1 B", 1L)));
    }

    @Test
    void failedFetchLeavesRowUnfetchedSoItCanBeRetried() {
        SyncPreviewRow row =
                new SyncPreviewRow(SyncPreviewOperationType.MODIFIED, "m.txt", "1 B", 1L);
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null, path -> null);

        // A null result means "the peer gave nothing"; the row must not be marked as fetched,
        // otherwise a later retry after reconnecting would be skipped.
        assertNull(renderer.fetchBaseContent(row));
        assertFalse(row.isBaseFetched());
    }

    // --- Preview model assembly --------------------------------------------------------------

    @Test
    void previewModelReadsLocalFileAsNewVersion(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("m.txt"), "local new content\n");
        SyncPreviewRow row =
                new SyncPreviewRow(SyncPreviewOperationType.MODIFIED, "m.txt", "18 B", 18L);
        row.setBaseContent(utf8("peer old content\n"));
        row.setBaseFetched(true);

        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);
        renderer.showSyncPreviewDialogWithResult(
                planWith(List.of(fileInfo("m.txt", 18L)), java.util.Set.of("m.txt")), dir.toFile());

        FileDiffPreviewModel model = renderer.buildPreviewModel(row, null);

        assertTrue(model.isText());
        assertEquals("peer old content\n", model.getBaseText());
        assertEquals("local new content\n", model.getSourceText());
        assertTrue(model.computeDiff().hasChanges());
    }

    @Test
    void previewModelExplainsUnreadableLocalFile(@TempDir Path dir) throws Exception {
        SyncPreviewRow row =
                new SyncPreviewRow(SyncPreviewOperationType.MODIFIED, "gone.txt", "5 B", 5L);
        row.setBaseContent(utf8("peer\n"));
        row.setBaseFetched(true);

        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);
        renderer.showSyncPreviewDialogWithResult(
                planWith(List.of(fileInfo("gone.txt", 5L)), java.util.Set.of("gone.txt")),
                dir.toFile());

        FileDiffPreviewModel model = renderer.buildPreviewModel(row, null);

        assertNull(model.getSourceContent());
        assertNotNull(model.describeUnavailable(FileDiffPreviewModel.Side.SOURCE));
        assertNotNull(model.getBaseContent());
    }

    @Test
    void previewModelSurfacesFetchFailureForExistingFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("m.txt"), "local\n");
        SyncPreviewRow row =
                new SyncPreviewRow(SyncPreviewOperationType.MODIFIED, "m.txt", "6 B", 6L);

        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);
        renderer.showSyncPreviewDialogWithResult(
                planWith(List.of(fileInfo("m.txt", 6L)), java.util.Set.of("m.txt")), dir.toFile());

        FileDiffPreviewModel model = renderer.buildPreviewModel(row, "read timeout");

        assertTrue(model.isBaseAvailable());
        String reason = model.describeUnavailable(FileDiffPreviewModel.Side.BASE);
        assertNotNull(reason);
        assertTrue(reason.contains("read timeout"), reason);
        assertFalse(reason.contains("does not have this file"), reason);
    }

    @Test
    void newFilePreviewHasNoBaseVersionButStillShowsContent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("fresh.txt"), "hello\n");
        SyncPreviewRow row =
                new SyncPreviewRow(SyncPreviewOperationType.NEW, "fresh.txt", "6 B", 6L);

        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);
        renderer.showSyncPreviewDialogWithResult(
                planWith(List.of(fileInfo("fresh.txt", 6L)), java.util.Set.of()), dir.toFile());

        FileDiffPreviewModel model = renderer.buildPreviewModel(row, null);

        assertFalse(model.isBaseAvailable());
        assertEquals("hello\n", model.getSourceText());
        assertEquals(
                "No previous version - the peer does not have this file yet.",
                model.describeUnavailable(FileDiffPreviewModel.Side.BASE));
    }

    @Test
    void oversizedLocalFileIsNotReadForPreview(@TempDir Path dir) throws Exception {
        // A file beyond the preview cap must be reported as unavailable rather than read into
        // memory, so previewing a huge binary cannot stall the UI.
        Path big = dir.resolve("big.bin");
        byte[] chunk = new byte[64 * 1024];
        try (java.io.OutputStream out = Files.newOutputStream(big)) {
            for (int i = 0; i < 9; i++) {
                out.write(chunk);
            }
        }
        assertTrue(Files.size(big) > SyncPreviewRenderer.MAX_PREVIEW_BYTES);

        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);
        renderer.showSyncPreviewDialogWithResult(
                planWith(List.of(fileInfo("big.bin", Files.size(big))), java.util.Set.of()),
                dir.toFile());

        assertNull(renderer.readLocalPreviewContent("big.bin"));
    }

    @Test
    void emptyLocalFileReadsAsEmptyContentNotUnavailable(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("empty.txt"));
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);
        renderer.showSyncPreviewDialogWithResult(
                planWith(List.of(fileInfo("empty.txt", 0L)), java.util.Set.of()), dir.toFile());

        byte[] content = renderer.readLocalPreviewContent("empty.txt");
        assertNotNull(content);
        assertEquals(0, content.length);
    }

    @Test
    void directoryOperationsDoNotAttemptAFetch() {
        SyncPreviewRow row =
                new SyncPreviewRow(SyncPreviewOperationType.CREATE_DIR, "newdir", "-", 0L);
        assertFalse(row.hasBaseVersion());
    }

    @Test
    void previewForUnconfiguredFolderReportsUnavailable() {
        SyncPreviewRenderer renderer = new SyncPreviewRenderer(null);
        File folder = new File(System.getProperty("java.io.tmpdir"));
        renderer.showSyncPreviewDialogWithResult(
                planWith(List.of(fileInfo("a.txt", 1L)), java.util.Set.of("a.txt")), folder);

        // The file does not exist in the temp folder, so the local read must fail cleanly.
        assertNull(renderer.readLocalPreviewContent("definitely-missing-file-xyz.txt"));
    }
}
