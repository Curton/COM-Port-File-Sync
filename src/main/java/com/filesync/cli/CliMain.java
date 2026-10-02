package com.filesync.cli;

import com.filesync.AppVersion;
import com.filesync.config.SettingsManager;
import com.filesync.serial.SerialPortManager;
import java.io.PrintStream;

/**
 * Command-line entry point for one-shot headless sync sessions.
 *
 * <p>Usage: {@code java -jar com-file-sync.jar <mode> [options]} with the mode {@code send}, {@code
 * receive} or {@code preview}. See {@link #printUsage(PrintStream)} for the full surface. Running
 * the jar without arguments still starts the Swing GUI.
 */
public final class CliMain {

    /** Sync session finished cleanly. */
    public static final int EXIT_SUCCESS = 0;

    /** Runtime failure: port open failed, connect timeout, sync error, link or session timeout. */
    public static final int EXIT_FAILURE = 1;

    /** Malformed or missing arguments. */
    public static final int EXIT_USAGE = 2;

    /** The sync session was cancelled (peer cancel or local interrupt). */
    public static final int EXIT_CANCELLED = 3;

    /** Session finished, but locked receiver-side files are still pending and were not written. */
    public static final int EXIT_PARTIAL = 4;

    private CliMain() {}

    public static int run(String[] args) {
        return run(args, new SettingsManager(), null);
    }

    /**
     * Runs one CLI session. {@code settings} supplies defaults for options not given on the command
     * line; {@code portOverride} lets tests substitute an in-memory port for real hardware (null in
     * production).
     */
    static int run(String[] args, SettingsManager settings, SerialPortManager portOverride) {
        CliSpec spec;
        try {
            spec = CliSpec.parse(args, settings);
        } catch (CliSpec.UsageException e) {
            System.err.println("error: " + e.getMessage());
            printUsage(System.err);
            return EXIT_USAGE;
        }
        if (spec.help) {
            printUsage(System.out);
            return EXIT_SUCCESS;
        }
        if (spec.version) {
            System.out.println("com-file-sync " + AppVersion.get());
            return EXIT_SUCCESS;
        }
        return new CliSession(spec, settings, portOverride).run();
    }

    static void printUsage(PrintStream out) {
        out.println("Usage: java -jar com-file-sync.jar <mode> [options]");
        out.println();
        out.println("Runs one headless sync session over the serial link and exits with a code.");
        out.println("Without arguments the Swing GUI starts as before. Every mode needs the peer");
        out.println("up as the opposite side: send and preview talk to a connected receiver (the");
        out.println("GUI in receiver direction works too), receive waits for a sender to start.");
        out.println();
        out.println("Modes:");
        out.println("  send      Push a full sync as sender, disconnect when the session ends.");
        out.println(
                "  receive   Serve exactly one incoming sync session, then disconnect. Exits 1");
        out.println(
                "            if the peer hangs up before a session starts (e.g. after preview).");
        out.println(
                "  preview   Sender-side dry run: fetch the peer's manifest and print what send");
        out.println("            would transfer/delete/rename. Nothing is sent, so only this side");
        out.println("            runs a command; the peer just answers the manifest request.");
        out.println();
        out.println("Options:");
        out.println("  --port <name>         Serial port to open, e.g. COM3 (required)");
        out.println("  --folder <dir>        Sync folder, must exist (required)");
        out.println("  --baud <rate>         Baud rate (default: saved GUI setting, 115200)");
        out.println("  --databits <n>        Data bits: 5, 6, 7 or 8 (default: saved setting, 8)");
        out.println("  --stopbits <n>        Stop bits: 1, 1.5 or 2 (default: saved setting, 1)");
        out.println(
                "  --parity <name>       none, odd, even, mark or space (default: saved, none)");
        out.println("  --strict / --no-strict");
        out.println("                        Mirror mode: delete receiver-side extras");
        out.println("  --gitignore / --no-gitignore");
        out.println("                        Respect .gitignore rules");
        out.println("  --fast / --no-fast    Delta and append fast mode (default: on)");
        out.println("  --debug / --no-debug  Verbose progress output");
        out.println("  --wait <seconds>      Connect wait; 0 = wait forever (default: 60)");
        out.println(
                "  --timeout <seconds>   Session guard after connecting; 0 = none (default: 0)");
        out.println("  --help, -h            Show this help and exit");
        out.println("  --version, -v         Show the version and exit");
        out.println();
        out.println("Serial parameters and mode flags default to the saved GUI settings so both");
        out.println(
                "interfaces share one configuration; options given here override them for this");
        out.println("run only and are not written back. Unresolved conflicts transfer with the");
        out.println("sender's version (\"local wins\"), the same default the GUI applies.");
        out.println();
        out.println("Exit codes:");
        out.println("  0  success");
        out.println("  1  runtime failure (port open, connect timeout, sync error, link lost)");
        out.println("  2  usage error");
        out.println("  3  sync cancelled by the peer");
        out.println("  4  completed, but locked receiver-side files are still pending");
    }
}
