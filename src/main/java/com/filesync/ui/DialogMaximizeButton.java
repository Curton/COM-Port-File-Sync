package com.filesync.ui;

import java.awt.Dialog;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import javax.swing.JButton;

/**
 * A "Maximize/Restore" toggle button for {@link Dialog}s.
 *
 * <p>A dialog has no extended state - {@code setExtendedState(MAXIMIZED_BOTH)} is a JFrame-only
 * feature - so the toggle swaps the window's bounds directly: maximizing fills the usable area of
 * the monitor the dialog is on right now (screen minus taskbar and other reserved edges), and
 * restoring returns it to the bounds it had when it was maximized. The dialogs carrying this button
 * stay resizable, so it coexists with the native title-bar maximize; it just gives the window an
 * explicit maximize control of its own.
 */
final class DialogMaximizeButton {

    private static final String MAXIMIZE_TEXT = "Maximize";
    private static final String RESTORE_TEXT = "Restore";

    private final Dialog dialog;
    private final JButton button = new JButton(MAXIMIZE_TEXT);

    /** Bounds to return to on restore; null while the dialog is not maximized by this button. */
    private Rectangle normalBounds;

    private DialogMaximizeButton(Dialog dialog) {
        this.dialog = dialog;
        button.addActionListener(e -> toggle());
    }

    /**
     * A fresh toggle wired to {@code dialog}; the caller adds the returned button to the dialog's
     * controls.
     */
    static JButton create(Dialog dialog) {
        return new DialogMaximizeButton(dialog).button;
    }

    private void toggle() {
        if (normalBounds == null) {
            normalBounds = dialog.getBounds();
            dialog.setBounds(usableScreenBounds());
            button.setText(RESTORE_TEXT);
        } else {
            dialog.setBounds(normalBounds);
            normalBounds = null;
            button.setText(MAXIMIZE_TEXT);
        }
    }

    /** The monitor the dialog is currently on, minus its taskbar and other reserved edges. */
    private Rectangle usableScreenBounds() {
        Rectangle screen = dialog.getGraphicsConfiguration().getBounds();
        Insets insets =
                Toolkit.getDefaultToolkit().getScreenInsets(dialog.getGraphicsConfiguration());
        return new Rectangle(
                screen.x + insets.left,
                screen.y + insets.top,
                screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom);
    }
}
