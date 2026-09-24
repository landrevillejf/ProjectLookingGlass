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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
 * The Image &rarr; Resize dialog: a new pixel width/height with an optional
 * keep-aspect-ratio lock. OK returns the target size for
 * {@link PaintDocument#resize}.
 */
public class ResizeDialog extends JDialog {

    private final JSpinner width;
    private final JSpinner height;
    private final JCheckBox keepAspect = new JCheckBox("Keep aspect ratio", true);
    private final double ratio;
    private boolean approved;
    private boolean syncing;

    public ResizeDialog(Window owner, int curW, int curH) {
        super(owner, "Resize", ModalityType.APPLICATION_MODAL);
        ratio = (curH == 0) ? 1.0 : (double) curW / curH;
        width = new JSpinner(new SpinnerNumberModel(curW, 1, 20000, 1));
        height = new JSpinner(new SpinnerNumberModel(curH, 1, 20000, 1));
        width.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                if (syncing || !keepAspect.isSelected()) {
                    return;
                }
                syncing = true;
                int w = ((Number) width.getValue()).intValue();
                height.setValue(Math.max(1, (int) Math.round(w / ratio)));
                syncing = false;
            }
        });
        height.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                if (syncing || !keepAspect.isSelected()) {
                    return;
                }
                syncing = true;
                int h = ((Number) height.getValue()).intValue();
                width.setValue(Math.max(1, (int) Math.round(h * ratio)));
                syncing = false;
            }
        });
        buildUI();
        pack();
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("Width"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        form.add(width, c);
        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        form.add(new JLabel("Height"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        form.add(height, c);
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        form.add(keepAspect, c);

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
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    public boolean showDialog() {
        approved = false;
        setVisible(true);
        return approved;
    }

    public int getNewWidth() {
        return ((Number) width.getValue()).intValue();
    }

    public int getNewHeight() {
        return ((Number) height.getValue()).intValue();
    }
}
