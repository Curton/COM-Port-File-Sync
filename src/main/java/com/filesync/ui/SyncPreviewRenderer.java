package com.filesync.ui;

import com.filesync.sync.ConflictAnalyzer;
import com.filesync.sync.ConflictInfo;
import com.filesync.sync.FileChangeDetector;
import com.filesync.sync.GitStatusUtil;
import com.filesync.sync.SyncPreviewPlan;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
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
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.event.MouseInputAdapter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;

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

    /** Receives git-selection log lines; must be thread-safe (LogController.log is). */
    private final java.util.function.Consumer<String> logSink;

    public SyncPreviewRenderer(JFrame owner, ConflictResolver conflictResolver) {
        this(owner, conflictResolver, msg -> {});
    }

    public SyncPreviewRenderer(JFrame owner) {
        this(owner, null);
    }

    /**
     * @param logSink sink for git-selection outcomes (success counts, zero-match diagnostics,
     *     failures) so results are visible in the main window's log even when the preview dialog's
     *     inline summary label is missed; may be a no-op in tests
     */
    public SyncPreviewRenderer(
            JFrame owner,
            ConflictResolver conflictResolver,
            java.util.function.Consumer<String> logSink) {
        this.owner = owner;
        this.conflictResolver = conflictResolver;
        this.logSink = logSink != null ? logSink : msg -> {};
    }

    public SyncPreviewResult showSyncPreviewDialogWithResult(
            SyncPreviewPlan syncPreview, File syncFolder) {
        List<SyncPreviewRow> rows = buildSyncPreviewRows(syncPreview);
        DefaultTableModel previewModel = createSyncPreviewTableModel(rows);

        JLabel selectionSummary = new JLabel();
        TableRowSorter<DefaultTableModel> previewSorter = createPreviewSorter(previewModel);
        JPanel previewPanel =
                createPreviewPanel(previewModel, rows, syncFolder, selectionSummary, previewSorter);

        // Auto-default: launch the git-based selection off the EDT right before showing the modal
        // dialog. The SwingWorker's done() is dispatched by the modal dialog's nested event pump,
        // flipping checkboxes to git's changed set (or leaving them unchecked on failure/timeout).
        triggerGitBasedSelection(previewModel, rows, syncFolder, selectionSummary, previewSorter);

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
            JLabel selectionSummary,
            TableRowSorter<DefaultTableModel> previewSorter) {
        JTable previewTable = createPreviewTable(previewModel, rows, previewSorter);
        updateSyncPreviewSummary(selectionSummary, previewModel, rows);
        previewModel.addTableModelListener(
                event -> updateSyncPreviewSummary(selectionSummary, previewModel, rows));

        JPanel controlPanel =
                createControlPanel(previewModel, selectionSummary, rows, syncFolder, previewSorter);
        JScrollPane previewScroll = new JScrollPane(previewTable);
        previewScroll.setPreferredSize(new Dimension(720, 480));

        JPanel previewPanel = new JPanel(new java.awt.BorderLayout(0, 8));
        previewPanel.add(controlPanel, java.awt.BorderLayout.NORTH);
        previewPanel.add(previewScroll, java.awt.BorderLayout.CENTER);
        return previewPanel;
    }

    private JTable createPreviewTable(
            DefaultTableModel previewModel,
            List<SyncPreviewRow> rows,
            TableRowSorter<DefaultTableModel> previewSorter) {
        JTable previewTable = new JTable(previewModel);
        previewTable.setFillsViewportHeight(true);
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        // Clicking Sync/Type/Size/Path headers sorts the table; the default sort key is Sync so
        // batch selections can put checked rows on top. Individual checkbox toggles never
        // re-sort (the sorter ignores model updates).
        previewTable.setRowSorter(previewSorter);
        previewTable.getColumnModel().getColumn(0).setPreferredWidth(25);
        previewTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        previewTable.getColumnModel().getColumn(2).setPreferredWidth(50);
        previewTable.getColumnModel().getColumn(3).setPreferredWidth(500);
        previewTable.getColumnModel().getColumn(1).setCellRenderer(createTypeCellRenderer(rows));
        previewTable.getColumnModel().getColumn(2).setCellRenderer(createSizeCellRenderer(rows));
        previewTable.getColumnModel().getColumn(3).setCellRenderer(createPathTailRenderer());
        previewTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        previewTable.addMouseListener(
                new MouseInputAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (e.getClickCount() != 2) {
                            return;
                        }
                        int viewRow = previewTable.rowAtPoint(e.getPoint());
                        if (viewRow < 0) {
                            return;
                        }
                        // Sorting may permute the view, so resolve the model row before editing.
                        int row = previewTable.convertRowIndexToModel(viewRow);
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
            File syncFolder,
            TableRowSorter<DefaultTableModel> previewSorter) {
        javax.swing.JButton selectAllButton = new javax.swing.JButton("Select All");
        selectAllButton.addActionListener(
                event -> setPreviewSelection(previewModel, true, previewSorter));

        javax.swing.JButton selectGitButton = new javax.swing.JButton("Select Changes (git)");
        selectGitButton.setToolTipText(
                "Select only files reported by 'git status --short' in the sync folder");
        selectGitButton.addActionListener(
                event ->
                        triggerGitBasedSelection(
                                previewModel, rows, syncFolder, selectionSummary, previewSorter));

        javax.swing.JButton deselectAllButton = new javax.swing.JButton("Deselect All");
        deselectAllButton.addActionListener(
                event -> setPreviewSelection(previewModel, false, previewSorter));

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
     * summaryLabel} so the modal dialog is not disrupted, and every outcome is additionally written
     * to {@code logSink} — the git selection must never fail silently. Used both by the "Select
     * Changes (git)" button and as the dialog's auto-default selection.
     */
    private void triggerGitBasedSelection(
            DefaultTableModel previewModel,
            List<SyncPreviewRow> rows,
            File syncFolder,
            JLabel summaryLabel,
            TableRowSorter<DefaultTableModel> previewSorter) {
        if (syncFolder == null) {
            summaryLabel.setText("git: sync folder unknown");
            logSink.accept("git: selection skipped - sync folder unknown");
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
                            int matches =
                                    applyGitSelection(previewModel, rows, changed, previewSorter);
                            summaryLabel.setText(
                                    "git: matched "
                                            + matches
                                            + " of "
                                            + changed.size()
                                            + " changed file(s)");
                            logGitSelectionOutcome(changed, matches, rows);
                        } catch (Exception e) {
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            String msg = cause.getMessage();
                            if (msg == null) {
                                msg = cause.getClass().getSimpleName();
                            }
                            summaryLabel.setText("git: " + msg);
                            logSink.accept("git: selection failed - " + msg);
                        }
                    }
                };
        worker.execute();
    }

    /**
     * Write the git selection outcome to the log. A zero-match result is the classic "button did
     * nothing, no error" symptom (sync folder not matching the git repository, ignored paths,
     * encoding), so it is logged with sample paths from both sides to make the mismatch visible.
     */
    void logGitSelectionOutcome(Set<String> changedPaths, int matches, List<SyncPreviewRow> rows) {
        logSink.accept(
                "git: matched "
                        + matches
                        + " of "
                        + rows.size()
                        + " preview row(s); git reported "
                        + changedPaths.size()
                        + " changed path(s)");
        if (matches == 0 && !changedPaths.isEmpty() && !rows.isEmpty()) {
            logSink.accept(
                    "git: no preview row matched a git path; git paths: "
                            + samplePaths(changedPaths)
                            + "; preview paths: "
                            + samplePaths(rows.stream().map(SyncPreviewRow::getPath).toList()));
        }
    }

    /** First few entries of a path set, for diagnostic log lines. */
    private static String samplePaths(Set<String> paths) {
        return samplePaths(paths.stream().toList());
    }

    private static String samplePaths(List<String> paths) {
        int limit = 5;
        String joined = String.join(", ", paths.subList(0, Math.min(limit, paths.size())));
        return paths.size() > limit ? joined + ", ... (" + paths.size() + " total)" : joined;
    }

    /**
     * Set each row's checkbox to true iff its path appears in {@code changedPaths}; all other rows
     * are unchecked. Returns the number of rows that matched. Once the batch is applied, the
     * Sync-column sort is re-applied so checked rows move to the top.
     */
    int applyGitSelection(
            DefaultTableModel previewModel,
            List<SyncPreviewRow> rows,
            Set<String> changedPaths,
            TableRowSorter<DefaultTableModel> previewSorter) {
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
        resortIfSyncSorted(previewSorter);
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
                        if (columnIndex == 0) {
                            return Boolean.class;
                        }
                        if (columnIndex == 2) {
                            return Long.class;
                        }
                        return String.class;
                    }

                    @Override
                    public boolean isCellEditable(int row, int column) {
                        return column == 0;
                    }
                };
        // All rows start unchecked; the git-based auto-default (and the "Select Changes (git)"
        // button) flips checkboxes after the dialog opens. No size-based pre-selection heuristic.
        // The Size column holds raw byte counts (Long) so sorting is numeric; the cell renderer
        // displays the formatted sizeText instead.
        for (SyncPreviewRow row : rows) {
            model.addRow(
                    new Object[] {
                        Boolean.FALSE, row.getTypeLabel(), row.getSizeBytes(), row.getPath()
                    });
        }
        return model;
    }

    /**
     * Build the preview table's row sorter. All four columns (Sync, Type, Size, Path) are sortable
     * by clicking their headers; Path compares in directory order, Type case-insensitively. The
     * default sort key is the Sync column descending, so a re-sort puts checked rows on top. Model
     * updates never re-sort — the Sync sort is re-applied only after a batch selection change (see
     * {@link #resortIfSyncSorted}) or a header click, so rows don't move under the cursor while
     * checkboxes are toggled individually.
     */
    static TableRowSorter<DefaultTableModel> createPreviewSorter(DefaultTableModel previewModel) {
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(previewModel);
        sorter.setComparator(1, TYPE_LABEL_COMPARATOR);
        sorter.setComparator(3, PATH_DIRECTORY_ORDER_COMPARATOR);
        // Boolean descending puts checked (TRUE) rows first.
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
        return sorter;
    }

    /**
     * Re-apply the Sync-column sort once after a batch selection change (git selection, Select All,
     * Deselect All), so checked rows move to the top. Skipped when the user has sorted by another
     * column; individual checkbox toggles never trigger this.
     */
    static void resortIfSyncSorted(TableRowSorter<DefaultTableModel> previewSorter) {
        if (previewSorter == null) {
            return;
        }
        List<? extends RowSorter.SortKey> keys = previewSorter.getSortKeys();
        if (!keys.isEmpty() && keys.get(0).getColumn() == 0) {
            previewSorter.sort();
        }
    }

    private static final Comparator<String> TYPE_LABEL_COMPARATOR =
            (a, b) -> {
                int cmp = a.compareToIgnoreCase(b);
                return cmp != 0 ? cmp : a.compareTo(b);
            };

    /**
     * Compare paths in directory order: segment by segment, so "a/b.txt" sorts before "a.txt" and
     * "a/b" before "a-x/c" (raw string order would compare the separator characters themselves).
     * Comparison ignores case first, then falls back to case-sensitive.
     */
    static int comparePathsDirectoryOrder(String a, String b) {
        String[] as = splitPathSegments(a);
        String[] bs = splitPathSegments(b);
        int common = Math.min(as.length, bs.length);
        for (int i = 0; i < common; i++) {
            int cmp = as[i].compareToIgnoreCase(bs[i]);
            if (cmp == 0) {
                cmp = as[i].compareTo(bs[i]);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(as.length, bs.length);
    }

    private static final Comparator<String> PATH_DIRECTORY_ORDER_COMPARATOR =
            SyncPreviewRenderer::comparePathsDirectoryOrder;

    private static String[] splitPathSegments(String path) {
        if (path == null || path.isEmpty()) {
            return new String[0];
        }
        return path.split("[/\\\\]+");
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

    private TableCellRenderer createTypeCellRenderer(List<SyncPreviewRow> rows) {
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
                int modelRow = table.convertRowIndexToModel(row);
                SyncPreviewRow previewRow =
                        modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
                if (previewRow != null && !isSelected) {
                    Color color = typeColor(previewRow.getOperationType());
                    if (color != null) {
                        setForeground(color);
                    }
                }
                return this;
            }

            private Color typeColor(SyncPreviewOperationType type) {
                return switch (type) {
                    case CONFLICT -> new Color(200, 0, 0);
                    case NEW -> new Color(0, 128, 0);
                    case MODIFIED -> new Color(0, 0, 180);
                    case APPEND -> new Color(0, 128, 128);
                    default -> null;
                };
            }
        };
    }

    private TableCellRenderer createSizeCellRenderer(List<SyncPreviewRow> rows) {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table,
                    Object value,
                    boolean isSelected,
                    boolean hasFocus,
                    int row,
                    int column) {
                // The model stores raw byte counts (Long) so the column sorts numerically; the
                // display text comes from the row, keeping the "-" placeholder for directory
                // and delete operations.
                int modelRow = table.convertRowIndexToModel(row);
                SyncPreviewRow previewRow =
                        modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
                String text = previewRow != null ? previewRow.getSizeText() : "";
                super.getTableCellRendererComponent(table, text, isSelected, hasFocus, row, column);
                return this;
            }
        };
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
            SyncPreviewOperationType type;
            if (conflict != null) {
                type = SyncPreviewOperationType.CONFLICT;
            } else if (syncPreview.getAppendResumablePaths().contains(fileInfo.getPath())) {
                // Receiver holds a verified byte-prefix: only the missing tail will be sent.
                type = SyncPreviewOperationType.APPEND;
            } else if (syncPreview.getExistingRemotePaths().contains(fileInfo.getPath())) {
                type = SyncPreviewOperationType.MODIFIED;
            } else {
                type = SyncPreviewOperationType.NEW;
            }
            long displaySize = fileInfo.getSize();
            if (type == SyncPreviewOperationType.APPEND) {
                FileChangeDetector.FileInfo remoteInfo =
                        syncPreview.getRemoteFileInfo(fileInfo.getPath());
                if (remoteInfo != null
                        && remoteInfo.getSize() >= 0
                        && displaySize > remoteInfo.getSize()) {
                    // Show only the missing tail so the selected-bytes summary matches what the
                    // sync will actually transfer.
                    displaySize -= remoteInfo.getSize();
                }
            }
            rows.add(
                    new SyncPreviewRow(
                            type,
                            fileInfo.getPath(),
                            UiFormatting.formatBytes(displaySize),
                            displaySize,
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

    /** Check or uncheck every row, then re-apply the Sync-column sort once for the batch. */
    void setPreviewSelection(
            DefaultTableModel previewModel,
            boolean selected,
            TableRowSorter<DefaultTableModel> previewSorter) {
        for (int i = 0; i < previewModel.getRowCount(); i++) {
            previewModel.setValueAt(selected, i, 0);
        }
        resortIfSyncSorted(previewSorter);
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
                case CONFLICT, TRANSFER_FILE, NEW, MODIFIED, APPEND ->
                        selectedTransferFiles.add(row.getPath());
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
