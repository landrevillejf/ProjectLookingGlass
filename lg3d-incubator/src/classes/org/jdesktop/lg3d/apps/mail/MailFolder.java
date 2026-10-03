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
 * A mailbox folder on one account: its (server) name, its role, and the counts
 * the folder tree shows beside it.
 *
 * <p>The {@link Type} is inferred from the name so the UI can pin the well-known
 * folders (Inbox / Sent / Drafts / Trash / Junk) to the top and pick the right
 * icon, while still listing any custom folder the server exposes.</p>
 */
public final class MailFolder {

    /** The well-known roles a folder can play. */
    public enum Type {
        INBOX, SENT, DRAFTS, TRASH, JUNK, OTHER
    }

    private final String accountId;
    private final String name;
    private final Type type;
    private int unreadCount;
    private int messageCount;

    public MailFolder(String accountId, String name) {
        this.accountId = (accountId == null) ? "" : accountId;
        this.name = (name == null) ? "" : name;
        this.type = inferType(this.name);
    }

    /** Maps a server folder name onto a well-known {@link Type}. */
    static Type inferType(String name) {
        String n = name.toLowerCase();
        // Strip a leading hierarchy prefix such as "[Gmail]/Sent Mail".
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        n = n.replace(" ", "");
        if (n.equals("inbox")) {
            return Type.INBOX;
        }
        if (n.contains("sent") || n.contains("envoy")) {
            return Type.SENT;
        }
        if (n.contains("draft") || n.contains("brouillon")) {
            return Type.DRAFTS;
        }
        if (n.contains("trash") || n.contains("corbeille") || n.contains("deleted")) {
            return Type.TRASH;
        }
        if (n.contains("junk") || n.contains("spam") || n.contains("ind")) {
            return Type.JUNK;
        }
        return Type.OTHER;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    /** The label shown in the tree: the last path segment, prettied. */
    public String getDisplayLabel() {
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    @Override
    public String toString() {
        return getDisplayLabel();
    }
}
