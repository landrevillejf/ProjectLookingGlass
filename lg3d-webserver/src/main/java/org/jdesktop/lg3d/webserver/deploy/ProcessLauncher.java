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
 * The seam that runs an external command to completion and captures its output.
 *
 * <p>One indirection makes both the Docker CLI and the local-jar backend
 * headless-testable: production uses {@link RealProcessLauncher} (a
 * {@link ProcessBuilder}), the tests inject a fake that records the argv and
 * returns a canned {@link CommandResult} -- so no {@code docker} daemon and no
 * real child process are ever needed in CI.</p>
 *
 * <p>Implementations never throw: a command that cannot be run (missing
 * executable, timeout, interrupt) is reported as a {@link CommandResult#failure}
 * so callers degrade instead of propagating an {@code IOException}.</p>
 */
@FunctionalInterface
public interface ProcessLauncher {

    /**
     * Runs {@code command} and waits for it to finish.
     *
     * @param command       the full argv, executable first (no shell expansion)
     * @param timeoutMillis how long to wait before destroying the process
     * @return the exit code and captured output, or a failure result
     */
    CommandResult launch(List<String> command, long timeoutMillis);
}
