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
package org.jdesktop.lg3d.apps.wonderland;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

/**
 * The Wonderland World application's Swing face: a read-only browser over a
 * running {@code wonderland-server} world, fed by the AWT-free
 * {@link WorldCatalog} backend (the server's own {@code /api/world/cells} REST
 * view, over the JDK {@code java.net.http} client with Jackson &mdash; no
 * third-party transport).
 *
 * <p>The panel is plain Swing and touches no Java&nbsp;3D, so the one class
 * serves both desktops, exactly like the Database Manager and the Weather app:
 * in the 3D desktop the {@link WonderlandWorld} wrapper hosts it on a
 * {@code SwingNode} inside a {@code Frame3D} via {@code TitledSwingWindow}; in
 * the 2D/Swing desktop {@code Desktop2DAppRegistry.PANEL_APPS} opens the very
 * same panel as an MDI internal frame. Only layout managers and LAF-neutral
 * components are used, and the colours come from the look and feel rather than
 * hard-coded values, so it also paints correctly into the SwingNode offscreen
 * buffer.</p>
 *
 * <p>What it shows is the <em>published</em> world: the cells the server's
 * {@code WorldModel} holds, indented by the parent chain the four wire fields
 * describe, with the selected cell's breadcrumb in the right-hand column.
 * Reading is deliberately the whole feature &mdash; moving or deleting a cell
 * rides the cell protocol, and a desktop tool that could edit the world behind
 * that protocol's back would be a bug report waiting to happen.</p>
 *
 * <p>Fetching runs on one background daemon thread (never the EDT) and results
 * are handed back to the EDT to fill the list. The status line always states the
 * real HTTP outcome: a server that is not running says so instead of showing an
 * empty world as though it were loaded.</p>
 */
public class WonderlandWorldPanel extends JPanel {

    /** Panel size in native pixels; the wrapper hands these to TitledSwingWindow. */
    public static final int WIDTH_PX = 660;
    public static final int HEIGHT_PX = 420;

    /** Indent added per tree level, in pixels. */
    private static final int INDENT_PX = 16;

    /** Left indents for levels 0..7; anything deeper reuses the last one. */
    private static final Border[] INDENTS = buildIndents();

    /**
     * One list row: a published cell with the depth and breadcrumb computed from
     * the snapshot it arrived in, so the face never re-derives the tree.
     */
    static final class Row {
        final WorldCatalog.Cell cell;
        final int depth;
        final String path;

        Row(WorldCatalog.Cell cell, int depth, String path) {
            this.cell = cell;
            this.depth = depth;
            this.path = path;
        }

        @Override
        public String toString() {
            return cell.label();
        }
    }

    /** The outcome of one read, tagged with the address that was asked. */
    static final class Loaded {
        final String server;
        final WorldCatalog.WorldView world;
        final IOException failure;

        Loaded(String server, WorldCatalog.WorldView world, IOException failure) {
            this.server = server;
            this.world = world;
            this.failure = failure;
        }
    }

    private final JTextField serverField = new JTextField(24);
    private final JButton refreshButton = new JButton("Refresh");
    private final DefaultListModel<Row> cellModel = new DefaultListModel<>();
    private final JList<Row> cellList = new JList<>(cellModel);
    private final JLabel statusLabel = new JLabel(" ");

    // The detail column, filled when a row is selected.
    private final JLabel detailName = new JLabel(" ");
    private final JLabel detailClass = new JLabel(" ");
    private final JLabel detailId = new JLabel(" ");
    private final JLabel detailParent = new JLabel(" ");
    private final JLabel detailPath = new JLabel(" ");

    /** The transport; a scripted one in the headless tests. */
    private final WorldCatalog.Fetcher fetcher;

