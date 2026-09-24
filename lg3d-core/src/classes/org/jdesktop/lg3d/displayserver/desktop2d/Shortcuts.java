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

import java.util.Optional;
import javax.swing.KeyStroke;

/**
 * Turns a resolved shortcut action id into a call on the desktop.
 *
 * <p>The dispatch is a pure {@code switch} over the action ids in
 * {@link ShortcutMap}, calling the matching method on a small {@link Target}
 * interface. Factoring it out this way lets a test pass a fake {@code Target}
 * that records which action fired, so the whole resolution-and-dispatch path is
 * covered headless without a {@code KeyEventDispatcher}, a focus manager or a
 * display. {@link Desktop2D} supplies the real {@code Target}.</p>
 */
final class Shortcuts {

    private Shortcuts() {
        // no instances
    }

    /** The desktop operations a shortcut can trigger. */
    interface Target {
        void showDesktop();

        void snapLeft();

        void snapRight();

        void snapMaximize();

        void runDialog();

        void openTerminal();

        void closeWindow();
    }

    /**
     * Resolves {@code stroke} through {@code map} and, when it is bound,
     * dispatches the action to {@code target}. Returns true only when a bound
     * action fired, so the caller (a {@code KeyEventDispatcher}) consumes the
     * event; an unbound stroke returns false and falls through to the normal
     * Swing key handling — which is how the Alt+` window switcher keeps working.
     */
    static boolean dispatch(KeyStroke stroke, ShortcutMap map, Target target) {
        if (map == null || target == null) {
            return false;
        }
        Optional<String> action = map.actionFor(stroke);
        if (action.isEmpty()) {
            return false;
        }
        handle(action.get(), target);
        return true;
    }

    /** Fires {@code actionId} on {@code target}; unknown ids are ignored. */
    static void handle(String actionId, Target target) {
        if (actionId == null || target == null) {
            return;
        }
        switch (actionId) {
            case ShortcutMap.SHOW_DESKTOP -> target.showDesktop();
            case ShortcutMap.SNAP_LEFT -> target.snapLeft();
            case ShortcutMap.SNAP_RIGHT -> target.snapRight();
            case ShortcutMap.SNAP_MAXIMIZE -> target.snapMaximize();
            case ShortcutMap.RUN_DIALOG -> target.runDialog();
            case ShortcutMap.OPEN_TERMINAL -> target.openTerminal();
            case ShortcutMap.WINDOW_CLOSE -> target.closeWindow();
            default -> {
                // An unmapped action id: nothing to do.
            }
        }
    }
}
