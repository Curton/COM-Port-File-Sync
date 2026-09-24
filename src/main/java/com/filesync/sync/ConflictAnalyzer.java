package com.filesync.sync;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analyzes two file manifests to detect conflicts — files modified on both sides since the last
 * successful sync.
 *
 * <p>Arbitration compares three states per path: the local manifest entry (L), the remote manifest
 * entry (R) and the base (B) recorded by the {@link SyncStateStore} for the last successful sync.
 * When content differs (L != R):
 *
 * <ul>
 *   <li>R == B: only the sender changed the file — a normal transfer, no conflict.
 *   <li>R != B (any L): the receiver changed its copy too — pushing the sender's version would
 *       overwrite those changes, so the conflict dialog decides.
 *   <li>B missing (first pairing, or the state store was wiped): the history is unknown, so the
 *       path is treated as a conflict conservative once; the session then records fresh bases.
 *   <li>L or R without a hash (fast mode leaves binaries unhashed): no hash to arbitrate with, so
 *       the timestamps decide exactly as before.
 * </ul>
 *
 * <p>Conflict detection needs manifest metadata only (MD5, or size+mtime in fast mode); actual
 * content is fetched later when needed for the merge UI.
 */
public class ConflictAnalyzer {

    private static final Set<String> BINARY_EXTENSIONS = new HashSet<>();

    static {
        // Common binary file extensions (no duplicates)
        String[] binaryExts = {
            "jpg", "jpeg", "png", "gif", "bmp", "ico", "webp", "svg", "avif", "pdf", "doc", "docx",
            "xls", "xlsx", "ppt", "pptx", "zip", "gz", "bz2", "xz", "7z", "rar", "tar", "mp3",
            "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "aac", "ogg", "flac", "wma", "m4a",
            "exe", "dll", "so", "dylib", "class", "jar", "war", "ear", "ttf", "otf", "woff",
            "woff2", "eot", "db", "sqlite", "mdb", "dat", "bin"
        };
        for (String ext : binaryExts) {
            BINARY_EXTENSIONS.add(ext);
        }
    }

    /**
     * Find all conflicts between two manifests. A conflict occurs when the same file exists on both
     * sides, their content differs, and the remote copy is not the state both sides last agreed on
     * (see the class javadoc for the full rule).
     *
     * <p>For text files, this method computes a line-by-line diff and filters out conflicts that
     * only have trivial differences (whitespace-only changes, blank lines). Binary files are always
     * treated as conflicts if their content differs.
     *
     * @param localManifest the sender's manifest
     * @param remoteManifest the receiver's manifest
     * @param localFolder the sender's sync folder (to read local content for ConflictInfo)
     * @param syncState the store of last-successfully-synced states; a null store means "no base
     *     known", the same state a first pairing is in (every difference is a conflict)
     * @return list of detected conflicts with meaningful differences, never null
     */
    public static List<ConflictInfo> findConflicts(
            FileChangeDetector.FileManifest localManifest,
            FileChangeDetector.FileManifest remoteManifest,
            File localFolder,
            SyncStateStore syncState) {

        List<ConflictInfo> conflicts = new ArrayList<>();

        Map<String, FileChangeDetector.FileInfo> localFiles = localManifest.getFiles();
        Map<String, FileChangeDetector.FileInfo> remoteFiles = remoteManifest.getFiles();

        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(localFiles.keySet());
        allPaths.addAll(remoteFiles.keySet());

        for (String path : allPaths) {
            FileChangeDetector.FileInfo localInfo = localFiles.get(path);
            FileChangeDetector.FileInfo remoteInfo = remoteFiles.get(path);

            if (localInfo != null && remoteInfo != null) {
                // File exists on both sides - check if content differs
                if (contentDiffers(localInfo, remoteInfo)) {
                    // Decide whether this is a conflict from the recorded base: transferring is
                    // only harmless when the receiver still holds the agreed-on version.
                    if (!isRemoteDivergedFromBase(path, localInfo, remoteInfo, syncState)) {
                        continue; // only the sender modified: normal transfer
                    }
                    boolean isBinary = isBinaryExtension(path);
                    File localFile = new File(localFolder, path);

                    ConflictInfo conflict =
                            new ConflictInfo(path, localInfo, remoteInfo, isBinary, (byte[]) null);
                    conflict.setLazyLocalFile(localFile);

                    conflicts.add(conflict);
                }
            }
            // Files that exist on only one side are not conflicts - normal sync direction
        }

        return conflicts;
    }

