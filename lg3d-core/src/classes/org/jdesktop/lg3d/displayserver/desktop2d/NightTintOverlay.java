/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import org.jdesktop.lg3d.utils.schedule.DayNightCurve;

/**
 * The 2D counterpart of the 3D scene-light day/night shift: a cool, translucent
 * veil painted over the whole desktop whose opacity grows as night falls. The
 * conventional Swing desktop has no scene graph to re-light, so the schedule
 * dims and cools the wallpaper and application windows through this wash
 * instead, mirroring what {@code StandardGlobalLights} does in 3D.
 *
 * <p>Like {@link ToastLayer}, it lives on the desktop pane's
 * {@link JDesktopPane#POPUP_LAYER}, above the application windows, and reports
 * {@link #contains(int, int)} as false so it never eats desktop input. The
 * taskbar sits outside the desktop pane, so it stays crisp and un-tinted. All
 * the opacity math lives in {@link DayNightCurve}, so this class is a thin,
 * headless-constructible view.</p>
 */
final class NightTintOverlay extends JComponent {

    /** The current day/night blend factor (0 = day, 1 = full night). */
    private float factor;
    private JDesktopPane host;

    NightTintOverlay() {
        setOpaque(false);
        setFocusable(false);
        setVisible(false);
    }

    /**
     * Installs the veil on {@code desktop}'s popup layer, above the application
     * windows, and keeps it sized to the pane. Safe to call once from the
     * desktop constructor.
     */
    void install(JDesktopPane desktop) {
        this.host = desktop;
        setBounds(0, 0, desktop.getWidth(), desktop.getHeight());
        desktop.add(this, JDesktopPane.POPUP_LAYER);
        desktop.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeToHost();
            }
        });
    }

    /**
     * Applies the day/night blend {@code factor} (clamped 0..1) and shows,
     * hides or repaints the veil accordingly. A factor of 0 removes the tint
     * entirely. Safe to call from any thread's caller (the schedule service
     * marshals through {@link Desktop2D#setNightTint(float)}).
     */
    void setFactor(float value) {
        factor = clamp01(value);
        resizeToHost();
        setVisible(factor > 0.0f);
        repaint();
    }

    /** The current day/night blend factor (0 = day, 1 = full night). */
    float factor() {
        return factor;
    }

    private void resizeToHost() {
        if (host != null) {
            setBounds(0, 0, host.getWidth(), host.getHeight());
        }
    }

    /** Never the mouse target: the veil must not eat desktop input. */
    @Override
    public boolean contains(int x, int y) {
        return false;
    }

    @Override
    protected void paintComponent(Graphics g) {
        int alpha = DayNightCurve.tintAlpha(factor);
        if (alpha <= 0) {
            return;
        }
        int[] rgb = DayNightCurve.NIGHT_TINT_RGB;
        g.setColor(new Color(rgb[0], rgb[1], rgb[2], alpha));
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }
}
