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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link MailService} used by the headless {@link MailSessionManager}
 * and {@link MailPanel} tests so the UI and the manager can be exercised without a
 * server. Folders are seeded with {@link #seed}; every mutation (flags, move,
 * delete, send) is applied to the in-memory lists so the tests can assert on them,
 * and outgoing drafts are captured in {@link #sent}.
 */
final class FakeMailService implements MailService {

    final Map<String, List<MailMessage>> folders =
            new LinkedHashMap<String, List<MailMessage>>();
    final List<MailMessage> sent = new ArrayList<MailMessage>();

    boolean connected;
    MailAccount account;
    String password;
    int connectCount;

    /** Seeds a folder, creating it if needed. */
    void seed(String folder, MailMessage... msgs) {
        List<MailMessage> list = folders.get(folder);
        if (list == null) {
            list = new ArrayList<MailMessage>();
            folders.put(folder, list);
        }
        for (MailMessage m : msgs) {
            m.setFolder(folder);
            list.add(m);
        }
    }

    private List<MailMessage> folder(String name) {
        List<MailMessage> list = folders.get(name);
        if (list == null) {
            list = new ArrayList<MailMessage>();
            folders.put(name, list);
        }
        return list;
    }

    private MailMessage find(String folderName, String id) {
        for (MailMessage m : folder(folderName)) {
            if (m.getId().equals(id)) {
                return m;
            }
        }
        return null;
    }

    @Override
    public void connect(MailAccount account, String password) {
        this.account = account;
        this.password = password;
        this.connected = true;
        this.connectCount++;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public MailAccount getAccount() {
        return account;
    }

    @Override
    public List<MailFolder> listFolders() {
        List<MailFolder> out = new ArrayList<MailFolder>();
        for (Map.Entry<String, List<MailMessage>> e : folders.entrySet()) {
            MailFolder f = new MailFolder(
                    account == null ? "" : account.getId(), e.getKey());
            int unread = 0;
            for (MailMessage m : e.getValue()) {
                if (!m.isRead()) {
                    unread++;
                }
            }
            f.setUnreadCount(unread);
            f.setMessageCount(e.getValue().size());
            out.add(f);
        }
        return out;
    }

    @Override
    public int unreadCount(String folderName) {
        int unread = 0;
        for (MailMessage m : folder(folderName)) {
            if (!m.isRead()) {
                unread++;
            }
        }
        return unread;
    }

    @Override
    public List<MailMessage> list(String folderName) {
        return new ArrayList<MailMessage>(folder(folderName));
    }

    @Override
    public List<MailMessage> list(String folderName, int limit) {
        List<MailMessage> all = folder(folderName);
        int from = Math.max(0, all.size() - limit);
        return new ArrayList<MailMessage>(all.subList(from, all.size()));
    }

    @Override
    public MailMessage open(MailMessage envelope) {
        MailMessage m = find(envelope.getFolder(), envelope.getId());
        MailMessage target = (m == null) ? envelope : m;
        target.setBodyLoaded(true);
        return target;
    }

    @Override
    public byte[] openAttachment(MailMessage message, MailAttachment attachment) {
        return attachment.getData() == null ? new byte[0] : attachment.getData();
    }

    @Override
    public List<MailMessage> search(String folderName, String terms) {
        if (terms == null || terms.trim().isEmpty()) {
            return list(folderName);
        }
        String t = terms.toLowerCase();
        List<MailMessage> out = new ArrayList<MailMessage>();
        for (MailMessage m : folder(folderName)) {
            if (m.getFrom().format().toLowerCase().contains(t)
                    || m.getSubject().toLowerCase().contains(t)
                    || m.toLine().toLowerCase().contains(t)) {
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public void setRead(MailMessage message, boolean read) {
        message.setRead(read);
        MailMessage stored = find(message.getFolder(), message.getId());
        if (stored != null) {
            stored.setRead(read);
        }
    }

    @Override
    public void setFlagged(MailMessage message, boolean flagged) {
        message.setFlagged(flagged);
        MailMessage stored = find(message.getFolder(), message.getId());
        if (stored != null) {
            stored.setFlagged(flagged);
        }
    }

    @Override
    public void move(MailMessage message, String toFolder) {
        folder(message.getFolder()).remove(message);
        message.setFolder(toFolder);
        folder(toFolder).add(message);
    }

    @Override
    public void delete(MailMessage message) {
        folder(message.getFolder()).remove(message);
    }

    @Override
    public void send(MailMessage draft, List<MailAttachment> attachments) {
        sent.add(draft);
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public void close() {
        disconnect();
    }
}
