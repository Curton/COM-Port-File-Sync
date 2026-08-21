package com.filesync.ui;

import javax.swing.event.DocumentEvent.EventType;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;
import javax.swing.undo.UndoableEdit;

/**
 * Word-style undo manager for the shared text area.
 *
 * <p>Consecutive single-character typing is coalesced into a single undoable run, broken by
 * whitespace, caret jumps (non-contiguous offsets) or multi-character inserts (paste). Consecutive
 * single-character removals (backspace / forward delete) are coalesced likewise. Programmatic
 * changes such as {@code setText} can be wrapped with {@link #runAsSingleEdit(Runnable)} so the
 * whole operation undoes in one step.
 *
 * <p>An in-progress run is not yet registered with the {@link UndoManager}; {@link #tryUndo()} and
 * {@link #tryRedo()} seal it first so a full run is always undone or redone as a unit.
 */
public class SharedTextUndoManager extends UndoManager {

    private static final int UNDO_LIMIT = 200;

    private enum RunKind {
        TYPING,
        DELETION
    }

    private CompoundEdit currentRun;
    private RunKind currentRunKind;
    private int lastRunOffset;

    private CompoundEdit explicitCompound;
    private boolean explicitCompoundHasEdits;

    public SharedTextUndoManager() {
        setLimit(UNDO_LIMIT);
    }

    /**
     * Runs {@code action} so every document change it makes counts as a single undo step. Used for
     * programmatic replacements (clipboard overwrite, remote sync overwrite) whose underlying
     * remove+insert would otherwise take two Ctrl+Z presses to revert.
     */
    public synchronized void runAsSingleEdit(Runnable action) {
        if (explicitCompound != null) {
            throw new IllegalStateException("runAsSingleEdit cannot be nested");
        }
        sealRun();
        explicitCompound = new CompoundEdit();
        explicitCompoundHasEdits = false;
        try {
            action.run();
        } finally {
            CompoundEdit finished = explicitCompound;
            boolean hasEdits = explicitCompoundHasEdits;
            explicitCompound = null;
            explicitCompoundHasEdits = false;
            finished.end();
            if (hasEdits) {
                super.addEdit(finished);
            }
        }
    }

    /**
     * Seals any in-progress run, then undoes the most recent step.
     *
     * @return true if an edit was undone
     */
    public synchronized boolean tryUndo() {
        sealRun();
        if (!canUndo()) {
            return false;
        }
        undo();
        return true;
    }

    /**
     * Seals any in-progress run, then redoes the most recently undone step.
     *
     * @return true if an edit was redone
     */
    public synchronized boolean tryRedo() {
        sealRun();
        if (!canRedo()) {
            return false;
        }
        redo();
        return true;
    }

    @Override
    public synchronized boolean addEdit(UndoableEdit anEdit) {
        if (explicitCompound != null) {
            explicitCompound.addEdit(anEdit);
            explicitCompoundHasEdits = true;
            return true;
        }
        if (anEdit instanceof AbstractDocument.DefaultDocumentEvent event) {
            int offset = event.getOffset();
            if (isSingleCharTyping(event)) {
                if (currentRunKind == RunKind.TYPING
                        && currentRun != null
                        && offset == lastRunOffset + 1) {
                    currentRun.addEdit(anEdit);
                } else {
                    startRun(RunKind.TYPING, anEdit);
                }
                lastRunOffset = offset;
                return true;
            }
            if (isSingleCharRemoval(event)) {
                boolean contiguousBackspace = offset == lastRunOffset - 1;
                boolean contiguousForwardDelete = offset == lastRunOffset;
                if (currentRunKind == RunKind.DELETION
                        && currentRun != null
                        && (contiguousBackspace || contiguousForwardDelete)) {
                    currentRun.addEdit(anEdit);
                } else {
                    startRun(RunKind.DELETION, anEdit);
                }
                lastRunOffset = offset;
                return true;
            }
        }
        sealRun();
        return super.addEdit(anEdit);
    }

    private void startRun(RunKind kind, UndoableEdit firstEdit) {
        sealRun();
        currentRun = new CompoundEdit();
        currentRun.addEdit(firstEdit);
        currentRunKind = kind;
    }

    private void sealRun() {
        if (currentRun != null) {
            currentRun.end();
            super.addEdit(currentRun);
            currentRun = null;
            currentRunKind = null;
        }
    }

    private boolean isSingleCharTyping(AbstractDocument.DefaultDocumentEvent event) {
        if (event.getType() != EventType.INSERT || event.getLength() != 1) {
            return false;
        }
        char inserted = insertedChar(event);
        return inserted != 0 && !Character.isWhitespace(inserted);
    }

    private boolean isSingleCharRemoval(AbstractDocument.DefaultDocumentEvent event) {
        return event.getType() == EventType.REMOVE && event.getLength() == 1;
    }

    private char insertedChar(AbstractDocument.DefaultDocumentEvent event) {
        try {
            return event.getDocument().getText(event.getOffset(), 1).charAt(0);
        } catch (BadLocationException e) {
            return 0;
        }
    }
}
