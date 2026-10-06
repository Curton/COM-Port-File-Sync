package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for the shared remote-path containment check. */
class SafePathsTest {

    @TempDir Path tempDir;

    @ParameterizedTest
    @ValueSource(
            strings = {"sub/dir/file.txt", "sub\\dir\\file.txt", "notes..txt", "v1..2/data.csv"})
    void resolvesRelativePathInsideBase(String path) throws IOException {
        File base = tempDir.resolve("sync").toFile();
        base.mkdirs();

        assertEquals(
                new File(base, path.replace('\\', '/')).getCanonicalFile(),
                SafePaths.resolveWithin(base, path),
                "A relative path must resolve inside the base");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "/etc/passwd",
                "\\evil.txt",
                "C:\\evil.txt",
                "C:evil.txt",
                "\\\\srv\\share\\x",
                "file.txt:stream",
                ".",
                "..",
                "sub/..",
                "sub/../x",
                "sub/./x"
            })
    void rejectsPathsOutsideTheBase(String path) {
        File base = tempDir.resolve("sync").toFile();

        assertThrows(IOException.class, () -> SafePaths.resolveWithin(base, path));
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
