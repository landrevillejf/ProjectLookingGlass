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
package org.jdesktop.lg3d.widgets.host;

import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Container3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jogamp.vecmath.Vector3f;

/**
 * The desktop widget layer: a screen-sized {@link Container3D} placed in front of
 * the background and behind application windows. Widget nodes are added as
 * children and positioned absolutely (the default null layout manager leaves them
 * where {@link Component3D#setTranslation} puts them).
 *
 * <p>Positions are expressed as a fraction of the screen - {@code fx} in
 * {@code [0,1]} left-to-right and {@code fy} in {@code [0,1]} top-to-bottom - so a
 * saved layout survives resolution changes. The scene origin is the centre of the
 * screen with +x to the right and +y up, hence the conversions below.</p>
 */
public class WidgetLayer extends Container3D {

    /** Depth of the widget plane: in front of the background (far -z), behind
     *  application windows (z ~ 0), roughly level with the taskbar. */
    static final float WIDGET_Z = -0.05f;

    /** Keeps widgets clear of the taskbar strip along the bottom edge. */
    private static final float TASKBAR_MARGIN = 0.035f;

    private float screenWidth = 1.0f;
    private float screenHeight = 1.0f;

    public WidgetLayer() {
        setName("WidgetLayer");
        setTranslation(0.0f, 0.0f, WIDGET_Z);
        updateScreenSize();
    }

    /**
     * Refreshes the cached screen dimensions from {@link Toolkit3D}. Values that
     * are not yet available (canvas not realized) are ignored so a previous good
     * size is retained.
     */
    public final void updateScreenSize() {
        try {
            Toolkit3D tk = Toolkit3D.getToolkit3D();
            float w = tk.getScreenWidth();
            float h = tk.getScreenHeight();
            if (w > 0f) {
                screenWidth = w;
            }
            if (h > 0f) {
                screenHeight = h;
            }
        } catch (Throwable t) {
            // Toolkit not ready yet; keep the fallback/previous size.
        }
        setPreferredSize(new Vector3f(screenWidth, screenHeight, 0f));
    }

    public float screenWidth() { return screenWidth; }
    public float screenHeight() { return screenHeight; }

    /** Converts fractional screen coords to a layer-local translation. */
    public Vector3f fractionalToWorld(float fx, float fy, Vector3f out) {
        out.set((clamp01(fx) - 0.5f) * screenWidth,
                (0.5f - clamp01(fy)) * screenHeight,
                0.0f);
        return out;
    }

    public float worldXToFraction(float worldX) {
        return clamp01(worldX / screenWidth + 0.5f);
    }

    public float worldYToFraction(float worldY) {
        return clamp01(0.5f - worldY / screenHeight);
    }

    /** Places a widget node at the given fractional screen position. */
    public void placeAt(Component3D node, float fx, float fy) {
        Vector3f v = fractionalToWorld(fx, fy, new Vector3f());
        node.setTranslation(v.x, v.y, v.z);
    }

    /**
     * Clamps a widget's current translation so the widget stays fully on screen
     * (and clear of the taskbar). Called after a drag ends.
     */
    public void clampToScreen(Component3D node) {
        Vector3f t = node.getTranslation(new Vector3f());
        Vector3f ps = node.getPreferredSize(new Vector3f());
        float halfW = Math.max(0f, ps.x * 0.5f);
        float halfH = Math.max(0f, ps.y * 0.5f);

        float left = -screenWidth * 0.5f + halfW;
        float right = screenWidth * 0.5f - halfW;
        float top = screenHeight * 0.5f - halfH;
        float bottom = -screenHeight * 0.5f + halfH + TASKBAR_MARGIN;

        float x = (left <= right) ? clamp(t.x, left, right) : 0f;
        float y = (bottom <= top) ? clamp(t.y, bottom, top) : 0f;
        node.setTranslation(x, y, t.z);
    }

    private static float clamp01(float v) {
        return (v < 0f) ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp(float v, float lo, float hi) {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }
}
