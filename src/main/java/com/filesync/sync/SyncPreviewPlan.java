package com.filesync.sync;

import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Set;

/**
 * Immutable result of a sync pre-check that shows all planned operations.
 */
public final class SyncPreviewPlan {

    private final List<FileChangeDetector.FileInfo> filesToTransfer;
    private final List<String> emptyDirectoriesToCreate;
    private final List<String> filesToDelete;
    private final List<String> emptyDirectoriesToDelete;
    private final long totalBytesToTransfer;
    private final boolean strictSyncMode;
    private final int totalOperations;

    public SyncPreviewPlan(List<FileChangeDetector.FileInfo> filesToTransfer,
                           List<String> emptyDirectoriesToCreate,
                           List<String> filesToDelete,
                           List<String> emptyDirectoriesToDelete,
                           long totalBytesToTransfer,
                           boolean strictSyncMode) {
        this.filesToTransfer = copyFiles(filesToTransfer);
        this.emptyDirectoriesToCreate = copyPaths(emptyDirectoriesToCreate);
        this.filesToDelete = copyPaths(filesToDelete);
        this.emptyDirectoriesToDelete = copyPaths(emptyDirectoriesToDelete);
        this.totalBytesToTransfer = totalBytesToTransfer;
        this.strictSyncMode = strictSyncMode;
        this.totalOperations = this.filesToTransfer.size()
                + this.emptyDirectoriesToCreate.size()
                + this.filesToDelete.size()
                + this.emptyDirectoriesToDelete.size();
    }

    public SyncPreviewPlan createFilteredPlan(Set<String> selectedFilesToTransfer,
                                             Set<String> selectedEmptyDirectoriesToCreate,
                                             Set<String> selectedFilesToDelete,
                                             Set<String> selectedEmptyDirectoriesToDelete) {
        List<FileChangeDetector.FileInfo> filteredFilesToTransfer =
                filterFilesBySelection(filesToTransfer, selectedFilesToTransfer);

        List<String> filteredEmptyDirectoriesToCreate =
                filterPathsBySelection(emptyDirectoriesToCreate, selectedEmptyDirectoriesToCreate);

        List<String> filteredFilesToDelete =
                filterPathsBySelection(filesToDelete, selectedFilesToDelete);

        List<String> filteredEmptyDirectoriesToDelete =
                filterPathsBySelection(emptyDirectoriesToDelete, selectedEmptyDirectoriesToDelete);

        long filteredTotalBytesToTransfer = filteredFilesToTransfer.stream()
                .mapToLong(FileChangeDetector.FileInfo::getSize)
                .sum();

        return new SyncPreviewPlan(
                filteredFilesToTransfer,
                filteredEmptyDirectoriesToCreate,
                filteredFilesToDelete,
                filteredEmptyDirectoriesToDelete,
                filteredTotalBytesToTransfer,
                strictSyncMode);
    }

    private static List<FileChangeDetector.FileInfo> filterFilesBySelection(List<FileChangeDetector.FileInfo> source,
                                                                         Set<String> selectedPaths) {
        if (selectedPaths == null) {
            return copyFiles(source);
        }
        List<FileChangeDetector.FileInfo> result = new ArrayList<>();
        for (FileChangeDetector.FileInfo fileInfo : source) {
            if (selectedPaths.contains(fileInfo.getPath())) {
                result.add(fileInfo);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> filterPathsBySelection(List<String> source,
                                                      Set<String> selectedPaths) {
        if (selectedPaths == null) {
            return copyPaths(source);
        }
        List<String> result = new ArrayList<>();
        for (String path : source) {
            if (selectedPaths.contains(path)) {
                result.add(path);
            }
        }
        return List.copyOf(result);
    }

    private static List<FileChangeDetector.FileInfo> copyFiles(List<FileChangeDetector.FileInfo> files) {
        return files == null ? Collections.emptyList() : List.copyOf(files);
    }

    private static List<String> copyPaths(List<String> paths) {
        return paths == null ? Collections.emptyList() : List.copyOf(paths);
    }

    public List<FileChangeDetector.FileInfo> getFilesToTransfer() {
        return filesToTransfer;
    }

    public List<String> getEmptyDirectoriesToCreate() {
        return emptyDirectoriesToCreate;
    }

    public List<String> getFilesToDelete() {
        return filesToDelete;
    }

    public List<String> getEmptyDirectoriesToDelete() {
        return emptyDirectoriesToDelete;
    }

    public long getTotalBytesToTransfer() {
        return totalBytesToTransfer;
    }

    public boolean isStrictSyncMode() {
        return strictSyncMode;
    }

    public int getTotalOperations() {
        return totalOperations;
    }
}
