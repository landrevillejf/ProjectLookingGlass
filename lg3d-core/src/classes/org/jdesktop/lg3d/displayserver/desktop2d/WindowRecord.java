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

import java.awt.Rectangle;

/**
 * One persisted application window of a 2D-desktop session: enough to relaunch
 * the application and put its window back where the user left it.
 *
 * <p>The {@code command} is the start-menu descriptor command (e.g.
 * {@code java org.jdesktop.lg3d.apps.calculator.Calculator}) the desktop
 * relaunches the panel from; {@code appName} keys the window the way the
 * taskbar and the launcher's duplicate check do; {@code iconResource} is the
 * classpath icon so the restored window keeps its artwork. The bounds and the
 * iconified/maximised flags capture the window's placement.</p>
 *
 * <p>Pure, immutable data plus AWT geometry (no Swing, no Java 3D), so a record
 * and its {@linkplain #restoredBounds(Rectangle) on-screen clamping} are
 * unit-testable headless.</p>
 */
final class WindowRecord {

    private final String appName;
    private final String command;
    private final String iconResource;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final boolean iconified;
    private final boolean maximized;

    /**
     * @param appName      the window/taskbar name (non-blank)
     * @param command      the descriptor command to relaunch from (non-blank)
     * @param iconResource the classpath icon location, or null for none
     * @param x            the window's left edge in desktop-pane coordinates
     * @param y            the window's top edge in desktop-pane coordinates
     * @param width        the window width, at least 1
     * @param height       the window height, at least 1
     * @param iconified    whether the window was minimised
     * @param maximized    whether the window was maximised
     * @throws IllegalArgumentException if {@code appName} or {@code command} is
     *                                  blank, or a dimension is not positive
     */
    WindowRecord(String appName, String command, String iconResource,
                 int x, int y, int width, int height,
                 boolean iconified, boolean maximized) {
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("appName must be non-blank");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must be non-blank");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "width and height must be positive: " + width + "x" + height);
        }
        this.appName = appName;
        this.command = command;
        this.iconResource = (iconResource == null || iconResource.isBlank())
                ? null : iconResource;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.iconified = iconified;
        this.maximized = maximized;
    }

    String appName() {
        return appName;
    }

    String command() {
        return command;
    }

    /** The classpath icon location, or null when the window had none. */
    String iconResource() {
        return iconResource;
    }

    int x() {
        return x;
    }

    int y() {
        return y;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    boolean iconified() {
        return iconified;
    }

    boolean maximized() {
        return maximized;
    }

    /** The persisted placement, as recorded (no clamping). */
    Rectangle bounds() {
        return new Rectangle(x, y, width, height);
    }

    /**
     * The persisted placement pulled fully inside {@code desktop}, so a session
     * saved on a larger or differently-arranged screen never strands a window
     * off-screen. The size is capped to the desktop and the position clamped so
     * the whole window (title bar included) stays reachable; when the desktop is
     * null or not yet laid out the raw {@link #bounds()} are returned.
     */
    Rectangle restoredBounds(Rectangle desktop) {
        if (desktop == null || desktop.width <= 0 || desktop.height <= 0) {
            return bounds();
        }
        int w = Math.min(width, desktop.width);
        int h = Math.min(height, desktop.height);
        int clampedX = clamp(x, desktop.x, desktop.x + desktop.width - w);
        int clampedY = clamp(y, desktop.y, desktop.y + desktop.height - h);
        return new Rectangle(clampedX, clampedY, w, h);
    }

    private static int clamp(int value, int min, int max) {
        // A desktop narrower than the minimum would make max < min; prefer min.
        return (max < min) ? min : Math.max(min, Math.min(value, max));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WindowRecord)) {
            return false;
        }
        WindowRecord r = (WindowRecord) other;
        return x == r.x && y == r.y && width == r.width && height == r.height
                && iconified == r.iconified && maximized == r.maximized
                && appName.equals(r.appName) && command.equals(r.command)
                && java.util.Objects.equals(iconResource, r.iconResource);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(appName, command, iconResource,
                x, y, width, height, iconified, maximized);
    }

    @Override
    public String toString() {
        return "WindowRecord[" + appName + " @ " + x + "," + y + " " + width
                + "x" + height + (iconified ? " iconified" : "")
                + (maximized ? " maximized" : "") + "]";
    }
}
