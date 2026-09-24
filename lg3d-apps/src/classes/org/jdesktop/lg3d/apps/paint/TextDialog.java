/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
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
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;

/**
 * The text-entry dialog opened by {@link TextTool}. A multi-line text area plus a
 * font family / style / size picker and a colour button; OK returns the entered
 * text and the chosen {@link Font} and {@link Color}. A plain modal
 * {@link JDialog}, so the capture layer presents it as an in-scene overlay.
 */
public class TextDialog extends JDialog {

    private final JTextArea text = new JTextArea(4, 24);
    private final JComboBox<String> family;
    private final JComboBox<String> style;
    private final JSpinner size;
    private Color color;
    private final JButton colorButton = new JButton("Colour...");
    private boolean approved;

    public TextDialog(Window owner, String initialText, Font initialFont,
            Color initialColor) {
        super(owner, "Text", ModalityType.APPLICATION_MODAL);
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        family = new JComboBox<String>(families);
        style = new JComboBox<String>(new String[] {
            "Plain", "Bold", "Italic", "Bold Italic"
        });
        size = new JSpinner(new SpinnerNumberModel(24, 4, 400, 1));
        if (initialFont != null) {
            family.setSelectedItem(initialFont.getFamily());
            style.setSelectedIndex(styleIndex(initialFont.getStyle()));
            size.setValue(Math.max(4, initialFont.getSize()));
        }
        color = (initialColor == null) ? Color.BLACK : initialColor;
        if (initialText != null) {
            text.setText(initialText);
        }
        colorButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Color c = JColorChooser.showDialog(TextDialog.this,
                        "Text colour", color);
                if (c != null) {
                    color = c;
                }
            }
        });
        buildUI();
        pack();
        setLocationRelativeTo(owner);
    }

    private static int styleIndex(int awtStyle) {
        switch (awtStyle) {
            case Font.BOLD:
                return 1;
            case Font.ITALIC:
                return 2;
            case Font.BOLD | Font.ITALIC:
                return 3;
            default:
                return 0;
        }
    }

    private void buildUI() {
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(new JScrollPane(text), BorderLayout.CENTER);

        JPanel fontPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        fontPanel.add(new JLabel("Font"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        fontPanel.add(family, c);
        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        fontPanel.add(new JLabel("Style"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        fontPanel.add(style, c);
        c.gridx = 0;
        c.gridy = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        fontPanel.add(new JLabel("Size"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        fontPanel.add(size, c);
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        fontPanel.add(colorButton, c);
        content.add(fontPanel, BorderLayout.SOUTH);

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
                approved = false;
                dispose();
            }
        });
        buttons.add(ok);
        buttons.add(cancel);
        getRootPane().setDefaultButton(ok);

        setLayout(new BorderLayout());
        add(content, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    /** Shows the dialog modally; true when the user pressed OK. */
    public boolean showDialog() {
        approved = false;
        setVisible(true);
        return approved;
    }

    public String getText() {
        return text.getText();
    }

    public Font getSelectedFont() {
        String fam = (String) family.getSelectedItem();
        int st = style.getSelectedIndex();
        int awtStyle = Font.PLAIN;
        if (st == 1) {
            awtStyle = Font.BOLD;
        } else if (st == 2) {
            awtStyle = Font.ITALIC;
        } else if (st == 3) {
            awtStyle = Font.BOLD | Font.ITALIC;
        }
        int sz = ((Number) size.getValue()).intValue();
        return new Font(fam, awtStyle, sz);
    }

    public Color getSelectedColor() {
        return color;
    }
}
