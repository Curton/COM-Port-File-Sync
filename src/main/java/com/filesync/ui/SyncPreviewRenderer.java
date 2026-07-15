package com.filesync.ui;

import com.filesync.sync.ConflictAnalyzer;
import com.filesync.sync.ConflictInfo;
import com.filesync.sync.FileChangeDetector;
import com.filesync.sync.GitStatusUtil;
import com.filesync.sync.SyncPreviewPlan;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.event.MouseEvent;
import java.io.File;
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
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.event.MouseInputAdapter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

/** Build and render sync preview text/table UIs. */
public class SyncPreviewRenderer {

    /** Result of a sync preview dialog showing both the selected plan and UI state. */
    public static final class SyncPreviewResult {
        private final SyncPreviewPlan plan;
        private final DefaultTableModel model;
        private final List<SyncPreviewRow> rows;

        SyncPreviewResult(
                SyncPreviewPlan plan, DefaultTableModel model, List<SyncPreviewRow> rows) {
            this.plan = plan;
            this.model = model;
            this.rows = rows;
        }

        public SyncPreviewPlan getPlan() {
            return plan;
        }

        public DefaultTableModel getModel() {
            return model;
        }

        public List<SyncPreviewRow> getRows() {
            return rows;
        }
    }

    private final JFrame owner;
    private final ConflictResolver conflictResolver;

    public SyncPreviewRenderer(JFrame owner, ConflictResolver conflictResolver) {
        this.owner = owner;
        this.conflictResolver = conflictResolver;
    }

    public SyncPreviewRenderer(JFrame owner) {
        this(owner, null);
    }

    public SyncPreviewResult showSyncPreviewDialogWithResult(
            SyncPreviewPlan syncPreview, File syncFolder) {
        List<SyncPreviewRow> rows = buildSyncPreviewRows(syncPreview);
        DefaultTableModel previewModel = createSyncPreviewTableModel(rows);

        JLabel selectionSummary = new JLabel();
        JPanel previewPanel = createPreviewPanel(previewModel, rows, syncFolder, selectionSummary);

        // Auto-default: launch the git-based selection off the EDT right before showing the modal
        // dialog. The SwingWorker's done() is dispatched by the modal dialog's nested event pump,
        // flipping checkboxes to git's changed set (or leaving them unchecked on failure/timeout).
        triggerGitBasedSelection(previewModel, rows, syncFolder, selectionSummary);

        int response = showPreviewOptionDialog(previewPanel);

        if (response != 0) {
            return null;
        }
        SyncPreviewPlan plan = createFilteredSyncPlan(syncPreview, previewModel, rows);
        return new SyncPreviewResult(plan, previewModel, rows);
    }

    private JPanel createPreviewPanel(
            DefaultTableModel previewModel,
            List<SyncPreviewRow> rows,
            File syncFolder,
            JLabel selectionSummary) {
        JTable previewTable = createPreviewTable(previewModel);
        updateSyncPreviewSummary(selectionSummary, previewModel, rows);
        previewModel.addTableModelListener(
                event -> updateSyncPreviewSummary(selectionSummary, previewModel, rows));

        JPanel controlPanel = createControlPanel(previewModel, selectionSummary, rows, syncFolder);
        JScrollPane previewScroll = new JScrollPane(previewTable);
        previewScroll.setPreferredSize(new Dimension(720, 480));

        JPanel previewPanel = new JPanel(new java.awt.BorderLayout(0, 8));
        previewPanel.add(controlPanel, java.awt.BorderLayout.NORTH);
        previewPanel.add(previewScroll, java.awt.BorderLayout.CENTER);
        return previewPanel;
    }