    /**
     * Compute and record whether each text conflict has meaningful differences. This method should
     * be called after remote content has been fetched.
     *
     * <p>Nothing is removed: a conflict whose differences are purely whitespace or blank lines is
     * pre-resolved to {@link ConflictInfo.Resolution#KEEP_LOCAL} so it syncs the sender's version
     * directly, and the caller drops it from the resolution queue by checking {@link
     * ConflictInfo#isResolved()} together with {@link ConflictInfo#hasMeaningfulDifferences()}.
     *
     * @param conflicts list of conflicts to annotate (modified in place)
     * @return the same list, for convenience
     */
    public static List<ConflictInfo> filterTrivialConflicts(List<ConflictInfo> conflicts) {
        conflicts.removeIf(
                conflict -> {
                    if (conflict.isBinary()) {
                        return false; // Binary files are always meaningful
                    }
                    String localText = conflict.getLocalContentAsString();
                    String remoteText = conflict.getRemoteContentAsString();
                    if (localText == null || remoteText == null) {
                        return false; // Can't determine, keep the conflict
                    }
                    // Compute diff and check for meaningful differences
                    TextDiffUtil.DiffResult diff = TextDiffUtil.computeDiff(localText, remoteText);
                    conflict.setDiffResult(diff);
                    boolean hasMeaningful = diff.hasMeaningfulChanges();
                    conflict.setHasMeaningfulDifferences(hasMeaningful);
                    if (!hasMeaningful) {
                        // Mark trivial conflicts as KEEP_LOCAL to match direct sync behavior
                        conflict.setResolution(ConflictInfo.Resolution.KEEP_LOCAL);
                        return false; // Keep in the list - will sync local version
                    }
                    return false;
                });
        return conflicts;
    }

    /**
     * Remove conflicts whose receiver-side file is a byte-prefix of the sender's file, returning
     * the set of exempted paths.
     *
     * <p>This is the shape of a partially copied file: the receiver holds the sender's first N
     * bytes (e.g. an archive transferred halfway through some outside-the-sync channel). Such a
     * file is not receiver-modified content — its base state is neither the old agreed-on version
     * nor verifiable as an append, so the manifests alone classify it as a conflict, which excludes
     * it from the delta candidates and forces a full transfer through the conflict dialog.
     * Exempting it lets the file reach the append/delta path, which sends only the missing tail.
     *
     * <p>The match is verified by hashing the sender's first {@code remoteInfo.getSize()} bytes
     * with the manifest algorithm ({@link FileChangeDetector#hashFilePrefix}) and comparing against
     * the receiver's manifest md5. Conflicts without a receiver md5 (fast mode leaves binary files
     * unhashed) are never exempted — an unverified exemption could silently overwrite a genuinely
     * receiver-modified file. Text conflicts are also left alone; they keep the merge flow.
     *
     * <p>Mutates the given list in place (like {@link #filterTrivialConflicts}).
     *
     * @param conflicts conflicts to filter, as returned by {@link #findConflicts}
     * @param localFolder the sender's sync folder, used to resolve conflict paths to files
     * @return paths that were exempted from the conflict path
     */
    public static Set<String> exemptPrefixShapedConflicts(
            List<ConflictInfo> conflicts, File localFolder) {
        Set<String> exempted = new LinkedHashSet<>();
        if (conflicts == null || conflicts.isEmpty()) {
            return exempted;
        }
        Iterator<ConflictInfo> iterator = conflicts.iterator();
        while (iterator.hasNext()) {
            ConflictInfo conflict = iterator.next();
            if (!conflict.isBinary()) {
                continue;
            }
            FileChangeDetector.FileInfo localInfo = conflict.getLocalInfo();
            FileChangeDetector.FileInfo remoteInfo = conflict.getRemoteInfo();
            if (localInfo == null || remoteInfo == null) {
                continue;
            }
            long baseSize = remoteInfo.getSize();
            String remoteMd5 = remoteInfo.getMd5();
            if (baseSize <= 0 || baseSize >= localInfo.getSize()) {
                // The receiver's copy must be a strictly shorter, non-empty prefix candidate.
                continue;
            }
            if (remoteMd5 == null || remoteMd5.isEmpty()) {
                continue;
            }
            File localFile = new File(localFolder, conflict.getPath());
            if (!localFile.isFile()) {
                continue;
            }
            try {
                String prefixMd5 =
                        FileChangeDetector.hashFilePrefix(localFile, baseSize).manifestMd5();
                if (!remoteMd5.equals(prefixMd5)) {
                    continue; // not a prefix: a genuine receiver modification
                }
            } catch (IOException e) {
                // Unreadable or the on-disk file is shorter than the announced prefix: keep the
                // conflict rather than guess.
                continue;
            }
            iterator.remove();
            exempted.add(conflict.getPath());
        }
        return exempted;
    }

