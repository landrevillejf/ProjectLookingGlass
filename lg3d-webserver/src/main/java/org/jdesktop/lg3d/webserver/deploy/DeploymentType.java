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
 * How an application is run.
 *
 * <p>{@link #DOCKER} is the primary target the todo asks for -- a containerized
 * Spring Boot application driven through the system {@code docker} CLI.
 * {@link #JAR} is the daemon-free sibling: the server forks a Spring Boot
 * {@code bootJar} as a local child process, which both covers a machine with no
 * Docker and gives the headless test suite a backend it can exercise with a fake
 * process.</p>
 */
public enum DeploymentType {
    /** A Docker container started from an image. */
    DOCKER,
    /** A local {@code java -jar} child process. */
    JAR
}
