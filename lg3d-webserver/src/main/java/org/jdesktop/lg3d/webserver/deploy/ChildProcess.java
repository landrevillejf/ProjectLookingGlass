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
 * A long-lived child process the server forked and keeps supervising -- the
 * handle a {@link DeploymentType#JAR} application runs behind.
 *
 * <p>Where {@link ProcessLauncher} runs a command to completion (a {@code docker}
 * invocation), this is the opposite: a Spring Boot {@code bootJar} started with
 * {@code java -jar} that must stay up, be probed for liveness, have its recent
 * output read, and be stopped on demand. Keeping it behind an interface lets the
 * {@link JarProcessBackend} be tested with a fake that never forks a real JVM.</p>
 *
 * <p>Implementations are expected to drain the process output on their own thread
 * so a chatty child cannot fill the OS pipe buffer and deadlock, and to bound the
 * retained output so a long-running app does not grow the heap without limit.</p>
 */
public interface ChildProcess {

    /** @return the operating-system process id. */
    long pid();

    /** @return true while the child has not exited. */
    boolean isAlive();

    /**
     * Asks the child to terminate, escalating to a forcible kill if it ignores
     * the request. Never throws; stopping an already-exited process is a no-op.
     */
    void stop();

    /**
     * @return the most recent combined stdout/stderr, oldest lines dropped once
     *         the retained window is exceeded (never null; may be empty)
     */
    String output();
}