    /**
     * Among delta candidates, report the ones whose local file is the receiver's copy plus a tail:
     * the receiver's manifest entry has an md5, its size is strictly shorter than the local file,
     * and hashing the local prefix of that length (with the manifest algorithm) reproduces the
     * receiver's md5.
     *
     * <p>This covers the same shape as {@link #exemptPrefixShapedConflicts} but outside the
     * conflict path: after an interrupted append is salvaged the receiver's mtime matches the
     * sender's, so the file classifies as a plain modification even though only the missing tail
     * will be sent. The preview labels the reported paths APPEND; execution re-verifies the shape
     * in {@code detectAppendCandidate} (including the rejection cache), so a stale label at worst
     * falls back to the signature-delta path.
     *
     * @param candidatePaths delta candidate paths to probe, relative to the sync folder
     * @param remoteManifest the receiver's manifest
     * @param localFolder the sender's sync folder, used to resolve candidate paths to files
     * @return the subset of {@code candidatePaths} verified as pure appends, never null
     */
    public static Set<String> findPrefixShapedDeltaCandidates(
            Set<String> candidatePaths,
            FileChangeDetector.FileManifest remoteManifest,
            File localFolder) {
        Set<String> appendShaped = new LinkedHashSet<>();
        if (candidatePaths == null || candidatePaths.isEmpty() || remoteManifest == null) {
            return appendShaped;
        }
        for (String path : candidatePaths) {
            FileChangeDetector.FileInfo remoteInfo = remoteManifest.getFiles().get(path);
            if (remoteInfo == null) {
                continue;
            }
            long baseSize = remoteInfo.getSize();
            String remoteMd5 = remoteInfo.getMd5();
            if (baseSize <= 0 || remoteMd5 == null || remoteMd5.isEmpty()) {
                continue; // unverified without a receiver md5 (fast mode) or empty receiver copy
            }
            File localFile = new File(localFolder, path);
            if (!localFile.isFile() || localFile.length() <= baseSize) {
                continue;
            }
            try {
                String prefixMd5 =
                        FileChangeDetector.hashFilePrefix(localFile, baseSize).manifestMd5();
                if (remoteMd5.equals(prefixMd5)) {
                    appendShaped.add(path);
                }
            } catch (IOException e) {
                // Unreadable or the on-disk file shrank below the announced prefix: skip.
            }
        }
        return appendShaped;
    }

