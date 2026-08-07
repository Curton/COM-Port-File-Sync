package com.filesync.ui;

import com.filesync.config.SettingsManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

/** Shared logging area controller. */
public class LogController {
    private static final int MAX_LOG_LINES = 10_000;
    private static final String DEBUG_PREFIX = "[DEBUG]";

    private final JTextArea logTextArea;
    private SettingsManager settings;
    private boolean debugModeEnabled;

    /**
     * Mirror of the log document kept for thread-safe reads (e.g. by the remote peer's log request,
     * which runs on the serial listener thread and must not touch the JTextArea).
     */
    private final StringBuffer logBuffer = new StringBuffer();

    public LogController(JTextArea logTextArea) {
        this.logTextArea = logTextArea;
    }

    public void setSettingsManager(SettingsManager settings) {
        this.settings = settings;
        if (settings != null) {
            this.debugModeEnabled = settings.isDebugMode();
        }
    }

    public void setDebugMode(boolean enabled) {
        this.debugModeEnabled = enabled;
    }

    public boolean isDebugMode() {
        return debugModeEnabled;
    }

    public void log(String message) {
        if (!debugModeEnabled && message.contains(DEBUG_PREFIX)) {
            return;
        }
        String line = timestampedLine(message);
        SwingUtilities.invokeLater(
                () -> {
                    appendToLogBuffer(line);
                    appendLineToUi(line);
                });
    }

    /**
     * Logs a line synchronously into the thread-safe log mirror and asynchronously into the UI.
     * Used by the serial listener thread when the remote peer requests a TIME-SYNC marker: the
     * marker must be visible in {@link #getLogText()} immediately, without waiting for the EDT.
     */
    public void logMarker(String message) {
        String line = timestampedLine(message);
        appendToLogBuffer(line);
        SwingUtilities.invokeLater(() -> appendLineToUi(line));
    }

    private static String timestampedLine(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return "[" + sdf.format(new Date()) + "] " + message + "\n";
    }

    private void appendLineToUi(String line) {
        logTextArea.append(line);
        trimLogLinesIfNeeded();
        logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
    }

    /** Snapshot of the current log text, safe to call from any thread. */
    public String getLogText() {
        return logBuffer.toString();
    }

    void appendToLogBuffer(String line) {
        logBuffer.append(line);
    }

    void trimLogBuffer(int charCount) {
        logBuffer.delete(0, charCount);
    }

    public void logDebug(String message) {
        if (debugModeEnabled) {
            log(DEBUG_PREFIX + " " + message);
        }
    }

    private void trimLogLinesIfNeeded() {
        try {
            int lineCount = logTextArea.getLineCount();
            if (lineCount <= MAX_LOG_LINES) {
                return;
            }

            int linesToTrim = lineCount - MAX_LOG_LINES;
            int endOffset = logTextArea.getLineEndOffset(linesToTrim - 1);
            logTextArea.getDocument().remove(0, endOffset);
            trimLogBuffer(endOffset);
        } catch (BadLocationException ex) {
            // Ignore trimming failure and keep all log lines.
        }
    }
}
