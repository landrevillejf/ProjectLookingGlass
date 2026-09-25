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
import javax.swing.JComponent;

/**
 * A software brightness dim for hosts whose hardware backlight is not
 * controllable unprivileged (a root-owned {@code /sys/class/backlight} node with
 * no polkit agent to escalate through). Installed as the desktop frame's glass
 * pane, it paints a translucent black wash over the whole desktop - wallpaper,
 * windows and taskbar - whose opacity grows as the requested brightness falls,
 * so the taskbar brightness slider always produces a visible change.
 *
 * <p>The overlay reports {@link #contains(int, int)} as false, which keeps it
 * out of Swing's event-dispatch path entirely: it paints above everything but
 * never swallows a click or a drag. Popups (including the brightness slider's
 * own) live in the root pane's popup layer, above the glass layer, so they stay
 * crisp and fully interactive while the desktop behind them dims.</p>
 */
final class BrightnessDimmer extends JComponent {

    /** Wash opacity at 0% brightness; 100% paints nothing at all. */
    static final int MAX_ALPHA = 180;

    /** The brightness percentage currently applied (100 = no dim). */
    private int percent = 100;

    BrightnessDimmer() {
        setOpaque(false);
        setFocusable(false);
    }

    /** The brightness percentage currently applied (100 = no dim). */
    int percent() {
        return percent;
    }

    /**
     * Applies a brightness percentage (0-100), clamped, and shows or hides the
     * wash accordingly. 100 removes the dim entirely.
     */
    void setPercent(int value) {
        percent = BrightnessStatus.clamp(value);
        setVisible(percent < 100);
        repaint();
    }

    /** The wash opacity for a brightness percentage; pure so it is testable. */
    static int alphaFor(int percent) {
        return MAX_ALPHA * (100 - BrightnessStatus.clamp(percent)) / 100;
    }

    /** Never the mouse target: the overlay must not eat desktop input. */
    @Override
    public boolean contains(int x, int y) {
        return false;
    }

    @Override
    protected void paintComponent(Graphics g) {
        int alpha = alphaFor(percent);
        if (alpha <= 0) {
            return;
        }
        g.setColor(new Color(0, 0, 0, alpha));
        g.fillRect(0, 0, getWidth(), getHeight());
    }
}
