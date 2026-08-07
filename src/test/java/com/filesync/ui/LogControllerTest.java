package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link LogController} log-buffer mirror, which backs the "save combined log"
 * feature: the buffer must stay in lockstep with the log document as lines are appended and as the
 * document is trimmed, so {@link LogController#getLogText()} can be read safely from the serial
 * listener thread.
 *
 * <p>{@code log()} schedules work on the EDT, so each test flushes the EDT queue via {@code
 * invokeAndWait} before asserting.
 */
class LogControllerTest {

    @Test
    void log_appendsVisibleMessagesToDocumentAndMirror() throws Exception {
        JTextArea area = new JTextArea();
        LogController controller = new LogController(area);
        controller.setDebugMode(false);

        controller.log("first message");
        controller.log("[DEBUG] hidden detail");
        controller.log("second message");
        flushEdt();

        String documentText = documentText(area);
        assertEquals(
                controller.getLogText(),
                documentText,
                "The buffer mirror must equal the log document");
        assertTrue(documentText.contains("first message"), "Visible message must be logged");
        assertTrue(documentText.contains("second message"), "Visible message must be logged");
        assertFalse(
                documentText.contains("hidden detail"),
                "Debug lines must be filtered out when debug mode is off");
        assertTrue(
                documentText
                        .lines()
                        .anyMatch(line -> line.matches("\\[\\d{2}:\\d{2}:\\d{2}\\] first message")),
                "Lines must carry the [HH:mm:ss] timestamp prefix");
    }

    @Test
    void logDebug_onlyLogsWhenDebugModeEnabled() throws Exception {
        JTextArea area = new JTextArea();
        LogController controller = new LogController(area);
        controller.setDebugMode(false);

        controller.logDebug("trace detail");
        flushEdt();
        assertEquals("", documentText(area), "Debug logging must be a no-op when disabled");

        controller.setDebugMode(true);
        controller.logDebug("trace detail");
        flushEdt();
        assertTrue(
                documentText(area).contains("[DEBUG] trace detail"),
                "Debug logging must append once enabled");
    }

    @Test
    void trim_keepsMirrorInSyncWithDocument() throws Exception {
        JTextArea area = new JTextArea();
        LogController controller = new LogController(area);
        controller.setDebugMode(true);

        // Prefill one line past MAX_LOG_LINES by appending to both the document and the mirror
        // through the package-private mirror entry point (the production log() path is what runs
        // trimLogLinesIfNeeded below).
        final int prefillLines = 10_001;
        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < prefillLines; i++) {
            bulk.append("[10:00:00] line ").append(i).append('\n');
        }
        final String bulkText = bulk.toString();
        SwingUtilities.invokeAndWait(
                () -> {
                    area.append(bulkText);
                    controller.appendToLogBuffer(bulkText);
                });

        controller.log("final line");
        flushEdt();

        final int[] lineCount = new int[1];
        final String[] documentText = new String[1];
        SwingUtilities.invokeAndWait(
                () -> {
                    lineCount[0] = area.getLineCount();
                    documentText[0] = area.getText();
                });

        assertEquals(10_000, lineCount[0], "Document must be trimmed back to the max line count");
        assertEquals(
                controller.getLogText(),
                documentText[0],
                "The mirror must be trimmed in lockstep with the document");
        assertTrue(
                documentText[0].endsWith("final line\n"),
                "The newly appended line must survive the trim");
    }

    @Test
    void logMarker_writesMirrorSynchronouslyAndUiAsync() throws Exception {
        JTextArea area = new JTextArea();
        LogController controller = new LogController(area);
        controller.setDebugMode(false);

        // Unlike log(), logMarker must be visible in the mirror immediately (the serial listener
        // thread relies on this before its log is fetched), without waiting for the EDT.
        controller.logMarker("TIME-SYNC 2026-08-06 17:41:37.123 1764995400123");

        assertTrue(
                controller.getLogText().contains("TIME-SYNC 2026-08-06"),
                "The marker must be in the mirror before the EDT runs the UI append");

        flushEdt();
        assertTrue(
                documentText(area).contains("TIME-SYNC 2026-08-06"),
                "The marker must appear in the UI once the EDT processes the append");
        assertEquals(
                controller.getLogText(),
                documentText(area),
                "The mirror and the document must stay in sync");
    }

    @Test
    void trim_toleratesDocumentRemoveFailure() throws Exception {
        JTextArea area = new JTextArea();
        area.setDocument(new FailingRemoveDocument());
        LogController controller = new LogController(area);
        controller.setDebugMode(true);

        // Prefill one line past MAX_LOG_LINES, exactly like the happy-path trim test.
        final int prefillLines = 10_001;
        StringBuilder bulk = new StringBuilder();
        for (int i = 0; i < prefillLines; i++) {
            bulk.append("[10:00:00] line ").append(i).append('\n');
        }
        final String bulkText = bulk.toString();
        SwingUtilities.invokeAndWait(
                () -> {
                    area.append(bulkText);
                    controller.appendToLogBuffer(bulkText);
                });

        controller.log("final line");
        flushEdt();

        final int[] lineCount = new int[1];
        final String[] documentText = new String[1];
        SwingUtilities.invokeAndWait(
                () -> {
                    lineCount[0] = area.getLineCount();
                    documentText[0] = area.getText();
                });

        // 10_001 prefilled lines + the logged line; the document's trailing newline adds one extra
        // element, and the failing remove() leaves everything untouched.
        assertEquals(10_003, lineCount[0], "A failed trim must leave the document untouched");
        assertEquals(
                controller.getLogText(),
                documentText[0],
                "The mirror must stay in sync when the document trim fails");
        assertTrue(
                documentText[0].endsWith("final line\n"),
                "The newly appended line must survive the failed trim");
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
    }

    private static String documentText(JTextArea area) throws Exception {
        final String[] text = new String[1];
        SwingUtilities.invokeAndWait(() -> text[0] = area.getText());
        return text[0];
    }

    /**
     * A document whose {@code remove} always fails, forcing the trim path into its {@link
     * BadLocationException} handling.
     */
    private static final class FailingRemoveDocument extends PlainDocument {
        @Override
        public void remove(int offs, int len) throws BadLocationException {
            throw new BadLocationException("simulated trim failure", offs);
        }
    }
}
