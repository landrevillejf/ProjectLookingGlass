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
package org.jdesktop.lg3d.apps.passwordmanager;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.SecretKey;

/**
 * The sealed vault as it is stored on disk: a PBKDF2 iteration count, a random
 * salt, a GCM IV and the AES-GCM ciphertext of the whole vault JSON, each binary
 * field Base64-encoded so the envelope is a plain Jackson bean
 * ({@link VaultStore} round-trips it as {@code vault.json}).
 *
 * <p>This is the <em>only</em> secret-bearing artefact persisted, and it holds no
 * plaintext: opening it needs the master password, from which {@link VaultCrypto}
 * re-derives the key using the stored salt and iteration count. Because the cipher
 * is authenticated, a wrong password makes {@link #open} throw rather than return
 * garbage, which is how the panel distinguishes "incorrect master password" from a
 * corrupt file.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VaultEnvelope {

    private int iterations = VaultCrypto.DEFAULT_ITERATIONS;
    private String salt = "";
    private String iv = "";
    private String data = "";

    /** No-arg constructor for Jackson. */
    public VaultEnvelope() {
    }

    /**
     * Seals plaintext under a master password: generates a fresh salt and IV,
     * derives the key and encrypts. The result is what {@link VaultStore} writes.
     *
     * @param plaintext  the vault JSON bytes
     * @param master     the master password
     * @param iterations the PBKDF2 iteration count
     * @return the sealed envelope, never null
     * @throws GeneralSecurityException if sealing fails
     */
    public static VaultEnvelope seal(byte[] plaintext, char[] master, int iterations)
            throws GeneralSecurityException {
        int effectiveIterations = Math.max(1_000, iterations);
        byte[] salt = VaultCrypto.newSalt();
        byte[] iv = VaultCrypto.newIv();
        SecretKey key = VaultCrypto.deriveKey(master, salt, effectiveIterations);
        byte[] cipher = VaultCrypto.encrypt(plaintext, key, iv);
        Base64.Encoder enc = Base64.getEncoder();
        VaultEnvelope envelope = new VaultEnvelope();
        envelope.iterations = effectiveIterations;
        envelope.salt = enc.encodeToString(salt);
        envelope.iv = enc.encodeToString(iv);
        envelope.data = enc.encodeToString(cipher);
        return envelope;
    }

    /**
     * Opens the envelope with the given master password.
     *
     * @param master the master password
     * @return the recovered vault JSON bytes
     * @throws GeneralSecurityException if the password is wrong or the blob is
     *                                  tampered / malformed
     */
    public byte[] open(char[] master) throws GeneralSecurityException {
        Base64.Decoder dec = Base64.getDecoder();
        byte[] saltBytes = dec.decode(salt);
        byte[] ivBytes = dec.decode(iv);
        byte[] cipher = dec.decode(data);
        SecretKey key = VaultCrypto.deriveKey(master, saltBytes, iterations);
        return VaultCrypto.decrypt(cipher, key, ivBytes);
    }

    /**
     * True when this envelope actually carries a sealed vault (all four fields
     * present). A default / empty envelope is how {@link VaultStore} represents
     * "no vault created yet".
     *
     * @return true when there is ciphertext to open
     */
    public boolean isPresent() {
        return !salt.isBlank() && !iv.isBlank() && !data.isBlank();
    }

    public int getIterations() {
        return iterations;
    }

    public void setIterations(int iterations) {
        this.iterations = Math.max(1_000, iterations);
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = (salt == null) ? "" : salt.trim();
    }

    public String getIv() {
        return iv;
    }

    public void setIv(String iv) {
        this.iv = (iv == null) ? "" : iv.trim();
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = (data == null) ? "" : data.trim();
    }
}
