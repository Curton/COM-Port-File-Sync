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
        SwingUtilities.invokeLater(
                () -> {
                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
                    String timestamp = sdf.format(new Date());
                    logTextArea.append("[" + timestamp + "] " + message + "\n");
                    trimLogLinesIfNeeded();
                    logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
                });
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
        } catch (BadLocationException ex) {
            // Ignore trimming failure and keep all log lines.
        }
    }
}
