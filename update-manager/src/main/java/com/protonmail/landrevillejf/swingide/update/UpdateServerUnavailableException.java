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
package com.protonmail.landrevillejf.swingide.update;

/**
 * Signals that the update metadata endpoint answered with a non-2xx HTTP status
 * (typically 401/404 on a private GitHub release asset, or 5xx on a broken
 * mirror).
 * <p>
 * The condition is operationally equivalent to an unreachable server: the client
 * cannot retrieve the metadata right now, but nothing is wrong with the IDE
 * itself. {@link UpdateChecker} treats it as a quiet skip so neither the
 * periodic background check nor Help&nbsp;&rarr;&nbsp;Check for Update raises a
 * full error dialog when the release host is misconfigured or offline.
 * </p>
 */
public class UpdateServerUnavailableException extends UpdateException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public UpdateServerUnavailableException(int statusCode, String url) {
        super("Update server returned HTTP " + statusCode + " for " + url);
        this.statusCode = statusCode;
    }

    /**
     * HTTP status code returned by the update metadata endpoint.
     */
    public int getStatusCode() {
        return statusCode;
    }
}
