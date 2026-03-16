package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitignoreParserTest {

    @TempDir
    Path tempDir;

    @Test
    void ignoresSimpleGlobsAndDirectoryOnlyPatterns() throws IOException {
        writeGitignore(tempDir, "*.tmp", "build/", "docs/");

        GitignoreParser parser = new GitignoreParser(tempDir.toFile());
        parser.loadGitignoreFiles();

        assertTrue(parser.isIgnored("main.tmp", false), "Wildcard file pattern should ignore matching files");
        assertTrue(parser.isIgnored("build", true), "Directory-only pattern should match directory path");
        assertFalse(parser.isIgnored("build/notes.txt", false),
                "Directory-only pattern should not match files without directory flag");
        assertTrue(parser.isIgnored("sub/build", true), "Directory patterns should match directory names in nested paths");
        assertFalse(parser.isIgnored("readme.txt", false), "Unmatched file should not be ignored");
    }

    @Test
    void supportsNegationToUnignoreMatchedPaths() throws IOException {
        writeGitignore(tempDir, "*.log", "!important.log", "tmp/*.tmp", "!tmp/keep.tmp");

        GitignoreParser parser = new GitignoreParser(tempDir.toFile());
        parser.loadGitignoreFiles();

        assertTrue(parser.isIgnored("application.log", false), "Negation should be needed to unignore");
        assertFalse(parser.isIgnored("important.log", false), "Negated pattern should unignore explicit match");
        assertTrue(parser.isIgnored("tmp/throwaway.tmp", false), "Subdirectory wildcard should still ignore");
        assertFalse(parser.isIgnored("tmp/keep.tmp", false), "Trailing negation should restore this file");
    }

    @Test
    void evaluatesNestedGitignorePatternsFromLeafLast() throws IOException {
        writeGitignore(tempDir, "*.tmp");
        Files.createDirectories(tempDir.resolve("src"));
        writeGitignore(tempDir.resolve("src"), "!.tmp", "generated/");

        GitignoreParser parser = new GitignoreParser(tempDir.toFile());
        parser.loadGitignoreFiles();

        assertTrue(parser.isIgnored("root.tmp", false), "Root pattern should ignore temporary files");
        assertFalse(parser.isIgnored("src/.tmp", false), "Nested .gitignore should override parent rule");
        assertTrue(parser.isIgnored("src/generated", true), "Nested directory-only rule should ignore generated directory");
    }

    @Test
    void supportsAnchoredPatternsForRootOnly() throws IOException {
        writeGitignore(tempDir, "/release.config", "release/**");

        GitignoreParser parser = new GitignoreParser(tempDir.toFile());
        parser.loadGitignoreFiles();

        assertTrue(parser.isIgnored("release.config", false), "Leading slash should anchor pattern to root");
        assertFalse(parser.isIgnored("nested/release.config", false), "Anchored root pattern should not match nested path");
        assertTrue(parser.isIgnored("release/candidate/file.txt", false), "Non-anchored pattern should match nested");
    }

    @Test
    void supportsDoubleStarPatternsAcrossDirectories() throws IOException {
        writeGitignore(tempDir, "**/dist/", "**/tmp");

        GitignoreParser parser = new GitignoreParser(tempDir.toFile());
        parser.loadGitignoreFiles();

        assertTrue(parser.isIgnored("dist", true), "Double-star pattern should match top-level dist directory");
        assertTrue(parser.isIgnored("a/b/dist", true), "Double-star pattern should match dist in nested path");
        assertTrue(parser.isIgnored("tmp/a/file.txt", false), "Double-star wildcard should match nested tmp paths");
        assertFalse(parser.isIgnored("temp/file.txt", false), "Non-matching path segment should not be ignored");
    }

    @Test
    void normalizesWindowsStyleSeparatorsBeforeMatching() throws IOException {
        writeGitignore(tempDir, "*.log");

        GitignoreParser parser = new GitignoreParser(tempDir.toFile());
        parser.loadGitignoreFiles();

        assertTrue(parser.isIgnored("windows\\style\\file.log", false),
                "Windows separators should be normalized before matching");
    }

    private static void writeGitignore(Path directory, String... lines) throws IOException {
        Path gitignore = directory.resolve(".gitignore");
        String content = Arrays.stream(lines).collect(Collectors.joining(System.lineSeparator()));
        Files.writeString(gitignore, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
