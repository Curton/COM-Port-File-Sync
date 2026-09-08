package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link GitStatusUtil}. */
class GitStatusUtilTest {

    // ---- parseGitStatusShort: deterministic parser tests ----

    @Test
    void parseEmptyOrNullReturnsEmpty() {
        assertTrue(GitStatusUtil.parseGitStatusShort(null).isEmpty());
        assertTrue(GitStatusUtil.parseGitStatusShort("").isEmpty());
        assertTrue(GitStatusUtil.parseGitStatusShort("   \n\n").isEmpty());
    }

    @Test
    void parseModifiedFile() {
        Set<String> paths = GitStatusUtil.parseGitStatusShort(" M src/Main.java\n");
        assertEquals(Set.of("src/Main.java"), paths);
    }

    @Test
    void parseStagedAdd() {
        Set<String> paths = GitStatusUtil.parseGitStatusShort("A  new.txt\n");
        assertEquals(Set.of("new.txt"), paths);
    }

    @Test
    void parseUntracked() {
        Set<String> paths = GitStatusUtil.parseGitStatusShort("?? untracked.txt\n");
        assertEquals(Set.of("untracked.txt"), paths);
    }

    @Test
    void parseDeleted() {
        Set<String> paths = GitStatusUtil.parseGitStatusShort(" D gone.txt\n");
        assertEquals(Set.of("gone.txt"), paths);
    }

    @Test
    void parseRenameReturnsBothPaths() {
        Set<String> paths = GitStatusUtil.parseGitStatusShort("R  old.txt -> new.txt\n");
        assertEquals(Set.of("old.txt", "new.txt"), paths);
    }

    @Test
    void parseCopyReturnsBothPaths() {
        Set<String> paths = GitStatusUtil.parseGitStatusShort("C  orig.txt -> copy.txt\n");
        assertEquals(Set.of("orig.txt", "copy.txt"), paths);
    }

    @Test
    void parseQuotedPathWithSpace() {
        Set<String> paths = GitStatusUtil.parseGitStatusShort("?? \"my file.txt\"\n");
        assertEquals(Set.of("my file.txt"), paths);
    }

    @Test
    void parseOctalEscapedNonAscii() {
        // "caf\303\251.txt" decodes (UTF-8) to "café.txt"
        Set<String> paths = GitStatusUtil.parseGitStatusShort("?? \"caf\\303\\251.txt\"\n");
        assertEquals(Set.of("café.txt"), paths);
    }

    @Test
    void parseMixedMultipleLinesPreservesOrder() {
        String output =
                " M src/Main.java\n"
                        + "A  docs/readme.md\n"
                        + "?? tmp/untracked.log\n"
                        + " D obsolete/old.bin\n"
                        + "R  renamed/from.txt -> renamed/to.txt\n";
        Set<String> paths = GitStatusUtil.parseGitStatusShort(output);
        assertEquals(
                Set.of(
                        "src/Main.java",
                        "docs/readme.md",
                        "tmp/untracked.log",
                        "obsolete/old.bin",
                        "renamed/from.txt",
                        "renamed/to.txt"),
                paths);
        // LinkedHashSet preserves insertion order; verify the first reported entry.
        Iterator<String> it = paths.iterator();
        assertEquals("src/Main.java", it.next());
        assertEquals("docs/readme.md", it.next());
    }

    @Test
    void parseCrlfLineEndings() {
        Set<String> paths = GitStatusUtil.parseGitStatusShort(" M a.txt\r\n?? b.txt\r\n");
        assertEquals(Set.of("a.txt", "b.txt"), paths);
    }

    @Test
    void parseIgnoresShortLines() {
        Set<String> paths = GitStatusUtil.parseGitStatusShort("XY\n M ok.txt\n");
        assertEquals(Set.of("ok.txt"), paths);
    }

    // ---- dequoteGitPath: direct package-private tests ----

    @Test
    void dequoteUnquotedPathNormalizesBackslashes() {
        assertEquals("dir/file.txt", GitStatusUtil.dequoteGitPath("dir\\file.txt"));
    }

    @Test
    void dequoteQuotedPathWithEscapedQuoteAndBackslash() {
        // "a\"b\\c.txt" -> a"b\c.txt -> normalized a"b/c.txt
        assertEquals("a\"b/c.txt", GitStatusUtil.dequoteGitPath("\"a\\\"b\\\\c.txt\""));
    }

    @Test
    void dequoteQuotedPathWithTabEscape() {
        assertEquals("a\tb.txt", GitStatusUtil.dequoteGitPath("\"a\\tb.txt\""));
    }

    // ---- relativizeToWorkingDir: repo-root -> working-dir path conversion ----

