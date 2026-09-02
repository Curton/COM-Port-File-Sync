package com.filesync.sync;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analyzes two file manifests to detect conflicts - files that have been modified on both sender
 * and receiver since the last sync.
 *
 * <p>Conflict detection is based on manifest metadata (MD5 checksums or size+mtime). Actual content
 * is fetched later when needed for merge UI.
 */
public class ConflictAnalyzer {

    private static final Set<String> BINARY_EXTENSIONS = new HashSet<>();

    // 50MB threshold - files larger than this will use sampling for content analysis
    private static final long MAX_FULL_READ_BYTES = 1024 * 1024;

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
     * sides with different content.
     *
     * <p>For text files, this method computes a line-by-line diff and filters out conflicts that
     * only have trivial differences (whitespace-only changes, blank lines). Binary files are always
     * treated as conflicts if their content differs.
     *
     * @param localManifest the sender's manifest
     * @param remoteManifest the receiver's manifest
     * @param localFolder the sender's sync folder (to read local content for ConflictInfo)
     * @return list of detected conflicts with meaningful differences, never null
     */
    public static List<ConflictInfo> findConflicts(
            FileChangeDetector.FileManifest localManifest,
            FileChangeDetector.FileManifest remoteManifest,
            File localFolder) {

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
                    // Only treat as conflict when receiver has newer changes (we would overwrite
                    // them).
                    // If only sender modified, remote has old version - normal transfer, no
                    // conflict.
                    if (!isReceiverNewer(localInfo, remoteInfo)) {
                        continue; // Sender's version is same or newer, safe to transfer
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
     * Filter conflicts to only include those with meaningful differences. This method should be
     * called after remote content has been fetched.
     *
     * @param conflicts list of conflicts to filter (modified in place)
     * @return the same list with trivial conflicts removed (trivial conflicts are marked as SKIP)
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
     * Remove conflicts whose receiver-side file is a byte-prefix of the sender's file, returning the
     * set of exempted paths.
     *
     * <p>This is the shape of a partially copied file: the receiver holds the sender's first N
     * bytes (e.g. an archive transferred halfway through some outside-the-sync channel, so the
     * copy's mtime is newer than the sender's). Such a file is not receiver-modified content, but
     * the md5/mtime manifests alone classify it as a binary conflict, which excludes it from the
     * delta candidates and forces a full transfer through the conflict dialog. Exempting it lets
     * the file reach the append/delta path, which sends only the missing tail.
     *
     * <p>The match is verified by hashing the sender's first {@code remoteInfo.getSize()} bytes with
     * the manifest algorithm ({@link FileChangeDetector#hashFilePrefix}) and comparing against the
     * receiver's manifest md5. Conflicts without a receiver md5 (fast mode leaves binary files
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
     * Compute and store diff for a conflict. Useful when remote content has been fetched and we
     * need to prepare the conflict for UI display.
     *
     * @param conflict the conflict to compute diff for
     */
    public static void computeConflictDiff(ConflictInfo conflict) {
        if (conflict.isBinary()) {
            return; // No diff for binary files
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
     * True when receiver (remote) has a newer version than sender (local). In that case we would
     * overwrite receiver's changes - a real conflict. When only sender modified, remote is older -
     * no conflict, normal transfer.
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

    /**
     * Check if content appears to be binary based on byte analysis. Uses the same logic as
     * CompressionUtil.isLikelyBinaryContent.
     */
    public static boolean isLikelyBinary(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }

        int sampleLength = Math.min(data.length, 4096);
        int nonTextCount = 0;

        for (int i = 0; i < sampleLength; i++) {
            int b = data[i] & 0xFF;
            // Non-text: null bytes, or control chars (except tab, newline, carriage return)
            if (b == 0 || (b < 32 && b != 9 && b != 10 && b != 13) || b == 127) {
                nonTextCount++;
            }
        }

        return (double) nonTextCount / sampleLength > 0.10; // More than 10% non-text bytes
    }

    /**
     * Read file content, with a size limit for memory protection. Files larger than
     * MAX_FULL_READ_BYTES will return null; use readFileSample() instead.
     */
    public static byte[] readFileContent(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }

        try {
            long fileSize = file.length();
            if (fileSize > MAX_FULL_READ_BYTES) {
                return null; // File too large, use sample instead
            }
            if (fileSize > Integer.MAX_VALUE) {
                return null; // File too large
            }

            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    /** Read a small sample of file content for binary detection. */
    public static byte[] readFileSample(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return new byte[0];
        }

        try {
            long fileSize = file.length();
            int toRead = (int) Math.min(fileSize, 4096);

            byte[] sample = new byte[toRead];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                int totalRead = 0;
                while (totalRead < toRead) {
                    int read = fis.read(sample, totalRead, toRead - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
            }
            return sample;
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
