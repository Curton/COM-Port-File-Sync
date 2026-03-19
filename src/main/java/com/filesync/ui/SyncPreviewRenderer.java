package com.filesync.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

import com.filesync.sync.FileChangeDetector;
import com.filesync.sync.SyncPreviewPlan;

/**
 * Build and render sync preview text/table UIs.
 */
public class SyncPreviewRenderer {
    private final JFrame owner;

    public SyncPreviewRenderer(JFrame owner) {
        this.owner = owner;
    }

    public SyncPreviewPlan showSyncPreviewDialog(SyncPreviewPlan syncPreview, boolean requireConfirmation) {
        JTextArea previewArea = createSyncPreviewTextArea(syncPreview);
        if (!requireConfirmation) {
            JOptionPane.showMessageDialog(
                    owner,
                    previewArea,
                    "Sync Preview",
                    JOptionPane.INFORMATION_MESSAGE);
            return syncPreview;
        }

        List<SyncPreviewRow> rows = buildSyncPreviewRows(syncPreview);
        DefaultTableModel previewModel = createSyncPreviewTableModel(rows);
        JTable previewTable = new JTable(previewModel);
        previewTable.setFillsViewportHeight(true);
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        previewTable.getColumnModel().getColumn(0).setPreferredWidth(25);
        previewTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        previewTable.getColumnModel().getColumn(2).setPreferredWidth(50);
        previewTable.getColumnModel().getColumn(3).setPreferredWidth(500);
        previewTable.getColumnModel().getColumn(3).setCellRenderer(createPathTailRenderer());
        previewTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JLabel selectionSummary = new JLabel();
        updateSyncPreviewSummary(selectionSummary, previewModel, rows);
        previewModel.addTableModelListener(event -> updateSyncPreviewSummary(selectionSummary, previewModel, rows));

        javax.swing.JButton selectAllButton = new javax.swing.JButton("Select All");
        selectAllButton.addActionListener(event -> setPreviewSelection(previewModel, true));
        javax.swing.JButton deselectAllButton = new javax.swing.JButton("Deselect All");
        deselectAllButton.addActionListener(event -> setPreviewSelection(previewModel, false));

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controlPanel.add(selectAllButton);
        controlPanel.add(deselectAllButton);
        controlPanel.add(selectionSummary);

        JScrollPane previewScroll = new JScrollPane(previewTable);
        previewScroll.setPreferredSize(new Dimension(720, 480));

        JPanel previewPanel = new JPanel(new java.awt.BorderLayout(0, 8));
        previewPanel.add(controlPanel, java.awt.BorderLayout.NORTH);
        previewPanel.add(previewScroll, java.awt.BorderLayout.CENTER);

        int response = JOptionPane.showOptionDialog(
                owner,
                previewPanel,
                "Sync Preview - Select Files",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                new Object[] {"Start Sync", "Cancel"},
                "Start Sync");
        if (response != 0) {
            return null;
        }

        SyncPreviewPlan selectedPlan = createFilteredSyncPlan(syncPreview, previewModel, rows);
        if (selectedPlan.getTotalOperations() == 0) {
            return null;
        }
        return selectedPlan;
    }

    public JTextArea createSyncPreviewTextArea(SyncPreviewPlan syncPreview) {
        StringBuilder previewText = new StringBuilder();
        previewText.append("Sync Preview\n\n");

        previewText.append("Total size to transfer: ")
                .append(UiFormatting.formatBytes(syncPreview.getTotalBytesToTransfer()))
                .append("\n\n");

        previewText.append("Files to transfer (")
                .append(syncPreview.getFilesToTransfer().size())
                .append("):\n");
        for (FileChangeDetector.FileInfo fileInfo : syncPreview.getFilesToTransfer()) {
            previewText.append("  ")
                    .append(fileInfo.getPath())
                    .append(" (")
                    .append(UiFormatting.formatBytes(fileInfo.getSize()))
                    .append(")")
                    .append("\n");
        }
        if (syncPreview.getFilesToTransfer().isEmpty()) {
            previewText.append("  (none)\n");
        }

        previewText.append("\nFiles to delete (Strict Mode, ")
                .append(syncPreview.getFilesToDelete().size())
                .append("):\n");
        for (String path : syncPreview.getFilesToDelete()) {
            previewText.append("  ").append(path).append("\n");
        }
        if (!syncPreview.isStrictSyncMode()) {
            previewText.append("  (strict mode disabled; no remote-only files will be deleted)\n");
        } else if (syncPreview.getFilesToDelete().isEmpty()) {
            previewText.append("  (none)\n");
        }

        JTextArea previewArea = new JTextArea(previewText.toString(), 24, 100);
        previewArea.setEditable(false);
        previewArea.setLineWrap(false);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return previewArea;
    }

