package com.filesync.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.filesync.config.SettingsManager;
import com.filesync.plugin.FakeLinkPlugin;
import com.filesync.plugin.LinkPluginRegistry;
import com.filesync.serial.RoutingPortManager;
import com.filesync.serial.SerialPortManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void missingOptionsAreUsageErrors() {
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

    /** No plugins installed: the session keeps building its plain serial manager from the spec. */
    @Test
    void withoutPluginsTheSessionGetsNoPortOverride() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("plain-folder"));
        CliSpec spec =
                CliSpec.parse(
                        new String[] {"send", "--port", "COM1", "--folder", folder.toString()},
                        new SettingsManager(true));

        assertNull(CliMain.pluginRoutingPort(spec, LinkPluginRegistry.empty()));
    }

    /** With a plugin installed, {@code --port <endpoint>} routes to the plugin's manager. */
    @Test
    void withPluginsThePortOverrideRoutesEndpointNames() throws Exception {
        Path folder = Files.createDirectories(tempDir.resolve("plugin-folder"));
        CliSpec spec =
                CliSpec.parse(
                        new String[] {
                            "send",
                            "--port",
                            "hidlink:A",
                            "--folder",
                            folder.toString(),
                            "--baud",
                            "9600"
                        },
                        new SettingsManager(true));
        SerialPortManager pluginPort =
                new SerialPortManager() {
                    @Override
                    public boolean open(String portName) {
                        return true;
                    }

                    @Override
                    public boolean isOpen() {
                        return true;
                    }
                };
        FakeLinkPlugin bridge =
                new FakeLinkPlugin("hidlink").endpoint("hidlink:A").manager(pluginPort);

        SerialPortManager port =
                CliMain.pluginRoutingPort(spec, new LinkPluginRegistry(List.of(bridge)));

        assertTrue(port instanceof RoutingPortManager);
        assertEquals(9600, port.getBaudRate(), "the CLI baud still configures the real manager");
        assertTrue(port.open("hidlink:A"));
        assertEquals(List.of("hidlink:A"), bridge.createRequests());
    }
}
