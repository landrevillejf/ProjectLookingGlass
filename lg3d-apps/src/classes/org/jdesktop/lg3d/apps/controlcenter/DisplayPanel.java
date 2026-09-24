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
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import org.jdesktop.lg3d.utils.system.DisplayService;
import org.jdesktop.lg3d.utils.system.SystemInfoService;

/**
 * Display configuration panel backed by {@link DisplayService} (xrandr):
 * lists connected outputs and lets the user pick a resolution, refresh rate,
 * multi-monitor position, primary flag and scale. Applying captures a snapshot
 * first and shows a timed "keep these settings?" prompt that reverts to the
 * snapshot unless confirmed.
 *
 * <p>Under a Wayland compositor (or without xrandr) the panel degrades to a
 * clear read-only/warning state instead of pretending changes took effect.</p>
 */
public class DisplayPanel implements ControlPanel {

    private static final int REVERT_SECONDS = 20;
    private static final String KEEP_POSITION = "Keep current position";

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<DisplayService.Output> outputs =
            new DefaultListModel<>();
    private final JList<DisplayService.Output> outputList = new JList<>(outputs);
    private final JComboBox<DisplayService.Mode> modeCombo = new JComboBox<>();
    private final JComboBox<Double> rateCombo = new JComboBox<>();
    private final JComboBox<String> positionCombo = new JComboBox<>();
    private final JComboBox<Double> scaleCombo = new JComboBox<>(
            new Double[] { 1.0, 1.25, 1.5, 1.75, 2.0 });
    private final JCheckBox primaryCheck = new JCheckBox("Primary display");
    private final JButton applyButton = new JButton("Apply");
    private final JLabel warningLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");

    private boolean populating;

