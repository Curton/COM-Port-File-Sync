package com.filesync.sync;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Runs {@code git status --short --porcelain} in a working directory and parses the output into a
 * set of repository-relative paths (forward-slash separated).
 *
 * <p>Supports the standard porcelain v1 quoting: paths containing spaces or special characters are
 * wrapped in double quotes, and non-ASCII bytes are octal-escaped as {@code \nnn}. Rename and copy
 * entries ({@code R}/{@code C}) report both the source and destination paths separated by {@code "
 * -> "}.
 */
public final class GitStatusUtil {

    private GitStatusUtil() {}

    /**
     * Run {@code git -C <workingDir> status --short --porcelain} and return the set of changed
     * paths (relative to {@code workingDir}, forward-slash separated).
     *
     * @param workingDir the repository working tree root (or any path inside it)
     * @return a possibly-empty set of changed relative paths
     * @throws IOException if git is not installed, {@code workingDir} is not inside a git
     *     repository, or the command fails
     */
    public static Set<String> getChangedFiles(File workingDir) throws IOException {
        if (workingDir == null) {
            throw new IOException("workingDir is null");
        }
        File dir = workingDir.getAbsoluteFile();
        ProcessBuilder pb =
                new ProcessBuilder(
                                "git",
                                "-C",
                                dir.getAbsolutePath(),
                                "status",
                                "--short",
                                "--porcelain")
                        .redirectErrorStream(true);
        pb.directory(dir);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git status interrupted", e);
        }
        if (exitCode != 0) {
            String msg = output.toString().trim();
            if (msg.isEmpty()) {
                msg = "git status failed with exit code " + exitCode;
            }
            throw new IOException(msg);
        }
        return parseGitStatusShort(output.toString());
    }

    /**
     * Parse {@code git status --short --porcelain} output into a set of repository-relative paths.
     *
     * <p>Each line begins with a two-character XY status followed by a space and one or two paths.
     * Rename/copy lines contain {@code -> } separating the source and destination; both are
     * returned. Quoted paths are de-quoted and unescaped.
     *
     * @param output the raw porcelain output; may be null or blank
     * @return a possibly-empty ordered set of forward-slash relative paths
     */
    public static Set<String> parseGitStatusShort(String output) {
        Set<String> paths = new LinkedHashSet<>();
        if (output == null || output.isEmpty()) {
            return paths;
        }
        for (String rawLine : output.split("\n", -1)) {
            String line =
                    rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isEmpty()) {
                continue;
            }
            // Each porcelain v1 line is "XY<space><path>" (at least 3 chars).
            if (line.length() < 3) {
                continue;
            }
            // The path field starts after "XY " (2 status flags + 1 space).
            String pathField = line.substring(3);
            int arrow = pathField.indexOf(" -> ");
            if (arrow >= 0) {
                String src = pathField.substring(0, arrow);
                String dst = pathField.substring(arrow + 4);
                addDequoted(paths, src);
                addDequoted(paths, dst);
            } else {
                addDequoted(paths, pathField);
            }
        }
        return paths;
    }

    private static void addDequoted(Set<String> paths, String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return;
        }
        String dequoted = dequoteGitPath(rawPath);
        if (!dequoted.isEmpty()) {
            paths.add(dequoted);
        }
    }

    /**
     * Strip the surrounding double quotes (if present) from a porcelain-quoted path and unescape
     * the C-style sequences git emits: {@code \\}, {@code \"}, {@code \t}, {@code \n}, {@code \r},
     * and {@code \nnn} octal byte escapes (which are UTF-8 encoded). Backslashes are normalized to
     * forward slashes for cross-platform comparison.
     */
    static String dequoteGitPath(String rawPath) {
        String s = rawPath;
        boolean quoted = s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2;
        if (!quoted) {
            return s.replace('\\', '/');
        }
        s = s.substring(1, s.length() - 1);
        StringBuilder out = new StringBuilder(s.length());
        // Collect raw bytes so octal escapes can be re-decoded as UTF-8.
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                bytes.write(c & 0xFF);
                continue;
            }
            i++;
            if (i >= s.length()) {
                // Trailing backslash; emit literally.
                bytes.write('\\');
                break;
            }
            char esc = s.charAt(i);
            switch (esc) {
                case '\\':
                    bytes.write('\\');
                    break;
                case '"':
                    bytes.write('"');
                    break;
                case 't':
                    bytes.write('\t');
                    break;
                case 'n':
                    bytes.write('\n');
                    break;
                case 'r':
                    bytes.write('\r');
                    break;
                default:
                    if (esc >= '0' && esc <= '7') {
                        // Up to three octal digits.
                        int val = esc - '0';
                        int count = 1;
                        while (count < 3 && i + 1 < s.length()) {
                            char d = s.charAt(i + 1);
                            if (d < '0' || d > '7') {
                                break;
                            }
                            val = (val << 3) | (d - '0');
                            i++;
                            count++;
                        }
                        bytes.write(val & 0xFF);
                    } else {
                        // Unknown escape; keep the escaped char verbatim.
                        bytes.write(esc & 0xFF);
                    }
            }
        }
        out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        return out.toString().replace('\\', '/');
    }
}