    @Test
    void relativizeReturnsInputWhenWorkingDirIsRepoRoot(@TempDir Path tempDir) {
        Set<String> in = Set.of("a.txt", "dir/b.txt");
        assertEquals(in, GitStatusUtil.relativizeToWorkingDir(in, tempDir, tempDir));
    }

    @Test
    void relativizeStripsSubdirectoryPrefixAndDropsOutsidePaths(@TempDir Path tempDir) {
        Path sub = tempDir.resolve("sub");
        Set<String> in = Set.of("sub/inside.txt", "root.txt", "sub", "other/sub2.txt");
        // Only files under "sub" belong to the sync folder; a path equal to the folder itself
        // ("sub") is dropped too.
        assertEquals(Set.of("inside.txt"), GitStatusUtil.relativizeToWorkingDir(in, tempDir, sub));
    }

    @Test
    void relativizeReturnsEmptyWhenWorkingDirOutsideRepoRoot(@TempDir Path tempDir) {
        Path other = tempDir.resolve("other");
        assertEquals(
                Set.of(), GitStatusUtil.relativizeToWorkingDir(Set.of("a.txt"), tempDir, other));
    }

    // ---- git executable fallback: probed when plain "git" is not on PATH ----

    @Test
    void isProgramNotFoundDetectsExecutableMissing() {
        assertTrue(
                GitStatusUtil.isProgramNotFound(
                        new IOException(
                                "Cannot run program \"git\": CreateProcess error=2, "
                                        + "系统找不到指定的文件。")));
        assertTrue(
                GitStatusUtil.isProgramNotFound(
                        new IOException(
                                "Cannot run program \"git\": error=2, No such file or directory")));
    }

    @Test
    void isProgramNotFoundRejectsOtherFailures() {
        // git ran but failed on its own, or another OS error - must not be rewritten as
        // "executable not found".
        assertFalse(
                GitStatusUtil.isProgramNotFound(
                        new IOException(
                                "fatal: not a git repository (or any of the parent directories): .git")));
        assertFalse(
                GitStatusUtil.isProgramNotFound(
                        new IOException(
                                "Cannot run program \"git\": CreateProcess error=5, 拒绝访问。")));
        assertFalse(GitStatusUtil.isProgramNotFound(new IOException((String) null)));
    }

