package com.filesync.ui;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Rectangle;
import javax.swing.JMenuItem;
import javax.swing.plaf.basic.BasicMenuItemUI;

/**
 * Menu entries sized and centered to their own text.
 *
 * <p>The Windows L&amp;F menu layout pins the text after a leading icon column (menu items ignore
 * horizontalAlignment), so an entry would render visibly off-center and far wider than its text.
 * Size the entry to the text itself and draw the text centered over it, keeping the rest of the
 * standard menu rendering. The popup itself gets a thin border (see callers) so the menu hugs the
 * entry; the vertical padding stays small because the Windows menu metrics are sized for a
 * DPI-scaled system menu font that this app's smaller font never matches.
 */
final class CenteredMenuItem {
    /**
     * The L&amp;F centers the text on the font's metric box (ascent + descent + leading), while the
     * visible ink stops at the descent line. On the entry's tight box that leaves the label sitting
     * visibly low — measured 17 device px above the ink against 4 below on a 200% display. Climb
     * the label to even the padding out; 3 user px is 6 px there.
     */
    private static final int VERTICAL_OPTICAL_NUDGE = 3;

    private CenteredMenuItem() {}

    static JMenuItem of(String text) {
        JMenuItem item =
                new JMenuItem(text) {
                    @Override
                    public Dimension getPreferredSize() {
                        FontMetrics fm = getFontMetrics(getFont());
                        return new Dimension(fm.stringWidth(getText()) + 12, fm.getHeight() + 4);
                    }
                };
        item.setUI(
                new BasicMenuItemUI() {
                    @Override
                    protected void paintText(
                            Graphics g, JMenuItem menuItem, Rectangle textRect, String text) {
                        Rectangle centered = new Rectangle(textRect);
                        centered.x = (menuItem.getWidth() - textRect.width) / 2;
                        centered.y = textRect.y - VERTICAL_OPTICAL_NUDGE;
                        super.paintText(g, menuItem, centered, text);
                    }
                });
        return item;
    }
}
