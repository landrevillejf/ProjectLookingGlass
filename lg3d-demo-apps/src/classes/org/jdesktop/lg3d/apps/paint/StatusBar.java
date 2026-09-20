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
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * The bottom status strip: pointer coordinates, zoom percentage, document size
 * and a hint for the active tool. Pure labels updated by {@link PaintCanvas} and
 * {@link PaintFrame}.
 */
public class StatusBar extends JPanel {

    private final JLabel coords = new JLabel(" ");
    private final JLabel zoom = new JLabel("100%");
    private final JLabel size = new JLabel(" ");
    private final JLabel hint = new JLabel(" ");

    public StatusBar() {
        super(new BorderLayout());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0,
                        java.awt.Color.GRAY),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));

        coords.setHorizontalAlignment(SwingConstants.LEFT);
        coords.setPreferredSize(new java.awt.Dimension(140, 18));
        zoom.setHorizontalAlignment(SwingConstants.LEFT);
        zoom.setPreferredSize(new java.awt.Dimension(70, 18));
        size.setHorizontalAlignment(SwingConstants.LEFT);
        size.setPreferredSize(new java.awt.Dimension(140, 18));
        hint.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel left = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 12, 0));
        left.setOpaque(false);
        left.add(coords);
        left.add(zoom);
        left.add(size);
        add(left, BorderLayout.WEST);
        add(hint, BorderLayout.CENTER);
    }

    public void setCoords(int x, int y) {
        coords.setText(x + ", " + y + " px");
    }

    public void clearCoords() {
        coords.setText(" ");
    }

    public void setZoom(double zoomLevel) {
        zoom.setText(Math.round(zoomLevel * 100.0) + "%");
    }

    public void setDocumentSize(int w, int h) {
        size.setText(w + " x " + h + " px");
    }

    public void setHint(String text) {
        hint.setText(text == null ? " " : text);
    }
}
