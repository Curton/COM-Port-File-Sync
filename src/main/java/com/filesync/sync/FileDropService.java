package com.filesync.sync;

import com.filesync.protocol.SyncProtocol;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.filechooser.FileSystemView;

/** Handles drag-and-drop single file transfer between peers. */
public class FileDropService {

    private static final String WINDOWS_DOWNLOADS_GUID = "{374DE290-123F-4565-9164-39C4925E467B}";
    private static final String WINDOWS_USER_SHELL_FOLDERS_KEY =
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders";

    private final SyncProtocol protocol;
    private final SyncEventBus eventBus;
    private final BooleanSupplier runningSupplier;
    private final BooleanSupplier connectionAliveSupplier;
    private final BooleanSupplier syncingSupplier;
    private final BooleanSupplier transferBusySupplier;
    private final AtomicBoolean transferInProgress = new AtomicBoolean(false);

    public FileDropService(
            SyncProtocol protocol,
            SyncEventBus eventBus,
            BooleanSupplier runningSupplier,
            BooleanSupplier connectionAliveSupplier,
            BooleanSupplier syncingSupplier,
            BooleanSupplier transferBusySupplier) {
        this.protocol = protocol;
        this.eventBus = eventBus;
        this.runningSupplier = runningSupplier;
        this.connectionAliveSupplier = connectionAliveSupplier;
        this.syncingSupplier = syncingSupplier;
        this.transferBusySupplier = transferBusySupplier;
    }

