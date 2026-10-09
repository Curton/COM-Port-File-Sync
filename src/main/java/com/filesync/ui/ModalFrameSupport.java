package com.filesync.ui;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
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
 * dialog uses, keeps the dispatch thread serving events.
 *
 * <p>Blocking covers the whole application, like APPLICATION_MODAL would: every other visible
 * top-level frame and dialog is disabled for the duration, so nothing reacts while the frame is up.
 * That has to be done by hand - and the frame itself has to be exempted from application modality
 * with {@link Dialog.ModalExclusionType#APPLICATION_EXCLUDE} - because the preview in particular is
 * opened from inside a modal dialog (the sync preview table is a JOptionPane), and a frame born
 * while such a dialog is pumping is auto-blocked by it: input refused, pressed below the dialog,
 * and no amount of toFront undoes that.
 */
final class ModalFrameSupport {

    private ModalFrameSupport() {}

    /**
     * Show {@code frame} and block until it is disposed. Must be called on the event dispatch
     * thread; {@code owner} is only used to hand focus back when no other window was silenced.
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
        // While the frame is up every other window refuses input, so the only way back into the
        // app is the frame itself. Anything that buries it - the taskbar or the shell raising
        // another window, the frame left minimized in the taskbar - would therefore dead-end the
        // whole flow. Every activation elsewhere drags the frame back out in front, which is what
        // the native owner/dialog pair enforces for free.
        WindowAdapter guard =
                new WindowAdapter() {
                    @Override
                    public void windowActivated(WindowEvent e) {
                        if (frame.isShowing()) {
                            raiseToFront(frame);
                        }
                    }
                };
        // Plain Windows (popups, tooltips) are left alone: they belong to whoever shows them, and
        // disabling them would break the frame's own dropdowns. Only windows that are enabled now
        // are silenced, and only those are restored - a window disabled for its own reasons must
        // stay that way.
        List<Window> silenced = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (window == frame
                    || !window.isShowing()
                    || !(window instanceof Frame || window instanceof Dialog)
                    || !window.isEnabled()) {
                continue;
            }
            window.addWindowListener(guard);
            window.setEnabled(false);
            silenced.add(window);
        }
        frame.setModalExclusionType(Dialog.ModalExclusionType.APPLICATION_EXCLUDE);
        frame.setVisible(true);
        raiseToFront(frame);
        // On the dispatch thread the frame cannot be closed between setVisible and enter - no
        // event can run there; the check only keeps a stray off-EDT call from hanging in enter().
        if (!closed.get()) {
            loop.enter();
        }
        Window focusBack = owner;
        for (int i = silenced.size() - 1; i >= 0; i--) {
            Window window = silenced.get(i);
            window.removeWindowListener(guard);
            window.setEnabled(true);
            // The most recently created dialog is the one the user was working in - today the
            // sync preview table - and should get the focus back, not the main frame under it.
            if (focusBack == null || window instanceof Dialog) {
                focusBack = window;
            }
        }
        if (focusBack != null) {
            focusBack.toFront();
        }
    }

    /**
     * Put {@code frame} in front and focused, de-iconifying it first if needed.
     *
     * <p>A plain toFront is not enough at birth: a window shown while its process is not the
     * foreground one - the user switched away during the serial fetch that precedes a preview - is
     * born behind, and Windows' foreground lock silently ignores toFront for background processes.
     * A brief always-on-top assignment carries the right to jump the z-order; dropping it right
     * afterwards restores normal stacking against other applications.
     */
    private static void raiseToFront(JFrame frame) {
        if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        }
        frame.setAlwaysOnTop(true);
        frame.toFront();
        frame.requestFocus();
        frame.setAlwaysOnTop(false);
    }
}