    public DisplayPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        outputList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        outputList.setCellRenderer(new DefaultListCellRendererOutput());
        outputList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onOutputSelected();
            }
        });

        modeCombo.addActionListener(e -> {
            if (!populating) {
                populateRates();
            }
        });
        applyButton.addActionListener(e -> apply());

        root.add(buildForm(), BorderLayout.CENTER);
        root.add(buildSouth(), BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Display";
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

    private JComponent buildForm() {
        JPanel center = new JPanel(new BorderLayout(8, 0));
        center.add(new JScrollPane(outputList), BorderLayout.WEST);
        outputList.setPreferredSize(new java.awt.Dimension(170, 0));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        form.add(new JLabel("Resolution:"), c);
        c.gridx = 1; c.weightx = 1;
        form.add(modeCombo, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        form.add(new JLabel("Refresh rate:"), c);
        c.gridx = 1; c.weightx = 1;
        form.add(rateCombo, c);

        c.gridx = 0; c.gridy = 2; c.weightx = 0;
        form.add(new JLabel("Position:"), c);
        c.gridx = 1; c.weightx = 1;
        form.add(positionCombo, c);

        c.gridx = 0; c.gridy = 3; c.weightx = 0;
        form.add(new JLabel("Scale:"), c);
        c.gridx = 1; c.weightx = 1;
        form.add(scaleCombo, c);

        c.gridx = 1; c.gridy = 4;
        form.add(primaryCheck, c);

        c.gridx = 1; c.gridy = 5; c.weightx = 0; c.anchor = GridBagConstraints.EAST;
        form.add(applyButton, c);

        center.add(form, BorderLayout.CENTER);
        return center;
    }

    private JComponent buildSouth() {
        JPanel south = new JPanel(new BorderLayout());
        warningLabel.setForeground(new java.awt.Color(160, 90, 20));
        south.add(warningLabel, BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(statusLabel);
        south.add(row, BorderLayout.SOUTH);
        return south;
    }

    private void reload() {
        outputs.clear();
        for (DisplayService.Output o : DisplayService.query()) {
            outputs.addElement(o);
        }
        updateAvailability();
        for (int i = 0; i < outputs.size(); i++) {
            if (outputs.get(i).isEnabled()) {
                outputList.setSelectedIndex(i);
                return;
            }
        }
        if (!outputs.isEmpty()) {
            outputList.setSelectedIndex(0);
        } else {
            onOutputSelected();
        }
    }

    private void updateAvailability() {
        boolean available = DisplayService.isAvailable();
        if (!available) {
            warningLabel.setText("xrandr is not installed; display configuration is read-only.");
            applyButton.setEnabled(false);
        } else if (SystemInfoService.isWayland()) {
            warningLabel.setText("Wayland session detected: xrandr changes may not apply "
                    + "on this display server.");
            applyButton.setEnabled(true);
        } else {
            warningLabel.setText(" ");
            applyButton.setEnabled(true);
        }
    }

    private void onOutputSelected() {
        populating = true;
        try {
            modeCombo.removeAllItems();
            rateCombo.removeAllItems();
            positionCombo.removeAllItems();
            DisplayService.Output o = outputList.getSelectedValue();
            if (o == null) {
                return;
            }
            for (DisplayService.Mode m : o.getModes()) {
                modeCombo.addItem(m);
            }
            DisplayService.Mode cur = o.getCurrentMode();
            if (cur != null) {
                modeCombo.setSelectedItem(cur);
            } else if (modeCombo.getItemCount() > 0) {
                modeCombo.setSelectedIndex(0);
            }
            populateRates();

            positionCombo.addItem(KEEP_POSITION);
            for (int i = 0; i < outputs.size(); i++) {
                DisplayService.Output other = outputs.get(i);
                if (other == o || !other.isConnected()) {
                    continue;
                }
                positionCombo.addItem("Left of " + other.getName() + "|left-of:" + other.getName());
                positionCombo.addItem("Right of " + other.getName() + "|right-of:" + other.getName());
                positionCombo.addItem("Above " + other.getName() + "|above:" + other.getName());
                positionCombo.addItem("Below " + other.getName() + "|below:" + other.getName());
            }
            primaryCheck.setSelected(o.isPrimary());
            scaleCombo.setSelectedItem(Double.valueOf(1.0));
        } finally {
            populating = false;
        }
    }

    private void populateRates() {
        rateCombo.removeAllItems();
        DisplayService.Mode m = (DisplayService.Mode) modeCombo.getSelectedItem();
        if (m == null) {
            return;
        }
        for (Double r : m.getRates()) {
            rateCombo.addItem(r);
        }
        if (m.getCurrentRate() > 0) {
            rateCombo.setSelectedItem(Double.valueOf(m.getCurrentRate()));
        } else if (rateCombo.getItemCount() > 0) {
            rateCombo.setSelectedIndex(0);
        }
    }

    // ------------------------------------------------------------------

    private void apply() {
        DisplayService.Output o = outputList.getSelectedValue();
        if (o == null) {
            return;
        }
        final List<DisplayService.OutputSetting> snapshot = DisplayService.capture();

        DisplayService.OutputSetting s = new DisplayService.OutputSetting(o.getName());
        DisplayService.Mode m = (DisplayService.Mode) modeCombo.getSelectedItem();
        s.mode = (m != null) ? m.getId() : null;
        Double rate = (Double) rateCombo.getSelectedItem();
        s.rate = rate;
        s.primary = primaryCheck.isSelected();
        Double scale = (Double) scaleCombo.getSelectedItem();
        if (scale != null && Math.abs(scale - 1.0) > 0.001) {
            s.scale = scale;
        }
        String pos = (String) positionCombo.getSelectedItem();
        if (pos != null && pos.contains("|")) {
            s.relativeTo = pos.substring(pos.indexOf('|') + 1);
        }

        DisplayService.ApplyResult result = DisplayService.apply(List.of(s));
        if (!result.isSuccess()) {
            statusLabel.setText("Apply failed: " + result.getMessage());
            JOptionPane.showMessageDialog(root,
                    "Could not apply the display configuration:\n" + result.getMessage(),
                    "Display", JOptionPane.WARNING_MESSAGE);
            return;
        }
        statusLabel.setText("Applied. Waiting for confirmation...");
        showRevertConfirm(snapshot);
    }

    /** Timed "keep these settings?" prompt; reverts on timeout. */
    private void showRevertConfirm(final List<DisplayService.OutputSetting> snapshot) {
        final javax.swing.JDialog dialog = new javax.swing.JDialog();
        dialog.setTitle("Display");
        final JLabel countdown = new JLabel(
                "Keep these display settings? Reverting in " + REVERT_SECONDS + "s...");
        JButton keep = new JButton("Keep");
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(countdown, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        btns.add(keep);
        content.add(btns, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(root);

        final int[] left = { REVERT_SECONDS };
        final Timer timer = new Timer(1000, null);
        timer.addActionListener(e -> {
            left[0]--;
            if (left[0] <= 0) {
                timer.stop();
                dialog.dispose();
                DisplayService.restore(snapshot);
                statusLabel.setText("Reverted to the previous configuration.");
                reload();
            } else {
                countdown.setText("Keep these display settings? Reverting in "
                        + left[0] + "s...");
            }
        });
        keep.addActionListener(e -> {
            timer.stop();
            dialog.dispose();
            statusLabel.setText("Display settings kept.");
        });
        timer.start();
        dialog.setVisible(true);
    }

    /** Renders outputs with their connected/enabled state. */
    private static final class DefaultListCellRendererOutput
            extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list,
                Object value, int index, boolean isSelected, boolean cellHasFocus) {
            DisplayService.Output o = (DisplayService.Output) value;
            String label = o.getName()
                    + (o.isEnabled() ? "  (" + o.getCurrentWidth() + "x"
                            + o.getCurrentHeight() + ")" : (o.isConnected() ? "" : "  (off)"));
            return super.getListCellRendererComponent(list, label, index,
                    isSelected, cellHasFocus);
        }
    }
}
