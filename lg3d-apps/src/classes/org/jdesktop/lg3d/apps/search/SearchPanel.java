/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.search;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.jdesktop.lg3d.utils.search.SearchEngine;
import org.jdesktop.lg3d.utils.search.SearchHandle;
import org.jdesktop.lg3d.utils.search.SearchMatch;
import org.jdesktop.lg3d.utils.search.SearchQuery;
import org.jdesktop.lg3d.utils.system.Opener;

/**
 * The Advanced Search panel: a production file-and-content finder for the desktop
 * and the (minimal) LFS system. It streams results live from
 * {@link SearchEngine} into a ranked table, offers name (contains / glob / regex),
 * content (grep), type, size and recency filters over one or more scopes, and
 * opens a hit or its containing folder on demand.
 *
 * <p>The same panel serves both desktops: the 3D desktop hosts it on a
 * {@code SwingNode} via {@link Search}, and the 2D/Swing desktop hosts it as an
 * MDI internal frame through {@code Desktop2DAppRegistry}. It therefore uses only
 * SwingNode-safe widgets ({@link JList}, plain {@link JButton}, {@link JTextField},
 * {@link JTable}, {@link JTextArea}) &mdash; never {@code JComboBox}, check boxes
 * or radio buttons, whose Synth peers NPE when painted offscreen.</p>
 *
 * <p>{@code Ctrl+Shift+F} is the desktop-wide accelerator that opens this app; the
 * panel also binds {@code Ctrl+F} (and {@code Ctrl+Shift+F} for the 3D host, where
 * no desktop dispatcher consumes it) to focus and select the query field.</p>
 */
public class SearchPanel extends JPanel {

    /** Preferred width in pixels. */
    public static final int WIDTH_PX = 1000;
    /** Preferred height in pixels. */
    public static final int HEIGHT_PX = 660;

    private static final String[] NAME_MODES = {"Contains", "Glob", "Regex"};
    private static final String[] KINDS = {"All", "Files", "Folders"};
    private static final String[] COLUMNS = {"Name", "Location", "Size", "Modified", "Score"};

    private final SearchEngine engine = new SearchEngine();
    private final List<SearchMatch> results = new ArrayList<>();
    private final List<SearchFormat.Scope> scopes = SearchFormat.defaultScopes();

    private final JTextField queryField = new JTextField(28);
    private final JTextField contentField = new JTextField(18);
    private final JTextField minSizeField = new JTextField(6);
    private final JTextField maxSizeField = new JTextField(6);
    private final JTextField daysField = new JTextField(4);
    private final JList<SearchFormat.Scope> scopeList =
            new JList<>(scopes.toArray(new SearchFormat.Scope[0]));
    private final JList<String> nameModeList = new JList<>(NAME_MODES);
    private final JList<String> kindList = new JList<>(KINDS);
    private final JButton caseButton = new JButton("Match case: Off");
    private final JButton hiddenButton = new JButton("Hidden files: Off");
    private final JButton contentButton = new JButton("Search contents: Off");
    private final JButton searchButton = new JButton("Search");
    private final JButton stopButton = new JButton("Stop");
    private final JButton openButton = new JButton("Open");
    private final JButton openFolderButton = new JButton("Open Folder");
    private final JLabel statusLabel = new JLabel("Ready. Ctrl+Shift+F opens Search from anywhere.");
    private final JProgressBar progress = new JProgressBar();
    private final DefaultTableModel tableModel = new NonEditableTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextArea details = new JTextArea(6, 40);

    private boolean caseSensitive;
    private boolean includeHidden;
    private boolean contentSearch;
    private SearchHandle handle;
    private long startedAt;

    /** Builds the panel with its default (Home scope, contains) filters. */
    public SearchPanel() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        scopeList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        scopeList.setSelectedIndex(0);
        nameModeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nameModeList.setSelectedIndex(0);
        kindList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        kindList.setSelectedIndex(0);

