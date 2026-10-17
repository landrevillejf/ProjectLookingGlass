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

import java.util.List;

/**
 * The seam onto the system {@code docker} CLI -- the same "shell out, no
 * docker-java" approach the desktop's Docker Manager uses.
 *
 * <p>Callers pass the arguments <em>without</em> the leading {@code docker}; the
 * implementation prepends it. Keeping the seam this thin means the
 * {@link DockerDeploymentBackend} owns the exact argv (which the tests assert) and
 * a fake can stand in for the daemon in CI. {@link #isAvailable()} is the graceful
 * degradation point: with no Docker installed or no daemon running it returns
 * {@code false} and the management API reports {@code dockerAvailable=false}
 * instead of failing.</p>
 */
public interface DockerCli {

    /** @return true when a {@code docker} daemon answers a version probe. */
    boolean isAvailable();

    /** @return the daemon's version string, or null when unavailable. */
    String version();

    /**
     * Runs {@code docker <args...>} to completion.
     *
     * @param args the arguments after {@code docker} (e.g. {@code ["ps","-q"]})
     * @return the exit code and captured output, or a failure result
     */
    CommandResult execute(List<String> args);
}
