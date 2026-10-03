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

import jakarta.activation.DataHandler;
import jakarta.mail.Address;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.RecipientStringTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import jakarta.mail.util.ByteArrayDataSource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * The real {@link MailService}: IMAP for reading folders / messages and SMTP for
 * sending, on Jakarta Mail 2.x (the Eclipse Angus provider). One instance is bound
 * to one {@link MailAccount} by {@link #connect(MailAccount, String)}.
 *
 * <p>Design notes for the plan's non-functional goals:</p>
 * <ul>
 *   <li><b>Fast</b> - listing fetches only headers
 *       ({@link FetchProfile.Item#ENVELOPE}/FLAGS/CONTENT_INFO) so a big folder is
 *       cheap to show; bodies and attachment bytes are pulled lazily on
 *       {@link #open}/{@link #openAttachment}.</li>
 *   <li><b>Secure</b> - TLS by default (SSL or STARTTLS); certificate validation is
 *       left on (no {@code ssl.trust=*} wildcard); plaintext is only used when the
 *       account explicitly selects {@link MailAccount.Security#NONE}.</li>
 *   <li><b>Reliable</b> - explicit connect/read/write timeouts so a dead server
 *       fails fast instead of hanging the caller.</li>
 * </ul>
 */
public class ImapSmtpMailService implements MailService {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int WRITE_TIMEOUT_MS = 30000;
    private static final int DEFAULT_LIST_LIMIT = 200;

    private MailAccount account;
    private String password;
    private Store store;

    @Override
    public void connect(MailAccount account, String password)
            throws MailBackendException {
        this.account = account;
        this.password = password;
        String proto = (account.getImapSecurity() == MailAccount.Security.SSL)
                ? "imaps" : "imap";
        Session session = Session.getInstance(imapProperties(proto, account));
        try {
            store = session.getStore(proto);
            String user = login(account);
            store.connect(account.getImapHost(), account.getImapPort(),
                    user, password);
        } catch (MessagingException e) {
            store = null;
            throw new MailBackendException(
                    "Could not connect to " + account.getImapHost() + ": "
                            + rootMessage(e), e);
        }
    }

    private static String login(MailAccount account) {
        String u = account.getUsername();
        return (u == null || u.isEmpty()) ? account.getEmailAddress() : u;
    }

    private Properties imapProperties(String proto, MailAccount a) {
        Properties p = baseProperties(proto);
        MailAccount.Security sec = a.getImapSecurity();
        if (sec == MailAccount.Security.SSL) {
            p.put("mail." + proto + ".ssl.enable", "true");
        } else if (sec == MailAccount.Security.STARTTLS) {
            p.put("mail." + proto + ".starttls.enable", "true");
            p.put("mail." + proto + ".starttls.required", "true");
        }
        return p;
    }

    private Properties smtpProperties(MailAccount a) {
        String proto = (a.getSmtpSecurity() == MailAccount.Security.SSL)
                ? "smtps" : "smtp";
        Properties p = baseProperties(proto);
        // Pin the transport to the account's SMTP server. Without these,
        // Transport.send falls back to an MX lookup of the recipient domain and
        // tries localhost:25, which fails with a ConnectException.
        if (a.getSmtpHost() != null && !a.getSmtpHost().isEmpty()) {
            p.put("mail." + proto + ".host", a.getSmtpHost());
        }
        if (a.getSmtpPort() > 0) {
            p.put("mail." + proto + ".port", String.valueOf(a.getSmtpPort()));
        }
        MailAccount.Security sec = a.getSmtpSecurity();
        if (sec == MailAccount.Security.SSL) {
            p.put("mail." + proto + ".ssl.enable", "true");
        } else if (sec == MailAccount.Security.STARTTLS) {
            p.put("mail." + proto + ".starttls.enable", "true");
            p.put("mail." + proto + ".starttls.required", "true");
        }
        return p;
    }

    private static Properties baseProperties(String proto) {
        Properties p = new Properties();
        p.put("mail." + proto + ".auth", "true");
        p.put("mail." + proto + ".connectiontimeout",
                String.valueOf(CONNECT_TIMEOUT_MS));
        p.put("mail." + proto + ".timeout", String.valueOf(READ_TIMEOUT_MS));
        p.put("mail." + proto + ".writetimeout", String.valueOf(WRITE_TIMEOUT_MS));
        // Fail rather than silently downgrade: no wildcard trust, no plaintext
        // fallback. Certificate validation stays on the JDK default truststore.
        return p;
    }

    @Override
    public boolean isConnected() {
        return store != null && store.isConnected();
    }

    @Override
    public MailAccount getAccount() {
        return account;
    }

    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------

    @Override
    public List<MailFolder> listFolders() throws MailBackendException {
        requireConnection();
        List<MailFolder> out = new ArrayList<MailFolder>();
        try {
            Folder[] folders = store.getDefaultFolder().list("*");
            for (Folder f : folders) {
                if ((f.getType() & Folder.HOLDS_MESSAGES) == 0) {
                    continue;
                }
                out.add(toMailFolder(f));
            }
            if (out.isEmpty()) {
                Folder inbox = store.getFolder("INBOX");
                if (inbox.exists()) {
                    out.add(toMailFolder(inbox));
                }
            }
            sortFolders(out);
        } catch (MessagingException e) {
            throw new MailBackendException("Could not list folders: "
                    + rootMessage(e), e);
        }
        return out;
    }

    private MailFolder toMailFolder(Folder f) throws MessagingException {
        MailFolder mf = new MailFolder(account.getId(), f.getFullName());
        if (f.isOpen()) {
            mf.setMessageCount(f.getMessageCount());
            mf.setUnreadCount(f.getUnreadMessageCount());
        } else {
            // Cheap metadata; counts require opening, so leave them at 0 here and
            // let unreadCount(name) fill the inbox badge on demand.
            mf.setMessageCount(0);
            mf.setUnreadCount(0);
        }
        return mf;
    }

    /** Pins the well-known folders to the top in a conventional order. */
    private static void sortFolders(List<MailFolder> folders) {
        folders.sort((a, b) -> Integer.compare(rank(a.getType()), rank(b.getType())));
    }

    private static int rank(MailFolder.Type t) {
        switch (t) {
            case INBOX: return 0;
            case SENT: return 1;
            case DRAFTS: return 2;
            case TRASH: return 3;
            case JUNK: return 4;
            default: return 5;
        }
    }

    @Override
    public int unreadCount(String folderName) throws MailBackendException {
        requireConnection();
        Folder f = null;
        try {
            f = store.getFolder(folderName);
            if (!f.exists()) {
                return 0;
            }
            f.open(Folder.READ_ONLY);
            return f.getUnreadMessageCount();
        } catch (MessagingException e) {
            throw new MailBackendException("Could not read folder " + folderName
                    + ": " + rootMessage(e), e);
        } finally {
            closeQuietly(f);
        }
    }

    // ------------------------------------------------------------------
    // Listing / reading
    // ------------------------------------------------------------------

    @Override
    public List<MailMessage> list(String folderName) throws MailBackendException {
        return list(folderName, DEFAULT_LIST_LIMIT);
    }

    @Override
    public List<MailMessage> list(String folderName, int limit)
            throws MailBackendException {
        requireConnection();
        Folder f = null;
        try {
            f = openReadable(folderName);
            int count = f.getMessageCount();
            if (count == 0) {
                return new ArrayList<MailMessage>();
            }
            int from = Math.max(1, count - Math.max(1, limit) + 1);
            Message[] msgs = f.getMessages(from, count);
            fetchHeaders(f, msgs);
            List<MailMessage> out = new ArrayList<MailMessage>(msgs.length);
            for (Message m : msgs) {
                out.add(toEnvelope(f, m));
            }
            java.util.Collections.reverse(out);   // newest first
            return out;
        } catch (MessagingException e) {
            throw new MailBackendException("Could not list " + folderName + ": "
                    + rootMessage(e), e);
        } finally {
            closeQuietly(f);
        }
    }

    private void fetchHeaders(Folder f, Message[] msgs) throws MessagingException {
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.FLAGS);
        fp.add(FetchProfile.Item.CONTENT_INFO);
        f.fetch(msgs, fp);
    }

    private MailMessage toEnvelope(Folder f, Message m) throws MessagingException {
        MailMessage msg = new MailMessage();
        msg.setAccountId(account.getId());
        msg.setFolder(f.getFullName());
        msg.setId(idOf(f, m));
        Address[] from = m.getFrom();
        if (from != null && from.length > 0) {
            msg.setFrom(toAddress(from[0]));
        }
        msg.setTo(toAddresses(m.getRecipients(Message.RecipientType.TO)));
        msg.setCc(toAddresses(m.getRecipients(Message.RecipientType.CC)));
        msg.setSubject(m.getSubject() == null ? "" : m.getSubject());
        Date sent = m.getSentDate();
        Date recv = m.getReceivedDate();
        if (sent != null) {
            msg.setSentDate(sent.getTime());
        }
        if (recv != null) {
            msg.setReceivedDate(recv.getTime());
        } else if (sent != null) {
            msg.setReceivedDate(sent.getTime());
        }
        Flags flags = m.getFlags();
        msg.setRead(flags.contains(Flags.Flag.SEEN));
        msg.setAnswered(flags.contains(Flags.Flag.ANSWERED));
        msg.setFlagged(flags.contains(Flags.Flag.FLAGGED));
        msg.setDraft(flags.contains(Flags.Flag.DRAFT));
        String ct = m.getContentType();
        msg.setAttachmentsPresent(ct != null
                && ct.toLowerCase().startsWith("multipart/mixed"));
        return msg;
    }

    @Override
    public MailMessage open(MailMessage envelope) throws MailBackendException {
        requireConnection();
        Folder f = null;
        try {
            f = openReadable(envelope.getFolder());
            Message m = resolve(f, envelope);
            if (m == null) {
                throw new MailBackendException("Message no longer exists in "
                        + envelope.getFolder());
            }
            MailMessage full = toEnvelope(f, m);
            List<MailAttachment> atts = new ArrayList<MailAttachment>();
            parsePart(m, full, atts);
            full.setAttachments(atts);
            full.setAttachmentsPresent(!atts.isEmpty());
            full.setBodyLoaded(true);
            return full;
        } catch (MessagingException | java.io.IOException e) {
            throw new MailBackendException("Could not open message: "
                    + rootMessage(e), e);
        } finally {
            closeQuietly(f);
        }
    }

    @Override
    public byte[] openAttachment(MailMessage message, MailAttachment att)
            throws MailBackendException {
        requireConnection();
        Folder f = null;
        try {
            f = openReadable(message.getFolder());
            Message m = resolve(f, message);
            if (m == null) {
                throw new MailBackendException("Message no longer exists");
            }
            List<Part> parts = new ArrayList<Part>();
            collectAttachmentParts(m, parts);
            int index = message.getAttachments().indexOf(att);
            if (index < 0 || index >= parts.size()) {
                throw new MailBackendException("Attachment not found");
            }
            try (java.io.InputStream in = parts.get(index).getInputStream()) {
                return in.readAllBytes();
            }
        } catch (MessagingException | java.io.IOException e) {
            throw new MailBackendException("Could not download attachment: "
                    + rootMessage(e), e);
        } finally {
            closeQuietly(f);
        }
    }

    /** Recursively pulls text/html bodies and attachment metadata out of a part. */
    private void parsePart(Part p, MailMessage target, List<MailAttachment> atts)
            throws MessagingException, java.io.IOException {
        String fileName = p.getFileName();
        if (fileName != null && !fileName.isEmpty()) {
            atts.add(new MailAttachment(fileName, baseMimeType(p.getContentType()),
                    Math.max(0, p.getSize())));
            return;
        }
        if (p.isMimeType("text/plain")) {
            if (target.getTextBody().isEmpty()) {
                target.setTextBody(asString(p.getContent()));
            }
        } else if (p.isMimeType("text/html")) {
            if (target.getHtmlBody() == null) {
                target.setHtmlBody(asString(p.getContent()));
            }
        } else if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                parsePart(mp.getBodyPart(i), target, atts);
            }
        } else {
            Object c = p.getContent();
            if (c instanceof String) {
                if (target.getTextBody().isEmpty()) {
                    target.setTextBody((String) c);
                }
            }
        }
    }

    /** Collects the attachment-bearing parts in document order (for download). */
    private void collectAttachmentParts(Part p, List<Part> out)
            throws MessagingException, java.io.IOException {
        String fileName = p.getFileName();
        if (fileName != null && !fileName.isEmpty()) {
            out.add(p);
            return;
        }
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                collectAttachmentParts(mp.getBodyPart(i), out);
            }
        }
    }

    private static String baseMimeType(String contentType) {
        if (contentType == null) {
            return "application/octet-stream";
        }
        int semi = contentType.indexOf(';');
        return (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
    }

    private static String asString(Object content) {
        return (content == null) ? "" : content.toString();
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    @Override
    public List<MailMessage> search(String folderName, String terms)
            throws MailBackendException {
        requireConnection();
        if (terms == null || terms.trim().isEmpty()) {
            return list(folderName);
        }
        Folder f = null;
        try {
            f = openReadable(folderName);
            String t = terms.trim();
            SearchTerm term = new OrTerm(
                    new FromStringTerm(t),
                    new OrTerm(new SubjectTerm(t),
                            new RecipientStringTerm(Message.RecipientType.TO, t)));
            Message[] msgs = f.search(term);
            fetchHeaders(f, msgs);
            List<MailMessage> out = new ArrayList<MailMessage>(msgs.length);
            for (Message m : msgs) {
                out.add(toEnvelope(f, m));
            }
            java.util.Collections.reverse(out);
            return out;
        } catch (MessagingException e) {
            throw new MailBackendException("Search failed: " + rootMessage(e), e);
        } finally {
            closeQuietly(f);
        }
    }

    // ------------------------------------------------------------------
    // Triage
    // ------------------------------------------------------------------

    @Override
    public void setRead(MailMessage m, boolean read) throws MailBackendException {
        setFlag(m, Flags.Flag.SEEN, read);
    }

    @Override
    public void setFlagged(MailMessage m, boolean flagged)
            throws MailBackendException {
        setFlag(m, Flags.Flag.FLAGGED, flagged);
    }

    private void setFlag(MailMessage m, Flags.Flag flag, boolean value)
            throws MailBackendException {
        requireConnection();
        Folder f = null;
        try {
            f = openWritable(m.getFolder());
            Message msg = resolve(f, m);
            if (msg == null) {
                return;
            }
            msg.setFlag(flag, value);
        } catch (MessagingException e) {
            throw new MailBackendException("Could not update flags: "
                    + rootMessage(e), e);
        } finally {
            closeQuietly(f);
        }
    }

    @Override
    public void move(MailMessage m, String toFolder) throws MailBackendException {
        requireConnection();
        Folder src = null;
        Folder dst = null;
        try {
            src = openWritable(m.getFolder());
            Message msg = resolve(src, m);
            if (msg == null) {
                return;
            }
            dst = store.getFolder(toFolder);
            if (!dst.exists()) {
                dst.create(Folder.HOLDS_MESSAGES);
            }
            src.copyMessages(new Message[] { msg }, dst);
            msg.setFlag(Flags.Flag.DELETED, true);
        } catch (MessagingException e) {
            throw new MailBackendException("Could not move message: "
                    + rootMessage(e), e);
        } finally {
            // expunge() on close removes the \Deleted copy from the source.
            expungeAndClose(src);
            closeQuietly(dst);
        }
    }

    @Override
    public void delete(MailMessage m) throws MailBackendException {
        requireConnection();
        Folder f = null;
        try {
            f = openWritable(m.getFolder());
            Message msg = resolve(f, m);
            if (msg != null) {
                msg.setFlag(Flags.Flag.DELETED, true);
            }
        } catch (MessagingException e) {
            throw new MailBackendException("Could not delete message: "
                    + rootMessage(e), e);
        } finally {
            expungeAndClose(f);
        }
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    @Override
    public void send(MailMessage draft, List<MailAttachment> attachments)
            throws MailBackendException {
        if (account == null) {
            throw new MailBackendException("No account for sending");
        }
        try {
            Session session = Session.getInstance(smtpProperties(account));
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(toInternet(account.fromAddress()));
            msg.setRecipients(Message.RecipientType.TO,
                    toInternet(draft.getTo()));
            msg.setRecipients(Message.RecipientType.CC,
                    toInternet(draft.getCc()));
            msg.setRecipients(Message.RecipientType.BCC,
                    toInternet(draft.getBcc()));
            msg.setSubject(draft.getSubject());
            msg.setSentDate(new Date());

            boolean hasAtt = attachments != null && !attachments.isEmpty();
            MimeBodyPart body = new MimeBodyPart();
            MimeMultipart alt = null;
            if (draft.hasHtmlBody()) {
                alt = new MimeMultipart("alternative");
                MimeBodyPart text = new MimeBodyPart();
                text.setText(draft.getTextBody(), "UTF-8");
                MimeBodyPart html = new MimeBodyPart();
                html.setContent(draft.getHtmlBody(), "text/html; charset=UTF-8");
                alt.addBodyPart(text);
                alt.addBodyPart(html);
                body.setContent(alt);
            } else {
                body.setText(draft.getTextBody(), "UTF-8");
            }

            if (hasAtt) {
                MimeMultipart mixed = new MimeMultipart("mixed");
                mixed.addBodyPart(body);
                for (MailAttachment a : attachments) {
                    mixed.addBodyPart(attachmentPart(a));
                }
                msg.setContent(mixed);
            } else if (alt != null) {
                msg.setContent(alt);
            } else {
                msg.setText(draft.getTextBody(), "UTF-8");
            }
            msg.saveChanges();
            Transport.send(msg, login(account), password);
        } catch (MessagingException | java.io.IOException e) {
            throw new MailBackendException("Could not send message: "
                    + rootMessage(e), e);
        }
    }

    private static MimeBodyPart attachmentPart(MailAttachment a)
            throws MessagingException, java.io.IOException {
        MimeBodyPart part = new MimeBodyPart();
        if (a.getSource() != null) {
            part.attachFile(a.getSource());
        } else {
            byte[] data = a.getData();
            if (data == null) {
                data = new byte[0];
            }
            part.setDataHandler(new DataHandler(
                    new ByteArrayDataSource(data, a.getMimeType())));
            part.setFileName(a.getFileName());
        }
        return part;
    }

    private static Address[] toInternet(List<MailAddress> addresses)
            throws MessagingException {
        List<Address> out = new ArrayList<Address>();
        if (addresses != null) {
            for (MailAddress a : addresses) {
                if (a != null && a.hasEmail()) {
                    out.add(toInternet(a));
                }
            }
        }
        return out.toArray(new Address[0]);
    }

    private static InternetAddress toInternet(MailAddress a)
            throws MessagingException {
        InternetAddress ia = new InternetAddress(a.getEmail());
        if (a.getName() != null && !a.getName().isEmpty()) {
            try {
                ia.setPersonal(a.getName(), "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                throw new MessagingException("Cannot encode display name", e);
            }
        }
        return ia;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void requireConnection() throws MailBackendException {
        if (!isConnected()) {
            throw new MailBackendException("Not connected to the mail server");
        }
    }

    private Folder openReadable(String name) throws MessagingException {
        Folder f = store.getFolder(name);
        if (!f.isOpen()) {
            f.open(Folder.READ_ONLY);
        }
        return f;
    }

    private Folder openWritable(String name) throws MessagingException {
        Folder f = store.getFolder(name);
        if (!f.isOpen()) {
            f.open(Folder.READ_WRITE);
        }
        return f;
    }

    /** Finds the wire message for an envelope, by UID when offered else number. */
    private Message resolve(Folder f, MailMessage m) throws MessagingException {
        long id;
        try {
            id = Long.parseLong(m.getId());
        } catch (NumberFormatException e) {
            return null;
        }
        if (f instanceof UIDFolder) {
            return ((UIDFolder) f).getMessageByUID(id);
        }
        if (id >= 1 && id <= f.getMessageCount()) {
            return f.getMessage((int) id);
        }
        return null;
    }

    private static String idOf(Folder f, Message m) throws MessagingException {
        if (f instanceof UIDFolder) {
            long uid = ((UIDFolder) f).getUID(m);
            if (uid > 0) {
                return Long.toString(uid);
            }
        }
        return Integer.toString(m.getMessageNumber());
    }

    private static MailAddress toAddress(Address a) {
        if (a instanceof InternetAddress) {
            InternetAddress ia = (InternetAddress) a;
            return new MailAddress(ia.getPersonal(), ia.getAddress());
        }
        return MailAddress.of(a.toString());
    }

    private static List<MailAddress> toAddresses(Address[] addresses) {
        List<MailAddress> out = new ArrayList<MailAddress>();
        if (addresses != null) {
            for (Address a : addresses) {
                out.add(toAddress(a));
            }
        }
        return out;
    }

    private static void closeQuietly(Folder f) {
        if (f != null && f.isOpen()) {
            try {
                f.close(false);
            } catch (MessagingException ignored) {
                // best effort
            }
        }
    }

    private static void expungeAndClose(Folder f) {
        if (f != null && f.isOpen()) {
            try {
                f.close(true);
            } catch (MessagingException ignored) {
                // best effort
            }
        }
    }

    /** The most specific human message from a MessagingException chain. */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        String msg = cur.getMessage();
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
            if (cur.getMessage() != null) {
                msg = cur.getMessage();
            }
        }
        return (msg == null) ? t.getClass().getSimpleName() : msg;
    }

    @Override
    public void disconnect() {
        if (store != null) {
            try {
                store.close();
            } catch (MessagingException ignored) {
                // best effort
            }
        }
        store = null;
        password = null;
    }

    @Override
    public void close() {
        disconnect();
    }
}
