package com.filesync.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable result of a sync pre-check that shows all planned operations. */
public final class SyncPreviewPlan {

    private final List<FileChangeDetector.FileInfo> filesToTransfer;
    private final List<String> emptyDirectoriesToCreate;
    private final List<String> filesToDelete;
    private final List<String> emptyDirectoriesToDelete;
    private final long totalBytesToTransfer;
    private final boolean strictSyncMode;
    private final int totalOperations;
    private final List<ConflictInfo> conflicts;
    private final Set<String> deltaCandidatePaths;
    private final Set<String> appendResumablePaths;
    private final Set<String> existingRemotePaths;
    private final Map<String, FileChangeDetector.FileInfo> remoteFileInfos;
    private final Map<String, FileChangeDetector.FileInfo> localFileInfos;
    private final List<FileChangeDetector.FileRename> renames;

    public SyncPreviewPlan(
            List<FileChangeDetector.FileInfo> filesToTransfer,
            List<String> emptyDirectoriesToCreate,
            List<String> filesToDelete,
            List<String> emptyDirectoriesToDelete,
            long totalBytesToTransfer,
            boolean strictSyncMode) {
        this(
                filesToTransfer,
                emptyDirectoriesToCreate,
                filesToDelete,
                emptyDirectoriesToDelete,
                totalBytesToTransfer,
                strictSyncMode,
                Collections.emptyList());
    }

    public SyncPreviewPlan(
            List<FileChangeDetector.FileInfo> filesToTransfer,
            List<String> emptyDirectoriesToCreate,
            List<String> filesToDelete,
            List<String> emptyDirectoriesToDelete,
            long totalBytesToTransfer,
            boolean strictSyncMode,
            List<ConflictInfo> conflicts) {
        this(
                filesToTransfer,
                emptyDirectoriesToCreate,
                filesToDelete,
                emptyDirectoriesToDelete,
                totalBytesToTransfer,
                strictSyncMode,
                conflicts,
                Collections.emptySet());
    }

    public SyncPreviewPlan(
            List<FileChangeDetector.FileInfo> filesToTransfer,
            List<String> emptyDirectoriesToCreate,
            List<String> filesToDelete,
            List<String> emptyDirectoriesToDelete,
            long totalBytesToTransfer,
            boolean strictSyncMode,
            List<ConflictInfo> conflicts,
            Set<String> deltaCandidatePaths) {
        this(
                filesToTransfer,
                emptyDirectoriesToCreate,
                filesToDelete,
                emptyDirectoriesToDelete,
                totalBytesToTransfer,
                strictSyncMode,
                conflicts,
                deltaCandidatePaths,
                Collections.emptySet());
    }

    public SyncPreviewPlan(
            List<FileChangeDetector.FileInfo> filesToTransfer,
            List<String> emptyDirectoriesToCreate,
            List<String> filesToDelete,
            List<String> emptyDirectoriesToDelete,
            long totalBytesToTransfer,
            boolean strictSyncMode,
            List<ConflictInfo> conflicts,
            Set<String> deltaCandidatePaths,
            Set<String> existingRemotePaths) {
        this(
                filesToTransfer,
                emptyDirectoriesToCreate,
                filesToDelete,
                emptyDirectoriesToDelete,
                totalBytesToTransfer,
                strictSyncMode,
                conflicts,
                deltaCandidatePaths,
                existingRemotePaths,
                Collections.emptyMap());
    }

    /**
     * Full constructor, carrying the remote manifest's per-file metadata (size, md5, ...) for paths
     * that exist on the receiver. The transfer phase uses this to detect append-only changes
     * without re-exchanging manifests.
     */
    public SyncPreviewPlan(
            List<FileChangeDetector.FileInfo> filesToTransfer,
            List<String> emptyDirectoriesToCreate,
            List<String> filesToDelete,
            List<String> emptyDirectoriesToDelete,
            long totalBytesToTransfer,
            boolean strictSyncMode,
            List<ConflictInfo> conflicts,
            Set<String> deltaCandidatePaths,
            Set<String> existingRemotePaths,
            Map<String, FileChangeDetector.FileInfo> remoteFileInfos) {
        this(
                filesToTransfer,
                emptyDirectoriesToCreate,
                filesToDelete,
                emptyDirectoriesToDelete,
                totalBytesToTransfer,
                strictSyncMode,
                conflicts,
                deltaCandidatePaths,
                Collections.emptySet(),
                existingRemotePaths,
                remoteFileInfos);
    }

