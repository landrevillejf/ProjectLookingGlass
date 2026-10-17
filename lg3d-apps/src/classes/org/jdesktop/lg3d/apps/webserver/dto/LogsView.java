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
 * The tailed output of one deployment: the payload of
 * {@code GET /api/apps/{id}/logs}.
 *
 * <p>A client-side mirror of the server's own {@code LogsView} record; the
 * console never imports a server class, so the wire shape is restated here.
 * Unknown fields are ignored so a newer server can add to the payload without
 * breaking an older console.</p>
 *
 * @param id   the deployment the logs belong to
 * @param logs the tailed output, or an empty string when there is none yet
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogsView(String id, String logs) {

    /** The logs, never {@code null} (an absent tail reads as empty). */
    public String logsOrEmpty() {
        return logs == null ? "" : logs;
    }
}
