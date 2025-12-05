package com.filesync.sync;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Manual performance probe for manifest generation against a concrete folder on the
 * developer workstation. This test is intentionally data-dependent and will be skipped
 * automatically when the target directory is missing.
 */
public class FileChangeDetectorPerformanceTest {

    private static final Path TARGET_FOLDER = Path.of("C:\\Users\\liuke\\Desktop\\cal");

    @Test
    void generateManifestForCalFolder() throws IOException {
        File targetDir = TARGET_FOLDER.toFile();
        assumeTrue(targetDir.exists() && targetDir.isDirectory(),
                () -> "Skipping performance test because folder does not exist: " + TARGET_FOLDER);

        Instant start = Instant.now();
        FileChangeDetector.FileManifest manifest =
                FileChangeDetector.generateManifest(targetDir, false, true);
        Duration elapsed = Duration.between(start, Instant.now());

        assertNotNull(manifest, "Manifest should not be null");

        long millis = elapsed.toMillis();
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        System.out.printf("Manifest for %s: %d files, %d empty dirs, duration=%d ms (~%d s)%n",
                TARGET_FOLDER,
                manifest.getFileCount(),
                manifest.getEmptyDirectoryCount(),
                millis,
                seconds);
    }
}