    /**
     * Full constructor including {@code appendResumablePaths}: paths whose receiver copy is a
     * verified byte-prefix of the sender's file, so only the missing tail will be transferred —
     * binary conflicts exempted from the conflict path (a partially copied file) and delta
     * candidates verified as pure appends (e.g. an append resumed after an interruption). The
     * preview labels these rows as APPEND instead of MODIFIED.
     */
    public SyncPreviewPlan(
            List<FileChangeDetector.FileInfo> filesToTransfer,
            List<String> emptyDirectoriesToCreate,
            List<String> filesToDelete,
            List<String> emptyDirectoriesToDelete,
            long totalBytesToTransfer,
            boolean strictSyncMode,
            List<ConflictInfo> conflicts,
            Set<String> deltaCandidatePaths,
            Set<String> appendResumablePaths,
            Set<String> existingRemotePaths,
            Map<String, FileChangeDetector.FileInfo> remoteFileInfos) {
        this(
                filesToTransfer,
                emptyDirectoriesToCreate,
                filesToDelete,
                emptyDirectoriesToDelete,
                totalBytesToTransfer,
                strictSyncMode,
                conflicts,
                deltaCandidatePaths,
                appendResumablePaths,
                existingRemotePaths,
                remoteFileInfos,
                Collections.emptyMap());
    }

    /**
     * Full constructor additionally carrying the local manifest's per-file metadata. The end-of-
     * session base recording needs it to tell converged files (local == remote, nothing
     * transferred) from diverged ones, so their confirmed state stays fresh without re-sending.
     */
    public SyncPreviewPlan(
            List<FileChangeDetector.FileInfo> filesToTransfer,
            List<String> emptyDirectoriesToCreate,
            List<String> filesToDelete,
            List<String> emptyDirectoriesToDelete,
            long totalBytesToTransfer,
            boolean strictSyncMode,
            List<ConflictInfo> conflicts,
            Set<String> deltaCandidatePaths,
            Set<String> appendResumablePaths,
            Set<String> existingRemotePaths,
            Map<String, FileChangeDetector.FileInfo> remoteFileInfos,
            Map<String, FileChangeDetector.FileInfo> localFileInfos) {
        this(
                filesToTransfer,
                emptyDirectoriesToCreate,
                filesToDelete,
                emptyDirectoriesToDelete,
                totalBytesToTransfer,
                strictSyncMode,
                conflicts,
                deltaCandidatePaths,
                appendResumablePaths,
                existingRemotePaths,
                remoteFileInfos,
                localFileInfos,
                Collections.emptyList());
    }

