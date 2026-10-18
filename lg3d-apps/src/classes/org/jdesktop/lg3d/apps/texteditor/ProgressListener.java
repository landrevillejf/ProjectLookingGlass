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

/**
 * A one-way progress callback for the long, off-EDT provisioning work
 * (downloading and extracting a build tool or a JDK). The provisioner reports
 * human-readable milestones ("Downloading Gradle 8.10...", "Extracting...");
 * the host marshals each call onto the EDT and shows it on the status line.
 * Tests pass a no-op or a recording listener.
 */
@FunctionalInterface
interface ProgressListener {

    /** Reports a milestone; may be called from a background thread. */
    void onProgress(String message);

    /** A listener that ignores every update. */
    ProgressListener SILENT = message -> {
    };
}
