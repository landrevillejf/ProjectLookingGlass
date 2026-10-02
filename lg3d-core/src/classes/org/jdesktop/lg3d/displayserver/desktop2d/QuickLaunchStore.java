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

/**
 * Where the 2D desktop keeps its pinned taskbar quick-launch shortcuts. A seam
 * - exactly like {@link SessionStore} and {@link RunHistoryStore} - so the
 * pin/un-pin/reorder and persistence logic in {@link QuickLaunchModel} can be
 * exercised headless against an in-memory fake rather than the real user
 * preferences (which would write to the developer's home directory during a
 * test run).
 *
 * <p>The {@code seeded} pair records whether the desktop has already written
 * its first-run default set, so an empty list is unambiguous: a user who
 * un-pins everything is not greeted by the defaults again on the next start.</p>
 *
 * @see PrefsQuickLaunchStore
 */
interface QuickLaunchStore {

    /** Persists {@code entries}, replacing any previously saved list. */
    void save(List<QuickLaunchEntry> entries);

    /** The last saved list, or an empty list if none. Never null. */
    List<QuickLaunchEntry> load();

    /** Forgets any saved list; the next {@link #load()} is empty. */
    void clear();

    /** True once the desktop has written its first-run default set. */
    boolean isSeeded();

    /** Records that the default set has been seeded, so it is not repeated. */
    void markSeeded();
}