    public void sendDropFile(File file) {
        if (file == null) {
            eventBus.post(new SyncEvent.ErrorEvent("Dropped file transfer failed: file is null"));
            return;
        }
        if (!file.exists() || !file.isFile()) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Dropped file is not a valid file: " + file.getName()));
            return;
        }
        if (!runningSupplier.getAsBoolean() || !connectionAliveSupplier.getAsBoolean()) {
            eventBus.post(new SyncEvent.ErrorEvent("Dropped file transfer failed: not connected"));
            return;
        }
        if (syncingSupplier.getAsBoolean() || transferBusySupplier.getAsBoolean()) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Dropped file transfer blocked while a transfer is in progress"));
            return;
        }

        if (!transferInProgress.compareAndSet(false, true)) {
            eventBus.post(new SyncEvent.ErrorEvent("Dropped file transfer is already running"));
            return;
        }

        try {
            protocol.sendDropFile(file);
            eventBus.post(new SyncEvent.LogEvent("Dropped file sent: " + file.getName()));
            eventBus.post(new SyncEvent.SyncControlRefreshEvent());
        } catch (IOException e) {
            eventBus.post(
                    new SyncEvent.ErrorEvent("Failed to send dropped file: " + e.getMessage()));
            eventBus.post(new SyncEvent.SyncControlRefreshEvent());
        } finally {
            transferInProgress.set(false);
        }
    }

    public void handleIncomingDropFile(SyncProtocol.Message msg) {
        if (msg == null) {
            return;
        }

        String fileName = msg.getParam(0);
        if (fileName == null || fileName.trim().isEmpty()) {
            eventBus.post(new SyncEvent.ErrorEvent("Dropped file transfer failed: no filename"));
            return;
        }
        fileName = sanitizeFileName(fileName);
        int expectedSize = -1;
        if (msg.getParams().length > 1) {
            String expectedSizeParam = msg.getParam(1);
            if (expectedSizeParam != null && !expectedSizeParam.isBlank()) {
                try {
                    expectedSize = Integer.parseInt(expectedSizeParam);
                } catch (NumberFormatException e) {
                    eventBus.post(
                            new SyncEvent.ErrorEvent(
                                    "Dropped file transfer failed: invalid size header '"
                                            + expectedSizeParam
                                            + "'"));
                    return;
                }
            }
            if (expectedSizeParam != null && !expectedSizeParam.isBlank() && expectedSize < 0) {
                eventBus.post(
                        new SyncEvent.ErrorEvent(
                                "Dropped file transfer failed: negative size header "
                                        + expectedSizeParam));
                return;
            }
        }
        boolean compressed = msg.getParamAsBoolean(2);
        if (fileName == null || fileName.trim().isEmpty()) {
            eventBus.post(
                    new SyncEvent.ErrorEvent("Dropped file transfer failed: missing file name"));
            return;
        }

        File downloadsDir = getDownloadsDirectory();
        if (downloadsDir == null) {
            eventBus.post(
                    new SyncEvent.ErrorEvent(
                            "Dropped file transfer failed: unable to resolve Downloads folder"));
            return;
        }

        if (!transferInProgress.compareAndSet(false, true)) {
            eventBus.post(new SyncEvent.ErrorEvent("Dropped file transfer is already running"));
            return;
        }

        try {
            protocol.sendAck();
            File savedFile =
                    protocol.receiveDropFile(downloadsDir, fileName, expectedSize, compressed);
            eventBus.post(
                    new SyncEvent.DropFileReceivedEvent(
                            savedFile.getName(), savedFile.getAbsolutePath()));
            eventBus.post(
                    new SyncEvent.LogEvent(
                            "Dropped file received: " + savedFile.getAbsolutePath()));
            eventBus.post(new SyncEvent.SyncControlRefreshEvent());
        } catch (IOException e) {
            eventBus.post(
                    new SyncEvent.ErrorEvent("Failed to receive dropped file: " + e.getMessage()));
            eventBus.post(new SyncEvent.SyncControlRefreshEvent());
        } finally {
            transferInProgress.set(false);
        }
    }

    public boolean isTransferInProgress() {
        return transferInProgress.get();
    }

    private File getDownloadsDirectory() {
        File downloadsDir = getWindowsKnownDownloadsDirectory();
        if (downloadsDir != null) {
            return downloadsDir;
        }

        File homeDir = new File(System.getProperty("user.home"));
        File explicitDownloads = resolveExplicitDownloads(homeDir);
        if (explicitDownloads != null) {
            return explicitDownloads;
        }

        File xdgDownloads = getXdgDownloadsDirectory(homeDir);
        if (xdgDownloads != null) {
            return xdgDownloads;
        }

        try {
            File defaultDir = FileSystemView.getFileSystemView().getDefaultDirectory();
            if (defaultDir != null && defaultDir.exists() && defaultDir.isDirectory()) {
                if (looksLikeDownloadsFolder(defaultDir)) {
                    return defaultDir;
                }
            }
        } catch (Exception ignored) {
            // Best effort fallback
        }

        if (homeDir != null && homeDir.exists() && homeDir.isDirectory()) {
            return homeDir;
        }

        return null;
    }

    private File getWindowsKnownDownloadsDirectory() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("win")) {
            return null;
        }

        String command =
                String.format(
                        "reg query \"%s\" /v %s",
                        WINDOWS_USER_SHELL_FOLDERS_KEY, WINDOWS_DOWNLOADS_GUID);
        ProcessBuilder processBuilder = new ProcessBuilder("cmd", "/C", command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(1500, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            String line;
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                while ((line = reader.readLine()) != null) {
                    File candidateFile = parseKnownFolderFromRegLine(line);
                    if (candidateFile != null
                            && candidateFile.exists()
                            && candidateFile.isDirectory()) {
                        return candidateFile;
                    }
                }
            }
        } catch (Exception ignored) {
            // Best effort fallback
        }
        return null;
    }

    private File parseKnownFolderFromRegLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        String trimmed = line.trim();
        Pattern regPattern =
                Pattern.compile(
                        "^" + Pattern.quote(WINDOWS_DOWNLOADS_GUID) + "\\s+REG_\\S+\\s+(.+)$",
                        Pattern.CASE_INSENSITIVE);
        Matcher matcher = regPattern.matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }

        String candidate = expandEnvironmentVariables(matcher.group(1).trim());
        if (candidate.isBlank()) {
            return null;
        }
        return new File(candidate);
    }

    private String expandEnvironmentVariables(String value) {
        StringBuilder builder = new StringBuilder();
        int cursor = 0;
        while (cursor < value.length()) {
            int start = value.indexOf('%', cursor);
            if (start < 0) {
                builder.append(value, cursor, value.length());
                break;
            }

            int end = value.indexOf('%', start + 1);
            if (end < 0) {
                builder.append(value, cursor, value.length());
                break;
            }

            builder.append(value, cursor, start);
            String variableName = value.substring(start + 1, end);
            String variableValue = System.getenv(variableName);
            builder.append(variableValue != null ? variableValue : value.substring(start, end + 1));
            cursor = end + 1;
        }
        return builder.toString();
    }

    private File resolveExplicitDownloads(File homeDir) {
        File explicitDownloads = new File(homeDir, "Downloads");
        if (explicitDownloads.exists() && explicitDownloads.isDirectory()) {
            return explicitDownloads;
        }
        return null;
    }

    private File getXdgDownloadsDirectory(File homeDir) {
        File xdgFile = new File(homeDir, ".config/user-dirs.dirs");
        if (!xdgFile.exists() || !xdgFile.isFile()) {
            return null;
        }

        Pattern pattern = Pattern.compile("^XDG_DOWNLOAD_DIR=(.+)$");
        try {
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    new java.io.FileInputStream(xdgFile),
                                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = pattern.matcher(line.trim());
                    if (!matcher.matches()) {
                        continue;
                    }
                    String raw = matcher.group(1).trim();
                    String decoded = decodeXdgDirectoryValue(raw, homeDir);
                    File xdgDownloads = decoded.isBlank() ? null : new File(decoded);
                    if (xdgDownloads != null
                            && xdgDownloads.exists()
                            && xdgDownloads.isDirectory()) {
                        return xdgDownloads;
                    }
                    break;
                }
            }
        } catch (IOException ignored) {
            // Best effort fallback
        }
        return null;
    }

    private String decodeXdgDirectoryValue(String encoded, File homeDir) {
        if (encoded == null || encoded.isBlank()) {
            return "";
        }
        String value = encoded.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.startsWith("$HOME")) {
            value = new File(homeDir, value.substring("$HOME".length())).getAbsolutePath();
        }
        return value;
    }

    private boolean looksLikeDownloadsFolder(File directory) {
        if (directory == null) {
            return false;
        }
        return directory.getName().equalsIgnoreCase("Downloads")
                || directory.getName().equalsIgnoreCase("Download");
    }

    /** Strip path separators and traversal sequences from a remote-supplied filename. */
    private static String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "file";
        }
        String sanitized = name.replace('\\', '/');
        // strip any leading path components
        int lastSlash = sanitized.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < sanitized.length() - 1) {
            sanitized = sanitized.substring(lastSlash + 1);
        }
        // strip dangerous characters
        sanitized = sanitized.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return sanitized.isBlank() ? "file" : sanitized;
    }
}
