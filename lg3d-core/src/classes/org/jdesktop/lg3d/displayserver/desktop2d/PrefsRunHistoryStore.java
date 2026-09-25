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
 * The {@link RunHistoryStore} backed by the user {@link Preferences} node for
 * the desktop2d package — the same node {@link PrefsSessionStore} writes the
 * saved session to, so the run dialog's recalled commands live beside the other
 * per-user desktop settings and survive a restart.
 *
 * <p>The whole history is a single encoded {@link String} under one key (see
 * {@link RunHistory#encode()}); this class is thin glue over
 * {@code put}/{@code get}/{@code flush}, so the encoding and the add/trim logic
 * carry all the testable behaviour and this stays out of the way. A backing
 * store failure is logged and swallowed: losing the run history must never stop
 * the dialog from opening.</p>
 */
final class PrefsRunHistoryStore implements RunHistoryStore {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** The preferences key the encoded history is stored under. */
    static final String KEY_HISTORY = "run.history";

    private final Preferences prefs;

    /** Uses the desktop2d package's user preferences node. */
    PrefsRunHistoryStore() {
        this(LgPreferencesHelper.userNodeForPackage(PrefsRunHistoryStore.class));
    }

    /** Uses an explicit node; package-visible so the store can be injected. */
    PrefsRunHistoryStore(Preferences prefs) {
        this.prefs = prefs;
    }

    @Override
    public void save(RunHistory history) {
        String encoded = (history == null) ? "" : history.encode();
        prefs.put(KEY_HISTORY, encoded);
        flush();
    }

    @Override
    public RunHistory load() {
        return RunHistory.decode(prefs.get(KEY_HISTORY, null));
    }

    @Override
    public void clear() {
        prefs.remove(KEY_HISTORY);
        flush();
    }

    /** Forces the value to disk now; the JVM may exit right after a save. */
    private void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException bse) {
            logger.log(Level.WARNING, "Could not persist the 2D run history", bse);
        }
    }
}
