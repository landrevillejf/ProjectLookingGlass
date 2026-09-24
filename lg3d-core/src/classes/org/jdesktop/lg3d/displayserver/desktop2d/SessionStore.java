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

/**
 * Where the 2D desktop keeps its saved session. A seam so the snapshot
 * capture/restore logic can be exercised headless against an in-memory fake
 * rather than the real user preferences (which would write to the developer's
 * home directory during a test run).
 *
 * @see PrefsSessionStore
 */
interface SessionStore {

    /** Persists {@code snapshot}, replacing any previously saved session. */
    void save(SessionSnapshot snapshot);

    /** The last saved session, or {@link SessionSnapshot#EMPTY} if none. */
    SessionSnapshot load();

    /** Forgets any saved session; the next {@link #load()} is empty. */
    void clear();
}
