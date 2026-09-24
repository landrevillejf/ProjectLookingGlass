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
package org.jdesktop.lg3d.apps.paint;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * The Image &rarr; Rotate dialog: quick 90/180/-90 presets and an arbitrary
 * angle spinner. OK returns the chosen degrees (positive = clockwise) for
 * {@link PaintDocument#rotate}.
 */
public class RotateDialog extends JDialog {

    private final JSpinner degrees = new JSpinner(
            new SpinnerNumberModel(90, -360, 360, 15));
    private boolean approved;

    public RotateDialog(Window owner) {
        super(owner, "Rotate", ModalityType.APPLICATION_MODAL);
        buildUI();
        pack();
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 6));
        center.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        center.add(preset("90\u00b0 CW", 90));
        center.add(preset("180\u00b0", 180));
        center.add(preset("90\u00b0 CCW", -90));

        JPanel angle = new JPanel(new FlowLayout(FlowLayout.LEFT));
        angle.add(new JLabel("Angle"));
        angle.add(degrees);
        center.add(angle);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                approved = true;
                dispose();
            }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        buttons.add(ok);
        buttons.add(cancel);
        getRootPane().setDefaultButton(ok);

        setLayout(new BorderLayout());
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    private JButton preset(String label, final int value) {
        JButton b = new JButton(label);
        b.setFocusPainted(false);
        b.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                degrees.setValue(value);
            }
        });
        return b;
    }

    public boolean showDialog() {
        approved = false;
        setVisible(true);
        return approved;
    }

    public double getDegrees() {
        return ((Number) degrees.getValue()).doubleValue();
    }
}
