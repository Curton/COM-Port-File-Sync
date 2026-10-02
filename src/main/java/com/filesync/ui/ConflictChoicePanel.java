package com.filesync.ui;

import com.filesync.sync.ConflictInfo;

/**
 * A conflict card whose current choice can be read in the unified {@link ConflictInfo.Resolution}
 * vocabulary. Implemented by {@link BinaryConflictPanel} and {@link TextMergePanel}; the unified
 * dialog drives these methods instead of dispatching on the concrete panel types.
 */
public interface ConflictChoicePanel {

    /**
     * The panel's current choice, mapped from the panel's native resolution enum (a binary panel's
     * SKIP, a text panel's MERGE) into {@link ConflictInfo.Resolution}.
     */
    ConflictInfo.Resolution getConflictResolution();

    /**
     * Apply target fixed by the resolution: keep local writes to the receiver only; every other
     * choice (keep remote, skip, merge) exists on both sides.
     */
    default ConflictInfo.ApplyTarget getApplyTarget() {
        return getConflictResolution() == ConflictInfo.Resolution.KEEP_LOCAL
                ? ConflictInfo.ApplyTarget.REMOTE_ONLY
                : ConflictInfo.ApplyTarget.BOTH;
    }

    /**
     * Register a callback for a change of the selected resolution. The unified dialog uses it to
     * re-evaluate the controls that depend on the current choice (e.g. "use this for all
     * remaining").
     */
    void addSelectionChangeListener(Runnable listener);
}
