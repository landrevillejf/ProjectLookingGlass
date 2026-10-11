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
package org.jdesktop.lg3d.displayserver.desktop2d.screensaver;

/**
 * The lifecycle a screen-saver view component is expected to support.
 * {@link ScreenSaver#createView} must return a {@code javax.swing.JComponent}
 * that also implements this interface, so the host (
 * {@link ScreensaverService} on the desktop, the Control Center's preview
 * pane in the Screen Saver panel) can start and stop the animation without
 * knowing anything about the saver's internals.
 *
 * <p>Both methods must be idempotent: calling {@link #start()} twice starts
 * one animation loop, calling {@link #stop()} on a stopped view does nothing
 * (the host tears the view down without knowing whether it was ever started,
 * and a test run can overlap a user gesture).</p>
 *
 * <p>Contract for implementors:</p>
 * <ul>
 *   <li>{@code start}/{@code stop} are called on the EDT only.</li>
 *   <li>The view must own its animation clock (a {@code javax.swing.Timer}
 *       is the expected mechanism) and must not leak it: {@code stop()}
 *       halts the clock, and a tick that throws must stop the loop rather
 *       than crash the desktop (see {@link ScreenSaver} on the
 *       self-healing base class shipped by the saver suite).</li>
 *   <li>The view must not create top-level windows (no {@code JFrame} /
 *       {@code JWindow}); it is embedded in a host-managed container.</li>
 * </ul>
 */
public interface ScreenSaverView {

    /** Begins (or re-begins) the animation. Idempotent. EDT only. */
    void start();

    /** Halts the animation and releases any per-view clock. Idempotent. */
    void stop();
}
