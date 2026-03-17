package com.filesync.ui;

import java.util.Date;
import java.text.SimpleDateFormat;

import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;

/**
 * Shared logging area controller.
 */
public class LogController {
    private static final int MAX_LOG_LINES = 10_000;

    private final JTextArea logTextArea;

    public LogController(JTextArea logTextArea) {
        this.logTextArea = logTextArea;
    }

    public void log(String message) {
        SwingUtilities.invokeLater(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
            String timestamp = sdf.format(new Date());
            logTextArea.append("[" + timestamp + "] " + message + "\n");
            trimLogLinesIfNeeded();
            logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
        });
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