    private JTable createPreviewTable(DefaultTableModel previewModel) {
        JTable previewTable = new JTable(previewModel);
        previewTable.setFillsViewportHeight(true);
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        previewTable.getColumnModel().getColumn(0).setPreferredWidth(25);
        previewTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        previewTable.getColumnModel().getColumn(2).setPreferredWidth(50);
        previewTable.getColumnModel().getColumn(3).setPreferredWidth(500);
        previewTable.getColumnModel().getColumn(3).setCellRenderer(createPathTailRenderer());
        previewTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        previewTable.addMouseListener(
                new MouseInputAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() != 2) {
                            return;
                        }
                        int row = previewTable.rowAtPoint(e.getPoint());
                        if (row < 0) {
                            return;
                        }
                        // Commit any in-progress checkbox edit so we read the latest value.
                        if (previewTable.isEditing()) {
                            previewTable.getCellEditor().stopCellEditing();
                        }
                        boolean current = Boolean.TRUE.equals(previewModel.getValueAt(row, 0));
                        previewModel.setValueAt(!current, row, 0);
                    }
                });
        return previewTable;
    }

    private JPanel createControlPanel(
            DefaultTableModel previewModel,
            JLabel selectionSummary,
            List<SyncPreviewRow> rows,
            File syncFolder) {
        javax.swing.JButton selectAllButton = new javax.swing.JButton("Select All");
        selectAllButton.addActionListener(event -> setPreviewSelection(previewModel, true));

        javax.swing.JButton selectGitButton = new javax.swing.JButton("Select Changes (git)");
        selectGitButton.setToolTipText(
                "Select only files reported by 'git status --short' in the sync folder");
        selectGitButton.addActionListener(
                event ->
                        triggerGitBasedSelection(previewModel, rows, syncFolder, selectionSummary));

        javax.swing.JButton deselectAllButton = new javax.swing.JButton("Deselect All");
        deselectAllButton.addActionListener(event -> setPreviewSelection(previewModel, false));

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controlPanel.add(selectAllButton);
        controlPanel.add(selectGitButton);
        controlPanel.add(deselectAllButton);
        controlPanel.add(selectionSummary);
        return controlPanel;
    }

    /**
     * Run {@code git status --short} in {@code syncFolder} off the EDT, then set the preview
     * checkboxes to exactly the reported paths (matching rows are checked, all others unchecked).
     * Errors (git not installed / not a repository / timeout) are reported inline via {@code
     * summaryLabel} so the modal dialog is not disrupted. Used both by the "Select Changes (git)"
     * button and as the dialog's auto-default selection.
     */
    private void triggerGitBasedSelection(
            DefaultTableModel previewModel,
            List<SyncPreviewRow> rows,
            File syncFolder,
            JLabel summaryLabel) {
        if (syncFolder == null) {
            summaryLabel.setText("git: sync folder unknown");
            return;
        }
        summaryLabel.setText("git: checking...");
        SwingWorker<Set<String>, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected Set<String> doInBackground() throws Exception {
                        return GitStatusUtil.getChangedFiles(syncFolder);
                    }

                    @Override
                    protected void done() {
                        try {
                            Set<String> changed = get();
                            int matches = applyGitSelection(previewModel, rows, changed);
                            summaryLabel.setText(
                                    "git: matched "
                                            + matches
                                            + " of "
                                            + changed.size()
                                            + " changed file(s)");
                        } catch (Exception e) {
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            String msg = cause.getMessage();
                            if (msg == null) {
                                msg = cause.getClass().getSimpleName();
                            }
                            summaryLabel.setText("git: " + msg);
                        }
                    }
                };
        worker.execute();
    }

    /**
     * Set each row's checkbox to true iff its path appears in {@code changedPaths}; all other rows
     * are unchecked. Returns the number of rows that matched.
     */
    private int applyGitSelection(
            DefaultTableModel previewModel, List<SyncPreviewRow> rows, Set<String> changedPaths) {
        Set<String> effective = changedPaths != null ? changedPaths : Set.of();
        int matches = 0;
        for (int i = 0; i < previewModel.getRowCount(); i++) {
            SyncPreviewRow row = rows.get(i);
            boolean selected = row != null && effective.contains(row.getPath());
            if (selected) {
                matches++;
            }
            previewModel.setValueAt(selected, i, 0);
        }
        return matches;
    }

    private int showPreviewOptionDialog(JPanel previewPanel) {
        return JOptionPane.showOptionDialog(
                owner,
                previewPanel,
                "Sync Preview - Select Files",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                new Object[] {"Start Sync", "Cancel"},
                "Start Sync");
    }

    public DefaultTableModel createSyncPreviewTableModel(List<SyncPreviewRow> rows) {
        DefaultTableModel model =
                new DefaultTableModel(new String[] {"Sync", "Type", "Size", "Path"}, 0) {
                    @Override
                    public Class<?> getColumnClass(int columnIndex) {
                        return columnIndex == 0 ? Boolean.class : String.class;
                    }

                    @Override
                    public boolean isCellEditable(int row, int column) {
                        return column == 0;
                    }
                };
        // All rows start unchecked; the git-based auto-default (and the "Select Changes (git)"
        // button) flips checkboxes after the dialog opens. No size-based pre-selection heuristic.
        for (SyncPreviewRow row : rows) {
            model.addRow(
                    new Object[] {
                        Boolean.FALSE, row.getTypeLabel(), row.getSizeText(), row.getPath()
                    });
        }
        return model;
    }

    /**
     * Refresh the type label column in the table model for rows that have conflict info. Call this
     * after conflict resolution to update the display.
     *
     * @param model the table model to update
     * @param rows the rows list matching the model
     */
    public void refreshConflictTypeLabels(DefaultTableModel model, List<SyncPreviewRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            SyncPreviewRow row = rows.get(i);
            if (row.getOperationType() == SyncPreviewOperationType.CONFLICT) {
                model.setValueAt(row.getTypeLabel(), i, 1);
            }
        }
    }

    private TableCellRenderer createPathTailRenderer() {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table,
                    Object value,
                    boolean isSelected,
                    boolean hasFocus,
                    int row,
                    int column) {
                super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
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
            ConflictInfo conflict = syncPreview.getConflict(fileInfo.getPath());
            SyncPreviewOperationType type =
                    conflict != null
                            ? SyncPreviewOperationType.CONFLICT
                            : SyncPreviewOperationType.TRANSFER_FILE;
            rows.add(
                    new SyncPreviewRow(
                            type,
                            fileInfo.getPath(),
                            UiFormatting.formatBytes(fileInfo.getSize()),
                            fileInfo.getSize(),
                            conflict));
        }

        for (String path : syncPreview.getEmptyDirectoriesToCreate()) {
            rows.add(new SyncPreviewRow(SyncPreviewOperationType.CREATE_DIR, path, "-", 0L));
        }

        for (String path : syncPreview.getFilesToDelete()) {
            rows.add(new SyncPreviewRow(SyncPreviewOperationType.DELETE_FILE, path, "-", 0L));
        }

        for (String path : syncPreview.getEmptyDirectoriesToDelete()) {
            rows.add(new SyncPreviewRow(SyncPreviewOperationType.DELETE_DIR, path, "-", 0L));
        }
        return rows;
    }

    private void setPreviewSelection(DefaultTableModel previewModel, boolean selected) {
        for (int i = 0; i < previewModel.getRowCount(); i++) {
            previewModel.setValueAt(selected, i, 0);
        }
    }

    private void updateSyncPreviewSummary(
            JLabel summaryLabel, DefaultTableModel previewModel, List<SyncPreviewRow> rows) {
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
        summaryLabel.setText(
                "Selected "
                        + selectedCount
                        + " of "
                        + rows.size()
                        + " operations, "
                        + UiFormatting.formatBytes(selectedBytes)
                        + " transfer");
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
            if (row == null) {
                continue;
            }
            switch (row.getOperationType()) {
                case CONFLICT, TRANSFER_FILE -> selectedTransferFiles.add(row.getPath());
                case CREATE_DIR -> selectedCreateDirs.add(row.getPath());
                case DELETE_FILE -> selectedDeleteFiles.add(row.getPath());
                case DELETE_DIR -> selectedDeleteDirs.add(row.getPath());
            }
        }

        return syncPreview.createFilteredPlan(
                selectedTransferFiles, selectedCreateDirs, selectedDeleteFiles, selectedDeleteDirs);
    }

    /**
     * Resolve conflicts for selected files in the sync plan. Shows a single unified dialog with
     * Next/Previous navigation and progress (e.g. 2/5). User can cancel to abort the entire
     * resolution.
     *
     * @param plan the sync plan with conflicts
     * @param previewModel the table model to refresh after resolution
     * @param rows the rows list matching the model
     * @return true if all conflicts were resolved (user did not cancel), false if user cancelled
     */
    public boolean resolveConflictsForSelectedFiles(
            SyncPreviewPlan plan, DefaultTableModel previewModel, List<SyncPreviewRow> rows) {
        return resolveConflictsForSelectedFiles(plan, null, previewModel, rows);
    }

    /**
     * Resolve conflicts for selected files in one unified window. Fetches remote content for text
     * conflicts, then shows ConflictResolutionDialog with Next/Previous navigation and progress
     * indicator.
     *
     * @param plan the sync plan with conflicts
     * @param resolver provider to fetch remote content for merge UI (may be null to use injected
     *     resolver)
     * @param previewModel the table model to refresh after resolution
     * @param rows the rows list matching the model
     * @return true if all conflicts were resolved (user did not cancel), false if user cancelled
     */
    public boolean resolveConflictsForSelectedFiles(
            SyncPreviewPlan plan,
            ConflictResolver resolver,
            DefaultTableModel previewModel,
            List<SyncPreviewRow> rows) {

        ConflictResolver effectiveResolver = resolver != null ? resolver : conflictResolver;
        if (effectiveResolver == null) {
            throw new IllegalStateException("No ConflictResolver available");
        }

        if (plan.getConflicts().isEmpty()) {
            return true;
        }

        // Collect unresolved conflicts in transfer order
        List<ConflictInfo> toResolve = new ArrayList<>();
        for (FileChangeDetector.FileInfo fileInfo : plan.getFilesToTransfer()) {
            ConflictInfo conflict = plan.getConflict(fileInfo.getPath());
            if (conflict != null && !conflict.isResolved()) {
                toResolve.add(conflict);
            }
        }

        if (toResolve.isEmpty()) {
            return true;
        }

        // Fetch remote content and filter trivial conflicts one at a time to bound memory
        List<ConflictInfo> nonTrivial = new ArrayList<>();
        for (ConflictInfo conflict : toResolve) {
            byte[] remoteContent = effectiveResolver.fetchRemoteContent(conflict.getPath());
            if (remoteContent != null) {
                conflict.setRemoteContent(remoteContent);
            }
        }

        // Filter out trivial conflicts (whitespace-only changes) after remote content is available
        ConflictAnalyzer.filterTrivialConflicts(toResolve);
        for (ConflictInfo conflict : toResolve) {
            if (conflict.isResolved()
                    && conflict.getResolution() == ConflictInfo.Resolution.KEEP_LOCAL
                    && !conflict.hasMeaningfulDifferences()) {
                // Trivial conflict already marked KEEP_LOCAL — release remote content
                conflict.setRemoteContent(null);
            } else {
                nonTrivial.add(conflict);
            }
        }

        if (nonTrivial.isEmpty()) {
            return true; // All conflicts were trivial, nothing to resolve
        }

        final ConflictResolutionDialog.Result[] resultHolder =
                new ConflictResolutionDialog.Result[1];
        try {
            resultHolder[0] = ConflictResolutionDialog.showDialog(owner, nonTrivial);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to show conflict resolution dialog: " + e.getMessage(), e);
        }
        ConflictResolutionDialog.Result result = resultHolder[0];
        if (result != ConflictResolutionDialog.Result.COMPLETED) {
            return false;
        }

        // Refresh table to show resolved labels
        refreshConflictTypeLabels(previewModel, rows);
        return true;
    }

    /** Interface for fetching remote file content needed for conflict resolution. */
    public interface ConflictResolver {
        /**
         * Fetch remote file content for the given path.
         *
         * @param path the relative path of the file
         * @return the file content, or null if unavailable
         */
        byte[] fetchRemoteContent(String path);
    }
}
