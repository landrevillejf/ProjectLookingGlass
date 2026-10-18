/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import java.nio.file.Path;

/**
 * One JDK known to the manager. A JDK is either <em>managed</em> (Espresso
 * downloaded it into the shared store) or <em>registered</em> (the user pointed
 * Espresso at a folder they already had, e.g. {@code ~/.jdks/jdk-21...}). Either
 * way a project references it by {@link #id()} and never by an absolute path, so
 * the same project keeps resolving its JDK if the store moves.
 *
 * @param id      a stable identifier (the distribution folder name)
 * @param version the feature version, best-effort ("21", "25.0.1+7")
 * @param home    the JDK home directory (the folder holding {@code bin/java})
 * @param managed true when Espresso downloaded it, false when user-registered
 */
record JdkEntry(String id, String version, Path home, boolean managed) {

    JdkEntry {
        id = (id == null) ? "" : id;
        version = (version == null) ? "" : version;
    }

    /** The list-row label: the version, the id when no version was read. */
    String display() {
        String v = version.isEmpty() ? id : version;
        return managed ? v + "  (managed)" : v + "  (" + home + ")";
    }
}
