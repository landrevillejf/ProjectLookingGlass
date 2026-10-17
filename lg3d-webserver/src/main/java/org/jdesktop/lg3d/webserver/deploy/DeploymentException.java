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
 * An operational failure while deploying or supervising an application: the
 * Docker daemon is unreachable, an image will not pull, a container exits, a
 * port is already bound.
 *
 * <p>Kept free of any Spring type so the {@code deploy} package stays a pure
 * layer; the controller maps it to a readable {@code 503} body. The message is
 * always fit to show a human -- it is surfaced verbatim on
 * {@link Deployment#lastError()} and in the API response, never a stack
 * trace.</p>
 */
public class DeploymentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DeploymentException(String message) {
        super(message);
    }

    public DeploymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
