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
package org.jdesktop.lg3d.apps.mail;

/**
 * The single checked failure the {@link MailService} backend reports, wrapping a
 * {@code jakarta.mail.MessagingException} (or an I/O error) in a mail-domain
 * exception so the UI never has to import Jakarta Mail types.
 *
 * <p>The message is safe to show the user (a connection / auth / folder problem);
 * the underlying cause is retained for the log but never includes a password.</p>
 */
public class MailBackendException extends Exception {

    private static final long serialVersionUID = 1L;

    public MailBackendException(String message) {
        super(message);
    }

    public MailBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
