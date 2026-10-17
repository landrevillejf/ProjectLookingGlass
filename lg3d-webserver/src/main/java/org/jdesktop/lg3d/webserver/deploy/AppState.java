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
package org.jdesktop.lg3d.webserver.deploy;

/**
 * The lifecycle state of a deployed application.
 *
 * <p>Deliberately small: an app is either not running ({@link #STOPPED}), live
 * ({@link #RUNNING}) or its last start/stop attempt failed ({@link #FAILED},
 * with the reason kept on {@link Deployment#lastError()}). A deploy that cannot
 * reach the Docker daemon lands in {@code FAILED} with a readable message rather
 * than disappearing.</p>
 */
public enum AppState {
    /** Deployed but not currently running. */
    STOPPED,
    /** Live: the container is up or the child process is alive. */
    RUNNING,
    /** The last lifecycle attempt failed; see {@code lastError}. */
    FAILED
}
