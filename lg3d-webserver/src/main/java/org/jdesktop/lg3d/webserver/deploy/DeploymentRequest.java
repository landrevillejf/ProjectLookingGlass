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

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * The {@code POST /api/apps} request body: what to deploy.
 *
 * <p>Only {@code type} is bean-validated (a missing type is a straight 400); the
 * type-specific requirements -- an {@code image} for {@link DeploymentType#DOCKER},
 * a {@code jarPath} for {@link DeploymentType#JAR} -- are checked by the registry
 * so the failure carries a readable sentence rather than a field-error code. A
 * blank {@code name} is derived from the image/jar, a blank {@code contextPath}
 * from the name, and a null {@code port} is auto-assigned from the configured
 * range.</p>
 *
 * @param name        application name (blank derives one)
 * @param type        DOCKER or JAR (required)
 * @param image       Docker image to run (DOCKER)
 * @param jarPath     bootJar path to fork (JAR)
 * @param contextPath reverse-proxy prefix (blank derives {@code /<name>})
 * @param port        host port (null auto-assigns from the range)
 * @param env         extra environment variables passed to the app
 */
public record DeploymentRequest(
        String name,
        @NotNull DeploymentType type,
        String image,
        String jarPath,
        String contextPath,
        Integer port,
        Map<String, String> env) {

    /** @return {@code env} or an empty map when it was omitted. */
    public Map<String, String> envOrEmpty() {
        return env == null ? Map.of() : env;
    }
}
