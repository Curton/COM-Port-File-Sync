package com.filesync.sync;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code git status --short --porcelain} in a working directory and parses the output into a
 * set of paths relative to that working directory (forward-slash separated).
 *
 * <p>{@code git status} always reports repo-root-relative paths, even when run inside a
 * subdirectory, so the repository root is resolved via {@code git rev-parse --show-toplevel} and
 * the output paths are relativized back to the working directory (paths elsewhere in the repository
 * are dropped). {@code --untracked-files=all} is passed so files inside a wholly untracked
 * directory are listed individually instead of the directory being collapsed into a single {@code
 * ?? dir/} entry.
 *
 * <p>Supports the standard porcelain v1 quoting: paths containing spaces or special characters are
 * wrapped in double quotes, and non-ASCII bytes are octal-escaped as {@code \nnn}. Rename and copy
 * entries ({@code R}/{@code C}) report both the source and destination paths separated by {@code "
 * -> "}.
 *
 * <p>When {@code git} is not runnable from PATH (Git for Windows installed without "add to PATH"),
 * common install locations are probed — including {@code D:\appl\git} on the remote machine —
 * before giving up with an explicit "executable not found" error.
 */
public final class GitStatusUtil {

    private GitStatusUtil() {}

    /** Default deadline (ms) for a {@code git status} run; matches the folder-context timeout. */
    static final long DEFAULT_TIMEOUT_MS = 5000L;

    /**
     * Run {@code git -C <workingDir> rev-parse --show-toplevel} and {@code git -C <workingDir>
     * status --short --porcelain --untracked-files=all}, returning the set of changed paths
     * relative to {@code workingDir} (forward-slash separated). Each git invocation enforces a 5s
     * deadline.
     *
     * @param workingDir the repository working tree root (or any path inside it)
     * @return a possibly-empty set of changed relative paths
     * @throws IOException if git is not installed, {@code workingDir} is not inside a git
     *     repository, a command fails, or it does not finish within 5 seconds
     */
    public static Set<String> getChangedFiles(File workingDir) throws IOException {
        return getChangedFiles(workingDir, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Run {@code git rev-parse --show-toplevel} and {@code git status --short --porcelain
     * --untracked-files=all} in {@code workingDir} with an explicit deadline per invocation.
     *
     * <p>Output is drained on a daemon background thread so a hung or verbose process cannot
     * deadlock the OS pipe buffer, and so the foreground {@link Process#waitFor(long, TimeUnit)}
     * can actually enforce {@code timeoutMs}. If the deadline elapses the process is destroyed
     * (then destroyed forcibly if still alive) and an {@link IOException} is thrown.
     *
     * @param timeoutMs maximum time to wait for each git invocation to terminate; {@code 0} reports
     *     a timeout immediately when the just-started process has not yet exited (used by tests)
     * @throws IOException on null working dir, git failure, interruption, or timeout
     */
    static Set<String> getChangedFiles(File workingDir, long timeoutMs) throws IOException {
        if (workingDir == null) {
            throw new IOException("workingDir is null");
        }
        File dir = workingDir.getAbsoluteFile();
        // git status reports repo-root-relative paths even when run inside a subdirectory, which
        // would never match sync-folder-relative preview paths. Resolve the repo root first so the
        // result can be relativized back to the sync folder.
        String rootOutput = runGit(List.of("rev-parse", "--show-toplevel"), dir, timeoutMs).trim();
        if (rootOutput.isEmpty()) {
            throw new IOException("could not resolve git repository root");
        }
        String statusOutput =
                runGit(
                        List.of("status", "--short", "--porcelain", "--untracked-files=all"),
                        dir,
                        timeoutMs);
        Set<String> repoPaths = parseGitStatusShort(statusOutput);
        return relativizeToWorkingDir(repoPaths, Paths.get(rootOutput), dir.toPath());
    }

    /** Spawn {@code git <args>} in {@code dir} and return the merged stdout/stderr output. */
    private static String runGit(List<String> args, File dir, long timeoutMs) throws IOException {
        Process process = startGitProcess(args, dir);

        // Drain stdout/stderr on a daemon thread so a hung or chatty git can't block the pipe and
        // waitFor(timeoutMs) below enforces the deadline rather than waiting on EOF.
        StringBuilder output = new StringBuilder();
        Thread drain =
                new Thread(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getInputStream(),
                                                    StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    output.append(line).append('\n');
                                }
                            } catch (IOException ignored) {
                                // Best-effort drain; failures surface via exit code or timeout.
                            }
                        },
                        "GitStatusUtil-drain");
        drain.setDaemon(true);
        drain.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            throw new IOException("git status interrupted", e);
        }
        if (!finished) {
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            throw new IOException("timed out after " + (timeoutMs / 1000) + "s");
        }
        int exitCode = process.exitValue();
        // The process has terminated; let the drain thread reach EOF and finish capturing output.
        try {
            drain.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (exitCode != 0) {
            String msg = output.toString().trim();
            if (msg.isEmpty()) {
                msg = "git " + args.get(0) + " failed with exit code " + exitCode;
            }
            throw new IOException(msg);
        }
        return output.toString();
    }

    /**
     * Start {@code git <args>} in {@code dir}. When plain {@code git} cannot be started because the
     * executable does not exist (installed without "add to PATH"), common Git for Windows install
     * locations are probed and the first existing {@code git.exe} is retried before giving up.
     */
    private static Process startGitProcess(List<String> args, File dir) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir.getAbsolutePath());
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.directory(dir);
        try {
            return pb.start();
        } catch (IOException startError) {
            if (!isProgramNotFound(startError)) {
                throw startError;
            }
        }
        String resolved = findGitExecutable();
        if (resolved == null) {
            // Human-readable instead of the OS-localized "CreateProcess error=2, ...".
            throw new IOException("executable not found - install Git or add it to PATH");
        }
        command.set(0, resolved);
        pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.directory(dir);
        return pb.start();
    }

    /**
     * Whether an {@link IOException} from {@link ProcessBuilder#start()} means the executable
     * itself was not found (Windows ERROR_FILE_NOT_FOUND / POSIX ENOENT) as opposed to git running
     * and failing on its own (e.g. "fatal: not a git repository").
     */
    static boolean isProgramNotFound(IOException e) {
        String msg = e.getMessage();
        return msg != null && msg.startsWith("Cannot run program") && msg.contains("error=2");
    }

    /** Probe the default candidate list for a usable git executable. */
    static String findGitExecutable() {
        return findGitExecutable(gitExecutableCandidates());
    }

    /**
     * Return the first candidate that is an existing executable file, or null when none applies.
     * Only consulted when {@code git} is not on PATH, so wrong-platform entries are simply skipped.
     */
    static String findGitExecutable(List<String> candidates) {
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path.toString();
            }
        }
        return null;
    }

    /**
     * Known git.exe locations probed when {@code git} is not on PATH, in priority order: Git for
     * Windows install roots (machine-wide, 32-bit, per-user, and the remote machine's custom root),
     * scoop shims, then POSIX defaults.
     */
    static List<String> gitExecutableCandidates() {
        List<String> out = new ArrayList<>();
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String localAppData = System.getenv("LOCALAPPDATA");
        String userProfile = System.getenv("USERPROFILE");
        addInstallRoot(out, programFiles != null ? programFiles + "\\Git" : null);
        addInstallRoot(out, programFilesX86 != null ? programFilesX86 + "\\Git" : null);
        addInstallRoot(out, localAppData != null ? localAppData + "\\Programs\\Git" : null);
        // Custom install root on the remote machine (git not added to PATH there).
        addInstallRoot(out, "D:\\appl\\git");
        if (userProfile != null) {
            out.add(userProfile + "\\scoop\\shims\\git.exe");
        }
        out.add("/usr/bin/git");
        out.add("/usr/local/bin/git");
        out.add("/opt/homebrew/bin/git");
        return out;
    }

    /** Add a Git for Windows install root's two known git.exe locations. */
    private static void addInstallRoot(List<String> out, String root) {
        if (root == null || root.isBlank()) {
            return;
        }
        out.add(root + "\\cmd\\git.exe");
        out.add(root + "\\bin\\git.exe");
    }

    /**
     * Convert repo-root-relative git paths to paths relative to {@code workingDir}. Paths outside
     * {@code workingDir} (git reports the whole repository, the sync folder may be a subdirectory
     * of it) are dropped; a path equal to {@code workingDir} itself is dropped too.
     *
     * @param repoRootPaths repo-root-relative forward-slash paths
     * @param repoRoot the repository root as reported by {@code git rev-parse --show-toplevel}
     * @param workingDir the directory the caller wants paths relative to
     * @return working-dir-relative paths; empty if {@code workingDir} is not inside {@code
     *     repoRoot} or is the root itself
     */
    static Set<String> relativizeToWorkingDir(
            Set<String> repoRootPaths, Path repoRoot, Path workingDir) {
        Path root = repoRoot.toAbsolutePath().normalize();
        Path work = workingDir.toAbsolutePath().normalize();
        if (!work.startsWith(root)) {
            // Not inside the repository (should not happen: git resolves the repo by walking up
            // from the working dir). No git path can be inside it either.
            return Set.of();
        }
        String prefix = root.relativize(work).toString().replace('\\', '/');
        if (prefix.isEmpty()) {
            return repoRootPaths;
        }
        String dirPrefix = prefix + "/";
        Set<String> result = new LinkedHashSet<>();
        for (String path : repoRootPaths) {
            if (path.startsWith(dirPrefix)) {
                result.add(path.substring(dirPrefix.length()));
            }
        }
        return result;
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
