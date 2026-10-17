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
 * Runs and supervises deployed applications of one {@link DeploymentType}.
 *
 * <p>The registry is backend-agnostic: it picks the implementation whose
 * {@link #type()} matches the request and drives it through this lifecycle. Two
 * ship today -- {@link DockerDeploymentBackend} (the primary target: a
 * containerized Spring Boot app driven over the system {@code docker} CLI) and
 * {@link JarProcessBackend} (the daemon-free sibling: a local {@code java -jar}
 * child).</p>
 *
 * <p>Every method reports an operational failure by throwing
 * {@link DeploymentException} carrying a human-readable sentence -- never a bare
 * stack trace -- so the management API can degrade to a clean {@code 503}. A
 * backend whose runtime is simply absent (no Docker daemon) is not an error at the
 * seam level: {@link #isAvailable()} lets the registry and the health endpoint say
 * so plainly.</p>
 */
public interface DeploymentBackend {

    /** @return the deployment type this backend handles. */
    DeploymentType type();

    /**
     * @return true when this backend's runtime can actually start an app right now
     *         (for Docker: a daemon answers a probe)
     */
    boolean isAvailable();

    /**
     * Creates and starts a brand-new instance of {@code deployment}.
     *
     * @return the runtime id (Docker container id, or child PID as a string)
     * @throws DeploymentException if the app could not be created/started
     */
    String deploy(Deployment deployment);

    /**
     * Starts an already-created but stopped instance (a restart).
     *
     * @return the runtime id
     * @throws DeploymentException if the app could not be started
     */
    String start(Deployment deployment);

    /**
     * Stops a running instance, keeping it around so it can be started again.
     *
     * @throws DeploymentException if the app could not be stopped
     */
    void stop(Deployment deployment);

    /**
     * @param tail the maximum number of trailing output lines to return
     * @return the recent application output (never null; may be empty)
     */
    String logs(Deployment deployment, int tail);

    /**
     * Tears the instance down completely (stop + delete). Best effort: a missing
     * instance is not an error.
     */
    void remove(Deployment deployment);

    /** @return true when the instance is currently up. */
    boolean isRunning(Deployment deployment);
}
