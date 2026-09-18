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
package org.jdesktop.lg3d.apps.uikit;

import org.jdesktop.lg3d.sg.Node;
import org.jdesktop.lg3d.sg.utils.transparency.TransparencyOrderedGroup;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.shape.OriginTranslation;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Vector3f;

/**
 * Shared factory helpers for the lg3d-native "glassy" widgets used by the
 * demo applications (File Manager, Task Manager, Control Center).
 *
 * <p>The vocabulary mirrors the one proven by {@code Image Studio}: translucent
 * {@link GlassyPanel}s, {@link GlassyText2D} labels (no PNG assets), and push
 * buttons ({@link Button3D}) with the hover-highlight idiom of
 * {@code Lg3dHelp.Button}. Translucent shapes are grouped in a
 * {@link TransparencyOrderedGroup} so they blend back-to-front, and children
 * are added back-to-front as a second guarantee.</p>
 *
 * <p>A {@link GlassyText2D}'s geometry grows upward and (for CENTER/RIGHT
 * alignment) about its origin, so {@link #label} shifts it down by half its
 * height to center it vertically on the requested point.</p>
 */
public final class Ui3D {

    private Ui3D() { }

    // Foreground text colours.
    public static final Color4f TEXT_BRIGHT = new Color4f(0.90f, 0.95f, 1.00f, 1.0f);
    public static final Color4f TEXT_DIM    = new Color4f(0.60f, 0.68f, 0.78f, 1.0f);
    public static final Color4f TEXT_ACCENT = new Color4f(0.62f, 0.95f, 0.72f, 1.0f);

    // Glass tints.
    public static final Color4f PANEL_BG   = new Color4f(0.09f, 0.12f, 0.18f, 0.50f);
    public static final Color4f ROW_OFF    = new Color4f(0.13f, 0.18f, 0.26f, 0.55f);
    public static final Color4f ROW_ON     = new Color4f(0.30f, 0.48f, 0.70f, 0.80f);
    public static final Color4f BUTTON_OFF = new Color4f(0.24f, 0.32f, 0.45f, 0.80f);
    public static final Color4f BUTTON_ON  = new Color4f(0.42f, 0.62f, 0.85f, 0.92f);
    public static final Color4f TAB_OFF    = new Color4f(0.17f, 0.23f, 0.33f, 0.78f);
    public static final Color4f TAB_ON     = new Color4f(0.34f, 0.54f, 0.76f, 0.94f);
    public static final Color4f TRACK      = new Color4f(0.13f, 0.17f, 0.25f, 0.85f);
    public static final Color4f FILL       = new Color4f(0.40f, 0.80f, 0.65f, 0.90f);
    public static final Color4f CHIP_DIR   = new Color4f(0.85f, 0.70f, 0.35f, 0.90f);
    public static final Color4f CHIP_FILE  = new Color4f(0.45f, 0.55f, 0.68f, 0.85f);

    public static SimpleAppearance appearance(Color4f c) {
        return new SimpleAppearance(c.x, c.y, c.z, c.w,
                SimpleAppearance.DISABLE_CULLING);
    }

    public static GlassyPanel panel(float w, float h, float depth, Color4f c) {
        return new GlassyPanel(w, h, depth, appearance(c));
    }

    public static GlassyText2D makeText(String s, float maxWidth, float h, Color4f c,
            GlassyText2D.Alignment align) {
        return new GlassyText2D(s, maxWidth, h, c,
                GlassyText2D.LightDirection.TOP_LEFT, align);
    }

    /** Wrap a node so its local origin sits at (x, y, z) in the parent. */
    public static OriginTranslation at(Node node, float x, float y, float z) {
        return new OriginTranslation(node, new Vector3f(x, y, z));
    }

    /** A text label vertically centered on {@code cy} (text grows upward). */
    public static OriginTranslation label(String s, float maxWidth, float h, Color4f c,
            GlassyText2D.Alignment align, float cx, float cy, float z) {
        GlassyText2D t = makeText(s, maxWidth, h, c, align);
        return new OriginTranslation(t, new Vector3f(cx, cy - h * 0.5f, z));
    }

    /**
     * Wrap an arbitrary scene-graph node in a {@link Component3D} (inside a
     * {@link TransparencyOrderedGroup} for correct blending) so it can be added
     * straight to a {@code Container3D}/{@code Frame3D}, which only accept
     * {@code Component3D} children.
     */
    public static Component3D component(Node node) {
        TransparencyOrderedGroup tog = new TransparencyOrderedGroup();
        tog.addChild(node);
        Component3D c = new Component3D();
        c.addChild(tog);
        return c;
    }

    /** Formats a byte count for display (B / K / M / G). */
    public static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double v = bytes;
        String[] u = {"K", "M", "G", "T"};
        int i = -1;
        while (v >= 1024.0 && i < u.length - 1) {
            v /= 1024.0;
            i++;
        }
        return String.format("%.1f %sB", v, u[i]);
    }
}