    private final ExecutorService fetchThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WonderlandWorld-fetch");
        t.setDaemon(true);
        return t;
    });

    /** True while a read is in flight, so Refresh cannot pile up requests. */
    private volatile boolean busy;

    private Runnable onClose;

    /** The desktop-facing panel: reads the configured server over HTTP. */
    public WonderlandWorldPanel() {
        this(null, true);
    }

    /**
     * @param fetcher  the transport to read through, or {@code null} for the real
     *                 JDK HTTP client
     * @param autoLoad whether to read the server as soon as the panel is built
     *                 (the desktop always does; a test may not)
     */
    WonderlandWorldPanel(WorldCatalog.Fetcher fetcher, boolean autoLoad) {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        this.fetcher = (fetcher == null) ? WorldCatalog.HTTP_FETCHER : fetcher;

        serverField.setText(WorldCatalog.defaultServer());
        // Enter in the address field means the same as the button.
        serverField.addActionListener(e -> refresh());
        refreshButton.addActionListener(e -> refresh());

        cellList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cellList.setCellRenderer(new RowRenderer());
        cellList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetail(cellList.getSelectedValue());
            }
        });

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(statusLabel, BorderLayout.SOUTH);

        if (autoLoad) {
            refresh();
        }
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));
        bar.add(new JLabel("Server"), BorderLayout.WEST);
        bar.add(serverField, BorderLayout.CENTER);
        bar.add(refreshButton, BorderLayout.EAST);
        return bar;
    }

    private JComponent buildBody() {
        JScrollPane cells = new JScrollPane(cellList);
        cells.setBorder(BorderFactory.createEtchedBorder());

        JPanel detail = new JPanel(new GridBagLayout());
        detail.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        addDetailRow(detail, 0, "Name", detailName);
        addDetailRow(detail, 1, "Type", detailClass);
        addDetailRow(detail, 2, "Cell id", detailId);
        addDetailRow(detail, 3, "Parent", detailParent);
        addDetailRow(detail, 4, "Path", detailPath);
        // An empty stretch row keeps the fields top-aligned in a tall column.
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 1;
        filler.gridy = 5;
        filler.weighty = 1.0;
        filler.fill = GridBagConstraints.BOTH;
        detail.add(new JLabel(" "), filler);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cells, detail);
        split.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        split.setResizeWeight(0.42);
        split.setDividerLocation(260);
        return split;
    }

    private static void addDetailRow(JPanel panel, int row, String field, JLabel value) {
        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 0;
        label.gridy = row;
        label.anchor = GridBagConstraints.NORTHWEST;
        label.insets = new Insets(2, 0, 2, 8);
        panel.add(new JLabel(field + ":"), label);

        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = 1;
        cell.gridy = row;
        cell.anchor = GridBagConstraints.NORTHWEST;
        cell.fill = GridBagConstraints.HORIZONTAL;
        cell.weightx = 1.0;
        cell.insets = new Insets(2, 0, 2, 0);
        panel.add(value, cell);
    }

    // ------------------------------------------------------------------
    // Reading (background thread, then one EDT hop)
    // ------------------------------------------------------------------

    /**
     * Asks the server for the world. Called from the EDT by the Refresh button
     * and by {@link #WonderlandWorldPanel()}; a request already in flight is not
     * repeated, so a double click costs one read.
     */
    private void refresh() {
        final String server = serverField.getText();
        if (!isReadableAddress(server)) {
            statusLabel.setText("Enter a server address");
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        statusLabel.setText("Reading " + safeUrl(server) + " \u2026");
        fetchThread.execute(() -> {
            final Loaded loaded = read(server);
            SwingUtilities.invokeLater(() -> {
                busy = false;
                render(loaded);
            });
        });
    }

    /** Fetches and parses; runs off the EDT, and never throws. */
    Loaded read(String server) {
        try {
            return new Loaded(server, WorldCatalog.load(server, fetcher), null);
        } catch (IOException | RuntimeException failure) {
            IOException asIo = (failure instanceof IOException)
                    ? (IOException) failure
                    : new IOException(String.valueOf(failure.getMessage()), failure);
            return new Loaded(server, null, asIo);
        }
    }

    /**
     * The address rule: a field holding nothing is not a server to read, and
     * asking for one would only produce a confusing transport error where the
     * real answer is "type an address". Package-visible so the rule the guard
     * applies is a stated, tested claim rather than a private detail.
     */
    static boolean isReadableAddress(String server) {
        return server != null && !server.trim().isEmpty();
    }

    /**
     * Writes a read outcome into the face. Production calls this on the EDT (the
     * hop at the end of {@link #refresh()}); the headless suite calls it directly,
     * since nothing is painted and the assertions are about these strings and
     * rows.
     */
    void render(Loaded loaded) {
        if (loaded.failure != null) {
            cellModel.clear();
            showDetail(null);
            String message = loaded.failure.getMessage();
            statusLabel.setText("Cannot reach " + safeUrl(loaded.server)
                    + (message == null ? "" : " (" + message + ")"));
            return;
        }
        WorldCatalog.WorldView world = loaded.world;
        cellModel.clear();
        for (WorldCatalog.Cell cell : world.cells()) {
            cellModel.addElement(
                    new Row(cell, world.depthOf(cell.id), world.pathOf(cell.id)));
        }
        statusLabel.setText(world.summary() + " \u00B7 " + safeUrl(loaded.server));
        if (cellModel.isEmpty()) {
            showDetail(null);
        } else {
            cellList.setSelectedIndex(0);
        }
    }

    /** The URL to name in the status line, or the text as typed if it will not parse. */
    private static String safeUrl(String server) {
        try {
            return WorldCatalog.cellsUrl(server);
        } catch (IllegalArgumentException notAnAddress) {
            return server == null ? "" : server.trim();
        }
    }

    private void showDetail(Row row) {
        if (row == null) {
            detailName.setText(" ");
            detailClass.setText(" ");
            detailId.setText(" ");
            detailParent.setText(" ");
            detailPath.setText(" ");
            return;
        }
        WorldCatalog.Cell cell = row.cell;
        detailName.setText(cell.label());
        detailClass.setText(cell.simpleClassName());
        detailId.setText(String.valueOf(cell.id));
        detailParent.setText((cell.parent == null) ? "\u2014 (root)" : cell.parent);
        detailPath.setText(row.path);
    }

    // ------------------------------------------------------------------
    // Presentation seams and lifecycle
    // ------------------------------------------------------------------

    /** The rows currently listed, in publication order. For the tests. */
    List<Row> rows() {
        java.util.ArrayList<Row> out = new java.util.ArrayList<>();
        for (int i = 0; i < cellModel.size(); i++) {
            out.add(cellModel.get(i));
        }
        return out;
    }

    /** What the status strip says. For the tests. */
    String statusText() {
        return statusLabel.getText();
    }

    /** The address in the field. For the tests. */
    String serverText() {
        return serverField.getText();
    }

    /** The selected row, or {@code null}. For the tests. */
    Row selectedRow() {
        return cellList.getSelectedValue();
    }

    /** The breadcrumb the detail column shows for the selection. For the tests. */
    String detailPathText() {
        return detailPath.getText();
    }

    /** Wires the 2D desktop's Close toolbar button, when the host has one. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** Stops the fetch thread and runs the registered close hook. */
    public void close() {
        fetchThread.shutdownNow();
        if (onClose != null) {
            onClose.run();
        }
    }

    /** Indents a row by its depth; the colours stay the look and feel's. */
    private static final class RowRenderer implements ListCellRenderer<Row> {
        @Override
        public JComponent getListCellRendererComponent(
                JList<? extends Row> list, Row row, int index,
                boolean isSelected, boolean cellHasFocus) {
            JLabel label = new JLabel();
            if (row == null) {
                return label;
            }
            label.setText(row.toString());
            label.setBorder(INDENTS[Math.min(row.depth, INDENTS.length - 1)]);
            label.setOpaque(isSelected);
            label.setForeground(isSelected
                    ? list.getSelectionForeground() : list.getForeground());
            if (isSelected) {
                label.setBackground(list.getSelectionBackground());
            }
            label.setToolTipText(row.cell.className);
            return label;
        }
    }

    private static Border[] buildIndents() {
        Border[] borders = new Border[8];
        for (int i = 0; i < borders.length; i++) {
            borders[i] = BorderFactory.createEmptyBorder(1, i * INDENT_PX, 1, 4);
        }
        return borders;
    }
}