    @Test
    void candidatesIncludeStandardRootsAndRemoteMachineRoot() {
        List<String> candidates = GitStatusUtil.gitExecutableCandidates();
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null) {
            assertTrue(
                    candidates.contains(programFiles + "\\Git\\cmd\\git.exe"),
                    "expected machine-wide Git for Windows location in: " + candidates);
        }
        // The remote machine's custom install root (git not on PATH there).
        assertTrue(candidates.contains("D:\\appl\\git\\cmd\\git.exe"));
        assertTrue(candidates.contains("D:\\appl\\git\\bin\\git.exe"));
        assertTrue(candidates.contains("/usr/bin/git"));
    }

    @Test
    void findGitExecutableReturnsFirstExistingCandidate(@TempDir Path tempDir) throws Exception {
        Path cmd = tempDir.resolve("Git").resolve("cmd");
        Files.createDirectories(cmd);
        Path fakeGit = cmd.resolve("git.exe");
        Files.writeString(fakeGit, "stub");
        makeExecutable(fakeGit);

        String found =
                GitStatusUtil.findGitExecutable(
                        List.of(
                                tempDir.resolve("nope\\cmd\\git.exe").toString(),
                                fakeGit.toString()));
        assertEquals(fakeGit.toString(), found);
    }

    @Test
    void findGitExecutableSkipsDirectoriesAndReturnsNullWhenNothingExists(@TempDir Path tempDir)
            throws Exception {
        // A directory named git.exe must not be mistaken for the executable.
        Files.createDirectories(tempDir.resolve("Git\\cmd\\git.exe"));
        assertNull(
                GitStatusUtil.findGitExecutable(
                        List.of(
                                tempDir.resolve("Git\\cmd\\git.exe").toString(),
                                tempDir.resolve("absent\\cmd\\git.exe").toString())));
    }

    /** Make a file executable on POSIX filesystems; Windows needs no chmod for isExecutable. */
    private static void makeExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows filesystem: Files.isExecutable accepts any accessible regular file.
        }
    }

    // ---- getChangedFiles: integration test guarded by git availability ----

    @Test
    void getChangedFilesReportsModifiedAndUntracked(@TempDir Path tempDir) throws Exception {
        assumeTrue(isGitAvailable(), "git is not installed; skipping integration test");

        File repo = tempDir.toFile();
        runGit(repo, "init");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "user.name", "Test User");
        // Commit a baseline file so a later modification shows up as " M".
        Path committed = tempDir.resolve("committed.txt");
        Files.writeString(committed, "v1\n", StandardCharsets.UTF_8);
        runGit(repo, "add", "committed.txt");
        runGit(repo, "commit", "-m", "baseline");

        // Modify the tracked file and add an untracked file.
        Files.writeString(committed, "v2\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("untracked.txt"), "new\n", StandardCharsets.UTF_8);

        Set<String> changed = GitStatusUtil.getChangedFiles(repo);
        assertNotNull(changed);
        assertFalse(changed.isEmpty());
        assertTrue(changed.contains("committed.txt"), "expected modified file in: " + changed);
        assertTrue(changed.contains("untracked.txt"), "expected untracked file in: " + changed);
    }

    @Test
    void getChangedFilesRelativizesToSubDirectoryAndExpandsUntrackedDirs(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(isGitAvailable(), "git is not installed; skipping integration test");

        Path repoDir = tempDir.toRealPath();
        File repo = repoDir.toFile();
        runGit(repo, "init");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "user.name", "Test User");
        Path rootFile = repoDir.resolve("root.txt");
        Path sub = repoDir.resolve("sub");
        Files.createDirectories(sub);
        Path subFile = sub.resolve("sub.txt");
        Files.writeString(rootFile, "v1\n", StandardCharsets.UTF_8);
        Files.writeString(subFile, "v1\n", StandardCharsets.UTF_8);
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", "baseline");

        // Modify both tracked files and add a file inside a NEW (wholly untracked) directory.
        Files.writeString(rootFile, "v2\n", StandardCharsets.UTF_8);
        Files.writeString(subFile, "v2\n", StandardCharsets.UTF_8);
        Path newDir = sub.resolve("newdir");
        Files.createDirectories(newDir);
        Files.writeString(newDir.resolve("newfile.txt"), "new\n", StandardCharsets.UTF_8);

        // Sync folder is a subdirectory of the repo: paths must be relative to it, the modified
        // root-level file is outside and dropped, and the untracked directory is expanded
        // file-by-file (git collapses it to "?? newdir/" without --untracked-files=all).
        Set<String> changed = GitStatusUtil.getChangedFiles(sub.toFile());
        assertEquals(Set.of("sub.txt", "newdir/newfile.txt"), changed);

        // From the repo root the same changes report repo-root-relative paths.
        Set<String> fromRoot = GitStatusUtil.getChangedFiles(repo);
        assertEquals(Set.of("root.txt", "sub/sub.txt", "sub/newdir/newfile.txt"), fromRoot);
    }

    @Test
    void getChangedFilesThrowsForNonRepository(@TempDir Path tempDir) {
        assumeTrue(isGitAvailable(), "git is not installed; skipping integration test");
        File notARepo = tempDir.toFile();
        try {
            GitStatusUtil.getChangedFiles(notARepo);
            org.junit.jupiter.api.Assertions.fail(
                    "expected IOException for directory outside a git repository");
        } catch (IOException expected) {
            // Expected: git status fails outside a repository.
        }
    }

    @Test
    void getChangedFilesTimesOutWhenDeadlineIsZero(@TempDir Path tempDir) throws Exception {
        assumeTrue(isGitAvailable(), "git is not installed; skipping integration test");
        // A real (empty) repository keeps `git status` a valid runnable command. With timeoutMs=0
        // the package-private overload calls Process.waitFor(0, MILLISECONDS) immediately after
        // start(), when the just-launched git process has not yet terminated, so waitFor returns
        // false deterministically (no race with a fast exit) and the timeout path is taken.
        File repo = tempDir.toFile();
        runGit(repo, "init");
        IOException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IOException.class, () -> GitStatusUtil.getChangedFiles(repo, 0L));
        org.junit.jupiter.api.Assertions.assertTrue(
                ex.getMessage().contains("timed out"),
                "expected a timeout message, got: " + ex.getMessage());
    }

    /** Best-effort check that a {@code git} executable is on PATH. */
    private static boolean isGitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void runGit(File workingDir, String... args)
            throws IOException, InterruptedException {
        String[] full = new String[args.length + 1];
        full[0] = "git";
        System.arraycopy(args, 0, full, 1, args.length);
        ProcessBuilder pb =
                new ProcessBuilder(full).directory(workingDir).redirectErrorStream(true);
        Process p = pb.start();
        // Drain output.
        byte[] out = p.getInputStream().readAllBytes();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException(
                    "git "
                            + String.join(" ", args)
                            + " failed (exit "
                            + exit
                            + "): "
                            + new String(out, StandardCharsets.UTF_8));
        }
    }
}
