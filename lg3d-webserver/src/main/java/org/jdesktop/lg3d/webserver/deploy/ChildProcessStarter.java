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

import java.io.IOException;
import java.util.List;

/**
 * The seam that forks a long-lived child process -- the {@code java -jar} launch
 * behind a {@link DeploymentType#JAR} deployment.
 *
 * <p>Production uses {@link RealChildProcessStarter} (a {@link ProcessBuilder});
 * the tests inject a fake returning a controllable {@link ChildProcess}, so no
 * real JVM is ever forked in CI. Unlike {@link ProcessLauncher} this does not wait
 * for the process to finish -- it hands back a live handle immediately.</p>
 */
@FunctionalInterface
public interface ChildProcessStarter {

    /**
     * Starts {@code command} in its own process and returns a supervising handle.
     *
     * @param command the full argv, executable first (no shell expansion)
     * @return a live {@link ChildProcess}
     * @throws IOException if the process could not be started (e.g. no such
     *                     executable); the caller turns this into a
     *                     {@link DeploymentException}
     */
    ChildProcess start(List<String> command) throws IOException;
}
