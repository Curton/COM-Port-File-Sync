package com.filesync.ui;

import com.filesync.sync.TextDiffUtil.DiffHunk;
import com.filesync.sync.TextDiffUtil.DiffLine;
import com.filesync.sync.TextDiffUtil.DiffLineType;
import com.filesync.sync.TextDiffUtil.DiffResult;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

/**
 * Read-only preview of what a single file's change actually looks like, opened from the sync
 * preview table.
 *
 * <p>Text files get a side-by-side diff of the previous (peer) version and the new (to-be-sent)
 * version, with per-change navigation and highlighted added/removed lines. Non-text files get an
 * informational placeholder describing both sides (sizes, availability) since a byte-level diff
 * would be meaningless. Sides that could not be retrieved are replaced by an explanatory message
 * rather than an empty pane, so a missing base version is never mistaken for an empty file.
 */
public class FileDiffPreviewPanel extends JPanel {

    private static final Color ADDED_COLOR = new Color(200, 255, 200);
    private static final Color REMOVED_COLOR = new Color(255, 200, 200);
    private static final Color CONTEXT_COLOR = new Color(245, 245, 245);
    private static final Color HEADER_BG_COLOR = new Color(230, 230, 230);

    /** Shown when a hunk set is empty because the files are identical or unavailable. */
    private static final List<DiffHunk> NO_HUNKS = Collections.emptyList();

    private final FileDiffPreviewModel model;
    private final List<DiffHunk> hunks;

    private final JTextPane basePane;
    private final JTextPane sourcePane;

    // Non-final because they are created only for the text diff layout; a binary preview has no
    // change navigation at all.
    private JLabel changeCountLabel;
    private JButton prevChangeButton;
    private JButton nextChangeButton;
    private int currentHunkIndex;

    public FileDiffPreviewPanel(FileDiffPreviewModel model) {
        this.model = model;
        DiffResult diff = model.isText() ? model.computeDiff() : null;
        this.hunks = diff != null ? diff.getHunks() : NO_HUNKS;

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(940, 560));

        add(createHeaderPanel(), BorderLayout.NORTH);

        basePane = createContentPane();
        sourcePane = createContentPane();

