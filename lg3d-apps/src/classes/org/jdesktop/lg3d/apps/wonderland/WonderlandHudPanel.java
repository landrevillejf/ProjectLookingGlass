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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.jdesktop.wonderland.desktop.CellFrameBridge;
import org.jdesktop.wonderland.desktop.WorldHudModel;
import org.jdesktop.wonderland.desktop.WorldInventory;

/**
 * The desktop's world HUD: the Swing face of {@code wonderland-desktop}'s
 * {@link WorldHudModel}, showing what the live world contains and what of it is
 * actually in front of the user.
 *
 * <p>Where {@link WonderlandWorldPanel} is the shell's read-only inventory of a
 * server, this panel is the <em>3D</em> half of the M6c2 integration: it is
 * opened by {@link WonderlandWorld3D} next to the mounted world, and every row
 * joins two truths that arrive from different places &mdash; the published
 * inventory (through {@link WorldCatalogInventory}) and the scene-graph state
 * the {@link CellFrameBridge} owns. That join is the model's, not the panel's,
 * so the awkward cases (a mounted cell the world never published, a parent
 * chain that loops, a nameless cell) are asserted headlessly in
 * {@code WorldHudModelTest} rather than hunted for in a screenshot.</p>
 *
 * <p>What the user may do is hide and show. Only hide and show: putting a cell
 * into the scene graph is the client session's decision (it arrives through
 * {@code WonderlandDesktop.cellLoaded}) and taking one out is the server's, so a
 * button that mounted a cell would be a control that lies. A cell the world
 * published but the session has not loaded is listed as pending and its toggle
 * stays disabled &mdash; the honest answer to "can I show this?" when there is
 * nothing to show.</p>
 *
 * <p>Plain Swing, no Java&nbsp;3D: the 3D desktop hosts it on a
 * {@code SwingNode} inside a {@code Frame3D} through
 * {@link TitledSwingWindow#show}, so only layout managers, LAF-neutral
 * components and look-and-feel colours are used.</p>
 */
public final class WonderlandHudPanel extends JPanel {

    /** The window size the {@link TitledSwingWindow} host asks for. */
    public static final int WIDTH_PX = 460;

    /** See {@link #WIDTH_PX}. */
    public static final int HEIGHT_PX = 360;

    /** Pixels of left indent per level of the cell hierarchy. */
    private static final int INDENT_PX = 16;

    /**
     * Where the panel reads the world's published inventory. Returning the
     * bridge's mount state is not this interface's job -- the model joins the
     * two, so a reader only has to answer "what does the world publish?".
     */
    public interface InventoryReader {

        /**
         * @return the published inventory, never null
         * @throws IOException when the world cannot be read
         */
        WorldInventory read() throws IOException;
    }

    private final WorldHudModel model;
    private final CellFrameBridge bridge;
    private final InventoryReader reader;

    private final DefaultListModel<WorldHudModel.Row> rowModel =
            new DefaultListModel<>();
    private final JList<WorldHudModel.Row> list =
            new JList<>(rowModel);
    private final JButton toggleButton = new JButton("Show");
    private final JButton refreshButton = new JButton("Refresh");
    private final JLabel statusLabel = new JLabel(" ", SwingConstants.LEFT);

