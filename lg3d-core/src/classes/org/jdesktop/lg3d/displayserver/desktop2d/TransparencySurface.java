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
package org.jdesktop.lg3d.displayserver.desktop2d;

import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

/**
 * A paintable surface of the conventional 2D desktop whose opacity the user can
 * set, one per {@link DesktopConfig} preference so the four can be faded
 * independently.
 *
 * <p>Each constant also states <em>what</em> fades, because the right answer
 * differs per surface and the difference is deliberate:</p>
 *
 * <ul>
 *   <li>{@link #WINDOW} and {@link #WIDGET} fade as a <b>whole</b> - chrome,
 *   background and content together. A window hosts third-party Swing panels
 *   that paint themselves opaque, so fading only the chrome would show no
 *   wallpaper at all; fading the composite keeps the content's own contrast.</li>
 *   <li>{@link #TASKBAR} and {@link #NOTIFICATION} fade only their
 *   <b>backdrop</b>. Both are small, must be readable at a glance and are painted
 *   by the shell itself, so their buttons, titles and text stay fully opaque.</li>
 * </ul>
 *
 * <p>Every constant reads and writes its own persisted percent
 * ({@link DesktopConfig#MIN_SURFACE_OPACITY}..100); the painting lives in
 * {@link DesktopTransparency}.</p>
 */
public enum TransparencySurface {

    /** The application windows on the desktop pane: title bar and content. */
    WINDOW {
        @Override
        public int opacityOf(DesktopConfig cfg) {
            return cfg.getWindowOpacity();
        }

        @Override
        public void setOpacityOf(DesktopConfig cfg, int percent) {
            cfg.setWindowOpacity(percent);
        }
    },

    /** The taskbar shelf at the top or bottom edge of the screen. */
    TASKBAR {
        @Override
        public int opacityOf(DesktopConfig cfg) {
            return cfg.getTaskbarOpacity();
        }

        @Override
        public void setOpacityOf(DesktopConfig cfg, int percent) {
            cfg.setTaskbarOpacity(percent);
        }
    },

    /** The widget cards the widget layer drops on the wallpaper. */
    WIDGET {
        @Override
        public int opacityOf(DesktopConfig cfg) {
            return cfg.getWidgetOpacity();
        }

        @Override
        public void setOpacityOf(DesktopConfig cfg, int percent) {
            cfg.setWidgetOpacity(percent);
        }
    },

    /** The transient notification cards (toasts) in the corner. */
    NOTIFICATION {
        @Override
        public int opacityOf(DesktopConfig cfg) {
            return cfg.getNotificationOpacity();
        }

        @Override
        public void setOpacityOf(DesktopConfig cfg, int percent) {
            cfg.setNotificationOpacity(percent);
        }
    };

    /**
     * This surface's persisted opacity, as a percent of solid: {@code 100} fully
     * opaque (the default and the pixel-identical legacy look),
     * {@link DesktopConfig#MIN_SURFACE_OPACITY} maximally faded.
     */
    public abstract int opacityOf(DesktopConfig cfg);

    /** Stores this surface's opacity (clamped by {@link DesktopConfig}). */
    public abstract void setOpacityOf(DesktopConfig cfg, int percent);
}
