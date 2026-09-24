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

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;

/**
 * Text tool. A click opens the captured {@link TextDialog} (text, font, colour);
 * on OK the entered text is rendered into the active layer at the click point,
 * honouring multi-line input and the tool opacity, as one undoable edit.
 */
public class TextTool extends AbstractTool {

    public String getName() {
        return "Text";
    }

    public String getHint() {
        return "Click to place text with a chosen font and colour";
    }

    public Cursor getCursor() {
        return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    public void press(Point2D p, PaintContext ctx) {
        PaintLayer layer = ctx.getActiveLayer();
        if (layer == null) {
            return;
        }
        PaintState state = ctx.getState();
        TextDialog dialog = new TextDialog(ownerFor(ctx), "", state.getFont(),
                state.getForeground());
        if (!dialog.showDialog()) {
            return;
        }
        String text = dialog.getText();
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        Font font = dialog.getSelectedFont();
        Color color = dialog.getSelectedColor();
        BufferedImage before = layer.snapshot();
        Graphics2D g = ctx.activeLayerGraphics();
        if (g != null) {
            g.setFont(font);
            g.setColor(color);
            FontMetrics fm = g.getFontMetrics();
            int lineHeight = fm.getHeight();
            float x = (float) p.getX();
            float y = (float) p.getY() + fm.getAscent();
            for (String line : text.split("\n", -1)) {
                g.drawString(line, x, y);
                y += lineHeight;
            }
            g.dispose();
        }
        ctx.getDocument().invalidate();
        commitLayerEdit(ctx, before, "Text");
        ctx.repaint();
    }

    private static Window ownerFor(PaintContext ctx) {
        java.awt.Component parent = ctx.getDialogParent();
        if (parent instanceof Window) {
            return (Window) parent;
        }
        return (parent == null) ? null : SwingUtilities.getWindowAncestor(parent);
    }
}