    /**
     * Compute and store diff for a conflict. Useful when remote content has been fetched and we
     * need to prepare the conflict for UI display.
     *
     * @param conflict the conflict to compute diff for
     */
    public static void computeConflictDiff(ConflictInfo conflict) {
        if (conflict.isBinary()) {
            return; // No diff for binary files
        }
        if (!conflict.isLocalContentAvailable()) {
            // The local side could not be read (too large or unreadable). Diffing "" against the
            // remote text would report the whole remote file as an addition and let the merge
            // view discard the local version, so no diff is computed at all.
            return;
        }
        String localText = conflict.getLocalContentAsString();
        String remoteText = conflict.getRemoteContentAsString();
        if (localText != null && remoteText != null) {
            TextDiffUtil.DiffResult diff = TextDiffUtil.computeDiff(localText, remoteText);
            conflict.setDiffResult(diff);
            conflict.setHasMeaningfulDifferences(diff.hasMeaningfulChanges());
        }
    }

    /** Check if two file infos have different content. */
    public static boolean contentDiffers(
            FileChangeDetector.FileInfo local, FileChangeDetector.FileInfo remote) {
        // If both have MD5 checksums, compare them
        if (local.getMd5() != null && remote.getMd5() != null) {
            return !local.getMd5().equals(remote.getMd5());
        }

        // Fall back to size and timestamp comparison (fast mode)
        return local.getSize() != remote.getSize()
                || Math.abs(local.getLastModified() - remote.getLastModified())
                        > FileChangeDetector.MODIFY_WINDOW_MS;
    }

    /**
     * Whether the receiver's copy has diverged from the state both sides last agreed on (the base),
     * so transferring the sender's version would overwrite receiver-side changes.
     *
     * <p>With hashes on both sides the base is the authority: the receiver still holding the base
     * (R == B) means only the sender modified the file. A missing base (first sync, wiped store)
     * has unknown history and always counts as diverged. Without hashes (fast mode) there is
     * nothing to compare and the timestamps decide instead (see {@link #isReceiverNewer}).
     *
     * <p>Called only for paths whose content already differs (see {@link #contentDiffers}), so "L
     * == B" and "R == B" cannot both hold.
     */
    private static boolean isRemoteDivergedFromBase(
            String path,
            FileChangeDetector.FileInfo local,
            FileChangeDetector.FileInfo remote,
            SyncStateStore syncState) {
        String localMd5 = local.getMd5();
        String remoteMd5 = remote.getMd5();
        if (localMd5 == null || localMd5.isEmpty() || remoteMd5 == null || remoteMd5.isEmpty()) {
            return isReceiverNewer(local, remote);
        }
        if (syncState == null) {
            return true; // no recorded history: treat as diverged
        }
        SyncStateStore.Confirmed base = syncState.base(path);
        if (base == null || base.md5() == null || base.md5().isEmpty()) {
            return true; // never synced with a hash: unknown history
        }
        return !remoteMd5.equals(base.md5());
    }

    /**
     * True when the receiver's copy is timestamped after the sender's (beyond {@link
     * FileChangeDetector#MODIFY_WINDOW_MS}) — the fast-mode arbitration used when neither side
     * carries a hash to compare. Kept as the fallback until fast mode gains hash-based fast paths;
     * it no longer carries arbitration semantics for hashed content.
     */
    private static boolean isReceiverNewer(
            FileChangeDetector.FileInfo local, FileChangeDetector.FileInfo remote) {
        return remote.getLastModified()
                > local.getLastModified() + FileChangeDetector.MODIFY_WINDOW_MS;
    }

    /** Detect if a file is likely binary based on its extension. */
    public static boolean isBinaryExtension(String path) {
        if (path == null) {
            return false;
        }
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0 || lastDot == path.length() - 1) {
            return false;
        }
        String ext = path.substring(lastDot + 1).toLowerCase();
        return BINARY_EXTENSIONS.contains(ext);
    }
}
