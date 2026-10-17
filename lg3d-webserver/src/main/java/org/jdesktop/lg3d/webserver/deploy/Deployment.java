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

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;

/**
 * One deployed application and its current runtime state -- the record the
 * registry persists to {@code deployments.json} and the management API renders.
 *
 * <p>Immutable: a lifecycle transition returns a copy through one of the
 * {@code with*}/{@code running}/{@code stopped}/{@code failed} helpers, so a
 * snapshot handed to a caller never changes underneath them. {@code runtimeId} is
 * the Docker container id for a {@link DeploymentType#DOCKER} app or the child PID
 * for a {@link DeploymentType#JAR} app, and is null while stopped.</p>
 *
 * @param id          unique, docker-name-safe identifier
 * @param name        human-readable application name
 * @param type        how the app runs (Docker container or local jar)
 * @param image       the Docker image (DOCKER only; else null)
 * @param jarPath     the bootJar path (JAR only; else null)
 * @param contextPath the reverse-proxy prefix, e.g. {@code /shop}
 * @param port        the published host port the app is reachable on
 * @param env         extra environment variables passed to the app (may be empty)
 * @param state       the lifecycle state
 * @param runtimeId   the container id / PID while running, else null
 * @param deployedAt  epoch millis when the app was first deployed
 * @param lastError   the reason for a {@link AppState#FAILED} state, else null
 */
public record Deployment(
        String id,
        String name,
        DeploymentType type,
        String image,
        String jarPath,
        String contextPath,
        int port,
        Map<String, String> env,
        AppState state,
        String runtimeId,
        long deployedAt,
        String lastError) {

    /**
     * @return true when the app is currently {@link AppState#RUNNING}
     *
     * <p>Ignored by Jackson so the persisted record stays faithful to its
     * components: this is derived from {@code state}, never stored twice.</p>
     */
    @JsonIgnore
    public boolean isRunning() {
        return state == AppState.RUNNING;
    }

    /** @return {@code env} or an empty map when it was omitted. */
    public Map<String, String> envOrEmpty() {
        return env == null ? Map.of() : env;
    }

    /** @return a copy in {@link AppState#RUNNING} carrying its runtime id. */
    public Deployment running(String newRuntimeId) {
        return new Deployment(id, name, type, image, jarPath, contextPath, port, env,
                AppState.RUNNING, newRuntimeId, deployedAt, null);
    }

    /** @return a copy in {@link AppState#STOPPED} with no runtime id or error. */
    public Deployment stopped() {
        return new Deployment(id, name, type, image, jarPath, contextPath, port, env,
                AppState.STOPPED, null, deployedAt, null);
    }

    /** @return a copy in {@link AppState#FAILED} carrying the reason. */
    public Deployment failed(String error) {
        return new Deployment(id, name, type, image, jarPath, contextPath, port, env,
                AppState.FAILED, runtimeId, deployedAt, error);
    }
}
