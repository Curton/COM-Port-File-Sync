package com.filesync.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fazecast.jSerialComm.SerialPort;
import com.filesync.config.SettingsManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Argument parsing and validation of {@link CliSpec}. */
class CliSpecTest {

    @TempDir Path tempDir;

    /**
     * A test-mode {@link SettingsManager} with every field pinned, so assertions are deterministic
     * regardless of what earlier runs left in the shared test preferences node.
     */
    private SettingsManager settings() {
        SettingsManager settings = new SettingsManager(true);
        settings.setBaudRate(9600);
        settings.setDataBits(7);
        settings.setStopBits(SerialPort.TWO_STOP_BITS);
        settings.setParity(SerialPort.EVEN_PARITY);
        settings.setStrictSync(true);
        settings.setRespectGitignore(true);
        settings.setFastMode(false);
        settings.setDebugMode(false);
        return settings;
    }

    private String folderArg() throws Exception {
        return Files.createDirectories(tempDir.resolve("folder")).toString();
    }

    @Test
    void defaultsComeFromSavedSettings() throws Exception {
        CliSpec spec =
                CliSpec.parse(
                        new String[] {"send", "--port", "COM9", "--folder", folderArg()},
                        settings());
        assertEquals(CliSpec.Mode.SEND, spec.mode);
        assertEquals("COM9", spec.port);
        assertEquals(9600, spec.baudRate);
        assertEquals(7, spec.dataBits);
        assertEquals(SerialPort.TWO_STOP_BITS, spec.stopBits);
        assertEquals(SerialPort.EVEN_PARITY, spec.parity);
        assertTrue(spec.strict);
        assertTrue(spec.respectGitignore);
        assertFalse(spec.fastMode);
        assertFalse(spec.debug);
        assertEquals(60, spec.waitSeconds);
        assertEquals(0, spec.timeoutSeconds);
    }

    @Test
    void optionsOverrideSavedSettings() throws Exception {
        CliSpec spec =
                CliSpec.parse(
                        new String[] {
                            "receive",
                            "--port",
                            "COM3",
                            "--folder",
                            folderArg(),
                            "--baud",
                            "115200",
                            "--databits",
                            "8",
                            "--stopbits",
                            "1",
                            "--parity",
                            "odd",
                            "--no-strict",
                            "--no-gitignore",
                            "--fast",
                            "--debug",
                            "--wait",
                            "5",
                            "--timeout",
                            "9"
                        },
                        settings());
        assertEquals(CliSpec.Mode.RECEIVE, spec.mode);
        assertEquals(115200, spec.baudRate);
        assertEquals(8, spec.dataBits);
        assertEquals(SerialPort.ONE_STOP_BIT, spec.stopBits);
        assertEquals(SerialPort.ODD_PARITY, spec.parity);
        assertFalse(spec.strict);
        assertFalse(spec.respectGitignore);
        assertTrue(spec.fastMode);
        assertTrue(spec.debug);
        assertEquals(5, spec.waitSeconds);
        assertEquals(9, spec.timeoutSeconds);
    }

    @Test
    void modeIsCaseInsensitive() throws Exception {
        assertEquals(
                CliSpec.Mode.PREVIEW,
                CliSpec.parse(
                                new String[] {"Preview", "--port", "COM1", "--folder", folderArg()},
                                settings())
                        .mode);
    }

    @Test
    void helpAndVersionShortCircuitValidation() throws Exception {
        CliSpec help = CliSpec.parse(new String[] {"--help"}, settings());
        assertTrue(help.help);
        assertNull(help.mode);
        CliSpec version = CliSpec.parse(new String[] {"--version"}, settings());
        assertTrue(version.version);
        assertNull(version.mode);
    }

    @Test
    void zeroWaitsAreAllowed() throws Exception {
        CliSpec spec =
                CliSpec.parse(
                        new String[] {
                            "send",
                            "--port",
                            "COM1",
                            "--folder",
                            folderArg(),
                            "--wait",
                            "0",
                            "--timeout",
                            "0"
                        },
                        settings());
        assertEquals(0, spec.waitSeconds);
        assertEquals(0, spec.timeoutSeconds);
    }

    @Test
    void rejectsBadInput() throws Exception {
        String folder = folderArg();
        SettingsManager settings = settings();
        assertThrows(CliSpec.UsageException.class, () -> CliSpec.parse(new String[] {}, settings));
        assertThrows(
                CliSpec.UsageException.class,
                () -> CliSpec.parse(new String[] {"teleport"}, settings));
        assertThrows(
                CliSpec.UsageException.class, () -> CliSpec.parse(new String[] {"send"}, settings));
        assertThrows(
                CliSpec.UsageException.class,
                () -> CliSpec.parse(new String[] {"send", "--port", "COM1"}, settings));
        assertThrows(
                CliSpec.UsageException.class,
                () ->
                        CliSpec.parse(
                                new String[] {
                                    "send",
                                    "--port",
                                    "COM1",
                                    "--folder",
                                    tempDir.resolve("missing").toString()
                                },
                                settings));
        assertThrows(
                CliSpec.UsageException.class,
                () ->
                        CliSpec.parse(
                                new String[] {
                                    "send", "--port", "COM1", "--folder", folder, "--baud", "x"
                                },
                                settings));
        assertThrows(
                CliSpec.UsageException.class,
                () ->
                        CliSpec.parse(
                                new String[] {
                                    "send", "--port", "COM1", "--folder", folder, "--wait", "-1"
                                },
                                settings));
        assertThrows(
                CliSpec.UsageException.class,
                () ->
                        CliSpec.parse(
                                new String[] {
                                    "send",
                                    "--port",
                                    "COM1",
                                    "--folder",
                                    folder,
                                    "--parity",
                                    "purple"
                                },
                                settings));
        assertThrows(
                CliSpec.UsageException.class,
                () ->
                        CliSpec.parse(
                                new String[] {
                                    "send", "--port", "COM1", "--folder", folder, "--databits", "9"
                                },
                                settings));
        assertThrows(
                CliSpec.UsageException.class,
                () ->
                        CliSpec.parse(
                                new String[] {
                                    "send", "--port", "COM1", "--folder", folder, "--wat", "1"
                                },
                                settings));
        assertThrows(
                CliSpec.UsageException.class,
                () ->
                        CliSpec.parse(
                                new String[] {
                                    "send", "--port", "COM1", "--folder", folder, "extra"
                                },
                                settings));
        assertThrows(
                CliSpec.UsageException.class,
                () ->
                        CliSpec.parse(
                                new String[] {
                                    "send", "--port", "COM1", "--folder", folder, "--wait"
                                },
                                settings));
    }

    @Test
    void rejectsConflictingAndDuplicateFlags() throws Exception {
        String folder = folderArg();
        SettingsManager settings = settings();
        assertThrows(
                CliSpec.UsageException.class,
                () ->
                        CliSpec.parse(
                                new String[] {
                                    "send",
                                    "--port",
                                    "COM1",
                                    "--folder",
                                    folder,
                                    "--strict",
                                    "--no-strict"
                                },
                                settings));
        assertThrows(
                CliSpec.UsageException.class,
                () ->
                        CliSpec.parse(
                                new String[] {
                                    "send", "--port", "COM1", "--folder", folder, "--port", "COM2"
                                },
                                settings));
    }
}
