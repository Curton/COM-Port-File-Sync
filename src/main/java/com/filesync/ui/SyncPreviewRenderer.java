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
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.MouseInputAdapter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
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

    /** Sync folder a preview's local files are resolved against; set when the dialog is shown. */
    private File previewSyncFolder;

    /**
     * Point local-preview reads at {@code syncFolder} without showing the modal dialog. Package
     * private for tests: {@link #showSyncPreviewDialogWithResult} blocks on the option dialog,
     * which must never open in a test run.
     */
    void setPreviewSyncFolder(File syncFolder) {
        this.previewSyncFolder = syncFolder;
    }

    /** Largest file that will be read for a text preview, on either side. */
    static final int MAX_PREVIEW_BYTES = 512 * 1024;

    /** Maximum lines rendered per pane; beyond this the preview is marked truncated. */
    private static final int MAX_PREVIEW_LINES = 4000;

    /** Column index of the "Preview" button column. */
    static final int PREVIEW_COLUMN = 4;

    /** Column index of the Path column, which carries both the sort and the search highlight. */
    static final int PATH_COLUMN = 3;

    /** Row bucket for a path whose extension matches the query; sorts ahead of everything else. */
    static final int SEARCH_MATCH_EXTENSION = 0;

    /** Row bucket for a path whose file name (not extension) contains the query. */
    static final int SEARCH_MATCH_NAME = 1;

    /** Row bucket for a path that does not match the query at all; sorts last. */
    static final int SEARCH_MATCH_NONE = 2;

    /** Highlight colour applied to matched text in the Path column. */
    static final Color SEARCH_HIGHLIGHT_COLOR = new Color(255, 235, 59);

    /** Debounce before a keystroke re-sorts the table, so typing does not re-sort per character. */
    private static final int SEARCH_DEBOUNCE_MS = 120;

    /** Placeholder shown in the empty search field. */
    static final String SEARCH_FIELD_PLACEHOLDER =
            "Search: extension or file name (e.g. java, txt)";

    /**
     * Current search query, already trimmed. Empty means "no search": rows keep their natural order
     * and nothing is highlighted. Lives on the renderer because a preview dialog is modal and there
     * is only ever one open at a time.
     */
    private String previewSearchQuery = "";

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
        this.previewSyncFolder = syncFolder;
        // Every preview starts from the natural order; a stale query from a previous dialog must
        // not leak into this one.
        this.previewSearchQuery = "";

        JLabel selectionSummary = new JLabel();
        TableRowSorter<DefaultTableModel> previewSorter = createPreviewSorter(previewModel);
        JTextField searchField = createSearchField(previewSorter);
        JPanel previewPanel =
                createPreviewPanel(
                        previewModel,
                        rows,
                        syncFolder,
                        selectionSummary,
                        previewSorter,
                        searchField);

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

    /** Build a preview panel without a search field; used by tests that only exercise the table. */
    JPanel createPreviewPanel(
            DefaultTableModel previewModel,
            List<SyncPreviewRow> rows,
            File syncFolder,
            JLabel selectionSummary,
            TableRowSorter<DefaultTableModel> previewSorter) {
        return createPreviewPanel(
                previewModel, rows, syncFolder, selectionSummary, previewSorter, null);
    }

    JPanel createPreviewPanel(
            DefaultTableModel previewModel,
            List<SyncPreviewRow> rows,
            File syncFolder,
            JLabel selectionSummary,
            TableRowSorter<DefaultTableModel> previewSorter,
            JTextField searchField) {
        JTable previewTable = createPreviewTable(previewModel, rows, previewSorter);
        updateSyncPreviewSummary(selectionSummary, previewModel, rows);
        previewModel.addTableModelListener(
                event -> updateSyncPreviewSummary(selectionSummary, previewModel, rows));

        JPanel controlPanel =
                createControlPanel(previewModel, selectionSummary, rows, syncFolder, previewSorter);
        // Search bar sits *below* the Select All / Select Changes / Deselect All row, directly
        // above the table it filters.
        JPanel headerPanel = new JPanel(new java.awt.BorderLayout(0, 6));
        headerPanel.add(controlPanel, java.awt.BorderLayout.NORTH);
        headerPanel.add(
                searchField != null ? searchField : createSearchField(previewSorter),
                java.awt.BorderLayout.SOUTH);

        JScrollPane previewScroll = new JScrollPane(previewTable);
        previewScroll.setPreferredSize(new Dimension(720, 480));

        JPanel previewPanel = new JPanel(new java.awt.BorderLayout(0, 8));
        previewPanel.add(headerPanel, java.awt.BorderLayout.NORTH);
        previewPanel.add(previewScroll, java.awt.BorderLayout.CENTER);
        return previewPanel;
    }

    /**
     * Build the search field that re-sorts (and highlights) the preview rows as the user types.
     *
     * <p>Typing is debounced: the field's document events restart a short {@link Timer}, and only
     * when it fires does the query reach the sorter's comparator. That keeps a burst of keystrokes
     * to a single re-sort instead of one per character.
     */
    JTextField createSearchField(TableRowSorter<DefaultTableModel> previewSorter) {
        JTextField field = new JTextField();
        field.setToolTipText(
                "Type an extension (java, txt) or any part of a file name. Rows whose extension"
                        + " matches move to the top, then rows whose name contains the text; the"
                        + " rest follow in their normal order. Matches are highlighted.");
        field.putClientProperty("JTextField.placeholderText", SEARCH_FIELD_PLACEHOLDER);
        Timer debounce =
                new Timer(
                        SEARCH_DEBOUNCE_MS,
                        event -> applySearchQuery(previewSorter, field.getText()));
        debounce.setRepeats(false);
        field.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            @Override
                            public void insertUpdate(DocumentEvent e) {
                                debounce.restart();
                            }

                            @Override
                            public void removeUpdate(DocumentEvent e) {
                                debounce.restart();
                            }

                            @Override
                            public void changedUpdate(DocumentEvent e) {
                                debounce.restart();
                            }
                        });
        return field;
    }

    /**
     * Publish {@code rawQuery} to the table and re-sort. Exposed separately from the field so the
     * search behaviour (comparator + highlight) can be driven directly in tests without a document
     * event.
     */
    void applySearchQuery(TableRowSorter<DefaultTableModel> previewSorter, String rawQuery) {
        previewSearchQuery = rawQuery == null ? "" : rawQuery.trim();
        // Sorting the table repaints it, which is what refreshes the highlight.
        applySearch(previewSorter, previewSearchQuery);
    }

    /** The active, trimmed search query; empty when no search is in effect. */
    String getPreviewSearchQuery() {
        return previewSearchQuery;
    }

    JTable createPreviewTable(
            DefaultTableModel previewModel,
            List<SyncPreviewRow> rows,
            TableRowSorter<DefaultTableModel> previewSorter) {
        JTable previewTable = new JTable(previewModel);
        previewTable.setFillsViewportHeight(true);
        // Only the Path column absorbs extra width when the dialog is resized. With _LAST_COLUMN
        // the pinned one-off Preview column would be the one stretched, leaving its button
        // floating in slack; _SUBSEQUENT spreads the surplus over Path instead.
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
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
        previewTable
                .getColumnModel()
                .getColumn(PATH_COLUMN)
                .setCellRenderer(createPathTailRenderer());
        configurePreviewColumn(previewTable, rows);
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
                        int column = previewTable.columnAtPoint(e.getPoint());
                        // Double-clicking the Preview column opens the diff instead of toggling
                        // the checkbox, matching what the button under the cursor implies.
                        if (previewTable.convertColumnIndexToModel(column) == PREVIEW_COLUMN) {
                            openChangePreview(rows.get(row));
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

    /**
     * Turn the Preview column into a button column. The cell renders as a clickable button and, on
     * click, opens the change preview for that row — the same code path as double-clicking the
     * column, so both entry points behave identically.
     */
    private void configurePreviewColumn(JTable previewTable, List<SyncPreviewRow> rows) {
        if (previewTable.getColumnModel().getColumnCount() <= PREVIEW_COLUMN) {
            return;
        }
        TableColumn previewColumn = previewTable.getColumnModel().getColumn(PREVIEW_COLUMN);
        // The column is pinned to one exact width: preferred == min == max. An AUTO_RESIZE_LAST
        // table would otherwise stretch the last column, leaving the button floating in slack.
        int buttonWidth = previewButtonWidth(previewTable);
        previewColumn.setPreferredWidth(buttonWidth);
        previewColumn.setMinWidth(buttonWidth);
        previewColumn.setMaxWidth(buttonWidth);
        // Centre the header over the centred button so the column reads as one aligned unit.
        previewColumn.setHeaderRenderer(createPreviewHeaderRenderer());

        TableCellRenderer buttonRenderer = new PreviewButtonRenderer(rows);
        previewColumn.setCellRenderer(buttonRenderer);
        previewColumn.setCellEditor(
                new PreviewButtonEditor(previewTable, rows, this::openChangePreview));
    }

    /** Header renderer that centres the "Pre" label to match the centred cell buttons. */
    private TableCellRenderer createPreviewHeaderRenderer() {
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.setHorizontalAlignment(SwingConstants.CENTER);
        return renderer;
    }

    /**
     * Column width that exactly fits the Preview button: the label's rendered width plus the LAF's
     * horizontal button insets, with only a few pixels of slack. Deriving it from the font (rather
     * than hard-coding a number) keeps the button snug under different look-and-feels and font
     * sizes while staying wide enough that the label is never truncated.
     */
    static int previewButtonWidth(JTable table) {
        javax.swing.JButton probe = new javax.swing.JButton(previewButtonLabel());
        probe.setFont(table != null && table.getFont() != null ? table.getFont() : probe.getFont());
        // The Windows LAF gives a button ~10px of horizontal margin on each side, which for a
        // three-letter label means most of the button is empty chrome. Cells use the same tight
        // margin (see PREVIEW_BUTTON_MARGIN), so the column is measured against that, not the
        // default. getPreferredSize() accounts for the LAF border and insets as well.
        probe.setMargin(PREVIEW_BUTTON_MARGIN);
        int width = probe.getPreferredSize().width + previewButtonExtraPadding();
        return Math.max(width, previewButtonMinWidth());
    }

    /** Tight horizontal margin shared by the measured, rendered and editable Preview buttons. */
    static final java.awt.Insets PREVIEW_BUTTON_MARGIN = new java.awt.Insets(1, 6, 1, 6);

    /** Slack added to the measured button width so the text is never clipped. */
    private static int previewButtonExtraPadding() {
        return 2;
    }

    /** Floor for the column so an unusually small font cannot collapse the button entirely. */
    private static int previewButtonMinWidth() {
        return 34;
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
                            logGitSelectionOutcome(
                                    changed, matches, rows, GitStatusUtil.lastUsedExecutable());
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
     * The git executable actually used is included, so an install-location fallback (PATH missing
     * or blocked) is visible in the log.
     */
    void logGitSelectionOutcome(
            Set<String> changedPaths,
            int matches,
            List<SyncPreviewRow> rows,
            String gitExecutable) {
        logSink.accept(
                "git: matched "
                        + matches
                        + " of "
                        + rows.size()
                        + " preview row(s); git reported "
                        + changedPaths.size()
                        + " changed path(s) via "
                        + gitExecutable);
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
                new DefaultTableModel(
                        new String[] {"Sync", "Type", "Size", "Path", previewColumnHeader()}, 0) {
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
                        // The Preview column is a button: it is "edited" only to trigger the click
                        // handler, which immediately cancels the edit.
                        return column == 0 || column == PREVIEW_COLUMN;
                    }
                };
        // All rows start unchecked; the git-based auto-default (and the "Select Changes (git)"
        // button) flips checkboxes after the dialog opens. No size-based pre-selection heuristic.
        // The Size column holds raw byte counts (Long) so sorting is numeric; the cell renderer
        // displays the formatted sizeText instead.
        for (SyncPreviewRow row : rows) {
            model.addRow(
                    new Object[] {
                        Boolean.FALSE,
                        row.getTypeLabel(),
                        row.getSizeBytes(),
                        row.getPath(),
                        previewButtonLabel()
                    });
        }
        return model;
    }

    /**
     * Header for the preview column, kept consistent with the short {@link #previewButtonLabel()}
     * so the header and its buttons read as the same thing rather than appearing ellipsized.
     */
    static String previewColumnHeader() {
        return "Pre";
    }

    /**
     * Label shown on every Preview cell. Kept to the short form "Pre" (rather than "Preview") so
     * the button fits its column without the LAF ellipsizing it to "Pre..."; the tooltip spells the
     * action out in full.
     */
    static String previewButtonLabel() {
        return previewColumnHeader();
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
        PreviewSorter sorter = new PreviewSorter(previewModel);
        sorter.setComparator(1, TYPE_LABEL_COMPARATOR);
        // Boolean descending puts checked (TRUE) rows first.
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
        return sorter;
    }

    /**
     * A path sorter that is search-aware. The comparator installed on the Path column combines the
     * active search's relevance ranking with the usual directory ordering, so sorting the Path
     * column ASCENDING (see {@link #applySearch}) puts the best matches on top while each relevance
     * group keeps its normal order. A path that does not match at all is pushed to the bottom.
     */
    private static final class PreviewSorter extends TableRowSorter<DefaultTableModel> {

        private String query = "";

        PreviewSorter(DefaultTableModel model) {
            super(model);
            // RowSorter's comparator API is typed on Object while the Path column holds Strings.
            // The cast goes through the raw type because Java forbids Comparator<String> ->
            // Comparator<Object> directly; the comparator is only ever handed Path strings.
            @SuppressWarnings({"unchecked", "rawtypes"})
            Comparator<Object> pathComparator =
                    (Comparator) (Comparator<String>) this::comparePathsBySearch;
            setComparator(PATH_COLUMN, pathComparator);
        }

        void setSearchQuery(String query) {
            this.query = query == null ? "" : query;
        }

        private int comparePathsBySearch(String a, String b) {
            int bySearch = Integer.compare(searchRank(a, query), searchRank(b, query));
            if (bySearch != 0) {
                return bySearch;
            }
            return comparePathsDirectoryOrder(a, b);
        }
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

    /**
     * Apply the active search to the preview: with a query present, the Path column is sorted
     * ascending so the best matches rise to the top (extension matches first, then name matches,
     * then everything else); with an empty query the previous sort key is restored. The table is
     * repainted afterwards so the highlight refreshes.
     */
    static void applySearch(TableRowSorter<DefaultTableModel> previewSorter, String query) {
        if (previewSorter == null) {
            return;
        }
        String trimmed = query == null ? "" : query.trim();
        if (previewSorter instanceof PreviewSorter sorter) {
            sorter.setSearchQuery(trimmed);
        }
        if (trimmed.isEmpty()) {
            // Clearing the query restores the Sync-descending default, so the table's natural
            // checked-first view comes back.
            previewSorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
        } else {
            previewSorter.setSortKeys(
                    List.of(new RowSorter.SortKey(PATH_COLUMN, SortOrder.ASCENDING)));
        }
        previewSorter.sort();
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

    // --- Search ------------------------------------------------------------------------------

    /**
     * Relevance bucket for {@code path} under {@code query}, used both for the comparator and for
     * deciding what to highlight.
     *
     * <ul>
     *   <li>{@link #SEARCH_MATCH_EXTENSION} — the file name's extension contains the query, so
     *       searching "java" or "txt" floats those file types to the top.
     *   <li>{@link #SEARCH_MATCH_NAME} — otherwise, the file name's stem contains the query.
     *   <li>{@link #SEARCH_MATCH_NONE} — no match; sorted last.
     * </ul>
     *
     * Matching is case-insensitive. An empty query ranks every path the same, which leaves the
     * directory ordering untouched.
     */
    static int searchRank(String path, String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty() || path == null || path.isEmpty()) {
            return SEARCH_MATCH_NONE;
        }
        String lowerQuery = trimmed.toLowerCase(java.util.Locale.ROOT);
        String fileName = lastPathSegment(path).toLowerCase(java.util.Locale.ROOT);
        String extension = fileExtension(fileName);
        if (!extension.isEmpty() && extension.contains(lowerQuery)) {
            return SEARCH_MATCH_EXTENSION;
        }
        String stem =
                fileName.substring(0, Math.max(0, fileName.length() - extensionLength(fileName)));
        if (stem.contains(lowerQuery)) {
            return SEARCH_MATCH_NAME;
        }
        // A directory row (or a path whose stem doesn't match) can still match elsewhere in its
        // path; treat that as a name-level match so such rows are not buried below non-matches.
        return path.toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)
                ? SEARCH_MATCH_NAME
                : SEARCH_MATCH_NONE;
    }

    /** True when {@code path} matches {@code query} at all (extension or name level). */
    static boolean matchesSearch(String path, String query) {
        return searchRank(path, query) != SEARCH_MATCH_NONE;
    }

    /** The last {@code /}- or {@code \}-separated segment of a path; the whole path if none. */
    private static String lastPathSegment(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Extension of a file name (the text after the final dot), lower-cased; empty if none. */
    private static String fileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        // A leading dot means a hidden file with no extension (".gitignore"), not an extension.
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1);
    }

    private static int extensionLength(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return 0;
        }
        return fileName.length() - dot - 1;
    }

    /**
     * Character range of the matched text within {@code path} that should be highlighted, or {@code
     * null} when there is nothing to mark.
     *
     * <p>The extension is preferred over the name: a search for "txt" highlights the ".txt" of a
     * file whose stem also happens to contain "txt" — that is the part the user typed to filter
     * file types. Ranges are in the path's own coordinates, since the rendered cell text is a
     * truncated tail of the same string.
     */
    static int[] searchHighlightRange(String path, String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty() || path == null || path.isEmpty()) {
            return null;
        }
        String lowerQuery = trimmed.toLowerCase(java.util.Locale.ROOT);
        String lowerPath = path.toLowerCase(java.util.Locale.ROOT);

        int segmentStart = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1;
        int dot = path.lastIndexOf('.');
        // Extension range: only when the dot is past the last separator (a real file extension)
        // and the extension actually contains the query.
        if (dot > segmentStart && dot < path.length() - 1) {
            int index = lowerPath.indexOf(lowerQuery, dot + 1);
            if (index >= dot + 1) {
                return new int[] {index, index + lowerQuery.length()};
            }
        }
        int dotInName = path.lastIndexOf('.', path.length() - 1);
        int stemEnd = dotInName > segmentStart ? dotInName : path.length();
        int index = lowerPath.lastIndexOf(lowerQuery, Math.max(stemEnd - 1, 0));
        if (index >= 0 && index + lowerQuery.length() <= stemEnd) {
            return new int[] {index, index + lowerQuery.length()};
        }
        // Fall back to any occurrence in the path (e.g. a directory segment matched the query).
        int anywhere = lowerPath.indexOf(lowerQuery);
        return anywhere >= 0 ? new int[] {anywhere, anywhere + lowerQuery.length()} : null;
    }

    /**
     * Refresh the type label column in the table model for rows that have conflict info. Call thiss
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
                String display = truncatePathTail(table, column, path);
                // Highlight the match as it appears in the *displayed* text. The cell renders a
                // truncated tail, so a match that lived in a dropped leading segment is simply
                // absent from `display` and gets no highlight — which is the correct outcome,
                // since there is nothing on screen to mark.
                int[] displayRange = searchHighlightRange(display, previewSearchQuery);
                if (displayRange == null) {
                    setText(display);
                } else {
                    setText(highlightHtml(display, displayRange));
                }
                return this;
            }
        };
    }

    /**
     * Render {@code display} as HTML with the matched range wrapped in a yellow span. HTML is used
     * rather than a {@link javax.swing.text.Highlighter} because the path cell is a {@code JLabel},
     * and the explicit colour keeps the highlight legible when the row is selected or focused.
     */
    static String highlightHtml(String display, int[] range) {
        String prefix = display.substring(0, range[0]);
        String match = display.substring(range[0], range[1]);
        String suffix = display.substring(range[1]);
        return "<html>"
                + escapeHtml(prefix)
                + "<span style='background:#FFEB3B;color:#000000;'>"
                + escapeHtml(match)
                + "</span>"
                + escapeHtml(suffix)
                + "</html>";
    }

    /** Escape text for the small HTML fragment emitted by the highlight renderer. */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace(" ", "&#32;");
    }

    /**
     * Truncate a path to its tail so the file name (and thus the highlighted match) stays visible,
     * prefixing an ellipsis when characters were dropped.
     */
    private static String truncatePathTail(JTable table, int column, String path) {
        int cellWidth = table.getColumnModel().getColumn(column).getWidth();
        int avail = Math.max(cellWidth - 8, 50);
        FontMetrics fm = table.getFontMetrics(table.getFont());
        if (path.isEmpty() || fm.stringWidth(path) <= avail) {
            return path;
        }
        String ellipsis = "...";
        for (int i = 0; i < path.length(); i++) {
            String tail = path.substring(i);
            if (fm.stringWidth(ellipsis + tail) <= avail) {
                return ellipsis + tail;
            }
        }
        return ellipsis;
    }

    /**
     * Open the change preview for a row: fetch the peer's version of the file if it is not already
     * cached (off the EDT, since the fetch runs a serial round-trip), then show the modal diff
     * dialog. Directory and delete operations have no content to preview, so they are reported
     * inline instead of opening an empty window.
     */
    void openChangePreview(SyncPreviewRow row) {
        if (row == null) {
            return;
        }
        if (row.getOperationType() == SyncPreviewOperationType.CREATE_DIR
                || row.getOperationType() == SyncPreviewOperationType.DELETE_DIR) {
            showPreviewMessage(
                    "Directory operation",
                    "\""
                            + row.getPath()
                            + "\" is a directory operation, so there is no file"
                            + " content to preview.");
            return;
        }

        boolean needsFetch = row.hasBaseVersion() && !row.isBaseFetched();
        if (needsFetch) {
            fetchBaseContentThenShowPreview(row);
            return;
        }
        showPreviewForRow(row, null);
    }

    /** Fetch the row's previous (peer) version, then show the preview on the EDT. */
    private void fetchBaseContentThenShowPreview(SyncPreviewRow row) {
        logSink.accept("preview: fetching previous version of " + row.getPath() + " from peer...");
        SwingWorker<byte[], Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected byte[] doInBackground() {
                        return fetchBaseContent(row);
                    }

                    @Override
                    protected void done() {
                        byte[] base = null;
                        String failure = null;
                        try {
                            base = get();
                        } catch (Exception e) {
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            failure =
                                    cause.getMessage() != null
                                            ? cause.getMessage()
                                            : cause.getClass().getSimpleName();
                        }
                        // Only a successful fetch is cached; a failure stays unfetched so the user
                        // can retry after reconnecting instead of being stuck with "unavailable".
                        if (failure == null && base != null) {
                            row.setBaseContent(base);
                            row.setBaseFetched(true);
                            logSink.accept(
                                    "preview: previous version of "
                                            + row.getPath()
                                            + " received ("
                                            + UiFormatting.formatBytes(base.length)
                                            + ")");
                        } else if (failure != null) {
                            logSink.accept(
                                    "preview: failed to fetch previous version of "
                                            + row.getPath()
                                            + " - "
                                            + failure);
                        } else {
                            logSink.accept("preview: peer has no content for " + row.getPath());
                        }
                        showPreviewForRow(row, failure);
                    }
                };
        worker.execute();
    }

    /**
     * Retrieve the peer's copy of {@code row}'s file. Returns null when the peer has nothing to
     * give (a file the receiver does not hold, or an unreachable peer); the row keeps its "not
     * fetched" state in that case so the preview explains the absence itself.
     */
    byte[] fetchBaseContent(SyncPreviewRow row) {
        if (conflictResolver == null) {
            return null;
        }
        return conflictResolver.fetchRemoteContent(row.getPath());
    }

    /** Assemble the preview model for a row and show it. */
    void showPreviewForRow(SyncPreviewRow row, String fetchFailure) {
        FileDiffPreviewModel model = buildPreviewModel(row, fetchFailure);
        logSink.accept(
                "preview: "
                        + row.getPath()
                        + " - "
                        + (model.isText() ? "text" : "binary")
                        + ", "
                        + model.describeSummary());
        FileDiffPreviewPanel.showDialog(owner, model);
    }

    /** Build the two-sided preview model, reading the local file as the new version. */
    FileDiffPreviewModel buildPreviewModel(SyncPreviewRow row, String fetchFailure) {
        byte[] source = readLocalPreviewContent(row.getPath());
        String sourceReason =
                source == null
                        ? "The local file could not be read for preview (missing, locked, or larger"
                                + " than "
                                + UiFormatting.formatBytes(MAX_PREVIEW_BYTES)
                                + ")."
                        : null;

        byte[] base = row.getBaseContent();
        boolean baseAvailable = row.hasBaseVersion();
        String baseReason = null;
        if (fetchFailure != null) {
            baseReason = "Could not retrieve the peer's version: " + fetchFailure;
        } else if (baseAvailable && base == null && row.isBaseFetched()) {
            baseReason = "The peer reports no readable content for this file.";
        } else if (baseAvailable && base == null && !row.isBaseFetched()) {
            baseReason = "The peer's version has not been retrieved yet.";
        }

        boolean truncated =
                isTruncated(source, row)
                        || isTruncated(base, row)
                        || exceedsLineBudget(source)
                        || exceedsLineBudget(base);
        return FileDiffPreviewModel.of(
                row.getPath(),
                row.getOperationType(),
                source,
                sourceReason,
                base,
                baseAvailable,
                baseReason,
                truncated);
    }

    /** Read the local file for preview, or null when unreadable or over the preview size cap. */
    byte[] readLocalPreviewContent(String relativePath) {
        if (previewSyncFolder == null || relativePath == null) {
            return null;
        }
        File file = new File(previewSyncFolder, relativePath);
        if (!file.isFile()) {
            return null;
        }
        long length = file.length();
        if (length <= 0 || length > MAX_PREVIEW_BYTES) {
            // An empty file is legitimate; treat it as empty content rather than unreadable.
            return length == 0 ? new byte[0] : null;
        }
        try {
            return java.nio.file.Files.readAllBytes(file.toPath());
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** True when this side's size exceeds the preview cap, so the shown text is incomplete. */
    private boolean isTruncated(byte[] content, SyncPreviewRow row) {
        if (content == null) {
            return false;
        }
        return row.getSizeBytes() > MAX_PREVIEW_BYTES && content.length >= MAX_PREVIEW_BYTES;
    }

    private boolean exceedsLineBudget(byte[] content) {
        if (content == null || content.length == 0) {
            return false;
        }
        String text = FileDiffPreviewModel.decodeText(content);
        if (text == null) {
            return false;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++lines > MAX_PREVIEW_LINES) {
                return true;
            }
        }
        return false;
    }

    /** Simple informational dialog for rows with no previewable content. */
    private void showPreviewMessage(String title, String message) {
        javax.swing.JOptionPane.showMessageDialog(
                owner, message, title, javax.swing.JOptionPane.INFORMATION_MESSAGE);
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

    /**
     * Renders the Preview column as a button, dimming it for operations that have no file content
     * (directory create/delete) so the affordance reflects what a click can actually do.
     */
    private static final class PreviewButtonRenderer extends javax.swing.JButton
            implements TableCellRenderer {

        private final List<SyncPreviewRow> rows;
        private final javax.swing.JLabel fallbackLabel = new javax.swing.JLabel();

        PreviewButtonRenderer(List<SyncPreviewRow> rows) {
            this.rows = rows;
            setOpaque(true);
            setFocusPainted(false);
            setMargin(PREVIEW_BUTTON_MARGIN);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {
            int modelRow = table.convertRowIndexToModel(row);
            SyncPreviewRow previewRow =
                    modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
            if (previewRow != null && !hasPreviewableContent(previewRow)) {
                fallbackLabel.setText("\u2014");
                fallbackLabel.setHorizontalAlignment(SwingConstants.CENTER);
                fallbackLabel.setForeground(new Color(140, 140, 140));
                return fallbackLabel;
            }
            setText(previewButtonLabel());
            setToolTipText(
                    previewRow != null
                            ? "Preview the changes to " + previewRow.getPath()
                            : "Preview changes");
            setHorizontalAlignment(SwingConstants.CENTER);
            return this;
        }

        private static boolean hasPreviewableContent(SyncPreviewRow row) {
            return row.getOperationType() != SyncPreviewOperationType.CREATE_DIR
                    && row.getOperationType() != SyncPreviewOperationType.DELETE_DIR;
        }
    }

    /**
     * Turns a Preview-column click into a callback. The cell edit is cancelled immediately so the
     * table never enters a real editing state; the button only exists to capture the click.
     */
    private static final class PreviewButtonEditor extends javax.swing.AbstractCellEditor
            implements TableCellEditor {

        private final JTable table;
        private final List<SyncPreviewRow> rows;
        private final java.util.function.Consumer<SyncPreviewRow> onPreview;
        private final javax.swing.JButton button = new javax.swing.JButton(previewButtonLabel());

        PreviewButtonEditor(
                JTable table,
                List<SyncPreviewRow> rows,
                java.util.function.Consumer<SyncPreviewRow> onPreview) {
            this.table = table;
            this.rows = rows;
            this.onPreview = onPreview;
            button.setFocusPainted(false);
            button.setMargin(PREVIEW_BUTTON_MARGIN);
            button.addActionListener(
                    e -> {
                        int modelRow = table.convertRowIndexToModel(table.getEditingRow());
                        cancelCellEditing();
                        if (modelRow >= 0 && modelRow < rows.size()) {
                            onPreview.accept(rows.get(modelRow));
                        }
                    });
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected, int row, int column) {
            int modelRow = table.convertRowIndexToModel(row);
            SyncPreviewRow previewRow =
                    modelRow >= 0 && modelRow < rows.size() ? rows.get(modelRow) : null;
            button.setToolTipText(
                    previewRow != null
                            ? "Preview the changes to " + previewRow.getPath()
                            : "Preview changes");
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return previewButtonLabel();
        }
    }
}
