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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One deployed application as the management API renders it: the payload of
 * {@code GET /api/apps}, {@code GET /api/apps/{id}}, {@code POST /api/apps} and
 * the start/stop responses.
 *
 * <p>A client-side mirror of the server's own {@code AppView} record; the console
 * never imports a server class, so the wire shape is restated here. {@code type}
 * and {@code state} arrive as the server's enum {@code name()} strings
 * ({@code DOCKER}/{@code JAR}, {@code RUNNING}/{@code STOPPED}/{@code FAILED})
 * rather than enums, so an older console survives a server adding a new state.
 * Unknown fields are ignored for the same forward-compatibility reason.</p>
 *
 * @param id          the deployment's unique id
 * @param name        the human-facing application name
 * @param type        {@code DOCKER} or {@code JAR}
 * @param image       the docker image, or {@code null} for a jar deploy
 * @param jarPath     the bootJar path, or {@code null} for a docker deploy
 * @param contextPath the reverse-proxy prefix, e.g. {@code /shop}
 * @param port        the published host port
 * @param state       {@code RUNNING}, {@code STOPPED} or {@code FAILED}
 * @param runtimeId   the container id or child pid, or {@code null} when stopped
 * @param deployedAt  when the deployment was created, in epoch millis
 * @param lastError   the last failure's readable message, or {@code null}
 * @param url         the computed public URL the app is reachable at
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppView(
        String id,
        String name,
        String type,
        String image,
        String jarPath,
        String contextPath,
        int port,
        String state,
        String runtimeId,
        long deployedAt,
        String lastError,
        String url) {

    /** Whether the server reports this application running. */
    public boolean isRunning() {
        return "RUNNING".equalsIgnoreCase(state);
    }

    /** The image or jar this deployment runs, whichever applies. */
    public String source() {
        if (image != null && !image.isBlank()) {
            return image;
        }
        return jarPath;
    }
}