    /**
     * Full constructor additionally carrying the renames the detector paired up (see {@link
     * FileChangeDetector#findRenames}). A rename's new path is NOT part of {@code filesToTransfer}
     * and its old path is NOT part of {@code filesToDelete}: the rename replaces both operations,
     * so counting and filtering must account for it exactly once.
     */
    public SyncPreviewPlan(
            List<FileChangeDetector.FileInfo> filesToTransfer,
            List<String> emptyDirectoriesToCreate,
            List<String> filesToDelete,
            List<String> emptyDirectoriesToDelete,
            long totalBytesToTransfer,
            boolean strictSyncMode,
            List<ConflictInfo> conflicts,
            Set<String> deltaCandidatePaths,
            Set<String> appendResumablePaths,
            Set<String> existingRemotePaths,
            Map<String, FileChangeDetector.FileInfo> remoteFileInfos,
            Map<String, FileChangeDetector.FileInfo> localFileInfos,
            List<FileChangeDetector.FileRename> renames) {
        this.filesToTransfer = copyFiles(filesToTransfer);
        this.emptyDirectoriesToCreate = copyPaths(emptyDirectoriesToCreate);
        this.filesToDelete = copyPaths(filesToDelete);
        this.emptyDirectoriesToDelete = copyPaths(emptyDirectoriesToDelete);
        this.totalBytesToTransfer = totalBytesToTransfer;
        this.strictSyncMode = strictSyncMode;
        this.renames =
                renames != null
                        ? Collections.unmodifiableList(new ArrayList<>(renames))
                        : Collections.emptyList();
        this.totalOperations =
                this.filesToTransfer.size()
                        + this.emptyDirectoriesToCreate.size()
                        + this.filesToDelete.size()
                        + this.emptyDirectoriesToDelete.size()
                        + this.renames.size();
        this.conflicts = conflicts != null ? List.copyOf(conflicts) : Collections.emptyList();
        this.deltaCandidatePaths =
                deltaCandidatePaths != null
                        ? Collections.unmodifiableSet(new HashSet<>(deltaCandidatePaths))
                        : Collections.emptySet();
        this.appendResumablePaths =
                appendResumablePaths != null
                        ? Collections.unmodifiableSet(new HashSet<>(appendResumablePaths))
                        : Collections.emptySet();
        this.existingRemotePaths =
                existingRemotePaths != null
                        ? Collections.unmodifiableSet(new HashSet<>(existingRemotePaths))
                        : Collections.emptySet();
        this.remoteFileInfos =
                remoteFileInfos != null
                        ? Collections.unmodifiableMap(new HashMap<>(remoteFileInfos))
                        : Collections.emptyMap();
        this.localFileInfos =
                localFileInfos != null
                        ? Collections.unmodifiableMap(new HashMap<>(localFileInfos))
                        : Collections.emptyMap();
    }

    public SyncPreviewPlan createFilteredPlan(
            Set<String> selectedFilesToTransfer,
            Set<String> selectedEmptyDirectoriesToCreate,
            Set<String> selectedFilesToDelete,
            Set<String> selectedEmptyDirectoriesToDelete) {
        // No way to express a rename selection in this signature, so no rename survives: the
        // unselected rename leaves the receiver's old copy in place and the sender keeps the new
        // one, which the next sync pairs up again.
        return createFilteredPlan(
                selectedFilesToTransfer,
                selectedEmptyDirectoriesToCreate,
                selectedFilesToDelete,
                selectedEmptyDirectoriesToDelete,
                Collections.emptySet());
    }

    /**
     * Derive the plan for the checked preview rows. A rename survives the filter when its new path
     * is selected; the old path is not part of any selection set (it is not a delete row of this
     * plan), so the pair is keyed on the new path alone.
     */
    public SyncPreviewPlan createFilteredPlan(
            Set<String> selectedFilesToTransfer,
            Set<String> selectedEmptyDirectoriesToCreate,
            Set<String> selectedFilesToDelete,
            Set<String> selectedEmptyDirectoriesToDelete,
            Set<String> selectedRenames) {
        List<FileChangeDetector.FileInfo> filteredFilesToTransfer =
                filterFilesBySelectionAndConflicts(
                        filesToTransfer, selectedFilesToTransfer, conflicts);

        List<String> filteredEmptyDirectoriesToCreate =
                filterPathsBySelection(emptyDirectoriesToCreate, selectedEmptyDirectoriesToCreate);

        List<String> filteredFilesToDelete =
                filterPathsBySelection(filesToDelete, selectedFilesToDelete);

        List<String> filteredEmptyDirectoriesToDelete =
                filterPathsBySelection(emptyDirectoriesToDelete, selectedEmptyDirectoriesToDelete);

        List<FileChangeDetector.FileRename> filteredRenames =
                filterRenamesBySelection(renames, selectedRenames);

        long filteredTotalBytesToTransfer =
                filteredFilesToTransfer.stream()
                        .mapToLong(FileChangeDetector.FileInfo::getSize)
                        .sum();

        Set<String> filteredDeltaCandidates = filterDeltaCandidates(filteredFilesToTransfer);
        Set<String> filteredAppendResumable = filterAppendResumable(filteredFilesToTransfer);

        return new SyncPreviewPlan(
                filteredFilesToTransfer,
                filteredEmptyDirectoriesToCreate,
                filteredFilesToDelete,
                filteredEmptyDirectoriesToDelete,
                filteredTotalBytesToTransfer,
                strictSyncMode,
                conflicts,
                filteredDeltaCandidates,
                filteredAppendResumable,
                existingRemotePaths,
                remoteFileInfos,
                localFileInfos,
                filteredRenames);
    }

