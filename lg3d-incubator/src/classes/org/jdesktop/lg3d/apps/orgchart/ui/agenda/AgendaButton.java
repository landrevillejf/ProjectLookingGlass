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
package org.jdesktop.lg3d.apps.orgchart.ui.agenda;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import org.jdesktop.lg3d.apps.orgchart.ui.common.UIUtil;
import org.jdesktop.lg3d.sg.Appearance;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.action.AppearanceChangeAction;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MouseEnteredEventAdapter;
import org.jdesktop.lg3d.utils.shape.ImagePanel;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.Toolkit3D;

/**
 * A small labelled push button for the agenda control strip, drawn entirely at
 * runtime into a texture (no PNG assets) so it matches the glassy vocabulary
 * without shipping images.
 *
 * <p>The label is rendered centred into a rounded rectangle — the orgchart
 * {@code TextPanel} grows up-and-right from its origin and cannot be centred
 * reliably, so baking the text into the button image keeps the caption neatly
 * framed. Two images (normal and hover) are built once at construction and the
 * hover swap is a plain appearance change, which avoids re-attaching textures
 * on a live scene graph.</p>
 */
public class AgendaButton extends Component3D {

    private static final Font FONT = new Font("SansSerif", Font.BOLD, 18);

    private final ImagePanel shape;

    /**
     * @param label    caption drawn on the button
     * @param width    button width in physical (world) units
     * @param height   button height in physical (world) units
     * @param onClick  fired on a left click
     */
    public AgendaButton(String label, float width, float height,
            ActionNoArg onClick) {
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        int px = Math.max(8, tk.widthPhysicalToNative(width));
        int py = Math.max(8, tk.heightPhysicalToNative(height));

        BufferedImage normal = render(label, px, py,
                new Color(0.20f, 0.32f, 0.52f, 0.92f),
                new Color(0.55f, 0.70f, 0.95f, 1.0f));
        BufferedImage hover = render(label, px, py,
                new Color(0.30f, 0.50f, 0.80f, 0.98f),
                new Color(0.80f, 0.90f, 1.00f, 1.0f));

        shape = new ImagePanel(width, height);
        Appearance offApp = new SimpleAppearance(1.0f, 1.0f, 1.0f, 1.0f,
                SimpleAppearance.ENABLE_TEXTURE | SimpleAppearance.DISABLE_CULLING);
        UIUtil.setTexture(offApp, normal);
        Appearance onApp = new SimpleAppearance(1.0f, 1.0f, 1.0f, 1.0f,
                SimpleAppearance.ENABLE_TEXTURE | SimpleAppearance.DISABLE_CULLING);
        UIUtil.setTexture(onApp, hover);

        // Textures are fully populated before the shape goes live, and the
        // hover swap only changes which pre-built appearance is attached.
        shape.setAppearance(offApp);
        addChild(shape);

        addListener(new MouseEnteredEventAdapter(
                new AppearanceChangeAction(shape, onApp)));
        addListener(new MouseClickedEventAdapter(onClick));
        setCursor(Cursor3D.SMALL_CURSOR);
    }

    private static BufferedImage render(String label, int w, int h,
            Color fill, Color border) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(fill);
            g.fillRoundRect(1, 1, w - 2, h - 2, h / 2, h / 2);
            g.setColor(border);
            g.setStroke(new java.awt.BasicStroke(2.0f));
            g.drawRoundRect(1, 1, w - 3, h - 3, h / 2, h / 2);

            g.setFont(FONT);
            java.awt.FontMetrics fm = g.getFontMetrics();
            int tx = (w - fm.stringWidth(label)) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g.setColor(Color.WHITE);
            g.drawString(label, tx, ty);
        } finally {
            g.dispose();
        }
        return bi;
    }
}
