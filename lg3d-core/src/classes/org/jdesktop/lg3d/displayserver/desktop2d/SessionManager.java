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
import java.util.ArrayList;
import java.util.List;

/**
 * Captures the 2D desktop's open application windows into a
 * {@link SessionSnapshot} and persists it through a {@link SessionStore}, and
 * hands the saved snapshot back for {@link Desktop2D} to relaunch.
 *
 * <p>Capture reads only each window's identity, bounds and iconified/maximised
 * flags — no rendering — so it runs headless, and windows that cannot be
 * relaunched (no descriptor command, e.g. a folder opened ad hoc) are skipped
 * rather than persisted unrestorable. Relaunching itself needs the desktop's
 * launcher and icon lookup, so it stays in {@link Desktop2D}; this class is the
 * persistence half of the round trip.</p>
 */
final class SessionManager {

    private final SessionStore store;

    /** Uses the preferences-backed store. */
    SessionManager() {
        this(new PrefsSessionStore());
    }

    /** Uses an explicit store; package-visible so tests can inject a fake. */
    SessionManager(SessionStore store) {
        this.store = store;
    }

    /** Captures {@code openWindows} and persists the result. */
    void save(List<Desktop2DWindow> openWindows) {
        store.save(capture(openWindows));
    }

    /** The last saved session, or {@link SessionSnapshot#EMPTY} if none. */
    SessionSnapshot load() {
        SessionSnapshot snapshot = store.load();
        return (snapshot == null) ? SessionSnapshot.EMPTY : snapshot;
    }

    /** Forgets any saved session. */
    void clear() {
        store.clear();
    }

    /**
     * Builds a snapshot of {@code openWindows}, preserving their order and
     * dropping any that cannot be relaunched. Pure: reads window state only, so
     * it is unit-testable headless.
     */
    static SessionSnapshot capture(List<Desktop2DWindow> openWindows) {
        if (openWindows == null || openWindows.isEmpty()) {
            return SessionSnapshot.EMPTY;
        }
        List<WindowRecord> records = new ArrayList<>(openWindows.size());
        for (Desktop2DWindow window : openWindows) {
            WindowRecord record = recordFor(window);
            if (record != null) {
                records.add(record);
            }
        }
        return records.isEmpty() ? SessionSnapshot.EMPTY : new SessionSnapshot(records);
    }

    /**
     * The record for one window, or null if it carries no relaunch command (or
     * no name). A degenerate size is lifted to 1px so a minimised or not-yet-
     * laid-out window still round-trips through {@link WindowRecord}'s
     * positive-dimension guard.
     */
    private static WindowRecord recordFor(Desktop2DWindow window) {
        if (window == null) {
            return null;
        }
        String command = window.getCommand();
        String appName = window.getAppName();
        if (command == null || command.isBlank()
                || appName == null || appName.isBlank()) {
            return null;
        }
        Rectangle bounds = window.getBounds();
        int width = Math.max(1, bounds.width);
        int height = Math.max(1, bounds.height);
        return new WindowRecord(appName, command, window.getIconResource(),
                bounds.x, bounds.y, width, height,
                window.isIcon(), window.isMaximum());
    }
}
