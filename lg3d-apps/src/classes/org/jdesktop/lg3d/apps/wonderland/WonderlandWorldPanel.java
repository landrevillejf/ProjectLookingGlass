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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
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
 * describe, with the selected cell's breadcrumb in the right-hand column &mdash;
 * under a <em>vignette</em>: whichever world the picker holds, seen as a
 * picture when the server has one (its {@code /api/world/preview} art, the way
 * the original Wonderland browser listed worlds) and as a {@link WorldPlan}
 * top-down floor plan when it does not, so "what does this world look like?"
 * has an honest answer on the 2D desktop too (the walk inside it is
 * {@link WonderlandWorld3D}'s, on the 3D one). Reading is
 * deliberately the whole feature &mdash; moving or deleting a cell rides the
 * cell protocol, and a desktop tool that could edit the world behind that
 * protocol's back would be a bug report waiting to happen.</p>
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

    /**
     * The walk-in window's canvas. Deliberately larger than the panel: this is
     * the view the whole app exists to give, and a world seen through a
     * 660-pixel stamp is a thumbnail, not a place.
     */
    static final int VIEWER_WIDTH_PX = 1024;
    static final int VIEWER_HEIGHT_PX = 768;

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

        /** The world's picture, or {@code null} when it has none. */
        final BufferedImage preview;

        Loaded(String server, WorldCatalog.WorldView world, IOException failure) {
            this(server, null, world, failure, null);
        }

        Loaded(String server, String module, WorldCatalog.WorldView world,
                IOException failure) {
            this(server, module, world, failure, null);
        }

        Loaded(String server, String module, WorldCatalog.WorldView world,
                IOException failure, BufferedImage preview) {
            this.server = server;
            this.module = module;
            this.world = world;
            this.failure = failure;
            this.preview = preview;
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
    private final JButton enterButton = new JButton("Enter World");
    private final JButton startButton = new JButton("Start Server");
    private final JButton stopButton = new JButton("Stop Server");
    private final DefaultListModel<Row> cellModel = new DefaultListModel<>();
    private final JList<Row> cellList = new JList<>(cellModel);
    private final JLabel statusLabel = new JLabel(" ");

    /** The world's vignette: its picture, or its top-down plan. */
    private final WorldPreviewPanel vignette = new WorldPreviewPanel();

    // The detail column, filled when a row is selected.
    private final JLabel detailName = new JLabel(" ");
    private final JLabel detailClass = new JLabel(" ");
    private final JLabel detailId = new JLabel(" ");
    private final JLabel detailParent = new JLabel(" ");
    private final JLabel detailPath = new JLabel(" ");

    /** The transport; a scripted one in the headless tests. */
    private final WorldCatalog.Fetcher fetcher;

    /**
     * The binary transport the vignette's picture is read through. Separate from
     * {@link #fetcher} because an image is not a document; it defaults to the
     * real one, and a preview read that fails is never a world read that fails.
     */
    private WorldCatalog.ByteFetcher previewFetcher = WorldCatalog.HTTP_BYTE_FETCHER;

    /**
     * What the face needs of an open world window: raise it, close it, and hear
     * when it went away. An interface rather than the {@code WorldViewer} class
     * because a Java 3D canvas cannot be constructed in a headless JVM, and the
     * promise that Enter World opens exactly one window is a claim the suite has
     * to be able to test rather than one it has to take on faith.
     */
    interface WorldHandle {
        void focus();

        void close();

        void setOnClose(Runnable hook);
    }

    /**
     * The render path: the world's own picture, and the window that walks it.
     * A seam rather than a direct call for the same reason the transports are:
     * Java 3D has no business in a headless test, and a test that cannot name
     * what the panel asked for cannot prove the panel asked for it.
     */
    interface RenderPath {
        BufferedImage preview(String server, String module,
                WorldCatalog.WorldView world, WorldCatalog.ByteFetcher assets);

        WorldHandle open(String server, String module,
                WorldCatalog.WorldView world,
                WorldCatalog.ByteFetcher assets, int width, int height);
    }

    /** The live path: load the world's models and render them in Java 3D. */
    private RenderPath renderer = new LiveRender();

    /** The default {@link RenderPath}: {@link WorldRender}, nothing else. */
    private static final class LiveRender implements RenderPath {
        @Override
        public BufferedImage preview(String server, String module,
                WorldCatalog.WorldView world, WorldCatalog.ByteFetcher assets) {
            return WorldRender.preview(server, module, world, assets);
        }

        @Override
        public WorldHandle open(String server, String module,
                WorldCatalog.WorldView world,
                WorldCatalog.ByteFetcher assets, int width, int height) {
            org.jdesktop.wonderland.renderer.view.WorldViewer viewer =
                    WorldRender.open(server, module, world, assets, width, height);
            return new WorldHandle() {
                @Override
                public void focus() {
                    viewer.focus();
                }

                @Override
                public void close() {
                    viewer.close();
                }

                @Override
                public void setOnClose(Runnable hook) {
                    viewer.setOnClose(hook);
                }
            };
        }
    }

    /** The world the face currently shows, for the render and the walk-in. */
    private volatile WorldCatalog.WorldView current;

    /** The module {@link #current} was read under; a render must not cross worlds. */
    private volatile String currentModule;

    /** Bumped by every world read, so a slow render cannot answer a stale picker. */
    private volatile long worldGeneration;

    /** The open 3D window, or {@code null}. One per panel: re-entry raises it. */
    private volatile WorldHandle viewer;

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
        // The door into the world. Everything else on this face reads; this is
        // the one control that goes in, and it is the reason the panel exists --
        // a list of rooms you cannot walk into is an inventory, not a browser.
        enterButton.setToolTipText("Open the selected world in its own 3D window "
                + "and walk it: arrows or W/S move, A/D strafe, Q/E or the mouse "
                + "turn, PageUp/PageDown rise and sink, Home reframes the view.");
        enterButton.addActionListener(e -> enterWorld());
        // The explicit server controls: the auto-start on a failed read is a
        // convenience, but the panel must also let the operator bring Spring
        // Boot up and tear it down on demand, from the desktop, with no
        // terminal open anywhere.
        startButton.setToolTipText("Make the selected world live: start a local "
                + "wonderland-server (java -jar) on the address above, or, if a "
                + "server that does not host this world already answers there, "
                + "start ours on the next free port and follow the address.");
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
        JPanel worldButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        worldButtons.setOpaque(false);
        worldButtons.add(enterButton);
        worldButtons.add(refreshButton);
        bar.add(worldButtons, end0);

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

        // The right column is the vignette over the fields: whichever world
        // the picker holds, seen from above, then the selected cell's details.
        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        vignette.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        right.add(vignette, BorderLayout.NORTH);
        right.add(detail, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cells, right);
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
            clearVignette();
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
            clearVignette();
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
     * catalog. When the picker already holds a world, that is the world a
     * spawned server seeds &mdash; Start is how the operator answers "which
     * world is live?" without a terminal. The work is off the EDT and the
     * launcher never throws, so the press always resolves into one honest
     * status line.
     */
    private void startServer() {
        final String server = serverField.getText();
        if (launcher == null || busy || !isReadableAddress(server)) {
            return;
        }
        final String seed = selectedWorldModule(); // EDT-only combo read
        busy = true;
        startButton.setEnabled(false);
        statusLabel.setToolTipText(null);
        statusLabel.setText("Starting wonderland-server on " + server.trim()
                + " \u2026");
        fetchThread.execute(() -> {
            final WonderlandServerLauncher.Result started
                    = launcher.ensureRunning(server, seed);
            // A relocated spawn (the requested port carries somebody else's
            // unseeded server) reads from where the world actually went live.
            final String effective = started.serverUrl(server);
            final WorldsOutcome outcome =
                    started.ready() ? readWorlds(effective) : null;
            SwingUtilities.invokeLater(() -> renderStart(started, outcome));
        });
    }

    /**
     * Renders one explicit start attempt: the launcher's note when the server
     * did not come up, or the catalog read the press asked for &mdash; and if
     * the launcher had to move to a free port, the address field follows it,
     * so the panel keeps pointing at the server that actually runs the world.
     * Production calls this on the EDT; the headless suite calls it directly.
     */
    void renderStart(WonderlandServerLauncher.Result started, WorldsOutcome outcome) {
        busy = false;
        if (started.url() != null && !started.url().isBlank()) {
            serverField.setText(started.url());
        }
        if (!started.ready()) {
            setWorlds(List.of());
            cellModel.clear();
            showDetail(null);
            clearVignette();
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
            clearVignette();
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
        // The door is only worth a click once there is a world behind it, and
        // only one at a time: a second window over the same world would be a
        // second universe competing for the same canvas.
        enterButton.setEnabled(current != null && !busy && viewer == null);
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
     * world) and its preview picture; off the EDT, and never throws.
     */
    Loaded read(String server, String module) {
        try {
            WorldCatalog.WorldView world = (module == null)
                    ? WorldCatalog.load(server, fetcher)
                    : WorldCatalog.loadWorld(server, module, fetcher);
            return new Loaded(server, module, world, null, readPreview(server, module));
        } catch (IOException | RuntimeException failure) {
            return new Loaded(server, module, null, asIo(failure));
        }
    }

    // ------------------------------------------------------------------
    // Rendering (the world itself, not its inventory)
    // ------------------------------------------------------------------

    /**
     * Open whichever world the picker holds in its own navigable window. This is
     * the app's whole point: a list of rooms you cannot walk into is an
     * inventory. The read, the model loads and the Java 3D setup run on the
     * fetch thread (they are slow and must never freeze the face), and the only
     * thing that happens on the EDT is the window appearing.
     *
     * <p>Failures are stated, not swallowed: "no server", "no display" and
     * "none of this world's models loaded" are three different sentences a
     * visitor needs to hear, and a silent button is indistinguishable from a
     * broken one.</p>
     *
     * <p>Package-visible as the click handler's body, so the suite drives the
     * door itself rather than through a mouse event it cannot wait for.</p>
     */
    void enterWorld() {
        final String server = serverField.getText();
        final WorldCatalog.WorldView world = current;
        final String module = currentModule;
        if (world == null) {
            statusLabel.setText("Nothing to enter yet \u2014 read a world first");
            return;
        }
        if (!isReadableAddress(server)) {
            statusLabel.setText("Enter a server address");
            return;
        }
        WorldHandle already = viewer;
        if (already != null) {
            already.focus();
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        updateServerButtons();
        statusLabel.setToolTipText(null);
        statusLabel.setText("Entering " + nameOf(module)
                + " \u2014 loading its models \u2026");
        fetchThread.execute(() -> {
            WorldHandle shown = null;
            RuntimeException refusal = null;
            try {
                shown = renderer.open(server, module, world, previewFetcher,
                        VIEWER_WIDTH_PX, VIEWER_HEIGHT_PX);
            } catch (RuntimeException cannotRender) {
                refusal = cannotRender;
            }
            final WorldHandle opened = shown;
            final String why = (refusal == null) ? null
                    : String.valueOf(refusal.getMessage());
            SwingUtilities.invokeLater(() -> openFinished(opened, why, module));
        });
    }

    /** The EDT half of {@link #enterWorld()}: the window is up, or it is not. */
    private void openFinished(WorldHandle opened, String why, String module) {
        busy = false;
        if (opened != null) {
            viewer = opened;
            // Whoever closes the window -- the title bar, this panel's close(), a
            // crash -- puts the door back, so the button's state is the truth.
            opened.setOnClose(() -> {
                viewer = null;
                updateServerButtons();
            });
            statusLabel.setToolTipText(null);
            statusLabel.setText("Walking " + nameOf(module)
                    + " \u00B7 arrows or W/S move, A/D strafe, Q/E turn,"
                    + " PageUp/PageDown rise and sink, Home reframes");
        } else {
            statusLabel.setText("Cannot enter " + nameOf(module)
                    + (why == null ? "" : " (" + why + ")"));
            statusLabel.setToolTipText("<html>Wonderland could not show this world:"
                    + "<br><tt>" + why + "</tt><br>The world list above is still the "
                    + "server's own answer; only the view failed.</html>");
        }
        updateServerButtons();
    }

    /**
     * Ask Java 3D for one frame of the world and put it in the vignette. Only
     * ever run when the server had no picture of its own, so the render is the
     * fallback rather than a second copy of an image the server already draws.
     *
     * <p>The {@code generation} is the whole concurrency story: the picker can
     * move on while a world is still loading, and a frame of the clinic painted
     * over the pavilion is worse than no frame. A stale job skips its own work
     * and never touches the face.</p>
     */
    private void queueRender(final String server, final String module,
            final WorldCatalog.WorldView world, final long generation) {
        statusLabel.setText("Rendering " + nameOf(module)
                + " \u00B7 " + world.size() + " cells \u2026");
        fetchThread.execute(() -> {
            if (generation != worldGeneration) {
                return; // the picker moved on; this frame would paint the wrong world
            }
            final BufferedImage shot;
            try {
                shot = renderer.preview(server, module, world, previewFetcher);
            } catch (RuntimeException | Error cannotRender) {
                return; // no picture is the honest answer, and the plan stays up
            }
            if (shot == null) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (generation == worldGeneration) {
                    vignette.setPreviewImage(shot);
                }
            });
        });
    }

    /** The world's name for a status line; the module is the last resort. */
    private static String nameOf(String module) {
        return (module == null || module.isBlank()) ? "the live world" : module;
    }

    /**
     * The world's picture, decoded off the EDT, or {@code null} for "this world
     * has none". Deliberately unthrowable and silent: a preview is a courtesy,
     * so a 404, a dead server or bytes that are not an image all leave the vignette
     * on its floor plan rather than disturbing the world read beside them.
     */
    private BufferedImage readPreview(String server, String module) {
        if (module == null || module.isBlank()) {
            return null; // the live-world read names no module to ask a picture for
        }
        try {
            Optional<byte[]> bytes =
                    WorldCatalog.loadPreview(server, module, previewFetcher);
            if (bytes.isEmpty()) {
                return null;
            }
            return ImageIO.read(new ByteArrayInputStream(bytes.get()));
        } catch (IOException | RuntimeException absent) {
            return null;
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
            clearVignette();
            // No world, no door: a pressable Enter World over a status line that
            // says the server is down is the same lie as a stale picture.
            current = null;
            currentModule = null;
            worldGeneration++;
            updateServerButtons();
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
        vignette.setPreviewImage(loaded.preview);
        vignette.setPlan(WorldPlan.of(world.cells()));
        // Remember what is on screen: Enter World walks into exactly this world,
        // and a render must not answer a picker that has already moved on.
        current = world;
        currentModule = loaded.module;
        final long generation = ++worldGeneration;
        statusLabel.setToolTipText(null);
        statusLabel.setText(world.summary() + " \u00B7 " + url);
        if (cellModel.isEmpty()) {
            showDetail(null);
        } else {
            cellList.setSelectedIndex(0);
        }
        updateServerButtons();
        // The server's own picture wins when it has one; when it has none, the
        // world is asked for a frame of itself rather than left as a diagram.
        if (loaded.preview == null && loaded.module != null
                && WorldRender.hasGeometry(world)) {
            queueRender(loaded.server, loaded.module, world, generation);
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

    /**
     * Swaps the render path; the headless seam that keeps Java 3D out of the
     * suite the same way {@link #setPreviewFetcher} keeps the socket out. Not a
     * constructor argument: a panel that never renders still lists worlds, and
     * production always wants the live one.
     */
    void setRenderPath(RenderPath path) {
        this.renderer = (path == null) ? new LiveRender() : path;
    }

    /** Whether Enter World is currently pressable. For the tests. */
    boolean enterWorldEnabled() {
        return enterButton.isEnabled();
    }

    /** Whether a world window is open. For the tests. */
    boolean isInsideWorld() {
        return viewer != null;
    }

    /**
     * Wait for the work already queued on the fetch thread, then flush the EDT.
     * The only deterministic way for the suite to assert on what a background
     * pass <em>painted</em> rather than on what it will paint someday; a test
     * that sleeps instead would be a test that fails on a loaded machine.
     */
    void drainFetchThread() throws Exception {
        fetchThread.submit(() -> { }).get(15, java.util.concurrent.TimeUnit.SECONDS);
        java.awt.EventQueue.invokeAndWait(() -> { });
    }

    /** The world the face is showing, or {@code null} before the first read. */
    WorldCatalog.WorldView currentWorld() {
        return current;
    }

    /** The vignette's current plan. For the tests. */
    WorldPlan currentPlan() {
        return vignette.getPlan();
    }

    /** The vignette's current picture, or null when it shows the plan. For tests. */
    BufferedImage currentPreview() {
        return vignette.getPreviewImage();
    }

    /**
     * Empties the vignette: no picture, no plan. Every "the world is not there"
     * branch goes through here, because a stale clinic next to a status line
     * saying the server is down is exactly the lie this panel's vocabulary is
     * built to avoid.
     */
    private void clearVignette() {
        vignette.setPreviewImage(null);
        vignette.setPlan(WorldPlan.EMPTY);
    }

    /**
     * Swaps the binary transport the picture is read through; the headless
     * seam that matches {@link WorldCatalog.ByteFetcher}. Production never
     * calls it, and it is not a constructor argument because a panel with no
     * scripted cells transport has no business fetching images either.
     */
    void setPreviewFetcher(WorldCatalog.ByteFetcher fetcher) {
        this.previewFetcher = (fetcher == null)
                ? WorldCatalog.HTTP_BYTE_FETCHER : fetcher;
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
        // Down the door before the house: a viewer left open after its panel is
        // gone is a Java 3D canvas with nobody to close it.
        WorldHandle open = viewer;
        viewer = null;
        if (open != null) {
            open.close();
        }
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
