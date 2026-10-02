package com.filesync.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.filesync.config.SettingsManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exit codes of the {@link CliMain} entry for usage, help, version and connect-timeout paths. */
class CliMainTest {

    @TempDir Path tempDir;

    @Test
    void emptyArgsIsUsageError() {
        assertEquals(CliMain.EXIT_USAGE, CliMain.run(new String[0]));
    }

    @Test
    void unknownModeIsUsageError() {
        assertEquals(CliMain.EXIT_USAGE, CliMain.run(new String[] {"teleport"}));
    }

    @Test
    void missingOptionsAreUsageErrors() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("folder"));
        assertEquals(CliMain.EXIT_USAGE, CliMain.run(new String[] {"send"}));
        assertEquals(CliMain.EXIT_USAGE, CliMain.run(new String[] {"send", "--port", "COM1"}));
        assertEquals(
                CliMain.EXIT_USAGE,
                CliMain.run(
                        new String[] {
                            "send",
                            "--port",
                            "COM1",
                            "--folder",
                            tempDir.resolve("missing").toString()
                        }));
        assertEquals(
                CliMain.EXIT_USAGE,
                CliMain.run(new String[] {"send", "--folder", folder.toString()}));
    }

    @Test
    void helpExitsZero() {
        assertEquals(CliMain.EXIT_SUCCESS, CliMain.run(new String[] {"--help"}));
        assertEquals(CliMain.EXIT_SUCCESS, CliMain.run(new String[] {"-h"}));
    }

    @Test
    void versionExitsZero() {
        assertEquals(CliMain.EXIT_SUCCESS, CliMain.run(new String[] {"--version"}));
    }

    /**
     * A port wired to conduits with no peer: no heartbeat ever arrives, so {@code --wait 1} runs
     * out and the session fails with exit code 1.
     */
    @Test
    void connectTimeoutExitsOne() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("folder"));
        PipeSerialPortManager port = new PipeSerialPortManager(new Conduit(), new Conduit());
        int code =
                CliMain.run(
                        new String[] {
                            "receive",
                            "--port",
                            "PIPE",
                            "--folder",
                            folder.toString(),
                            "--wait",
                            "1"
                        },
                        new SettingsManager(true),
                        port);
        assertEquals(CliMain.EXIT_FAILURE, code);
    }
}
