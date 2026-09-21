/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * The File &rarr; New dialog: canvas width/height and a background colour. OK
 * returns the chosen size and colour for {@link PaintDocument#create}.
 */
public class NewImageDialog extends JDialog {

    private final JSpinner width = new JSpinner(
            new SpinnerNumberModel(800, 1, 8000, 10));
    private final JSpinner height = new JSpinner(
            new SpinnerNumberModel(600, 1, 8000, 10));
    private Color background = Color.WHITE;
    private final JButton bgButton = new JButton("Background...");
    private boolean approved;

    public NewImageDialog(Window owner) {
        super(owner, "New image", ModalityType.APPLICATION_MODAL);
        bgButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Color c = JColorChooser.showDialog(NewImageDialog.this,
                        "Background colour", background);
                if (c != null) {
                    background = c;
                    bgButton.setBackground(c);
                }
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
        form.add(bgButton, c);

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

    public int getWidth_() {
        return ((Number) width.getValue()).intValue();
    }

    public int getHeight_() {
        return ((Number) height.getValue()).intValue();
    }

    public Color getBackgroundColor() {
        return background;
    }
}
