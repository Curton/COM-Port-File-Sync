package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
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
