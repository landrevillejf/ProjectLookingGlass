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

import java.util.prefs.Preferences;

/**
 * One configured IMAP/SMTP account: the connection endpoints, the login, the
 * transport security, the outgoing signature, and how the password is handled.
 *
 * <p>The password itself is <em>never</em> a field here. {@link CredentialMode}
 * says whether the user is prompted each session ({@code ASK}, password held in
 * memory only) or whether an obfuscated copy is persisted ({@code SAVED}, sealed
 * by {@link CredentialVault}). Everything else is plain connection metadata that
 * {@link MailAccountStore} round-trips through the {@code /mail/accounts}
 * {@link Preferences} node.</p>
 */
public final class MailAccount {

    /** How the transport is secured. */
    public enum Security {
        /** Implicit TLS (the {@code imaps} / {@code smtps} protocols). */
        SSL,
        /** Plaintext upgraded in-band via the STARTTLS command. */
        STARTTLS,
        /** No encryption. Discouraged; kept for tests and legacy LAN servers. */
        NONE
    }

    /** Where the password comes from. */
    public enum CredentialMode {
        /** Prompted every session; never written to disk. */
        ASK,
        /** Sealed with {@link CredentialVault} and persisted. */
        SAVED
    }

    private static final String K_NAME = "displayName";
    private static final String K_EMAIL = "email";
    private static final String K_IMAP_HOST = "imapHost";
    private static final String K_IMAP_PORT = "imapPort";
    private static final String K_IMAP_SEC = "imapSecurity";
    private static final String K_SMTP_HOST = "smtpHost";
    private static final String K_SMTP_PORT = "smtpPort";
    private static final String K_SMTP_SEC = "smtpSecurity";
    private static final String K_USER = "username";
    private static final String K_CRED = "credentialMode";
    private static final String K_SIG = "signature";
    private static final String K_DEFAULT = "defaultAccount";

    private final String id;
    private String displayName = "";
    private String emailAddress = "";
    private String imapHost = "";
    private int imapPort = 993;
    private Security imapSecurity = Security.SSL;
    private String smtpHost = "";
    private int smtpPort = 465;
    private Security smtpSecurity = Security.SSL;
    private String username = "";
    private CredentialMode credentialMode = CredentialMode.ASK;
    private String signature = "";
    private boolean defaultAccount;

    public MailAccount(String id) {
        this.id = (id == null || id.isEmpty()) ? newId() : id;
    }

    /** A fresh, random, sort-irrelevant account id. */
    public static String newId() {
        return "acct-" + Long.toString(System.nanoTime(), 36)
                + "-" + Integer.toString(
                        (int) (Math.random() * 0xFFFFFF), 36);
    }

    // ------------------------------------------------------------------
    // Preferences (de)serialisation
    // ------------------------------------------------------------------

    /** Rebuilds an account from its persisted preferences node. */
    static MailAccount readFrom(String id, Preferences node) {
        MailAccount a = new MailAccount(id);
        a.displayName = node.get(K_NAME, "");
        a.emailAddress = node.get(K_EMAIL, "");
        a.imapHost = node.get(K_IMAP_HOST, "");
        a.imapPort = node.getInt(K_IMAP_PORT, 993);
        a.imapSecurity = parseSecurity(node.get(K_IMAP_SEC, null), Security.SSL);
        a.smtpHost = node.get(K_SMTP_HOST, "");
        a.smtpPort = node.getInt(K_SMTP_PORT, 465);
        a.smtpSecurity = parseSecurity(node.get(K_SMTP_SEC, null), Security.SSL);
        a.username = node.get(K_USER, "");
        a.credentialMode = parseCredential(node.get(K_CRED, null),
                CredentialMode.ASK);
        a.signature = node.get(K_SIG, "");
        a.defaultAccount = node.getBoolean(K_DEFAULT, false);
        return a;
    }

    /** Writes every non-secret field into the given preferences node. */
    void writeTo(Preferences node) {
        node.put(K_NAME, displayName);
        node.put(K_EMAIL, emailAddress);
        node.put(K_IMAP_HOST, imapHost);
        node.putInt(K_IMAP_PORT, imapPort);
        node.put(K_IMAP_SEC, imapSecurity.name());
        node.put(K_SMTP_HOST, smtpHost);
        node.putInt(K_SMTP_PORT, smtpPort);
        node.put(K_SMTP_SEC, smtpSecurity.name());
        node.put(K_USER, username);
        node.put(K_CRED, credentialMode.name());
        node.put(K_SIG, signature);
        node.putBoolean(K_DEFAULT, defaultAccount);
    }

    private static Security parseSecurity(String s, Security fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Security.valueOf(s);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static CredentialMode parseCredential(String s,
            CredentialMode fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return CredentialMode.valueOf(s);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = (displayName == null) ? "" : displayName.trim();
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = (emailAddress == null) ? "" : emailAddress.trim();
    }

    public String getImapHost() {
        return imapHost;
    }

    public void setImapHost(String imapHost) {
        this.imapHost = (imapHost == null) ? "" : imapHost.trim();
    }

    public int getImapPort() {
        return imapPort;
    }

    public void setImapPort(int imapPort) {
        this.imapPort = imapPort;
    }

    public Security getImapSecurity() {
        return imapSecurity;
    }

    public void setImapSecurity(Security imapSecurity) {
        this.imapSecurity = (imapSecurity == null) ? Security.SSL : imapSecurity;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public void setSmtpHost(String smtpHost) {
        this.smtpHost = (smtpHost == null) ? "" : smtpHost.trim();
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public void setSmtpPort(int smtpPort) {
        this.smtpPort = smtpPort;
    }

    public Security getSmtpSecurity() {
        return smtpSecurity;
    }

    public void setSmtpSecurity(Security smtpSecurity) {
        this.smtpSecurity = (smtpSecurity == null) ? Security.SSL : smtpSecurity;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = (username == null) ? "" : username.trim();
    }

    public CredentialMode getCredentialMode() {
        return credentialMode;
    }

    public void setCredentialMode(CredentialMode credentialMode) {
        this.credentialMode = (credentialMode == null)
                ? CredentialMode.ASK : credentialMode;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = (signature == null) ? "" : signature;
    }

    public boolean isDefaultAccount() {
        return defaultAccount;
    }

    public void setDefaultAccount(boolean defaultAccount) {
        this.defaultAccount = defaultAccount;
    }

    /** The address outgoing mail is sent from: the configured login identity. */
    public MailAddress fromAddress() {
        return new MailAddress(displayName, emailAddress);
    }

    /** True when the minimum needed to attempt a connection is present. */
    public boolean isComplete() {
        return !emailAddress.isEmpty() && !imapHost.isEmpty() && !smtpHost.isEmpty();
    }

    /** The label shown in the account picker. */
    public String getDisplayLabel() {
        if (!displayName.isEmpty()) {
            return displayName + " <" + emailAddress + ">";
        }
        return emailAddress.isEmpty() ? id : emailAddress;
    }

    @Override
    public String toString() {
        return getDisplayLabel();
    }
}
