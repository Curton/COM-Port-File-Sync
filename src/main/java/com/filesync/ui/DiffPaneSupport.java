package com.filesync.ui;

import com.filesync.sync.TextDiffUtil.DiffLine;
import com.filesync.sync.TextDiffUtil.DiffLineType;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Shared building blocks for the side-by-side diff views: the conflict merge panel and the file
 * change preview render the same two-column "hunk with coloured lines" layout, so the colours, the
 * pane construction, the titled panel skeleton and the per-line renderer live here.
 */
final class DiffPaneSupport {

    // Colors for diff highlighting
    static final Color ADDED_COLOR = new Color(200, 255, 200); // Light green
    static final Color REMOVED_COLOR = new Color(255, 200, 200); // Light red
    static final Color CONTEXT_COLOR = new Color(245, 245, 245); // Light gray
    static final Color HEADER_BG_COLOR = new Color(230, 230, 230);

    private DiffPaneSupport() {}

    /** A read-only pane in the monospaced font every diff view uses. */
    static JTextPane monospacedPane() {
        JTextPane pane = new JTextPane();
        pane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pane.setEditable(false);
        return pane;
    }

    /**
     * A titled panel: a bold header on an opaque background above the content. {@code addBorder}
     * pads the header label — a touch the file preview uses and the conflict panel does not.
     */
    static JPanel labeledPane(String title, JComponent content, boolean addBorder) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBackground(HEADER_BG_COLOR);
        label.setOpaque(true);
        if (addBorder) {
            label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        }
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Append the included diff lines to {@code pane}, one per document line, each carrying the
     * background colour chosen for its type and prefixed with {@code prefixFor}. The pane is not
     * cleared here: callers empty it first so that a failed render can fall back to plain text,
     * which is why {@link BadLocationException} propagates instead of being swallowed.
     */
    static void renderDiffLines(
            JTextPane pane,
            List<DiffLine> lines,
            Predicate<DiffLineType> include,
            Function<DiffLineType, Color> colorFor,
            Function<DiffLineType, String> prefixFor)
            throws BadLocationException {
        StyledDocument document = pane.getStyledDocument();
        for (DiffLine line : lines) {
            DiffLineType type = line.getType();
            if (!include.test(type)) {
                continue;
            }
            SimpleAttributeSet attr = new SimpleAttributeSet();
            StyleConstants.setBackground(attr, colorFor.apply(type));
            document.insertString(
                    document.getLength(), prefixFor.apply(type) + line.getContent() + "\n", attr);
        }
    }
}
