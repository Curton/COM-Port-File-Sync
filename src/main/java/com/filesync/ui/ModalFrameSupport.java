package com.filesync.ui;

import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JFrame;

/**
 * Gives a {@link JFrame} the blocking behavior of a modal dialog.
 *
 * <p>These windows are frames on purpose: on Windows only a frame's native title bar carries the
 * minimize/maximize/close buttons, and a diff or merge view is exactly the window a user wants to
 * maximize. Both callers still rely on the dialog contract ("returns when the user closes it"), so
 * {@link #showAndWait} keeps that too: the calling thread - which must be the event dispatch thread
 * - stays inside until the frame is disposed, while a {@link SecondaryLoop}, the same pump a modal
 * dialog uses, keeps the dispatch thread serving events. The owner window is disabled for the
 * duration so nothing else in the app reacts while the frame is up, and is re-enabled and brought
 * back to front afterwards.
 */
final class ModalFrameSupport {

    private ModalFrameSupport() {}

    /**
     * Show {@code frame} and block until it is disposed. Must be called on the event dispatch
     * thread; {@code owner} may be null, in which case nothing is disabled.
     */
    static void showAndWait(JFrame frame, Window owner) {
        SecondaryLoop loop =
                Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        AtomicBoolean closed = new AtomicBoolean(false);
        frame.addWindowListener(
                new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent e) {
                        closed.set(true);
                        loop.exit();
                    }
                });
        if (owner != null) {
            owner.setEnabled(false);
        }
        frame.setVisible(true);
        // On the dispatch thread the frame cannot be closed between setVisible and enter - no
        // event can run there; the check only keeps a stray off-EDT call from hanging in enter().
        if (!closed.get()) {
            loop.enter();
        }
        if (owner != null) {
            owner.setEnabled(true);
            owner.toFront();
        }
    }
}
