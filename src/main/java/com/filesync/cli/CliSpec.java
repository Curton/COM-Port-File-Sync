package com.filesync.cli;

import com.filesync.config.SettingsManager;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parsed and validated command-line arguments for one headless run.
 *
 * <p>Serial parameters and mode flags default to the saved GUI settings (both interfaces share one
 * {@link SettingsManager} store); any option given on the command line overrides them for this run
 * only. Nothing is written back to the store.
 */
final class CliSpec {

    enum Mode {
        SEND,
        RECEIVE,
        PREVIEW
    }

    /** Thrown for any malformed or missing argument; the caller prints usage and exits 2. */
    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    /** Null unless {@link #help} or {@link #version} was requested. */
    final Mode mode;

    final String port;
    final String folder;
    final int baudRate;
    final int dataBits;
    final int stopBits;
    final int parity;
    final boolean strict;
    final boolean respectGitignore;
    final boolean fastMode;
    final boolean debug;
    final int waitSeconds;
    final int timeoutSeconds;
    final boolean help;
    final boolean version;

    private CliSpec(
            Mode mode,
            String port,
            String folder,
            int baudRate,
            int dataBits,
            int stopBits,
            int parity,
            boolean strict,
            boolean respectGitignore,
            boolean fastMode,
            boolean debug,
            int waitSeconds,
            int timeoutSeconds,
            boolean help,
            boolean version) {
        this.mode = mode;
        this.port = port;
        this.folder = folder;
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;
        this.strict = strict;
        this.respectGitignore = respectGitignore;
        this.fastMode = fastMode;
        this.debug = debug;
        this.waitSeconds = waitSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.help = help;
        this.version = version;
    }

    static CliSpec parse(String[] args, SettingsManager settings) throws UsageException {
        if (args == null || args.length == 0) {
            throw new UsageException("missing mode (send, receive or preview)");
        }

        Mode mode = null;
        boolean help = false;
        boolean version = false;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "send" -> mode = Mode.SEND;
            case "receive" -> mode = Mode.RECEIVE;
            case "preview" -> mode = Mode.PREVIEW;
            case "--help", "-h", "help" -> help = true;
            case "--version", "-v", "version" -> version = true;
            default ->
                    throw new UsageException(
                            "unknown mode '" + args[0] + "' (expected send, receive or preview)");
        }

        Map<String, String> values = new HashMap<>();
        Map<String, Boolean> flags = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (!token.startsWith("--")) {
                throw new UsageException("unexpected argument '" + token + "'");
            }
            String name = token.substring(2).toLowerCase(Locale.ROOT);
            switch (name) {
                case "port",
                        "folder",
                        "baud",
                        "databits",
                        "stopbits",
                        "parity",
                        "wait",
                        "timeout" -> {
                    if (i + 1 >= args.length) {
                        throw new UsageException("missing value for --" + name);
                    }
                    if (values.put(name, args[++i]) != null) {
                        throw new UsageException("duplicate option --" + name);
                    }
                }
                case "strict",
                        "no-strict",
                        "gitignore",
                        "no-gitignore",
                        "fast",
                        "no-fast",
                        "debug",
                        "no-debug" -> {
                    if (flags.put(name, Boolean.TRUE) != null) {
                        throw new UsageException("duplicate option --" + name);
                    }
                }
                default -> throw new UsageException("unknown option '" + token + "'");
            }
        }

        if (help || version) {
            return new CliSpec(
                    null,
                    null,
                    null,
                    settings.getBaudRate(),
                    settings.getDataBits(),
                    settings.getStopBits(),
                    settings.getParity(),
                    settings.isStrictSync(),
                    settings.isRespectGitignore(),
                    settings.isFastMode(),
                    settings.isDebugMode(),
                    60,
                    0,
                    help,
                    version);
        }

        String port = requireValue(values, "port");
        String folder = requireValue(values, "folder");
        File folderDir = new File(folder);
        if (!folderDir.exists() || !folderDir.isDirectory()) {
            throw new UsageException("sync folder does not exist or is not a directory: " + folder);
        }

        int baudRate = settings.getBaudRate();
        if (values.containsKey("baud")) {
            baudRate = parseInt(values.get("baud"), "--baud");
            if (baudRate <= 0) {
                throw new UsageException("--baud must be positive: " + baudRate);
            }
        }
        int dataBits = settings.getDataBits();
        if (values.containsKey("databits")) {
            dataBits = parseInt(values.get("databits"), "--databits");
            if (dataBits < 5 || dataBits > 8) {
                throw new UsageException("--databits must be 5, 6, 7 or 8: " + dataBits);
            }
        }
        int stopBits = settings.getStopBits();
        if (values.containsKey("stopbits")) {
            stopBits =
                    parseNamedValue(
                            "--stopbits",
                            values.get("stopbits"),
                            SettingsManager.STOP_BITS_NAMES,
                            SettingsManager.STOP_BITS_VALUES);
        }
        int parity = settings.getParity();
        if (values.containsKey("parity")) {
            parity =
                    parseNamedValue(
                            "--parity",
                            values.get("parity"),
                            SettingsManager.PARITY_NAMES,
                            SettingsManager.PARITY_VALUES);
        }

        int waitSeconds = parseNonNegative(values, "wait", 60);
        int timeoutSeconds = parseNonNegative(values, "timeout", 0);

        return new CliSpec(
                mode,
                port,
                folder,
                baudRate,
                dataBits,
                stopBits,
                parity,
                resolveFlag(flags, "strict", "no-strict", settings.isStrictSync()),
                resolveFlag(flags, "gitignore", "no-gitignore", settings.isRespectGitignore()),
                resolveFlag(flags, "fast", "no-fast", settings.isFastMode()),
                resolveFlag(flags, "debug", "no-debug", settings.isDebugMode()),
                waitSeconds,
                timeoutSeconds,
                false,
                false);
    }

    private static String requireValue(Map<String, String> values, String name)
            throws UsageException {
        String value = values.get(name);
        if (value == null || value.trim().isEmpty()) {
            throw new UsageException("missing required option --" + name);
        }
        return value.trim();
    }

    private static int parseInt(String raw, String option) throws UsageException {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new UsageException(option + " expects a number: " + raw);
        }
    }

    private static int parseNonNegative(Map<String, String> values, String name, int defaultValue)
            throws UsageException {
        if (!values.containsKey(name)) {
            return defaultValue;
        }
        int value = parseInt(values.get(name), "--" + name);
        if (value < 0) {
            throw new UsageException("--" + name + " must be 0 or greater: " + value);
        }
        return value;
    }

    /** Resolves a {@code --x / --no-x} pair against the saved default; giving both is an error. */
    private static boolean resolveFlag(
            Map<String, Boolean> flags, String on, String off, boolean defaultValue)
            throws UsageException {
        if (flags.containsKey(on) && flags.containsKey(off)) {
            throw new UsageException("conflicting options --" + on + " and --" + off);
        }
        if (flags.containsKey(on)) {
            return true;
        }
        if (flags.containsKey(off)) {
            return false;
        }
        return defaultValue;
    }

    private static int parseNamedValue(String option, String raw, String[] names, int[] values)
            throws UsageException {
        String wanted = raw.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < names.length; i++) {
            if (names[i].toLowerCase(Locale.ROOT).equals(wanted)) {
                return values[i];
            }
        }
        throw new UsageException(
                option
                        + " expects one of "
                        + String.join(", ", names).toLowerCase(Locale.ROOT)
                        + ": "
                        + raw);
    }
}
