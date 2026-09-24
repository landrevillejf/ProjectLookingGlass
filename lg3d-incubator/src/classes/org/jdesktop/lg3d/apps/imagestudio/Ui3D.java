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
package org.jdesktop.lg3d.apps.imagestudio;

import org.jdesktop.lg3d.sg.Node;
import org.jdesktop.lg3d.sg.utils.transparency.TransparencyOrderedGroup;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.action.AppearanceChangeAction;
import org.jdesktop.lg3d.utils.action.ScaleActionBoolean;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MouseEnteredEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.shape.OriginTranslation;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Vector3f;

/**
 * Shared factory helpers for the Image Studio's lg3d-native "glassy" widgets.
 *
 * <p>Everything the studio draws is built from a small vocabulary: translucent
 * {@link GlassyPanel}s, {@link GlassyText2D} labels (no PNG assets), and push
 * buttons that combine the two with the hover-highlight idiom proven by
 * {@code Lg3dHelp.Button} ({@link AppearanceChangeAction} +
 * {@link ScaleActionBoolean} driven by a {@link MouseEnteredEventAdapter}) and a
 * {@link MouseClickedEventAdapter} for the click.</p>
 *
 * <p>Translucent shapes are grouped in a {@link TransparencyOrderedGroup} so
 * they blend back-to-front, since {@code Component3D} only sorts transparency
 * automatically when a transparency animation is installed (it is not, here).
 * Children are also added back-to-front as a second guarantee.</p>
 *
 * <p>A {@link GlassyText2D}'s geometry grows upward and (for CENTER/RIGHT
 * alignment) about its origin, so {@link #label} shifts it down by half its
 * height to center it vertically on the requested point.</p>
 */
final class Ui3D {

    private Ui3D() { }

    // Foreground text colours.
    static final Color4f TEXT_BRIGHT = new Color4f(0.90f, 0.95f, 1.00f, 1.0f);
    static final Color4f TEXT_DIM    = new Color4f(0.60f, 0.68f, 0.78f, 1.0f);
    static final Color4f TEXT_ACCENT = new Color4f(0.62f, 0.95f, 0.72f, 1.0f);

    // Glass tints.
    static final Color4f PANEL_BG    = new Color4f(0.09f, 0.12f, 0.18f, 0.50f);
    static final Color4f BUTTON_OFF  = new Color4f(0.24f, 0.32f, 0.45f, 0.80f);
    static final Color4f BUTTON_ON   = new Color4f(0.42f, 0.62f, 0.85f, 0.92f);
    static final Color4f TAB_OFF     = new Color4f(0.17f, 0.23f, 0.33f, 0.78f);
    static final Color4f TAB_ON      = new Color4f(0.34f, 0.54f, 0.76f, 0.94f);
    static final Color4f ACTION_OFF  = new Color4f(0.20f, 0.30f, 0.30f, 0.80f);
    static final Color4f ACTION_ON   = new Color4f(0.34f, 0.62f, 0.56f, 0.92f);
    static final Color4f TRACK       = new Color4f(0.13f, 0.17f, 0.25f, 0.85f);
    static final Color4f TRACK_OFF   = new Color4f(0.10f, 0.12f, 0.16f, 0.60f);
    static final Color4f KNOB        = new Color4f(0.66f, 0.90f, 1.00f, 0.95f);

    static SimpleAppearance appearance(Color4f c) {
        return new SimpleAppearance(c.x, c.y, c.z, c.w,
                SimpleAppearance.DISABLE_CULLING);
    }

    static GlassyPanel panel(float w, float h, float depth, Color4f c) {
        return new GlassyPanel(w, h, depth, appearance(c));
    }

    static GlassyText2D makeText(String s, float maxWidth, float h, Color4f c,
            GlassyText2D.Alignment align) {
        return new GlassyText2D(s, maxWidth, h, c,
                GlassyText2D.LightDirection.TOP_LEFT, align);
    }

    /** Wrap a node so its local origin sits at (x, y, z) in the parent. */
    static OriginTranslation at(Node node, float x, float y, float z) {
        return new OriginTranslation(node, new Vector3f(x, y, z));
    }

    /** A text label vertically centered on {@code cy} (text grows upward). */
    static OriginTranslation label(String s, float maxWidth, float h, Color4f c,
            GlassyText2D.Alignment align, float cx, float cy, float z) {
        GlassyText2D t = makeText(s, maxWidth, h, c, align);
        return new OriginTranslation(t, new Vector3f(cx, cy - h * 0.5f, z));
    }

    /**
     * Wrap an arbitrary scene-graph node in a {@link Component3D} (inside a
     * {@link TransparencyOrderedGroup} for correct blending) so it can be added
     * straight to a {@code Container3D}/{@code Frame3D}, which only accept
     * {@code Component3D} children. This is the same
     * {@code Component3D(TransparencyOrderedGroup(...))} idiom {@code Lg3dHelp}
     * uses to put its body into the frame.
     */
    static Component3D component(Node node) {
        TransparencyOrderedGroup tog = new TransparencyOrderedGroup();
        tog.addChild(node);
        Component3D c = new Component3D();
        c.addChild(tog);
        return c;
    }

    /**
     * A glassy push button centered at its own origin: a translucent panel with
     * a centered label, hover appearance + scale highlight, and a click action.
     * The caller positions the returned component with
     * {@link Component3D#setTranslation}.
     */
    static Component3D button(String text, float w, float h, float textH,
            Color4f off, Color4f on, Color4f textCol, ActionNoArg onClick) {
        Component3D c = new Component3D();

        TransparencyOrderedGroup tog = new TransparencyOrderedGroup();
        GlassyPanel bg = panel(w, h, 0.002f, off);
        tog.addChild(bg);
        tog.addChild(label(text, w * 0.94f, textH, textCol,
                GlassyText2D.Alignment.CENTER, 0.0f, 0.0f, 0.0025f));
        c.addChild(tog);

        c.addListener(new MouseEnteredEventAdapter(
                new AppearanceChangeAction(bg, appearance(on))));
        c.addListener(new MouseEnteredEventAdapter(
                new ScaleActionBoolean(c, 1.06f, 120)));
        if (onClick != null) {
            c.addListener(new MouseClickedEventAdapter(onClick));
        }
        c.setCursor(Cursor3D.SMALL_CURSOR);
        return c;
    }
}
