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
 * The {@code GET /api/server/info} payload: build/runtime identity plus a live
 * {@link MetricsView} snapshot.
 *
 * <p>A client-side mirror of the server's own {@code ServerInfoView} record; the
 * console never imports a server class, so the wire shape is restated here.
 * Unknown fields are ignored so a newer server can add to the payload without
 * breaking an older console.</p>
 *
 * @param version     the server build version, or {@code "dev"} off a source tree
 * @param port        the port the server is actually listening on
 * @param javaVersion the JVM version the server runs on
 * @param startTime   the server JVM's start time in epoch millis
 * @param metrics     a point-in-time JVM snapshot
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServerInfoView(
        String version,
        int port,
        String javaVersion,
        long startTime,
        MetricsView metrics) {
}