        add(buildQueryBar(), BorderLayout.NORTH);
        add(buildFilters(), BorderLayout.WEST);
        add(buildResults(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        wireActions();
        installKeyBindings();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private JComponent buildQueryBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.add(new JLabel("Search:"));
        bar.add(queryField);
        bar.add(searchButton);
        bar.add(stopButton);
        progress.setIndeterminate(false);
        progress.setPreferredSize(new Dimension(120, 18));
        bar.add(progress);
        stopButton.setEnabled(false);
        return bar;
    }

    private JComponent buildFilters() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Filters"));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(3, 4, 3, 4);
        int row = 0;

        c.gridy = row++;
        panel.add(new JLabel("Look in:"), c);
        c.gridy = row++;
        c.weighty = 0.4;
        panel.add(new JScrollPane(scopeList), c);
        c.weighty = 0.0;

        c.gridy = row++;
        panel.add(new JLabel("Name match:"), c);
        c.gridy = row++;
        panel.add(nameModeList, c);

        c.gridy = row++;
        panel.add(new JLabel("Type:"), c);
        c.gridy = row++;
        panel.add(kindList, c);

        c.gridy = row++;
        panel.add(caseButton, c);
        c.gridy = row++;
        panel.add(hiddenButton, c);
        c.gridy = row++;
        panel.add(contentButton, c);

        c.gridy = row++;
        panel.add(new JLabel("Contains text:"), c);
        c.gridy = row++;
        panel.add(contentField, c);

        c.gridy = row++;
        panel.add(new JLabel("Min size (e.g. 10K):"), c);
        c.gridy = row++;
        panel.add(minSizeField, c);
        c.gridy = row++;
        panel.add(new JLabel("Max size:"), c);
        c.gridy = row++;
        panel.add(maxSizeField, c);
        c.gridy = row++;
        panel.add(new JLabel("Modified within (days):"), c);
        c.gridy = row++;
        panel.add(daysField, c);

        // Spacer pushes the filters to the top.
        c.gridy = row;
        c.weighty = 1.0;
        panel.add(new JLabel(), c);
        panel.setPreferredSize(new Dimension(230, HEIGHT_PX));
        return panel;
    }

