package com.filesync.sync;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Packs a multi-item drag-and-drop selection (files and/or folders) into a single zip and unpacks
 * it again on the receiving peer. Entries for a lone dropped folder are relative to that folder's
 * contents so the archive unpacks as the folder itself; a multi-item selection keeps each item's
 * own name as the root path segment, with duplicate names disambiguated "file (2).ext"-style.
 * Extraction refuses entries that escape the target directory (zip-slip) and removes the partial
 * tree when it aborts.
 */
public final class DropArchiveUtil {

    private static final DateTimeFormatter ARCHIVE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private DropArchiveUtil() {}

    /** Base name for an archive holding a multi-item selection (no folder of its own to name). */
    public static String multiItemArchiveBaseName() {
        return "dropped-files-" + LocalDateTime.now().format(ARCHIVE_TIMESTAMP);
    }

    /**
     * Writes the selection into {@code targetZip}. A single directory is packed by its contents;
     * anything else keeps every item's own name as the root entry segment. Empty directories get
     * explicit entries so they survive the round trip.
     */
    public static void writeDropArchive(List<File> items, File targetZip) throws IOException {
        if (items == null || items.isEmpty()) {
            throw new IOException("No items to archive");
        }
        Path parent = targetZip.toPath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Set<String> usedEntryNames = new HashSet<>();
        try (ZipOutputStream zip =
                new ZipOutputStream(new FileOutputStream(targetZip), StandardCharsets.UTF_8)) {
            if (items.size() == 1 && items.get(0).isDirectory()) {
                for (Path child : sortedChildren(items.get(0).toPath())) {
                    addItem(zip, child, "", usedEntryNames);
                }
            } else {
                for (File item : items) {
                    addItem(zip, item.toPath(), "", usedEntryNames);
                }
            }
        }
    }

    /**
     * Extracts {@code zipFile} into a fresh subdirectory of {@code destBaseDir} named after the
     * archive's base name (deduplicated with " (n)" suffixes when taken) and returns that
     * directory. On any failure the partially extracted tree is removed again; the archive itself
     * is left for the caller to keep or discard.
     */
    public static File extractArchive(File zipFile, File destBaseDir) throws IOException {
        String baseName = archiveBaseName(zipFile.getName());
        Path destRoot = destBaseDir.toPath();
        Files.createDirectories(destRoot);
        Path targetDir = resolveUniqueDirectory(destRoot, baseName);
        Files.createDirectories(targetDir);
        try (ZipInputStream zip =
                new ZipInputStream(new FileInputStream(zipFile), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                extractEntry(zip, entry, targetDir);
            }
        } catch (IOException e) {
            deleteRecursively(targetDir.toFile());
            throw e;
        }
        return targetDir.toFile();
    }

    /** Best-effort recursive delete for temp archives and aborted partial extractions. */
    public static void deleteRecursively(File root) {
        if (root == null || !root.exists()) {
            return;
        }
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        try {
            Files.deleteIfExists(root.toPath());
        } catch (IOException ignored) {
            // Best effort: a locked leftover temp file is logged by the caller, not fatal here.
        }
    }

    private static void addItem(
            ZipOutputStream zip, Path item, String parentEntry, Set<String> usedEntryNames)
            throws IOException {
        String entryName =
                parentEntry.isEmpty()
                        ? entryName(item.getFileName().toString())
                        : parentEntry + "/" + entryName(item.getFileName().toString());
        if (Files.isDirectory(item)) {
            String dirName = uniqueEntryName(usedEntryNames, entryName, true);
            putDirectoryEntry(zip, dirName, item);
            for (Path child : sortedChildren(item)) {
                addItem(zip, child, dirName, usedEntryNames);
            }
        } else {
            putFileEntry(zip, uniqueEntryName(usedEntryNames, entryName, false), item);
        }
    }

    /**
     * Two dropped items can carry the same name (files picked from different folders); a duplicate
     * zip entry would abort the whole send, so later ones get the same " (n)" treatment the
     * receiver already uses for clashing file names.
     */
    private static String uniqueEntryName(Set<String> used, String candidate, boolean directory) {
        if (used.add(directory ? candidate + "/" : candidate)) {
            return candidate;
        }
        String base = candidate;
        String extension = "";
        if (!directory) {
            int dotIndex = candidate.lastIndexOf('.');
            if (dotIndex > 0) {
                base = candidate.substring(0, dotIndex);
                extension = candidate.substring(dotIndex);
            }
        }
        for (int index = 2; ; index++) {
            String alternative = base + " (" + index + ")" + extension;
            if (used.add(directory ? alternative + "/" : alternative)) {
                return alternative;
            }
        }
    }

    private static void putDirectoryEntry(ZipOutputStream zip, String entryName, Path directory)
            throws IOException {
        ZipEntry entry = new ZipEntry(entryName + "/");
        entry.setLastModifiedTime(lastModified(directory));
        zip.putNextEntry(entry);
        zip.closeEntry();
    }

    private static void putFileEntry(ZipOutputStream zip, String entryName, Path file)
            throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setLastModifiedTime(lastModified(file));
        zip.putNextEntry(entry);
        try (InputStream in = new FileInputStream(file.toFile())) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }

    private static FileTime lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return FileTime.fromMillis(System.currentTimeMillis());
        }
    }

    private static List<Path> sortedChildren(Path directory) throws IOException {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return children;
    }

    private static void extractEntry(ZipInputStream zip, ZipEntry entry, Path targetDir)
            throws IOException {
        Path target = resolveEntryPath(targetDir, entry.getName());
        if (target == null) {
            return;
        }
        if (entry.isDirectory()) {
            Files.createDirectories(target);
            return;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            zip.transferTo(out);
        }
        FileTime modified = entry.getLastModifiedTime();
        if (modified != null) {
            Files.setLastModifiedTime(target, modified);
        }
    }

    /**
     * Maps an archive entry onto a path inside {@code targetDir}, rejecting absolute paths, drive
     * letters and any traversal that resolves outside the target directory.
     */
    private static Path resolveEntryPath(Path targetDir, String entryName) throws IOException {
        String normalized = entryName.replace('\\', '/');
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.startsWith("/")) {
            throw new IOException("Archive entry has an absolute path: " + entryName);
        }
        if (normalized.length() > 1 && normalized.charAt(1) == ':') {
            throw new IOException("Archive entry has a drive-letter path: " + entryName);
        }
        Path resolved = targetDir.resolve(normalized).normalize();
        if (!resolved.startsWith(targetDir)) {
            throw new IOException("Archive entry escapes the target folder: " + entryName);
        }
        return resolved;
    }

    private static String archiveBaseName(String zipFileName) {
        String name = zipFileName == null ? "" : zipFileName.trim();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            name = name.substring(0, dotIndex);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return name.isBlank() ? "dropped-files" : name;
    }

    private static Path resolveUniqueDirectory(Path destRoot, String baseName) {
        Path candidate = destRoot.resolve(baseName);
        int index = 1;
        while (Files.exists(candidate)) {
            candidate = destRoot.resolve(baseName + " (" + index + ")");
            index++;
        }
        return candidate;
    }

    /** Entry names must survive as single path segments, so separators become underscores. */
    private static String entryName(String fileName) {
        String name = fileName.replace('\\', '_').replace('/', '_').replace(':', '_').trim();
        return name.isEmpty() ? "_" : name;
    }
}
