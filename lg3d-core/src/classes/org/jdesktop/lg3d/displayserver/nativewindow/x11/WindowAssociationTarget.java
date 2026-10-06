/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

/**
 * The minimal window identity the {@link X11WindowAssociator} needs to evaluate
 * its window-association rules: the window title (WM_NAME) and the WM_CLASS
 * res_name / res_class.
 *
 * <p>{@link X11Client} implements this in production. Extracting it as a seam
 * lets the associator's <em>orchestration</em> — the rule walk in
 * {@link X11WindowAssociator#getAssociatedWindow}, one-time rule retirement,
 * focus selection and {@link X11WindowAssociator#removeAllRules} — be
 * unit-tested headless against a lightweight fake. A real {@code X11Client}
 * extends {@code gnu.x11.Window} and needs a live {@code Display}, so it cannot
 * be constructed in a test JVM; worse, merely <em>class-loading</em> it runs its
 * static {@code new X11WindowAssociator()}, which registers an
 * {@code LgEventConnector} listener and reads preferences. A test that stays on
 * this interface (and the {@code X11WindowAssociator(boolean)} no-wiring
 * constructor) never touches {@code X11Client} at all.
 *
 * <p>The pure cls/name/title clause these values feed is factored out separately
 * as {@link X11WindowAssociator#matches}.
 */
interface WindowAssociationTarget {

    /**
     * The window title (WM_NAME), or {@code null} if unset.
     *
     * @return the window title
     */
    String getName();

    /**
     * The WM_CLASS res_class, or {@code null} if this window has no class hint.
     *
     * @return the WM_CLASS res_class, possibly {@code null}
     */
    String getResClass();

    /**
     * The WM_CLASS res_name, or {@code null} if this window has no class hint.
     *
     * @return the WM_CLASS res_name, possibly {@code null}
     */
    String getResName();
}
