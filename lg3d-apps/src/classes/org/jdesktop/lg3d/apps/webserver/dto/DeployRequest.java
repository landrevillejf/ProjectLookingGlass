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
package org.jdesktop.lg3d.apps.webserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The {@code POST /api/apps} request body the console sends to deploy an
 * application: what to run, and how to expose it.
 *
 * <p>A client-side mirror of the server's own {@code DeploymentRequest} record;
 * the console never imports a server class, so the wire shape is restated here.
 * Only {@code type} is required by the server &mdash; a blank {@code name} is
 * derived from the image/jar, a blank {@code contextPath} from the name, and a
 * {@code null} {@code port} is auto-assigned from the server's configured range
 * &mdash; so every field but {@code type} is optional and nulls are omitted from
 * the JSON rather than sent as explicit nulls.</p>
 *
 * @param name        application name (blank derives one server-side)
 * @param type        {@code DOCKER} or {@code JAR} (required)
 * @param image       docker image to run (DOCKER deploys)
 * @param jarPath     bootJar path to fork (JAR deploys)
 * @param contextPath reverse-proxy prefix (blank derives {@code /<name>})
 * @param port        host port (null auto-assigns from the server's range)
 * @param env         extra environment variables passed to the app
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeployRequest(
        String name,
        String type,
        String image,
        String jarPath,
        String contextPath,
        Integer port,
        Map<String, String> env) {

    /** The {@code type} wire value for a containerized deploy. */
    public static final String TYPE_DOCKER = "DOCKER";

    /** The {@code type} wire value for a local bootJar child-process deploy. */
    public static final String TYPE_JAR = "JAR";

    /**
     * A containerized deploy: run {@code image}, letting the server derive the
     * name/context and auto-assign a port.
     */
    public static DeployRequest docker(String image) {
        return new DeployRequest(null, TYPE_DOCKER, image, null, null, null, null);
    }

    /**
     * A containerized deploy with an explicit name, proxy prefix and port.
     *
     * @param port the host port, or {@code null} to auto-assign
     */
    public static DeployRequest docker(String name, String image, String contextPath,
            Integer port) {
        return new DeployRequest(name, TYPE_DOCKER, image, null, contextPath, port, null);
    }

    /**
     * A local bootJar deploy: fork {@code jarPath}, letting the server derive the
     * name/context and auto-assign a port.
     */
    public static DeployRequest jar(String jarPath) {
        return new DeployRequest(null, TYPE_JAR, null, jarPath, null, null, null);
    }

    /**
     * A local bootJar deploy with an explicit name, proxy prefix and port.
     *
     * @param port the host port, or {@code null} to auto-assign
     */
    public static DeployRequest jar(String name, String jarPath, String contextPath,
            Integer port) {
        return new DeployRequest(name, TYPE_JAR, null, jarPath, contextPath, port, null);
    }
}