    /** One daemon reader thread: a refresh that outlives the window is a leak. */
    private final ExecutorService readThread =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "WonderlandHud-read");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean busy;
    private Runnable onClose;
    private boolean closed;

    /**
     * Builds the HUD and reads the world once, on a background thread.
     *
     * @param model  the join of inventory and mount state; owned by the caller,
     *               which is also the object the live session updates
     * @param bridge the mount / visibility truth the toggles write through
     * @param reader where the published inventory comes from
     */
    public WonderlandHudPanel(WorldHudModel model, CellFrameBridge bridge,
            InventoryReader reader) {
        this(model, bridge, reader, true);
    }

    /**
     * @param autoRead false to build the panel without touching the reader --
     *                 what lets a headless test drive {@link #apply} directly
     *                 instead of racing the EDT
     */
    WonderlandHudPanel(WorldHudModel model, CellFrameBridge bridge,
            InventoryReader reader, boolean autoRead) {
        if (model == null || bridge == null || reader == null) {
            throw new IllegalArgumentException(
                    "model, bridge and reader must not be null");
        }
        this.model = model;
        this.bridge = bridge;
        this.reader = reader;

        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        toolbar.add(refreshButton);
        toolbar.add(toggleButton);
        add(toolbar, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new RowRenderer());
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateToggle();
            }
        });
        add(new JScrollPane(list), BorderLayout.CENTER);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        add(statusLabel, BorderLayout.SOUTH);

        refreshButton.addActionListener(event -> refresh());
        toggleButton.addActionListener(event -> toggleSelected());

        // Paint the mounted truth immediately: the session may already have
        // cells in the scene graph before the first read lands, and a HUD that
        // shows nothing while the world is on screen is a HUD that is wrong.
        repaintRows();

        if (autoRead) {
            refresh();
        } else {
            statusLabel.setText("World not read yet");
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /**
     * Reads the published inventory again in the background. A second call while
     * a read is in flight is ignored rather than queued, so hammering Refresh
     * cannot pile up HTTP calls against one world.
     */
    public void refresh() {
        if (busy || closed) {
            return;
        }
        busy = true;
        statusLabel.setText("Reading the world …");
        readThread.execute(() -> {
            WorldInventory inventory = null;
            IOException failure = null;
            try {
                inventory = reader.read();
            } catch (IOException | RuntimeException err) {
                failure = asIo(err);
            }
            final WorldInventory read = inventory;
            final IOException failed = failure;
            SwingUtilities.invokeLater(() -> {
                busy = false;
                apply(read, failed);
            });
        });
    }

    /**
     * Hides or shows the selected cell through the bridge, then re-joins. The
     * bridge is the only object that knows what is really in the scene graph, so
     * the panel never keeps its own visibility guess.
     */
    void toggleSelected() {
        WorldHudModel.Row row = list.getSelectedValue();
        if (row == null || row.state() == WorldHudModel.CellState.PENDING) {
            return;
        }
        boolean show = row.state() == WorldHudModel.CellState.HIDDEN;
        bridge.setCellVisible(row.cellId(), show);
        repaintRows();
        // The census follows the scene graph, in the same breath: a hide that
        // left "1 shown" on the strip would be the panel contradicting itself.
        statusLabel.setText(model.summary());
    }

    /**
     * Applies one read outcome on the event thread. Visible for tests: driving
     * this directly is what keeps the suite free of sleeps and EDT races.
     *
     * @param inventory the published cells, or null when the read failed
     * @param failure   why it failed, or null on success
     */
    void apply(WorldInventory inventory, IOException failure) {
        if (failure != null) {
            // Keep the inventory the panel already has: a refresh that fails is
            // not evidence that the world emptied, and the mounted rows stay
            // true whatever the read does.
            statusLabel.setText("Cannot read the world ("
                    + failure.getMessage() + ")");
            repaintRows();
            return;
        }
        model.setInventory(inventory);
        statusLabel.setText(model.summary());
        repaintRows();
    }

    /** Rebuilds the list from the model, keeping the selection where it still exists. */
    private void repaintRows() {
        String selectedId = selectedCellId();
        rowModel.clear();
        List<WorldHudModel.Row> rows = model.rows();
        for (WorldHudModel.Row row : rows) {
            rowModel.addElement(row);
        }
        if (selectedId != null) {
            for (int i = 0; i < rows.size(); i++) {
                if (selectedId.equals(rows.get(i).cellId())) {
                    list.setSelectedIndex(i);
                    updateToggle();
                    return;
                }
            }
        }
        if (!rows.isEmpty()) {
            list.setSelectedIndex(0);
        }
        updateToggle();
    }

    /** The toggle offers only what the scene graph can actually do. */
    private void updateToggle() {
        WorldHudModel.Row row = list.getSelectedValue();
        if (row == null || row.state() == WorldHudModel.CellState.PENDING) {
            toggleButton.setText("Show");
            toggleButton.setEnabled(false);
            return;
        }
        boolean hidden = row.state() == WorldHudModel.CellState.HIDDEN;
        toggleButton.setText(hidden ? "Show" : "Hide");
        toggleButton.setEnabled(true);
    }

    // ------------------------------------------------------------------
    // Test seams
    // ------------------------------------------------------------------

    /** The rows currently listed, top to bottom. */
    List<WorldHudModel.Row> rows() {
        List<WorldHudModel.Row> out = new ArrayList<>(rowModel.size());
        for (int i = 0; i < rowModel.size(); i++) {
            out.add(rowModel.getElementAt(i));
        }
        return out;
    }

    /** The status strip's text. */
    String statusText() {
        return statusLabel.getText();
    }

    /** The toggle's current label. */
    String toggleText() {
        return toggleButton.getText();
    }

    /** Whether the toggle acts on the selection (false while nothing is mounted). */
    boolean isToggleEnabled() {
        return toggleButton.isEnabled();
    }

    /** The selected row's cell id, or null with no selection. */
    String selectedCellId() {
        WorldHudModel.Row row = list.getSelectedValue();
        return (row == null) ? null : row.cellId();
    }

    /** Selects {@code cellId} so a test can drive the toggle without a mouse. */
    void select(String cellId) {
        List<WorldHudModel.Row> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).cellId().equals(cellId)) {
                list.setSelectedIndex(i);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Sets the hook the host window calls when the HUD is closed. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** Stops the reader thread and runs the close hook once. Idempotent. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        readThread.shutdownNow();
        Runnable hook = onClose;
        onClose = null;
        if (hook != null) {
            hook.run();
        }
    }

    private static IOException asIo(Throwable err) {
        return (err instanceof IOException)
                ? (IOException) err : new IOException(err);
    }

    /**
     * One row: the label indented by its depth, with the joined state printed
     * next to it. Colours come from the list's own selection state so the row
     * reads correctly under any look and feel, including the offscreen capture
     * the SwingNode paints.
     */
    private static final class RowRenderer
            implements ListCellRenderer<WorldHudModel.Row> {

        @Override
        public Component getListCellRendererComponent(
                JList<? extends WorldHudModel.Row> jList,
                WorldHudModel.Row row, int index, boolean isSelected,
                boolean cellHasFocus) {
            JLabel label = new JLabel(text(row));
            label.setBorder(BorderFactory.createEmptyBorder(
                    0, Math.min(row.depth(), 8) * INDENT_PX, 0, 0));
            if (row.className() != null) {
                label.setToolTipText(row.className());
            }
            if (isSelected) {
                label.setBackground(jList.getSelectionBackground());
                label.setForeground(jList.getSelectionForeground());
                label.setOpaque(true);
            } else {
                label.setBackground(jList.getBackground());
                label.setForeground(jList.getForeground());
                label.setOpaque(true);
            }
            return label;
        }

        private static String text(WorldHudModel.Row row) {
            String state;
            switch (row.state()) {
                case HIDDEN:
                    state = "hidden";
                    break;
                case PENDING:
                    state = "pending";
                    break;
                default:
                    state = "shown";
                    break;
            }
            return row.label() + "  [" + state + "]";
        }
    }
}
