package com.filesync.sync;

import java.util.HashSet;
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
    private final List<ConflictInfo> conflicts;

    public SyncPreviewPlan(List<FileChangeDetector.FileInfo> filesToTransfer,
                           List<String> emptyDirectoriesToCreate,
                           List<String> filesToDelete,
                           List<String> emptyDirectoriesToDelete,
                           long totalBytesToTransfer,
                           boolean strictSyncMode) {
        this(filesToTransfer, emptyDirectoriesToCreate, filesToDelete, emptyDirectoriesToDelete,
             totalBytesToTransfer, strictSyncMode, Collections.emptyList());
    }

    public SyncPreviewPlan(List<FileChangeDetector.FileInfo> filesToTransfer,
                           List<String> emptyDirectoriesToCreate,
                           List<String> filesToDelete,
                           List<String> emptyDirectoriesToDelete,
                           long totalBytesToTransfer,
                           boolean strictSyncMode,
                           List<ConflictInfo> conflicts) {
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
        this.conflicts = conflicts != null ? List.copyOf(conflicts) : Collections.emptyList();
    }

    public SyncPreviewPlan createFilteredPlan(Set<String> selectedFilesToTransfer,
                                             Set<String> selectedEmptyDirectoriesToCreate,
                                             Set<String> selectedFilesToDelete,
                                             Set<String> selectedEmptyDirectoriesToDelete) {
        List<FileChangeDetector.FileInfo> filteredFilesToTransfer =
                filterFilesBySelectionAndConflicts(filesToTransfer, selectedFilesToTransfer, conflicts);

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
                strictSyncMode,
                conflicts);
    }

    private static List<FileChangeDetector.FileInfo> filterFilesBySelectionAndConflicts(
            List<FileChangeDetector.FileInfo> source,
            Set<String> selectedPaths,
            List<ConflictInfo> conflicts) {
        if (selectedPaths == null && (conflicts == null || conflicts.isEmpty())) {
            return copyFiles(source);
        }
        Set<String> conflictSkipPaths = new HashSet<>();
        if (conflicts != null) {
            for (ConflictInfo conflict : conflicts) {
                ConflictInfo.Resolution res = conflict.getResolution();
                if (res == ConflictInfo.Resolution.SKIP || res == ConflictInfo.Resolution.KEEP_REMOTE) {
                    conflictSkipPaths.add(conflict.getPath());
                }
            }
        }
        List<FileChangeDetector.FileInfo> result = new ArrayList<>();
        for (FileChangeDetector.FileInfo fileInfo : source) {
            String path = fileInfo.getPath();
            if (selectedPaths != null && !selectedPaths.contains(path)) {
                continue;
            }
            if (conflictSkipPaths.contains(path)) {
                continue;
            }
            result.add(fileInfo);
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

    public List<ConflictInfo> getConflicts() {
        return conflicts;
    }

    public boolean hasConflict(String path) {
        return conflicts.stream().anyMatch(c -> c.getPath().equals(path));
    }

    public ConflictInfo getConflict(String path) {
        return conflicts.stream()
                .filter(c -> c.getPath().equals(path))
                .findFirst()
                .orElse(null);
    }
}
