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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seals and opens the passwords of accounts whose {@link MailAccount.CredentialMode}
 * is {@code SAVED}, so a convenience-seeking user is not prompted every launch.
 *
 * <p>This is deliberate, honest <em>obfuscation at rest</em>, not a hardware-backed
 * secret store: a random 256-bit key is generated once per install and kept in the
 * same {@link Preferences} tree as the sealed values (under {@code /mail/.secret}),
 * then used for AES-GCM (authenticated, so tampering fails loudly). Anyone with
 * read access to the user's preferences can therefore recover the password - which
 * is exactly why {@code ASK} (in-memory only) is the default and why this class
 * never logs a plaintext or a sealed value. Users who need real at-rest protection
 * should leave their accounts in {@code ASK} mode.</p>
 */
public final class CredentialVault {

    /** Absolute preferences path holding the per-install AES key. */
    static final String SECRET_ROOT = "/mail/.secret";
    private static final String KEY_NODE = "key";

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final Logger logger =
            Logger.getLogger(CredentialVault.class.getName());

    private final Preferences secretRoot;
    private final SecretKey key;

    public CredentialVault() {
        this.secretRoot = Preferences.userRoot().node(SECRET_ROOT);
        this.key = loadOrCreateKey();
    }

    private SecretKey loadOrCreateKey() {
        String existing = secretRoot.get(KEY_NODE, null);
        if (existing != null && !existing.isEmpty()) {
            try {
                byte[] raw = Base64.getDecoder().decode(existing);
                if (raw.length * 8 == KEY_BITS) {
                    return new SecretKeySpec(raw, "AES");
                }
            } catch (IllegalArgumentException e) {
                logger.log(Level.WARNING, "Corrupt vault key; regenerating", e);
            }
        }
        byte[] raw = new byte[KEY_BITS / 8];
        new SecureRandom().nextBytes(raw);
        SecretKey created = new SecretKeySpec(raw, "AES");
        try {
            secretRoot.put(KEY_NODE, Base64.getEncoder().encodeToString(raw));
            secretRoot.flush();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not persist vault key", e);
        }
        return created;
    }

    /**
     * Seals a plaintext password into a Base64 {@code iv || ciphertext} token.
     * Returns {@code null} for a null/empty password (nothing worth storing).
     */
    public String seal(String password) {
        if (password == null || password.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to seal credential", e);
            return null;
        }
    }

    /**
     * Opens a token produced by {@link #seal(String)}. Returns {@code null} when
     * the input is null/blank or fails authentication (tampered or foreign key).
     */
    public String open(String sealed) {
        if (sealed == null || sealed.isEmpty()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(sealed);
            if (all.length <= IV_BYTES) {
                return null;
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            byte[] ct = new byte[all.length - IV_BYTES];
            System.arraycopy(all, IV_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Authentication failure or corrupt data: report without the value.
            logger.log(Level.WARNING, "Failed to open credential", e);
            return null;
        }
    }
}
