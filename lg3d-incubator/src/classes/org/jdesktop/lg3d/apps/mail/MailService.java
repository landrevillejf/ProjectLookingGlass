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

import java.util.List;

/**
 * The mail backend contract both desktops program against. One instance is bound
 * to one {@link MailAccount} by {@link #connect}; the real implementation is
 * {@link ImapSmtpMailService} (Jakarta Mail), and the tests substitute an
 * in-memory fake so the UI and the {@link MailSessionManager} can be exercised
 * headlessly without a server.
 *
 * <p>Every method throws {@link MailBackendException} rather than a Jakarta Mail
 * type, so no caller needs the mail API on its own import list.</p>
 */
public interface MailService extends AutoCloseable {

    /** Connects and authenticates. Fails with a user-safe message on error. */
    void connect(MailAccount account, String password) throws MailBackendException;

    boolean isConnected();

    /** The account this service is bound to (null before connect). */
    MailAccount getAccount();

    /** Every message-bearing folder on the account, well-known ones first. */
    List<MailFolder> listFolders() throws MailBackendException;

    /** Unread-message badge count for a folder. */
    int unreadCount(String folderName) throws MailBackendException;

    /** Envelopes (headers only) in a folder, newest first, default cap. */
    List<MailMessage> list(String folderName) throws MailBackendException;

    /** Envelopes (headers only) in a folder, newest first, capped at {@code limit}. */
    List<MailMessage> list(String folderName, int limit) throws MailBackendException;

    /** Fetches an envelope's body and attachment metadata. */
    MailMessage open(MailMessage envelope) throws MailBackendException;

    /** Downloads one attachment's bytes on demand. */
    byte[] openAttachment(MailMessage message, MailAttachment attachment)
            throws MailBackendException;

    /** Server-side search over from/subject/to; empty terms lists the folder. */
    List<MailMessage> search(String folderName, String terms)
            throws MailBackendException;

    void setRead(MailMessage message, boolean read) throws MailBackendException;

    void setFlagged(MailMessage message, boolean flagged) throws MailBackendException;

    /** Copies a message to {@code toFolder} and removes it from its current one. */
    void move(MailMessage message, String toFolder) throws MailBackendException;

    /** Marks a message deleted and expunges it. */
    void delete(MailMessage message) throws MailBackendException;

    /** Sends a composed draft (with optional attachments) over SMTP. */
    void send(MailMessage draft, List<MailAttachment> attachments)
            throws MailBackendException;

    /** Closes the connection but keeps the instance reusable via connect(). */
    void disconnect();

    @Override
    void close();
}
