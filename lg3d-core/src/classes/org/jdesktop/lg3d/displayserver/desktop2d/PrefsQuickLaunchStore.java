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

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.jdesktop.lg3d.utils.prefs.LgPreferencesHelper;

/**
 * The {@link QuickLaunchStore} backed by the user {@link Preferences} node for
 * the desktop2d package - the same node {@link PrefsSessionStore} and
 * {@link PrefsRunHistoryStore} write to, so the pinned taskbar shortcuts live
 * beside the other per-user desktop settings and survive a restart.
 *
 * <p>The whole list is a single encoded {@link String} under one key (see
 * {@link QuickLaunchEntry#encodeList}), and the first-run seed guard is a
 * boolean under a second key; this class is thin glue over
 * {@code put}/{@code get}/{@code flush}, so the encoding and the pin/un-pin
 * logic carry all the testable behaviour and this stays out of the way. A
 * backing store failure is logged and swallowed: losing the pinned shortcuts
 * must never stop the desktop from starting.</p>
 */
final class PrefsQuickLaunchStore implements QuickLaunchStore {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** The preferences key the encoded pin list is stored under. */
    static final String KEY_ENTRIES = "quicklaunch.entries";

    /** The preferences key recording that the defaults were seeded once. */
    static final String KEY_SEEDED = "quicklaunch.seeded";

    private final Preferences prefs;

    /** Uses the desktop2d package's user preferences node. */
    PrefsQuickLaunchStore() {
        this(LgPreferencesHelper.userNodeForPackage(PrefsQuickLaunchStore.class));
    }

    /** Uses an explicit node; package-visible so the store can be injected. */
    PrefsQuickLaunchStore(Preferences prefs) {
        this.prefs = prefs;
    }

    @Override
    public void save(List<QuickLaunchEntry> entries) {
        prefs.put(KEY_ENTRIES, QuickLaunchEntry.encodeList(entries));
        flush();
    }

    @Override
    public List<QuickLaunchEntry> load() {
        return QuickLaunchEntry.decodeList(prefs.get(KEY_ENTRIES, null));
    }

    @Override
    public void clear() {
        prefs.remove(KEY_ENTRIES);
        flush();
    }

    @Override
    public boolean isSeeded() {
        return prefs.getBoolean(KEY_SEEDED, false);
    }

    @Override
    public void markSeeded() {
        prefs.putBoolean(KEY_SEEDED, true);
        flush();
    }

    /** Forces the value to disk now; the JVM may exit right after a save. */
    private void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException bse) {
            logger.log(Level.WARNING,
                    "Could not persist the 2D desktop quick-launch shortcuts", bse);
        }
    }
}
