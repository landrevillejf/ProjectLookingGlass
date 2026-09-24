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

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.jdesktop.lg3d.utils.prefs.LgPreferencesHelper;

/**
 * The {@link SessionStore} backed by the user {@link Preferences} node for the
 * desktop2d package — the same store {@code DesktopConfig} and the widget layer
 * persist through, so a saved session lives beside the other per-user desktop
 * settings and survives a restart.
 *
 * <p>The whole session is a single encoded {@link String} under one key (see
 * {@link SessionSnapshot#encode()}); this class is thin glue over
 * {@code put}/{@code get}/{@code flush}, so the encoding and the capture logic
 * carry all the testable behaviour and this stays out of the way. A backing
 * store failure is logged and swallowed: losing a saved session must never stop
 * the desktop from starting or exiting.</p>
 */
final class PrefsSessionStore implements SessionStore {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** The preferences key the encoded session is stored under. */
    static final String KEY_SESSION = "session.windows";

    private final Preferences prefs;

    /** Uses the desktop2d package's user preferences node. */
    PrefsSessionStore() {
        this(LgPreferencesHelper.userNodeForPackage(PrefsSessionStore.class));
    }

    /** Uses an explicit node; package-visible so the store can be injected. */
    PrefsSessionStore(Preferences prefs) {
        this.prefs = prefs;
    }

    @Override
    public void save(SessionSnapshot snapshot) {
        String encoded = (snapshot == null) ? "" : snapshot.encode();
        prefs.put(KEY_SESSION, encoded);
        flush();
    }

    @Override
    public SessionSnapshot load() {
        return SessionSnapshot.decode(prefs.get(KEY_SESSION, null));
    }

    @Override
    public void clear() {
        prefs.remove(KEY_SESSION);
        flush();
    }

    /** Forces the value to disk now; the JVM may exit right after a save. */
    private void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException bse) {
            logger.log(Level.WARNING, "Could not persist the 2D desktop session", bse);
        }
    }
}
