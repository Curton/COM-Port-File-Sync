package com.filesync.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.filesync.sync.ConflictInfo;
import com.filesync.sync.ConflictInfo.ApplyTarget;
import com.filesync.sync.FileChangeDetector;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The unified dialog re-evaluates its "use this for all remaining" control whenever the selected
 * resolution changes, and the apply target it records is derived from that selection.
 */
class ConflictPanelSelectionTest {

    private static ConflictInfo textConflict() {
        FileChangeDetector.FileInfo info =
                new FileChangeDetector.FileInfo("a.txt", 12L, 1L, "md5-local");
        return new ConflictInfo(
                "a.txt",
                info,
                new FileChangeDetector.FileInfo("a.txt", 5L, 9L, "md5-remote"),
                false,
                "local text".getBytes());
    }

    private static ConflictInfo binaryConflict() {
        FileChangeDetector.FileInfo info = new FileChangeDetector.FileInfo("a.bin", 12L, 1L, null);
        return new ConflictInfo(
                "a.bin", info, new FileChangeDetector.FileInfo("a.bin", 9L, 9L, null), true, null);
    }

    @Test
    void binaryPanelReportsTheApplyTargetForEachChoice() {
        BinaryConflictPanel panel = new BinaryConflictPanel(binaryConflict());
        AtomicInteger notifications = new AtomicInteger();
        panel.addSelectionChangeListener(notifications::incrementAndGet);

        assertEquals(
                ApplyTarget.REMOTE_ONLY,
                panel.getApplyTarget(),
                "keeping the local version writes it to the receiver only");
        assertFalse(panel.getResolution() == BinaryConflictPanel.Resolution.KEEP_REMOTE);

        panel.getKeepRemoteRadio().doClick();
        assertEquals(BinaryConflictPanel.Resolution.KEEP_REMOTE, panel.getResolution());
        assertEquals(
                ApplyTarget.BOTH,
                panel.getApplyTarget(),
                "keeping the remote version also overwrites the local file");

        panel.getSkipRadio().doClick();
        assertEquals(BinaryConflictPanel.Resolution.SKIP, panel.getResolution());
        assertEquals(
                ApplyTarget.BOTH, panel.getApplyTarget(), "a skipped binary exists on both sides");

        assertEquals(2, notifications.get(), "every selection change is announced to the dialog");
    }

    @Test
    void textPanelReportsMergeAndAnnouncesTheChange() {
        TextMergePanel panel = new TextMergePanel(textConflict());
        AtomicInteger notifications = new AtomicInteger();
        panel.addSelectionChangeListener(notifications::incrementAndGet);

        panel.getKeepLocalRadio().doClick();
        assertEquals(TextMergePanel.Resolution.KEEP_LOCAL, panel.getResolution());
        assertEquals(ApplyTarget.REMOTE_ONLY, panel.getApplyTarget());

        panel.getMergeRadio().doClick();
        assertEquals(TextMergePanel.Resolution.MERGE, panel.getResolution());
        assertEquals(ApplyTarget.BOTH, panel.getApplyTarget(), "a merge is written to both sides");

        panel.getKeepRemoteRadio().doClick();
        assertEquals(TextMergePanel.Resolution.KEEP_REMOTE, panel.getResolution());
        assertEquals(ApplyTarget.BOTH, panel.getApplyTarget());

        assertEquals(3, notifications.get(), "every selection change is announced to the dialog");
    }

    @Test
    void textPanelMergedContentCarriesTheResolvedText() {
        TextMergePanel panel = new TextMergePanel(textConflict());
        panel.getMergeRadio().doClick();

        // The panel seeds the edit area with a git-merge-style view; whatever the user leaves
        // there is what gets written to both sides.
        assertNotNull(panel.getMergedContent(), "merge seeds the edit area with the local version");
        panel.getMergeTextArea().setText("resolved text");
        assertEquals("resolved text", panel.getMergedContent());

        panel.getKeepLocalRadio().doClick();
        assertNull(
                panel.getMergedContent(),
                "a merge choice that was taken back yields no merged content");
    }
}
