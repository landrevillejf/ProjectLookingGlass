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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
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

    /**
     * The outcome of one cells read, tagged with the address and the world
     * module that were asked ({@code module} is null for the server's live
     * world), so the status line names the exact resource that answered or
     * refused.
     */
    static final class Loaded {
        final String server;
        final String module;
        final WorldCatalog.WorldView world;
        final IOException failure;

        Loaded(String server, WorldCatalog.WorldView world, IOException failure) {
            this(server, null, world, failure);
        }

        Loaded(String server, String module, WorldCatalog.WorldView world,
                IOException failure) {
            this.server = server;
            this.module = module;
            this.world = world;
            this.failure = failure;
        }
    }

    /**
     * The outcome of one world-catalog read: the list the server offers, or the
     * reason it refused, plus (when auto-start was attempted) the launcher's
     * one-line note so the status strip can explain why a server did not appear.
     */
    static final class WorldsOutcome {
        final String server;
        final List<WorldCatalog.WorldEntry> worlds;
        final IOException failure;
        final String attemptNote;

        WorldsOutcome(String server, List<WorldCatalog.WorldEntry> worlds,
                IOException failure, String attemptNote) {
            this.server = server;
            this.worlds = worlds;
            this.failure = failure;
            this.attemptNote = attemptNote;
        }
    }

    private final JTextField serverField = new JTextField(20);
    private final JComboBox<WorldCatalog.WorldEntry> worldsCombo = new JComboBox<>();
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton startButton = new JButton("Start Server");
    private final JButton stopButton = new JButton("Stop Server");
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

    /** The local server launcher, or {@code null} to leave auto-start off. */
    private final WonderlandServerLauncher launcher;

    private final ExecutorService fetchThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WonderlandWorld-fetch");
        t.setDaemon(true);
        return t;
    });

    /** True while a read is in flight, so Refresh cannot pile up requests. */
    private volatile boolean busy;

    /** True while the world picker is being repopulated, to suppress its listener. */
    private boolean fillingWorlds;

    private Runnable onClose;

    /** The desktop-facing panel: lists the server's worlds and reads the chosen one. */
    public WonderlandWorldPanel() {
        this(null, new WonderlandServerLauncher(), true);
    }

    /**
     * A panel with no auto-start (a scripted transport, or a host that starts
     * its own server). Kept for the headless tests that used the two-arg form.
     *
     * @param fetcher  the transport to read through, or {@code null} for the real
     *                 JDK HTTP client
     * @param autoLoad whether to read the server as soon as the panel is built
     */
    WonderlandWorldPanel(WorldCatalog.Fetcher fetcher, boolean autoLoad) {
        this(fetcher, null, autoLoad);
    }

    /**
     * @param fetcher  the transport to read through, or {@code null} for the real
     *                 JDK HTTP client
     * @param launcher the local server launcher, or {@code null} to keep
     *                 auto-start off (the address must be served already)
     * @param autoLoad whether to read the server as soon as the panel is built
     */
    WonderlandWorldPanel(WorldCatalog.Fetcher fetcher, WonderlandServerLauncher launcher,
            boolean autoLoad) {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        this.fetcher = (fetcher == null) ? WorldCatalog.HTTP_FETCHER : fetcher;
        this.launcher = launcher;

        serverField.setText(WorldCatalog.defaultServer());
        // Enter in the address field means the same as the button: re-read the
        // catalog from wherever the user just pointed it.
        serverField.addActionListener(e -> refresh());
        refreshButton.addActionListener(e -> refresh());
        // The explicit server controls: the auto-start on a failed read is a
        // convenience, but the panel must also let the operator bring Spring
        // Boot up and tear it down on demand, from the desktop, with no
        // terminal open anywhere.
        startButton.setToolTipText("Start a local wonderland-server (java -jar) "
                + "on the address above, then read its worlds.");
        startButton.addActionListener(e -> startServer());
        stopButton.setToolTipText("Stop the wonderland-server this panel "
                + "started. A server you started elsewhere is left to its owner.");
        stopButton.addActionListener(e -> stopServer());
        worldsCombo.setRenderer(new WorldRenderer());
        worldsCombo.addActionListener(e -> {
            if (!fillingWorlds) {
                loadSelectedWorld();
            }
        });

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
        updateServerButtons();

        if (autoLoad) {
            refresh();
        }
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private JComponent buildToolbar() {
        // Two rows: the world picker drives what is read, the server field is
        // the configuration underneath it. Both grow horizontally so a long
        // address or world title stays readable in a narrow window.
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 2, 8));

        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 0;
        label.insets = new Insets(2, 0, 2, 6);
        label.anchor = GridBagConstraints.WEST;
        GridBagConstraints grow = new GridBagConstraints();
        grow.gridx = 1;
        grow.weightx = 1.0;
        grow.fill = GridBagConstraints.HORIZONTAL;
        grow.insets = new Insets(2, 0, 2, 6);
        GridBagConstraints end = new GridBagConstraints();
        end.gridx = 2;
        end.insets = new Insets(2, 0, 2, 0);
        end.anchor = GridBagConstraints.EAST;

        GridBagConstraints row0 = (GridBagConstraints) label.clone();
        GridBagConstraints grow0 = (GridBagConstraints) grow.clone();
        GridBagConstraints end0 = (GridBagConstraints) end.clone();
        row0.gridy = 0;
        grow0.gridy = 0;
        end0.gridy = 0;
        bar.add(new JLabel("World"), row0);
        bar.add(worldsCombo, grow0);
        bar.add(refreshButton, end0);

        GridBagConstraints row1 = (GridBagConstraints) label.clone();
        GridBagConstraints grow1 = (GridBagConstraints) grow.clone();
        GridBagConstraints end1 = (GridBagConstraints) end.clone();
        row1.gridy = 1;
        grow1.gridy = 1;
        end1.gridy = 1;
        bar.add(new JLabel("Server"), row1);
        bar.add(serverField, grow1);
        JPanel serverButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        serverButtons.setOpaque(false);
        serverButtons.add(startButton);
        serverButtons.add(stopButton);
        bar.add(serverButtons, end1);
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
     * Re-reads the server's world catalog into the picker. Called from the EDT
     * by the Refresh button, the address field and {@link #WonderlandWorldPanel()};
     * a read already in flight is not repeated. Once the list arrives the first
     * world is opened, so a first launch lands on a real world rather than an
     * empty picker.
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
        statusLabel.setToolTipText(null);
        statusLabel.setText("Reading worlds from " + WorldCatalog.worldsUrl(server)
                + " \u2026");
        fetchThread.execute(() -> {
            final WorldsOutcome outcome = readWorlds(server);
            SwingUtilities.invokeLater(() -> renderWorlds(outcome));
        });
    }

    /** Fetches and parses the world catalog; off the EDT, and never throws. */
    WorldsOutcome readWorlds(String server) {
        try {
            return new WorldsOutcome(server, WorldCatalog.loadWorlds(server, fetcher),
                    null, null);
        } catch (IOException | RuntimeException first) {
            // The common first-launch failure is that no server is up. If a
            // launcher was given, bring one up for a local address and retry
            // once; otherwise the failure is the honest end of the road.
            if (launcher == null) {
                return new WorldsOutcome(server, null, asIo(first), null);
            }
            WonderlandServerLauncher.Result started = launcher.ensureRunning(server);
            if (!started.ready()) {
                return new WorldsOutcome(server, null, asIo(first), started.note());
            }
            try {
                return new WorldsOutcome(server, WorldCatalog.loadWorlds(server, fetcher),
                        null, started.note());
            } catch (IOException | RuntimeException again) {
                return new WorldsOutcome(server, null, asIo(again), started.note());
            }
        }
    }

    /**
     * Fills the picker from a catalog read and opens the first world. A failure
     * clears the stale list and explains why, naming the command that starts a
     * server (and, if auto-start was tried, why it did not come up).
     *
     * <p>Production calls this on the EDT; the headless suite calls it directly.
     * Package-visible so the worlds flow is a tested claim, not a private one.</p>
     */
    void renderWorlds(WorldsOutcome outcome) {
        busy = false;
        updateServerButtons();
        if (outcome.failure != null) {
            setWorlds(List.of());
            cellModel.clear();
            showDetail(null);
            String message = outcome.failure.getMessage();
            String reach = "Cannot reach " + safeUrl(outcome.server)
                    + (message == null ? "" : " (" + message + ")");
            String hint = (outcome.attemptNote == null)
                    ? "  \u2014  start it: " + WorldCatalog.startCommand()
                    : "  \u2014  auto-start: " + outcome.attemptNote;
            statusLabel.setText(reach + hint);
            statusLabel.setToolTipText("<html>No <code>wonderland-server</code> answered "
                    + safeUrl(outcome.server) + ". Run this in a terminal at the "
                    + "repository root:<br><tt>"
                    + WorldCatalog.startCommand() + "</tt></html>");
            return;
        }
        setWorlds(outcome.worlds);
        if (outcome.worlds.isEmpty()) {
            cellModel.clear();
            showDetail(null);
            statusLabel.setToolTipText(null);
            statusLabel.setText("no worlds published \u00B7 " + safeUrl(outcome.server));
            return;
        }
        loadSelectedWorld();
    }

    /** Repopulates the picker without firing the selection listener mid-fill. */
    private void setWorlds(List<WorldCatalog.WorldEntry> worlds) {
        fillingWorlds = true;
        try {
            worldsCombo.removeAllItems();
            for (WorldCatalog.WorldEntry world : worlds) {
                worldsCombo.addItem(world);
            }
            if (!worlds.isEmpty()) {
                worldsCombo.setSelectedIndex(0);
            }
        } finally {
            fillingWorlds = false;
        }
        worldsCombo.setEnabled(!worlds.isEmpty());
    }

    /**
     * The explicit Start press: bring up (or confirm) a local
     * wonderland-server for the address in the field, then read its world
     * catalog. The work is off the EDT and the launcher never throws, so the
     * press always resolves into one honest status line.
     */
    private void startServer() {
        final String server = serverField.getText();
        if (launcher == null || busy || !isReadableAddress(server)) {
            return;
        }
        busy = true;
        startButton.setEnabled(false);
        statusLabel.setToolTipText(null);
        statusLabel.setText("Starting wonderland-server on " + server.trim()
                + " \u2026");
        fetchThread.execute(() -> {
            final WonderlandServerLauncher.Result started
                    = launcher.ensureRunning(server);
            final WorldsOutcome outcome =
                    started.ready() ? readWorlds(server) : null;
            SwingUtilities.invokeLater(() -> renderStart(started, outcome));
        });
    }

    /**
     * Renders one explicit start attempt: the launcher's note when the server
     * did not come up, or the catalog read the press asked for. Production
     * calls this on the EDT; the headless suite calls it directly.
     */
    void renderStart(WonderlandServerLauncher.Result started, WorldsOutcome outcome) {
        busy = false;
        if (!started.ready()) {
            setWorlds(List.of());
            cellModel.clear();
            showDetail(null);
            statusLabel.setToolTipText(null);
            statusLabel.setText("cannot start wonderland-server \u2014 "
                    + started.note());
            updateServerButtons();
            return;
        }
        updateServerButtons();
        if (outcome != null) {
            renderWorlds(outcome);
        } else {
            statusLabel.setToolTipText(null);
            statusLabel.setText(started.note());
        }
    }

    /**
     * The explicit Stop press. Only a server this panel started is its child
     * to reap; anything the operator launched elsewhere (bootRun, a terminal)
     * stays running and the status line says so honestly. Returns the note
     * shown, for the tests.
     */
    String stopServer() {
        if (launcher == null) {
            return "no launcher";
        }
        boolean owned = launcher.isManaging();
        launcher.shutdown();
        if (owned) {
            setWorlds(List.of());
            cellModel.clear();
            showDetail(null);
        }
        String note = owned
                ? "wonderland-server stopped \u00B7 Start Server brings one back"
                : "this panel only stops a server it started \u00B7 the one at "
                        + serverField.getText().trim() + " is not its child";
        statusLabel.setToolTipText(null);
        statusLabel.setText(note);
        updateServerButtons();
        return note;
    }

    /** Reflects the launcher's state onto the two server buttons. */
    private void updateServerButtons() {
        startButton.setEnabled(launcher != null && !busy);
        stopButton.setEnabled(launcher != null && launcher.isManaging());
    }

    /** Reads the cells of whichever world the picker now holds. */
    private void loadSelectedWorld() {
        Object selected = worldsCombo.getSelectedItem();
        if (selected == null) {
            return;
        }
        final String server = serverField.getText();
        if (!isReadableAddress(server) || busy) {
            return;
        }
        final String module = ((WorldCatalog.WorldEntry) selected).module();
        busy = true;
        statusLabel.setToolTipText(null);
        statusLabel.setText("Reading " + safeModuleUrl(server, module) + " \u2026");
        fetchThread.execute(() -> {
            final Loaded loaded = read(server, module);
            SwingUtilities.invokeLater(() -> {
                busy = false;
                render(loaded);
            });
        });
    }

    /** Fetches and parses the live world; runs off the EDT, and never throws. */
    Loaded read(String server) {
        return read(server, null);
    }

    /**
     * Fetches and parses one world's cells ({@code module} null reads the live
     * world); off the EDT, and never throws.
     */
    Loaded read(String server, String module) {
        try {
            WorldCatalog.WorldView world = (module == null)
                    ? WorldCatalog.load(server, fetcher)
                    : WorldCatalog.loadWorld(server, module, fetcher);
            return new Loaded(server, module, world, null);
        } catch (IOException | RuntimeException failure) {
            return new Loaded(server, module, null, asIo(failure));
        }
    }

    /** Wraps any transport failure as an IOException for the status strip. */
    private static IOException asIo(Throwable failure) {
        return (failure instanceof IOException)
                ? (IOException) failure
                : new IOException(String.valueOf(failure.getMessage()), failure);
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
        final String url = (loaded.module == null)
                ? safeUrl(loaded.server) : safeModuleUrl(loaded.server, loaded.module);
        if (loaded.failure != null) {
            cellModel.clear();
            showDetail(null);
            String message = loaded.failure.getMessage();
            String reach = "Cannot reach " + url
                    + (message == null ? "" : " (" + message + ")");
            // The usual cause is not a wrong address but no server: the desktop
            // deliberately leaves wonderland-server a separate process, so name
            // the command that brings one up instead of dead-ending on a bare
            // transport error. The strip carries it; the tooltip repeats it in
            // full so a narrow window still shows the whole line.
            statusLabel.setText(reach + "  \u2014  start it: " + WorldCatalog.startCommand());
            statusLabel.setToolTipText("<html>No <code>wonderland-server</code> answered "
                    + url + ". Run this in a terminal at the repository root:<br><tt>"
                    + WorldCatalog.startCommand() + "</tt></html>");
            return;
        }
        WorldCatalog.WorldView world = loaded.world;
        cellModel.clear();
        for (WorldCatalog.Cell cell : world.cells()) {
            cellModel.addElement(
                    new Row(cell, world.depthOf(cell.id), world.pathOf(cell.id)));
        }
        statusLabel.setToolTipText(null);
        statusLabel.setText(world.summary() + " \u00B7 " + url);
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

    /** The per-world cells URL to name in the status line, tolerant of a bad address. */
    private static String safeModuleUrl(String server, String module) {
        try {
            return WorldCatalog.cellsUrl(server, module);
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

    /** How many worlds the picker currently offers. For the tests. */
    int worldCount() {
        return worldsCombo.getItemCount();
    }

    /** The module of the world the picker holds, or {@code null}. For the tests. */
    String selectedWorldModule() {
        Object selected = worldsCombo.getSelectedItem();
        return (selected == null) ? null : ((WorldCatalog.WorldEntry) selected).module();
    }

    /** Whether Start Server is currently pressable. For the tests. */
    boolean serverStartEnabled() {
        return startButton.isEnabled();
    }

    /** Whether Stop Server is currently pressable. For the tests. */
    boolean serverStopEnabled() {
        return stopButton.isEnabled();
    }

    /** Wires the 2D desktop's Close toolbar button, when the host has one. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /**
     * Stops the fetch thread, tears down a server this panel auto-started, and
     * runs the registered close hook. A server the panel did not start is left
     * alone &mdash; stopping somebody else's process would be worse than leaking
     * one the launcher never owned.
     */
    public void close() {
        fetchThread.shutdownNow();
        if (launcher != null) {
            launcher.shutdown();
        }
        if (onClose != null) {
            onClose.run();
        }
    }

    /** Shows a world entry by its title (module) rather than the record's toString. */
    private static final class WorldRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                javax.swing.JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof WorldCatalog.WorldEntry world) {
                setText(world.displayLabel());
                String description = world.description();
                setToolTipText((description == null || description.isBlank())
                        ? null : world.title() + " \u2014 " + description);
            }
            return this;
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
