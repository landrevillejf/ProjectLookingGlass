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
package org.jdesktop.lg3d.apps.controlcenter;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.NetworkConnections;

/**
 * Network panel: lists the saved NetworkManager connections with their type and
 * whether they are currently active, and offers connect / disconnect for the
 * selected one. The list is a {@link JList} (never a combo box) so the panel
 * keeps working when hosted offscreen in a {@code SwingNode} on the 3D desktop;
 * the same panel serves the 2D desktop.
 *
 * <p>All discovery and mutation go through the {@link NetworkConnections} seam,
 * which degrades to "no connections" on a host without NetworkManager rather
 * than failing.</p>
 */
public class NetworkPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<String> names = new DefaultListModel<>();
    private final JList<String> connectionList = new JList<>(names);
    private final JLabel statusLabel = new JLabel(" ");

    /** The connections behind the current list rows, parallel to {@link #names}. */
    private final List<NetworkConnections.Connection> current = new ArrayList<>();

    public NetworkPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        connectionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        connectionList.setVisibleRowCount(8);
        JScrollPane scroll = new JScrollPane(connectionList);
        scroll.setPreferredSize(new Dimension(360, 180));

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> reload());
        JButton connect = new JButton("Connect");
        connect.addActionListener(e -> connect());
        JButton disconnect = new JButton("Disconnect");
        disconnect.addActionListener(e -> disconnect());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(refresh);
        buttons.add(connect);
        buttons.add(disconnect);

        root.add(scroll, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        root.add(statusLabel, BorderLayout.NORTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Network";
    }

    @Override
    public javax.swing.Icon icon() {
        return null;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void onShow() {
        reload();
    }

    // ------------------------------------------------------------------

    /** Re-reads the saved connections, refreshing the list. */
    private void reload() {
        current.clear();
        names.clear();
        List<NetworkConnections.Connection> conns = NetworkConnections.read();
        for (NetworkConnections.Connection c : conns) {
            current.add(c);
            names.addElement(NetworkConnections.label(c));
        }
        if (!names.isEmpty()) {
            connectionList.setSelectedIndex(0);
            long active = conns.stream().filter(NetworkConnections.Connection::active).count();
            statusLabel.setText(conns.size() + " connection(s); " + active + " active");
        } else {
            statusLabel.setText("No network connections found (is NetworkManager running?)");
        }
    }

    /** The connection behind the selected row, or null when nothing is selected. */
    private NetworkConnections.Connection selected() {
        int i = connectionList.getSelectedIndex();
        return (i >= 0 && i < current.size()) ? current.get(i) : null;
    }

    private void connect() {
        NetworkConnections.Connection c = selected();
        if (c == null) {
            statusLabel.setText("Select a connection first.");
            return;
        }
        boolean ok = NetworkConnections.activate(c.name());
        statusLabel.setText(ok
                ? "Connected to " + c.name()
                : "Could not connect to " + c.name() + " (is NetworkManager running?)");
        reload();
    }

    private void disconnect() {
        NetworkConnections.Connection c = selected();
        if (c == null) {
            statusLabel.setText("Select a connection first.");
            return;
        }
        boolean ok = NetworkConnections.deactivate(c.name());
        statusLabel.setText(ok
                ? "Disconnected from " + c.name()
                : "Could not disconnect from " + c.name() + " (is NetworkManager running?)");
        reload();
    }
}