    /** Renames that survived the selection filter, in the plan's deterministic order. */
    private static List<FileChangeDetector.FileRename> filterRenamesBySelection(
            List<FileChangeDetector.FileRename> source, Set<String> selectedToPaths) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        if (selectedToPaths == null) {
            return new ArrayList<>(source);
        }
        List<FileChangeDetector.FileRename> result = new ArrayList<>();
        for (FileChangeDetector.FileRename rename : source) {
            if (selectedToPaths.contains(rename.getToPath())) {
                result.add(rename);
            }
        }
        return result;
    }

    /** Delta candidates that survived the selection filter (and were not dropped as conflicts). */
    private Set<String> filterDeltaCandidates(List<FileChangeDetector.FileInfo> filteredFiles) {
        if (deltaCandidatePaths.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (FileChangeDetector.FileInfo fi : filteredFiles) {
            if (deltaCandidatePaths.contains(fi.getPath())) {
                result.add(fi.getPath());
            }
        }
        return result;
    }

    /** Exempted prefix conflicts that survived the selection filter. */
    private Set<String> filterAppendResumable(List<FileChangeDetector.FileInfo> filteredFiles) {
        if (appendResumablePaths.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (FileChangeDetector.FileInfo fi : filteredFiles) {
            if (appendResumablePaths.contains(fi.getPath())) {
                result.add(fi.getPath());
            }
        }
        return result;
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
                if (res == ConflictInfo.Resolution.SKIP
                        || res == ConflictInfo.Resolution.KEEP_REMOTE) {
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

    private static List<String> filterPathsBySelection(
            List<String> source, Set<String> selectedPaths) {
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

    private static List<FileChangeDetector.FileInfo> copyFiles(
            List<FileChangeDetector.FileInfo> files) {
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

    /**
     * Paths of files eligible for rsync-style delta transfer (exist on both sides, large enough).
     */
    public Set<String> getDeltaCandidatePaths() {
        return deltaCandidatePaths;
    }

    /**
     * Binary conflicts exempted from the conflict path because the receiver's file verified as a
     * byte-prefix of the sender's; the transfer phase should send only the missing tail.
     */
    public Set<String> getAppendResumablePaths() {
        return appendResumablePaths;
    }

    /** Paths that already exist on the remote side; used to tell NEW files from MODIFIED files. */
    public Set<String> getExistingRemotePaths() {
        return existingRemotePaths;
    }

    /**
     * Remote manifest metadata (size, md5, lastModified) for a path that exists on the receiver, or
     * null if the receiver does not have the file.
     */
    public FileChangeDetector.FileInfo getRemoteFileInfo(String path) {
        return remoteFileInfos.get(path);
    }

    /**
     * Local manifest metadata (size, md5, lastModified) per path, for the end-of-session base
     * recording: a path whose local md5 equals the remote one is already converged and keeps its
     * confirmed state fresh without any transfer.
     */
    public Map<String, FileChangeDetector.FileInfo> getLocalFileInfos() {
        return localFileInfos;
    }

    /**
     * Renames to carry out instead of a transfer plus a delete (see {@link
     * FileChangeDetector#findRenames}), ordered by the new path. A rename's new path never appears
     * in {@link #getFilesToTransfer()} and its old path never in {@link #getFilesToDelete()}.
     */
    public List<FileChangeDetector.FileRename> getRenames() {
        return renames;
    }

    public boolean hasConflict(String path) {
        return conflicts.stream().anyMatch(c -> c.getPath().equals(path));
    }

    public ConflictInfo getConflict(String path) {
        return conflicts.stream().filter(c -> c.getPath().equals(path)).findFirst().orElse(null);
    }
}
