package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharedTextUndoManagerTest {

    private PlainDocument document;
    private SharedTextUndoManager undoManager;

    @BeforeEach
    void setUp() {
        document = new PlainDocument();
        undoManager = new SharedTextUndoManager();
        document.addUndoableEditListener(undoManager);
    }

    private String text() throws BadLocationException {
        return document.getText(0, document.getLength());
    }

    /** Types one character per document event, like real keyboard input. */
    private void type(String s) throws BadLocationException {
        for (int i = 0; i < s.length(); i++) {
            document.insertString(document.getLength(), String.valueOf(s.charAt(i)), null);
        }
    }

    private void backspace() throws BadLocationException {
        document.remove(document.getLength() - 1, 1);
    }

    @Test
    void typedWordCoalescesIntoSingleUndoStep() throws BadLocationException {
        type("hello");

        assertTrue(undoManager.tryUndo());
        assertEquals("", text());

        assertTrue(undoManager.tryRedo());
        assertEquals("hello", text());
    }

    @Test
    void whitespaceBreaksTypingRuns() throws BadLocationException {
        type("hello world");

        assertTrue(undoManager.tryUndo());
        assertEquals("hello ", text());
        assertTrue(undoManager.tryUndo());
        assertEquals("hello", text());
        assertTrue(undoManager.tryUndo());
        assertEquals("", text());
    }

    @Test
    void caretJumpBreaksTypingRun() throws BadLocationException {
        type("ab");
        document.insertString(0, "X", null);

        assertTrue(undoManager.tryUndo());
        assertEquals("ab", text());
        assertTrue(undoManager.tryUndo());
        assertEquals("", text());
    }

    @Test
    void consecutiveBackspacesCoalesceIntoSingleUndoStep() throws BadLocationException {
        type("hello");
        backspace();
        backspace();
        backspace();
        assertEquals("he", text());

        assertTrue(undoManager.tryUndo());
        assertEquals("hello", text());
        assertTrue(undoManager.tryUndo());
        assertEquals("", text());
    }

    @Test
    void multiCharPasteIsItsOwnUndoStep() throws BadLocationException {
        type("ab");
        document.insertString(document.getLength(), "XYZ", null);

        assertTrue(undoManager.tryUndo());
        assertEquals("ab", text());
        assertTrue(undoManager.tryUndo());
        assertEquals("", text());
    }

    @Test
    void runAsSingleEditUndoesReplacementInOneStep() throws BadLocationException {
        type("old");
        undoManager.runAsSingleEdit(
                () -> {
                    try {
                        document.remove(0, document.getLength());
                        document.insertString(0, "new text", null);
                    } catch (BadLocationException e) {
                        throw new RuntimeException(e);
                    }
                });
        assertEquals("new text", text());

        assertTrue(undoManager.tryUndo());
        assertEquals("old", text());
        // Two undo steps existed: the "old" typing run and the replacement. Both are consumed.
        assertTrue(undoManager.tryUndo());
        assertEquals("", text());
        assertFalse(undoManager.tryUndo());

        assertTrue(undoManager.tryRedo());
        assertEquals("old", text());
        assertTrue(undoManager.tryRedo());
        assertEquals("new text", text());
    }

    @Test
    void runAsSingleEditWithNoChangesAddsNothing() throws BadLocationException {
        undoManager.runAsSingleEdit(() -> {});
        assertFalse(undoManager.tryUndo());
        assertFalse(undoManager.tryRedo());
    }

    @Test
    void newEditDiscardsRedoHistory() throws BadLocationException {
        type("ab");
        assertTrue(undoManager.tryUndo());
        type("c");

        assertFalse(undoManager.tryRedo());
        assertEquals("c", text());
    }

    @Test
    void undoLimitEvictsOldestSteps() {
        for (int i = 0; i < 210; i++) {
            undoManager.runAsSingleEdit(
                    () -> {
                        try {
                            document.insertString(document.getLength(), "x", null);
                        } catch (BadLocationException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        int undone = 0;
        while (undoManager.tryUndo()) {
            undone++;
        }
        assertEquals(200, undone);
        assertEquals(10, document.getLength());
    }

    @Test
    void nestedRunAsSingleEditIsRejected() {
        undoManager.runAsSingleEdit(
                () ->
                        org.junit.jupiter.api.Assertions.assertThrows(
                                IllegalStateException.class,
                                () -> undoManager.runAsSingleEdit(() -> {})));
    }
}
