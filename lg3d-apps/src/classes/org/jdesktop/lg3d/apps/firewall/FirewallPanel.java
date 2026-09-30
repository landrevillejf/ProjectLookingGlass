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
package org.jdesktop.lg3d.apps.firewall;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/**
 * The firewall's Swing UI: displays firewall status, active rules, and provides
 * controls to enable/disable the firewall and manage rules.
 *
 * <p>The panel refreshes every five seconds from {@link FirewallService} to
 * monitor firewall status and rules.</p>
 */
public class FirewallPanel extends JPanel {

    private static final int PANEL_W = 700;
    private static final int PANEL_H = 500;
    private static final int REFRESH_MS = 5000;

    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Protocol", "Source", "Destination", "Port", "Action", "Target"}, 0);
    private final JTable table = new JTable(model);
    private final JLabel statusLabel = new JLabel("Loading...");
    private final JLabel summaryLabel = new JLabel(" ");
    private final Timer timer;

    private boolean refreshing;
    private Runnable onClose;

    public FirewallPanel() {
        super(new BorderLayout(6, 6));
        setPreferredSize(new Dimension(PANEL_W, PANEL_H));
        setBackground(new Color(238, 240, 244));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        buildTable();

        add(buildHeader(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        timer = new Timer(REFRESH_MS, e -> refresh());
        timer.setInitialDelay(0);
        // Timer not started automatically to avoid repeated authentication prompts
        // User can manually refresh via the "Refresh Now" button
        // Initial status set to "Click Refresh to load"
        statusLabel.setText("Click Refresh to load");
        statusLabel.setForeground(Color.GRAY);
    }

    /** Sets the callback invoked when the user presses Close. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** Stops the refresh timer (call when the window closes). */
    public void stop() {
        timer.stop();
    }

    // ------------------------------------------------------------------
    // UI construction

    private void buildTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setReorderingAllowed(false);

        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(70);
        table.getColumnModel().getColumn(5).setPreferredWidth(80);

        table.getColumnModel().getColumn(4).setCellRenderer(new ActionRenderer());
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(6, 6));
        header.setBackground(new Color(238, 240, 244));
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        statusPanel.setBackground(new Color(238, 240, 244));
        statusPanel.add(new JLabel("Status:"));
        statusPanel.add(statusLabel);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));

        header.add(statusPanel, BorderLayout.WEST);
        header.add(summaryLabel, BorderLayout.EAST);

        return header;
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        controls.setBackground(new Color(238, 240, 244));
        controls.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        JButton enableBtn = new JButton("Enable Firewall");
        JButton disableBtn = new JButton("Disable Firewall");
        JButton refreshBtn = new JButton("Refresh Now");
        JButton closeBtn = new JButton("Close");

        enableBtn.addActionListener(e -> enableFirewall());
        disableBtn.addActionListener(e -> disableFirewall());
        refreshBtn.addActionListener(e -> refresh());
        closeBtn.addActionListener(e -> {
            stop();
            if (onClose != null) {
                onClose.run();
            }
        });

        controls.add(enableBtn);
        controls.add(disableBtn);
        controls.add(refreshBtn);
        controls.add(closeBtn);

        return controls;
    }

    // ------------------------------------------------------------------
    // Actions

    private void enableFirewall() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Enable the firewall? This may affect network connectivity.",
                "Enable Firewall",
                JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    FirewallService.enableFirewall();
                    return null;
                }

                @Override
                protected void done() {
                    refresh();
                }
            }.execute();
        }
    }

    private void disableFirewall() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Disable the firewall? This may expose your system to network threats.",
                "Disable Firewall",
                JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    FirewallService.disableFirewall();
                    return null;
                }

                @Override
                protected void done() {
                    refresh();
                }
            }.execute();
        }
    }

    // ------------------------------------------------------------------
    // Refresh

    private void refresh() {
        if (refreshing) {
            return;
        }
        refreshing = true;

        new SwingWorker<FirewallStatus, Void>() {
            @Override
            protected FirewallStatus doInBackground() throws Exception {
                return FirewallService.getStatus();
            }

            @Override
            protected void done() {
                try {
                    FirewallStatus status = get();
                    updateUI(status);
                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                    statusLabel.setForeground(Color.RED);
                } finally {
                    refreshing = false;
                }
            }
        }.execute();
    }

    private void updateUI(FirewallStatus status) {
        SwingUtilities.invokeLater(() -> {
            // Update status label
            if (status.enabled) {
                statusLabel.setText("Active");
                statusLabel.setForeground(new Color(0, 128, 0));
            } else {
                statusLabel.setText("Inactive");
                statusLabel.setForeground(Color.RED);
            }

            // Update summary
            summaryLabel.setText(String.format("%d rules", status.rules.size()));

            // Update table
            model.setRowCount(0);
            for (FirewallRule rule : status.rules) {
                model.addRow(new Object[]{
                    rule.protocol,
                    rule.source,
                    rule.destination,
                    rule.port,
                    rule.action,
                    rule.target
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // Renderers

    private static class ActionRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value,
                    isSelected, hasFocus, row, column);
            if ("ACCEPT".equals(value)) {
                setForeground(new Color(0, 128, 0));
            } else if ("DROP".equals(value) || "REJECT".equals(value)) {
                setForeground(Color.RED);
            } else {
                setForeground(Color.BLACK);
            }
            return c;
        }
    }
}
