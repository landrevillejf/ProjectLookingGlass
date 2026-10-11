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

import java.util.List;
import java.util.Map;
import javax.swing.JComponent;

/**
 * The screen-saver service provider interface.
 *
 * <p>The 2D desktop's {@link ScreensaverService} watches for user idleness and,
 * when the configured timeout expires, covers every screen with a full-screen
 * window hosting the view of the selected saver. Savers are discovered with
 * {@link java.util.ServiceLoader} (see {@link ScreenSaverRegistry}), so the
 * desktop core depends on this contract only - the shipped suite lives in
 * lg3d-apps (written in Kotlin), and a third party can add more by dropping a
 * jar with a {@code META-INF/services} registration on the desktop classpath.
 * That is what makes the feature extensible without an SDK.</p>
 *
 * <p><b>Implementor contract.</b> A saver must be cheap to construct (the
 * registry instantiates every implementation to enumerate them), must be
 * thread-safe for the metadata methods (they may be called from the Control
 * Center's JVM-side UI), and its {@link #createView} must be called on the
 * EDT. The returned component may run its own animation clock but must honour
 * the {@link ScreenSaverView} lifecycle: {@code stop()} really stops it. A
 * view that throws during a tick is caught by the host for the first frame,
 * but a saver that wants production-grade self-healing should catch its own
 * exceptions (the Kotlin {@code SaverPanel} base class of the shipped suite
 * does exactly that).</p>
 *
 * <p><b>Security note.</b> A screensaver is a power/embellishment feature, not
 * a lock: the shell does not authenticate the returning user. Views must not
 * pretend otherwise, and the host never hides user data behind a fake login
 * prompt.</p>
 */
public interface ScreenSaver {

    /**
     * Stable machine identifier, persisted in
     * {@link ScreensaverSettings}. Lowercase, no spaces, no dots - for example
     * {@code "starfield"}. Two savers claiming the same id: the first
     * discovered wins, the rest are logged and skipped by the registry.
     */
    String id();

    /** Human-readable name shown in the saver list (e.g. "Starfield"). */
    String displayName();

    /** One-sentence description shown beside the saver list. */
    String description();

    /**
     * The option schema this saver exposes, in display order. May be empty.
     * Keys must be unique within the list and match what
     * {@link ScreensaverSettings} stores and {@link #createView} receives.
     */
    List<ScreenSaverOption> options();

    /**
     * Builds a fresh view component for one engagement (or one Control Center
     * preview). Called on the EDT. The host sizes the component (full screen
     * bounds in use, preview bounds in the Control Center) before showing it,
     * so the view must adapt to whatever {@code getWidth}/{@code getHeight}
     * report at paint time.
     *
     * @param options this saver's option keys mapped to
     *                {@link ScreenSaverOption#coerce}-validated string values;
     *                never contains keys the saver did not declare
     */
    JComponent createView(Map<String, String> options);
}
