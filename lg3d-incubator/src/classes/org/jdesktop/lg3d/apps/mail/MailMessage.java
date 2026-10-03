/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single e-mail message: the shared, Java 3D-free model both the 2D
 * {@link MailPanel} and the native-3D {@link Mail3D} render, and the value object
 * the {@link MailService} backend maps to and from the wire (Jakarta Mail).
 *
 * <p>A message is fetched in two stages. Listing a folder produces lightweight
 * <em>envelopes</em> carrying the headers, flags and attachment metadata but no
 * body ({@link #isBodyLoaded()} is {@code false}); opening one fills the text /
 * HTML body and the attachment bytes on demand. That split keeps a large folder
 * fast to list and only pays for the payload the user actually reads.</p>
 *
 * <p>{@link #getId()} is the stable per-folder identifier the backend uses to
 * re-find the message (the IMAP UID where the server offers one, else the message
 * number). {@link #getFolder()} and {@link #getAccountId()} say where it lives so
 * a triage action (move / delete / flag) can be routed back to the right account
 * and folder.</p>
 */
public class MailMessage {

    /** Standard folder names; real folders come from the server but these seed the UI. */
    public static final String FOLDER_INBOX = "INBOX";
    public static final String FOLDER_SENT = "Sent";
    public static final String FOLDER_DRAFTS = "Drafts";
    public static final String FOLDER_TRASH = "Trash";
    public static final String FOLDER_JUNK = "Junk";

    private String id = "";
    private String accountId = "";
    private String folder = FOLDER_INBOX;

    private MailAddress from = MailAddress.of("");
    private final List<MailAddress> to = new ArrayList<MailAddress>();
    private final List<MailAddress> cc = new ArrayList<MailAddress>();
    private final List<MailAddress> bcc = new ArrayList<MailAddress>();

    private String subject = "";
    private String textBody = "";
    private String htmlBody;            // nullable; only when the message has one

    private long sentDate = System.currentTimeMillis();
    private long receivedDate = System.currentTimeMillis();

    private boolean read;
    private boolean answered;
    private boolean flagged;
    private boolean draft;

    private final List<MailAttachment> attachments = new ArrayList<MailAttachment>();
    private boolean attachmentsPresent;
    private boolean bodyLoaded;

    public MailMessage() {
    }

    // ------------------------------------------------------------------
    // Identity / routing
    // ------------------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = (id == null) ? "" : id;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = (accountId == null) ? "" : accountId;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = (folder == null) ? FOLDER_INBOX : folder;
    }

    // ------------------------------------------------------------------
    // Addresses
    // ------------------------------------------------------------------

    public MailAddress getFrom() {
        return from;
    }

    public void setFrom(MailAddress from) {
        this.from = (from == null) ? MailAddress.of("") : from;
    }

    public List<MailAddress> getTo() {
        return to;
    }

    public List<MailAddress> getCc() {
        return cc;
    }

    public List<MailAddress> getBcc() {
        return bcc;
    }

    public void setTo(List<MailAddress> addresses) {
        replace(to, addresses);
    }

    public void setCc(List<MailAddress> addresses) {
        replace(cc, addresses);
    }

    public void setBcc(List<MailAddress> addresses) {
        replace(bcc, addresses);
    }

    private static void replace(List<MailAddress> target, List<MailAddress> src) {
        target.clear();
        if (src != null) {
            for (MailAddress a : src) {
                if (a != null) {
                    target.add(a);
                }
            }
        }
    }

    /** The single primary recipient, or an empty address when there is none. */
    public MailAddress primaryTo() {
        return to.isEmpty() ? MailAddress.of("") : to.get(0);
    }

    /** A comma-joined {@code "Name <email>"} list, for the reading-pane header. */
    public static String join(List<MailAddress> addresses) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addresses.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(addresses.get(i).format());
        }
        return sb.toString();
    }

    public String toLine() {
        return join(to);
    }

    public String ccLine() {
        return join(cc);
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = (subject == null) ? "" : subject;
    }

    public String getTextBody() {
        return textBody;
    }

    public void setTextBody(String textBody) {
        this.textBody = (textBody == null) ? "" : textBody;
    }

    public String getHtmlBody() {
        return htmlBody;
    }

    public void setHtmlBody(String htmlBody) {
        this.htmlBody = htmlBody;
    }

    public boolean hasHtmlBody() {
        return htmlBody != null && !htmlBody.isEmpty();
    }

    public List<MailAttachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<MailAttachment> list) {
        attachments.clear();
        if (list != null) {
            for (MailAttachment a : list) {
                if (a != null) {
                    attachments.add(a);
                }
            }
        }
    }

    public void addAttachment(MailAttachment a) {
        if (a != null) {
            attachments.add(a);
        }
    }

    public boolean hasAttachments() {
        return attachmentsPresent || !attachments.isEmpty();
    }

    /**
     * Sets the envelope-level hint that a message carries attachments, derived
     * cheaply from its content type while listing (before the multipart is
     * parsed). Cleared implicitly once {@link #setAttachments} fills the real
     * metadata on open.
     */
    public void setAttachmentsPresent(boolean attachmentsPresent) {
        this.attachmentsPresent = attachmentsPresent;
    }

    public boolean isBodyLoaded() {
        return bodyLoaded;
    }

    public void setBodyLoaded(boolean bodyLoaded) {
        this.bodyLoaded = bodyLoaded;
    }

    // ------------------------------------------------------------------
    // Dates
    // ------------------------------------------------------------------

    public long getSentDate() {
        return sentDate;
    }

    public void setSentDate(long sentDate) {
        this.sentDate = sentDate;
    }

    public long getReceivedDate() {
        return receivedDate;
    }

    public void setReceivedDate(long receivedDate) {
        this.receivedDate = receivedDate;
    }

    /** The date the list sorts and shows: sent when known, else received. */
    public long getWhen() {
        return sentDate > 0 ? sentDate : receivedDate;
    }

    // ------------------------------------------------------------------
    // Flags
    // ------------------------------------------------------------------

    public boolean isRead() {
        return read;
    }

    public void setRead(boolean read) {
        this.read = read;
    }

    public boolean isAnswered() {
        return answered;
    }

    public void setAnswered(boolean answered) {
        this.answered = answered;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public void setFlagged(boolean flagged) {
        this.flagged = flagged;
    }

    public boolean isDraft() {
        return draft;
    }

    public void setDraft(boolean draft) {
        this.draft = draft;
    }

    /** An unmodifiable view of every recipient, for rule matching. */
    public List<MailAddress> allRecipients() {
        List<MailAddress> all = new ArrayList<MailAddress>(to.size() + cc.size()
                + bcc.size());
        all.addAll(to);
        all.addAll(cc);
        all.addAll(bcc);
        return Collections.unmodifiableList(all);
    }

    @Override
    public String toString() {
        return (subject.isEmpty() ? "(no subject)" : subject);
    }
}
