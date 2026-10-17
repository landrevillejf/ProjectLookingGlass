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
 * The {@code GET /api/server/health} payload, as the console reads it: whether
 * the server is up and what it can actually run right now.
 *
 * <p>A client-side mirror of the server's own {@code HealthView} record. The two
 * are deliberately separate types in separate modules &mdash; the console never
 * imports a server class (Spring must not enter the desktop JVM) &mdash; so this
 * record restates the wire shape and nothing else. Unknown fields are ignored so
 * a newer server can add to the payload without breaking an older console.</p>
 *
 * @param status          liveness token, {@code "UP"} when the server answers
 * @param dockerAvailable whether a docker daemon is reachable for deploys
 * @param jarAvailable    whether the local bootJar child-process backend is usable
 * @param deployments     how many applications are registered
 * @param running         how many of them are currently running
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthView(
        String status,
        boolean dockerAvailable,
        boolean jarAvailable,
        int deployments,
        int running) {

    /** Whether the server reports itself up. */
    public boolean isUp() {
        return "UP".equalsIgnoreCase(status);
    }
}