    public DefaultTableModel createSyncPreviewTableModel(List<SyncPreviewRow> rows) {
        DefaultTableModel model = new DefaultTableModel(new String[] {"Sync", "Type", "Size", "Path"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };
        for (SyncPreviewRow row : rows) {
            model.addRow(new Object[] {
                    Boolean.TRUE,
                    row.getTypeLabel(),
                    row.getSizeText(),
                    row.getPath()
            });
        }
        return model;
    }

    private TableCellRenderer createPathTailRenderer() {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.TRAILING);
                String path = value != null ? value.toString() : "";
                setToolTipText(path.isEmpty() ? null : path);
                int cellWidth = table.getColumnModel().getColumn(column).getWidth();
                int avail = Math.max(cellWidth - 8, 50);
                FontMetrics fm = getFontMetrics(getFont());
                String display = path;
                if (path.length() > 0 && fm.stringWidth(path) > avail) {
                    String ellipsis = "...";
                    for (int i = 0; i < path.length(); i++) {
                        String tail = path.substring(i);
                        if (fm.stringWidth(ellipsis + tail) <= avail) {
                            display = ellipsis + tail;
                            break;
                        }
                    }
                }
                setText(display);
                return this;
            }
        };
    }

    private List<SyncPreviewRow> buildSyncPreviewRows(SyncPreviewPlan syncPreview) {
        List<SyncPreviewRow> rows = new ArrayList<>();

        for (FileChangeDetector.FileInfo fileInfo : syncPreview.getFilesToTransfer()) {
            rows.add(new SyncPreviewRow(
                    SyncPreviewOperationType.TRANSFER_FILE,
                    fileInfo.getPath(),
                    UiFormatting.formatBytes(fileInfo.getSize()),
                    fileInfo.getSize()));
        }

        for (String path : syncPreview.getEmptyDirectoriesToCreate()) {
            rows.add(new SyncPreviewRow(
                    SyncPreviewOperationType.CREATE_DIR,
                    path,
                    "-",
                    0L));
        }

        for (String path : syncPreview.getFilesToDelete()) {
            rows.add(new SyncPreviewRow(
                    SyncPreviewOperationType.DELETE_FILE,
                    path,
                    "-",
                    0L));
        }

        for (String path : syncPreview.getEmptyDirectoriesToDelete()) {
            rows.add(new SyncPreviewRow(
                    SyncPreviewOperationType.DELETE_DIR,
                    path,
                    "-",
                    0L));
        }
        return rows;
    }

    private void setPreviewSelection(DefaultTableModel previewModel, boolean selected) {
        for (int i = 0; i < previewModel.getRowCount(); i++) {
            previewModel.setValueAt(selected, i, 0);
        }
    }

    private void updateSyncPreviewSummary(JLabel summaryLabel, DefaultTableModel previewModel, List<SyncPreviewRow> rows) {
        int selectedCount = 0;
        long selectedBytes = 0L;
        for (int i = 0; i < previewModel.getRowCount(); i++) {
            if (!Boolean.TRUE.equals(previewModel.getValueAt(i, 0))) {
                continue;
            }
            SyncPreviewRow row = rows.get(i);
            selectedCount++;
            selectedBytes += row.getSizeBytes();
        }
        summaryLabel.setText("Selected " + selectedCount + " of " + rows.size()
                + " operations, " + UiFormatting.formatBytes(selectedBytes) + " transfer");
    }

    public SyncPreviewPlan createFilteredSyncPlan(
            SyncPreviewPlan syncPreview,
            DefaultTableModel previewModel,
            List<SyncPreviewRow> rows) {
        Set<String> selectedTransferFiles = new HashSet<>();
        Set<String> selectedCreateDirs = new HashSet<>();
        Set<String> selectedDeleteFiles = new HashSet<>();
        Set<String> selectedDeleteDirs = new HashSet<>();

        for (int i = 0; i < previewModel.getRowCount(); i++) {
            if (!Boolean.TRUE.equals(previewModel.getValueAt(i, 0))) {
                continue;
            }
            SyncPreviewRow row = rows.get(i);
            if (row.getOperationType() == SyncPreviewOperationType.TRANSFER_FILE) {
                selectedTransferFiles.add(row.getPath());
            } else if (row.getOperationType() == SyncPreviewOperationType.CREATE_DIR) {
                selectedCreateDirs.add(row.getPath());
            } else if (row.getOperationType() == SyncPreviewOperationType.DELETE_FILE) {
                selectedDeleteFiles.add(row.getPath());
            } else if (row.getOperationType() == SyncPreviewOperationType.DELETE_DIR) {
                selectedDeleteDirs.add(row.getPath());
            }
        }

        return syncPreview.createFilteredPlan(
                selectedTransferFiles,
                selectedCreateDirs,
                selectedDeleteFiles,
                selectedDeleteDirs);
    }
}
