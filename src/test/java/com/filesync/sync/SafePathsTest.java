package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the shared remote-path containment check. */
class SafePathsTest {

    @TempDir Path tempDir;

    @Test
    void resolvesNestedRelativePathInsideBase() throws IOException {
        File base = tempDir.resolve("sync").toFile();
        base.mkdirs();

        File resolved = SafePaths.resolveWithin(base, "sub/dir/file.txt");

        assertEquals(
                new File(base, "sub/dir/file.txt").getCanonicalFile(),
                resolved,
                "A plain relative path must resolve inside the base");
    }

    @Test
    void normalizesBackslashSeparators() throws IOException {
        File base = tempDir.resolve("sync").toFile();
        base.mkdirs();

        // The manifest walk produces backslash-separated paths on Windows.
        File resolved = SafePaths.resolveWithin(base, "sub\\dir\\file.txt");

        assertEquals(
                new File(base, "sub/dir/file.txt").getCanonicalFile(),
                resolved,
                "Separator style must not change where the path resolves");
    }

    @Test
    void allowsNamesThatContainDoubleDots() throws IOException {
        File base = tempDir.resolve("sync").toFile();
        base.mkdirs();

        assertEquals(
                new File(base, "notes..txt").getCanonicalFile(),
                SafePaths.resolveWithin(base, "notes..txt"),
                "'..' inside a name is not a traversal");
        assertEquals(
                new File(base, "v1..2/data.csv").getCanonicalFile(),
                SafePaths.resolveWithin(base, "v1..2/data.csv"),
                "'..' inside a directory name is not a traversal either");
    }

    @Test
    void rejectsEmptyAndNullPaths() {
        File base = tempDir.resolve("sync").toFile();

        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, ""));
        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, null));
    }

    @Test
    void rejectsAbsolutePaths() {
        File base = tempDir.resolve("sync").toFile();

        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, "/etc/passwd"));
        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, "\\evil.txt"));
    }

    @Test
    void rejectsDriveQualifiedAndStreamForms() {
        File base = tempDir.resolve("sync").toFile();

        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, "C:\\evil.txt"));
        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, "C:evil.txt"));
        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, "\\\\srv\\share\\x"));
        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, "file.txt:stream"));
    }

    @Test
    void rejectsDotAndDotDotSegments() {
        File base = tempDir.resolve("sync").toFile();

        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, "."));
        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, ".."));
        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, "sub/.."));
        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, "sub/../x"));
        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, "sub/./x"));
    }

    @Test
    void rejectsPathThatCanonicalizesOutsideTheBase() throws IOException {
        // A symlink inside the base is the form that needs the canonical check rather than a
        // lexical one: no segment is "." or "..", yet the write lands outside the base.
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        File base = tempDir.resolve("sync").toFile();
        base.mkdirs();
        Path link = base.toPath().resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException e) {
            // Without privilege to create symlinks the case cannot be exercised here; the lexical
            // checks above still cover the rest of the contract.
            return;
        }

        assertThrows(
                IOException.class,
                () -> SafePaths.resolveWithin(base, "link/payload.txt"),
                "A path that resolves through a symlink out of the base must be rejected");
    }
}