    private JComponent buildResults() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(320);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(150);
        table.getColumnModel().getColumn(4).setPreferredWidth(60);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelected();
                }
            }
        });

        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setBorder(BorderFactory.createTitledBorder("Content preview"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), new JScrollPane(details));
        split.setResizeWeight(0.72);
        split.setDividerLocation(360);
        return split;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(openButton);
        buttons.add(openFolderButton);
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    // ------------------------------------------------------------------
    // Behaviour
    // ------------------------------------------------------------------

    private void wireActions() {
        searchButton.addActionListener(e -> startSearch());
        stopButton.addActionListener(e -> stopSearch());
        queryField.addActionListener(e -> startSearch());
        openButton.addActionListener(e -> openSelected());
        openFolderButton.addActionListener(e -> openSelectedFolder());
        caseButton.addActionListener(e -> {
            caseSensitive = !caseSensitive;
            caseButton.setText("Match case: " + onOff(caseSensitive));
        });
        hiddenButton.addActionListener(e -> {
            includeHidden = !includeHidden;
            hiddenButton.setText("Hidden files: " + onOff(includeHidden));
        });
        contentButton.addActionListener(e -> {
            contentSearch = !contentSearch;
            contentButton.setText("Search contents: " + onOff(contentSearch));
        });
        table.getSelectionModel().addListSelectionListener(e -> showDetails());
    }

    private void installKeyBindings() {
        AbstractAction focus = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                queryField.requestFocusInWindow();
                queryField.selectAll();
            }
        };
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("control F"), "focus-search");
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("control shift F"), "focus-search");
        getActionMap().put("focus-search", focus);
    }

    /** Starts (or restarts) a search from the current field state. */
    void startSearch() {
        if (handle != null && !handle.isDone()) {
            handle.cancel();
        }
        results.clear();
        tableModel.setRowCount(0);
        details.setText("");
        startedAt = System.currentTimeMillis();
        progress.setIndeterminate(true);
        searchButton.setEnabled(false);
        stopButton.setEnabled(true);
        statusLabel.setText("Searching\u2026");

        SearchQuery query = buildQuery();
        handle = engine.search(query,
                match -> SwingUtilities.invokeLater(() -> appendMatch(match)),
                () -> SwingUtilities.invokeLater(this::finishSearch));
    }

    private void stopSearch() {
        if (handle != null) {
            handle.cancel();
        }
    }

    private void appendMatch(SearchMatch match) {
        results.add(match);
        tableModel.addRow(rowFor(match));
        statusLabel.setText(results.size() + " match"
                + (results.size() == 1 ? "" : "es") + " so far\u2026");
    }

    private void finishSearch() {
        progress.setIndeterminate(false);
        searchButton.setEnabled(true);
        stopButton.setEnabled(false);
        // Present the streamed hits best-first now that the walk is complete.
        results.sort(Comparator.comparingInt(SearchMatch::score).reversed()
                .thenComparing((SearchMatch m) -> m.name()));
        tableModel.setRowCount(0);
        for (SearchMatch m : results) {
            tableModel.addRow(rowFor(m));
        }
        double seconds = (System.currentTimeMillis() - startedAt) / 1000.0;
        statusLabel.setText(String.format("%d match%s in %.2fs",
                results.size(), results.size() == 1 ? "" : "es", seconds));
    }

    private Object[] rowFor(SearchMatch m) {
        return new Object[] {
            (m.directory() ? "\uD83D\uDCC1 " : "") + m.name(),
            SearchFormat.locationOf(m.path()),
            m.directory() ? "\u2014" : SearchFormat.formatSize(m.size()),
            SearchFormat.formatTimestamp(m.lastModifiedMillis()),
            m.score(),
        };
    }

    private void showDetails() {
        SearchMatch m = selectedMatch();
        if (m == null) {
            details.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder(m.path().toString()).append("\n\n");
        if (m.hits().isEmpty()) {
            sb.append(m.directory() ? "Folder" : "File")
                    .append("  \u2022  ").append(SearchFormat.formatSize(m.size()));
        } else {
            sb.append(m.hits().size()).append(" matching line")
                    .append(m.hits().size() == 1 ? "" : "s").append(":\n");
            for (var hit : m.hits()) {
                sb.append("  ").append(hit.line()).append(": ").append(hit.snippet()).append('\n');
            }
        }
        details.setText(sb.toString());
        details.setCaretPosition(0);
    }

    private SearchMatch selectedMatch() {
        int view = table.getSelectedRow();
        if (view < 0) {
            return null;
        }
        int model = table.convertRowIndexToModel(view);
        return (model >= 0 && model < results.size()) ? results.get(model) : null;
    }

    private void openSelected() {
        SearchMatch m = selectedMatch();
        if (m != null) {
            Opener.open(m.path());
        }
    }

    private void openSelectedFolder() {
        SearchMatch m = selectedMatch();
        if (m == null) {
            return;
        }
        Path folder = m.directory() ? m.path() : m.path().getParent();
        if (folder != null) {
            Opener.open(folder);
        }
    }

    /**
     * Builds the immutable {@link SearchQuery} from the current widget state. The
     * selected scopes become the roots (Home when nothing is selected); blank or
     * malformed size / day fields simply drop that bound.
     */
    SearchQuery buildQuery() {
        SearchQuery.Builder b = SearchQuery.builder()
                .roots(selectedRoots())
                .nameMode(nameModeFor(nameModeList.getSelectedIndex()))
                .namePattern(queryField.getText())
                .caseSensitive(caseSensitive)
                .includeHidden(includeHidden)
                .kind(kindFor(kindList.getSelectedIndex()))
                .contentSearch(contentSearch)
                .contentPattern(contentField.getText())
                .contentRegex(nameModeList.getSelectedIndex() == 2)
                .minSize(SearchFormat.parseSize(minSizeField.getText()))
                .maxSize(SearchFormat.parseSize(maxSizeField.getText()))
                .modifiedWithinDays(days());
        return b.build();
    }

    private OptionalInt days() {
        return SearchFormat.parseDays(daysField.getText());
    }

    private List<Path> selectedRoots() {
        List<SearchFormat.Scope> selected = scopeList.getSelectedValuesList();
        List<Path> roots = new ArrayList<>();
        for (SearchFormat.Scope s : selected) {
            roots.add(s.path());
        }
        if (roots.isEmpty() && !scopes.isEmpty()) {
            roots.add(scopes.get(0).path());
        }
        return roots;
    }

    static SearchQuery.NameMode nameModeFor(int index) {
        switch (index) {
            case 1:
                return SearchQuery.NameMode.GLOB;
            case 2:
                return SearchQuery.NameMode.REGEX;
            case 0:
            default:
                return SearchQuery.NameMode.SUBSTRING;
        }
    }

    static SearchQuery.Kind kindFor(int index) {
        switch (index) {
            case 1:
                return SearchQuery.Kind.FILES;
            case 2:
                return SearchQuery.Kind.DIRECTORIES;
            case 0:
            default:
                return SearchQuery.Kind.ANY;
        }
    }

    private static String onOff(boolean b) {
        return b ? "On" : "Off";
    }

    // -- package-private test hooks -----------------------------------

    /** The query text field, exposed for headless tests. */
    JTextField queryField() {
        return queryField;
    }

    /** The min-size field, exposed for headless tests. */
    JTextField minSizeField() {
        return minSizeField;
    }

    /** A table model that reports every cell as non-editable. */
    private static final class NonEditableTableModel extends DefaultTableModel {
        NonEditableTableModel() {
            super(COLUMNS, 0);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    }
}
