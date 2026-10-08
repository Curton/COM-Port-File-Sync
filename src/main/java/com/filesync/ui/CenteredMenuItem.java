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
 * standard menu rendering.
 */
final class CenteredMenuItem {
    private CenteredMenuItem() {}

    static JMenuItem of(String text) {
        JMenuItem item =
                new JMenuItem(text) {
                    @Override
                    public Dimension getPreferredSize() {
                        FontMetrics fm = getFontMetrics(getFont());
                        return new Dimension(fm.stringWidth(getText()) + 12, fm.getHeight() + 8);
                    }
                };
        item.setUI(
                new BasicMenuItemUI() {
                    @Override
                    protected void paintText(
                            Graphics g, JMenuItem menuItem, Rectangle textRect, String text) {
                        Rectangle centered = new Rectangle(textRect);
                        centered.x = (menuItem.getWidth() - textRect.width) / 2;
                        super.paintText(g, menuItem, centered, text);
                    }
                });
        return item;
    }
}
