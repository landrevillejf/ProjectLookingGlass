/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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

import java.util.prefs.Preferences;

/**
 * A single e-mail message for the native 3D mail client. Mirrors the
 * {@code Appointment} model in the agenda package: a plain value object that
 * knows how to serialise itself to / from a {@link Preferences} node so the
 * mailbox survives across desktop launches.
 *
 * <p>A message lives in exactly one folder ({@link #FOLDER_INBOX} or
 * {@link #FOLDER_SENT}) and carries the four header lines the reading pane
 * shows (from / to / subject / date) plus a wrapped body. {@code read} drives
 * the unread dot in the message list.</p>
 */
public class MailMessage {

    public static final String FOLDER_INBOX = "inbox";
    public static final String FOLDER_SENT = "sent";

    private static final String K_FROM = "from";
    private static final String K_FROM_EMAIL = "fromEmail";
    private static final String K_TO = "to";
    private static final String K_TO_EMAIL = "toEmail";
    private static final String K_SUBJECT = "subject";
    private static final String K_BODY = "body";
    private static final String K_WHEN = "when";
    private static final String K_READ = "read";
    private static final String K_FOLDER = "folder";

    private final String id;
    private String from;
    private String fromEmail;
    private String to;
    private String toEmail;
    private String subject;
    private String body;
    private long when;
    private boolean read;
    private String folder;

    public MailMessage(String id, String from, String fromEmail,
            String to, String toEmail, String subject, String body,
            String folder) {
        this.id = id;
        this.from = from;
        this.fromEmail = fromEmail;
        this.to = to;
        this.toEmail = toEmail;
        this.subject = subject;
        this.body = body;
        this.when = System.currentTimeMillis();
        this.read = false;
        this.folder = folder;
    }

    public String getId() {
        return id;
    }

    public String getFrom() {
        return from;
    }

    public String getFromEmail() {
        return fromEmail;
    }

    public String getTo() {
        return to;
    }

    public String getToEmail() {
        return toEmail;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public long getWhen() {
        return when;
    }

    public boolean isRead() {
        return read;
    }

    public String getFolder() {
        return folder;
    }

    public void setTo(String to, String toEmail) {
        this.to = to;
        this.toEmail = toEmail;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    /** Package-private: lets the seeder stagger sample timestamps. */
    void setWhen(long when) {
        this.when = when;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    /** Rebuilds a message from its persisted preferences node. */
    static MailMessage readFrom(String id, Preferences node) {
        MailMessage m = new MailMessage(id,
                node.get(K_FROM, ""), node.get(K_FROM_EMAIL, ""),
                node.get(K_TO, ""), node.get(K_TO_EMAIL, ""),
                node.get(K_SUBJECT, "(no subject)"), node.get(K_BODY, ""),
                node.get(K_FOLDER, FOLDER_INBOX));
        m.when = node.getLong(K_WHEN, System.currentTimeMillis());
        m.read = node.getBoolean(K_READ, false);
        return m;
    }

    /** Writes every field into the given preferences node. */
    void writeTo(Preferences node) {
        node.put(K_FROM, from);
        node.put(K_FROM_EMAIL, fromEmail);
        node.put(K_TO, to);
        node.put(K_TO_EMAIL, toEmail);
        node.put(K_SUBJECT, subject);
        node.put(K_BODY, body);
        node.putLong(K_WHEN, when);
        node.putBoolean(K_READ, read);
        node.put(K_FOLDER, folder);
    }
}