        if (!model.isText()) {
            add(createBinaryPlaceholder(), BorderLayout.CENTER);
        } else {
            add(createTextDiffCenter(), BorderLayout.CENTER);
        }
        add(createFooterPanel(), BorderLayout.SOUTH);
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(4, 4));
        JLabel title = new JLabel("<html><b>" + escapeHtml(model.getPath()) + "</b></html>");
        title.setToolTipText(model.getPath());
        header.add(title, BorderLayout.NORTH);

        String subtitle =
                model.getOperationType() != null
                        ? model.getOperationType().name() + " - " + model.describeSummary()
                        : model.describeSummary();
        JLabel summary =
                new JLabel(
                        "<html><span style='color:#555555'>"
                                + escapeHtml(subtitle)
                                + "</span></html>");
        header.add(summary, BorderLayout.SOUTH);
        return header;
    }

    private JTextPane createContentPane() {
        JTextPane pane = new JTextPane();
        pane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pane.setEditable(false);
        return pane;
    }

    /** Center area for text files: change navigation above, the two diff panes below. */
    private JPanel createTextDiffCenter() {
        // The new version is shown as soon as the panel is built, before any hunk is selected, so
        // a file with no visible diff still displays its content instead of an empty pane.
        renderNewVersionFirst();
        renderBaseVersionFirst();

        JPanel navPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        prevChangeButton = new JButton("\u25C0 Previous Change");
        prevChangeButton.addActionListener(e -> navigateHunk(-1));
        nextChangeButton = new JButton("Next Change \u25B6");
        nextChangeButton.addActionListener(e -> navigateHunk(1));
        changeCountLabel = new JLabel();
        navPanel.add(prevChangeButton);
        navPanel.add(nextChangeButton);
        navPanel.add(changeCountLabel);

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(navPanel, BorderLayout.NORTH);
        center.add(createDiffPanes(), BorderLayout.CENTER);

        currentHunkIndex = 0;
        if (hunks.isEmpty()) {
            renderFallback();
        } else {
            renderHunk(hunks.get(0));
        }
        updateNavigation();
        return center;
    }

    /**
     * Prime the new-version pane with the full file before a hunk is rendered: when there is no
     * hunk (identical content, or a side that cannot be compared) the pane would otherwise be
     * blank. The hunk renderer clears both panes first, so this is only a fallback.
     */
    private void renderNewVersionFirst() {
        if (model.describeUnavailable(FileDiffPreviewModel.Side.SOURCE) != null) {
            return;
        }
        String text = model.getSourceText();
        sourcePane.setText(text != null ? text : "");
    }

    /** Prime the previous-version pane, or show why it cannot be shown. */
    private void renderBaseVersionFirst() {
        String reason = model.describeUnavailable(FileDiffPreviewModel.Side.BASE);
        if (reason != null) {
            basePane.setText(reason);
            return;
        }
        String text = model.getBaseText();
        basePane.setText(text != null ? text : "");
    }

    private JPanel createDiffPanes() {
        JPanel diffPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 0.5;
        gbc.weighty = 1.0;

        gbc.gridx = 0;
        gbc.gridy = 0;
        diffPanel.add(labeledPane("PREVIOUS VERSION (peer)", basePane), gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        diffPanel.add(labeledPane("NEW VERSION (to send)", sourcePane), gbc);
        return diffPanel;
    }

    /** Placeholder shown for non-text files: sizes plus an explicit explanation. */
    private JPanel createBinaryPlaceholder() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEtchedBorder());

        JLabel icon = new JLabel("\u2261 \u2261 \u2261", SwingConstants.CENTER);
        icon.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
        icon.setForeground(new Color(150, 150, 150));

        JLabel message =
                new JLabel(
                        "<html><div style='text-align:center'><b>No text preview available</b><br/><br/>"
                                + "This file is not a text document, so its contents cannot be shown as a"
                                + " line-by-line comparison.<br/>Only its size and availability are shown"
                                + " below.</div></html>",
                        SwingConstants.CENTER);

        JPanel messageBox = new JPanel(new BorderLayout(4, 8));
        messageBox.add(icon, BorderLayout.NORTH);
        messageBox.add(message, BorderLayout.CENTER);

        JPanel infoPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 8, 2, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        infoPanel.add(new JLabel("File:"), gbc);
        gbc.gridx = 1;
        infoPanel.add(new JLabel(model.getFileName()), gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        infoPanel.add(new JLabel("Sizes:"), gbc);
        gbc.gridx = 1;
        infoPanel.add(new JLabel(model.describeSizes()), gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        infoPanel.add(new JLabel("Operation:"), gbc);
        gbc.gridx = 1;
        infoPanel.add(
                new JLabel(
                        model.getOperationType() != null ? model.getOperationType().name() : "-"),
                gbc);

        panel.add(messageBox, BorderLayout.CENTER);
        panel.add(infoPanel, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Footer describing sides that are missing. A brand-new file legitimately has no previous
     * version; a failed fetch is called out as a fetch failure instead, because those two cases
     * must not look alike.
     */
    private JPanel createFooterPanel() {
        JPanel footer = new JPanel(new BorderLayout(4, 2));
        StringBuilder notes = new StringBuilder();
        appendNote(notes, FileDiffPreviewModel.Side.SOURCE);
        appendNote(notes, FileDiffPreviewModel.Side.BASE);

        JLabel noteLabel = new JLabel("<html>" + escapeHtml(notes.toString()) + "</html>");
        noteLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        footer.add(noteLabel, BorderLayout.CENTER);
        return footer;
    }

    private void appendNote(StringBuilder notes, FileDiffPreviewModel.Side side) {
        String reason = model.describeUnavailable(side);
        if (reason == null) {
            return;
        }
        if (notes.length() > 0) {
            notes.append("<br/>");
        }
        notes.append(
                        side == FileDiffPreviewModel.Side.BASE
                                ? "Previous version: "
                                : "New version: ")
                .append(reason);
    }

    private JPanel labeledPane(String title, JTextPane pane) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBackground(HEADER_BG_COLOR);
        label.setOpaque(true);
        label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        panel.add(label, BorderLayout.NORTH);
        panel.add(new JScrollPane(pane), BorderLayout.CENTER);
        return panel;
    }

    private void navigateHunk(int direction) {
        if (hunks.isEmpty()) {
            return;
        }
        currentHunkIndex = Math.max(0, Math.min(currentHunkIndex + direction, hunks.size() - 1));
        renderHunk(hunks.get(currentHunkIndex));
        updateNavigation();
    }

    private void updateNavigation() {
        if (changeCountLabel == null) {
            return;
        }
        int total = hunks.size();
        if (total == 0) {
            changeCountLabel.setText(
                    "No changes to step through (identical or content unavailable)");
            return;
        }
        prevChangeButton.setEnabled(currentHunkIndex > 0);
        nextChangeButton.setEnabled(currentHunkIndex < total - 1);
        changeCountLabel.setText(
                "Change "
                        + (currentHunkIndex + 1)
                        + " of "
                        + total
                        + " region"
                        + (total == 1 ? "" : "s"));
    }

    /** Render one hunk: removed lines on the left, added lines on the right, context in both. */
    private void renderHunk(DiffHunk hunk) {
        basePane.setText("");
        sourcePane.setText("");

        SimpleAttributeSet contextAttr = attributeWithBackground(CONTEXT_COLOR);
        SimpleAttributeSet removedAttr = attributeWithBackground(REMOVED_COLOR);
        SimpleAttributeSet addedAttr = attributeWithBackground(ADDED_COLOR);

        // A pane is only filled when the model actually holds that side's bytes. A new file has a
        // renderable (indeed expected-to-be-empty) previous version but no base content at all, and
        // showing a blank pane there would hide the fact that there is simply nothing to compare.
        boolean baseRenderable = model.isBaseContentAvailable();
        boolean sourceRenderable = model.isSourceAvailable();

        try {
            if (baseRenderable) {
                for (DiffLine line : hunk.getLines()) {
                    if (line.getType() == DiffLineType.ADDED) {
                        continue;
                    }
                    boolean removed = line.getType() == DiffLineType.REMOVED;
                    basePane.getDocument()
                            .insertString(
                                    basePane.getDocument().getLength(),
                                    (removed ? "- " : "  ") + line.getContent() + "\n",
                                    removed ? removedAttr : contextAttr);
                }
            }
            if (sourceRenderable) {
                for (DiffLine line : hunk.getLines()) {
                    if (line.getType() == DiffLineType.REMOVED) {
                        continue;
                    }
                    boolean added = line.getType() == DiffLineType.ADDED;
                    sourcePane
                            .getDocument()
                            .insertString(
                                    sourcePane.getDocument().getLength(),
                                    (added ? "+ " : "  ") + line.getContent() + "\n",
                                    added ? addedAttr : contextAttr);
                }
            }
        } catch (BadLocationException e) {
            // Fall back to plain text so a rendering hiccup still shows something useful. Only the
            // pane whose doc was mid-insert is replaced; the missing-side explanations below still
            // take precedence for sides that have no content at all.
            if (baseRenderable) {
                basePane.setText(plainText(hunk, FileDiffPreviewModel.Side.BASE));
            }
            if (sourceRenderable) {
                sourcePane.setText(plainText(hunk, FileDiffPreviewModel.Side.SOURCE));
            }
        }

        // Sides without content carry their explanation instead of staying empty.
        if (!baseRenderable) {
            basePane.setText(unavailableText(FileDiffPreviewModel.Side.BASE));
        }
        if (!sourceRenderable) {
            sourcePane.setText(unavailableText(FileDiffPreviewModel.Side.SOURCE));
        }
        basePane.setCaretPosition(0);
        sourcePane.setCaretPosition(0);
    }

    /** The explanation for a side with no content, or an empty string when there is none. */
    private String unavailableText(FileDiffPreviewModel.Side side) {
        String reason = model.describeUnavailable(side);
        return reason != null ? reason : "";
    }

    /**
     * Shown when there is no hunk to render: incomplete files are displayed in full (there is no
     * meaningful diff against "nothing"), and identical/unavailable content shows an explanation.
     */
    private void renderFallback() {
        String baseReason = model.describeUnavailable(FileDiffPreviewModel.Side.BASE);
        String sourceReason = model.describeUnavailable(FileDiffPreviewModel.Side.SOURCE);

        boolean baseRenderable = baseReason == null;
        boolean sourceRenderable = sourceReason == null;

        if (baseRenderable) {
            basePane.setText(model.getBaseText() != null ? model.getBaseText() : "");
        } else {
            basePane.setText(baseReason);
        }
        if (sourceRenderable) {
            sourcePane.setText(model.getSourceText() != null ? model.getSourceText() : "");
        } else {
            sourcePane.setText(sourceReason);
        }
        // No hunks means either an identical pair or a side that cannot be compared; both cases
        // are already handled above, so only the caret needs resetting.
        basePane.setCaretPosition(0);
        sourcePane.setCaretPosition(0);
    }

    private String plainText(DiffHunk hunk, FileDiffPreviewModel.Side side) {
        StringBuilder sb = new StringBuilder();
        for (DiffLine line : hunk.getLines()) {
            switch (line.getType()) {
                case UNCHANGED -> sb.append("  ").append(line.getContent()).append("\n");
                case REMOVED -> {
                    if (side == FileDiffPreviewModel.Side.BASE) {
                        sb.append("- ").append(line.getContent()).append("\n");
                    }
                }
                case ADDED -> {
                    if (side == FileDiffPreviewModel.Side.SOURCE) {
                        sb.append("+ ").append(line.getContent()).append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private static SimpleAttributeSet attributeWithBackground(Color color) {
        SimpleAttributeSet attr = new SimpleAttributeSet();
        StyleConstants.setBackground(attr, color);
        return attr;
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br/>");
    }

    /** The model this panel renders; exposed for tests. */
    public FileDiffPreviewModel getModel() {
        return model;
    }

    /** Number of change regions found; exposed for tests. */
    public int getHunkCount() {
        return hunks.size();
    }

    /** Base (previous version) pane text; exposed for tests. */
    public String getBasePaneText() {
        return basePane.getText();
    }

    /** Source (new version) pane text; exposed for tests. */
    public String getSourcePaneText() {
        return sourcePane.getText();
    }

    /** True when this panel is rendering the non-text placeholder instead of a diff. */
    public boolean isBinaryPlaceholderShown() {
        return !model.isText();
    }

    /** Show a modal preview dialog. Returns when the user closes it. */
    public static void showDialog(java.awt.Component parent, FileDiffPreviewModel model) {
        if (model == null) {
            return;
        }
        java.awt.Window owner =
                parent instanceof java.awt.Window window
                        ? window
                        : javax.swing.SwingUtilities.getWindowAncestor(parent);
        String title = "File Change Preview - " + model.getFileName();
        JDialog dialog =
                owner instanceof java.awt.Frame frame
                        ? new JDialog(frame, title, true)
                        : new JDialog((java.awt.Dialog) null, title, true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(new FileDiffPreviewPanel(model));

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dialog.dispose());
        JPanel closePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        closePanel.add(closeButton);
        dialog.getContentPane().add(closePanel, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(closeButton);

        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}
