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

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AWT-free cryptographic seam of the Password Manager. Everything secret the
 * app does - deriving a key from the master password, sealing and opening the
 * vault, generating a random password and scoring its strength - lives here as
 * pure statics over the JDK's own {@code javax.crypto} providers, so the whole
 * contract is unit-testable headless with no display and no third-party library.
 *
 * <p>The vault is sealed with <strong>AES-256/GCM</strong> under a key derived
 * from the master password with <strong>PBKDF2-HMAC-SHA256</strong> and a fresh
 * random salt. GCM is authenticated, so a wrong master password does not return
 * garbage - {@link #decrypt} throws {@link GeneralSecurityException} (an
 * {@code AEADBadTagException}), which the panel surfaces as "incorrect master
 * password". No plaintext secret is ever written to disk: only the salt, the IV,
 * the iteration count and the ciphertext are persisted (see
 * {@link VaultEnvelope}).</p>
 *
 * <p>Nothing here holds state or touches the filesystem; {@link PasswordVault}
 * and {@link VaultStore} own the model and the I/O, and
 * {@link PasswordManagerPanel} owns the (guarded, user-driven) UI.</p>
 */
public final class VaultCrypto {

    /** The key-derivation function: PBKDF2 over HMAC-SHA256. */
    public static final String KDF = "PBKDF2WithHmacSHA256";

    /** The vault cipher: authenticated AES in GCM mode. */
    public static final String CIPHER = "AES/GCM/NoPadding";

    /** Derived key length in bits (AES-256). */
    public static final int KEY_BITS = 256;

    /** GCM authentication-tag length in bits. */
    public static final int TAG_BITS = 128;

    /** Salt length in bytes. */
    public static final int SALT_BYTES = 16;

    /** GCM initialisation-vector length in bytes (the recommended 96 bits). */
    public static final int IV_BYTES = 12;

    /**
     * PBKDF2 iteration count. 210&nbsp;000 is the current OWASP recommendation
     * for PBKDF2-HMAC-SHA256; it is stored per-vault in {@link VaultEnvelope} so
     * a vault sealed with a different count still opens.
     */
    public static final int DEFAULT_ITERATIONS = 210_000;

    private static final char[] UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWER = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] DIGITS = "23456789".toCharArray();
    private static final char[] SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?".toCharArray();

    private VaultCrypto() {
        // no instances
    }

    /** How strong {@link #strength(String)} judges a candidate password to be. */
    public enum Strength {
        /** Trivially guessable. */
        VERY_WEAK,
        /** Short or single-class. */
        WEAK,
        /** Reasonable but not great. */
        FAIR,
        /** Long and mixed. */
        STRONG,
        /** Very long and mixed across every class. */
        VERY_STRONG
    }

    // ------------------------------------------------------------------
    // Random material
    // ------------------------------------------------------------------

    /** @return a fresh cryptographically-random salt. */
    public static byte[] newSalt() {
        return randomBytes(SALT_BYTES, new SecureRandom());
    }

    /** @return a fresh cryptographically-random GCM IV. */
    public static byte[] newIv() {
        return randomBytes(IV_BYTES, new SecureRandom());
    }

    /**
     * Fills a byte array from the given source of randomness.
     *
     * @param length the number of bytes to produce
     * @param random the randomness source (a {@link SecureRandom} in production)
     * @return the random bytes, never null
     */
    public static byte[] randomBytes(int length, Random random) {
        int n = Math.max(0, length);
        byte[] out = new byte[n];
        if (random != null && n > 0) {
            // Random.nextBytes exists on SecureRandom too; loop for plain Random.
            for (int i = 0; i < n; i++) {
                out[i] = (byte) random.nextInt(256);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Key derivation
    // ------------------------------------------------------------------

    /**
     * Derives the AES key that seals the vault from the master password.
     *
     * @param masterPassword the user's master password (may be null/empty, which
     *                       yields a key over an empty passphrase - the panel
     *                       refuses an empty master password before calling this)
     * @param salt           the per-vault random salt
     * @param iterations     the PBKDF2 iteration count (clamped to a sane minimum)
     * @return the derived {@link SecretKey}, never null
     * @throws GeneralSecurityException if the KDF is unavailable (never on a JDK)
     */
    public static SecretKey deriveKey(char[] masterPassword, byte[] salt, int iterations)
            throws GeneralSecurityException {
        char[] password = (masterPassword == null) ? new char[0] : masterPassword;
        byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[SALT_BYTES] : salt;
        int effectiveIterations = Math.max(1_000, iterations);
        PBEKeySpec spec =
                new PBEKeySpec(password, effectiveSalt, effectiveIterations, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    // ------------------------------------------------------------------
    // Seal / open
    // ------------------------------------------------------------------

    /**
     * Seals plaintext under the derived key with a fresh-random-IV GCM cipher is
     * the caller's job; this overload takes the IV so the result is reproducible
     * and testable.
     *
     * @param plaintext the bytes to seal (may be null, treated as empty)
     * @param key       the derived AES key
     * @param iv        the GCM IV (must be {@link #IV_BYTES} long)
     * @return the ciphertext plus appended authentication tag
     * @throws GeneralSecurityException if sealing fails
     */
    public static byte[] encrypt(byte[] plaintext, SecretKey key, byte[] iv)
            throws GeneralSecurityException {
        byte[] data = (plaintext == null) ? new byte[0] : plaintext;
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(data);
    }

    /**
     * Opens ciphertext sealed by {@link #encrypt}. Because GCM is authenticated,
     * a tampered blob or a wrong master password throws rather than returning
     * garbage.
     *
     * @param ciphertext the sealed bytes (tag appended)
     * @param key        the derived AES key
     * @param iv         the GCM IV used when sealing
     * @return the recovered plaintext
     * @throws GeneralSecurityException if the password/tag does not verify
     */
    public static byte[] decrypt(byte[] ciphertext, SecretKey key, byte[] iv)
            throws GeneralSecurityException {
        byte[] data = (ciphertext == null) ? new byte[0] : ciphertext;
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(data);
    }

    // ------------------------------------------------------------------
    // Password generation
    // ------------------------------------------------------------------

    /**
     * Generates a random password honouring the requested character classes. At
     * least one character from every selected class is guaranteed (when the
     * length allows), then the remainder is drawn from the union and shuffled, so
     * the result never has a predictable class ordering.
     *
     * @param length    the desired length (clamped to 4..128)
     * @param upper     include upper-case letters
     * @param lower     include lower-case letters
     * @param digits    include digits
     * @param symbols   include symbols
     * @param random    the randomness source (a {@link SecureRandom} in production)
     * @return the generated password, never null; empty when no class is selected
     */
    public static String generatePassword(int length, boolean upper, boolean lower,
                                          boolean digits, boolean symbols, Random random) {
        Random rng = (random == null) ? new SecureRandom() : random;
        int len = Math.min(128, Math.max(4, length));

        java.util.List<char[]> classes = new java.util.ArrayList<>();
        if (upper) {
            classes.add(UPPER);
        }
        if (lower) {
            classes.add(LOWER);
        }
        if (digits) {
            classes.add(DIGITS);
        }
        if (symbols) {
            classes.add(SYMBOLS);
        }
        if (classes.isEmpty()) {
            return "";
        }

        // Union of every selected class.
        StringBuilder union = new StringBuilder();
        for (char[] set : classes) {
            union.append(set);
        }

        char[] out = new char[len];
        // Guarantee one character per selected class first.
        int guaranteed = Math.min(len, classes.size());
        for (int i = 0; i < guaranteed; i++) {
            char[] set = classes.get(i);
            out[i] = set[rng.nextInt(set.length)];
        }
        // Fill the rest from the union.
        String unionStr = union.toString();
        for (int i = guaranteed; i < len; i++) {
            out[i] = unionStr.charAt(rng.nextInt(unionStr.length()));
        }
        // Fisher-Yates shuffle so the guaranteed characters are not always first.
        for (int i = len - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            char tmp = out[i];
            out[i] = out[j];
            out[j] = tmp;
        }
        return new String(out);
    }

    // ------------------------------------------------------------------
    // Strength scoring
    // ------------------------------------------------------------------

    /**
     * Scores a candidate password from 0 (trivial) to 100 (excellent) using its
     * length, the number of character classes it spans and a small penalty for
     * obvious sequences / repeats. Pure and deterministic.
     *
     * @param password the candidate (may be null)
     * @return the score in {@code [0, 100]}
     */
    public static int strengthScore(String password) {
        if (password == null || password.isEmpty()) {
            return 0;
        }
        int length = password.length();
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSymbol = false;
        for (int i = 0; i < length; i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (Character.isLowerCase(c)) {
                hasLower = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else {
                hasSymbol = true;
            }
        }
        int classes = (hasUpper ? 1 : 0) + (hasLower ? 1 : 0)
                + (hasDigit ? 1 : 0) + (hasSymbol ? 1 : 0);

        // Length contributes up to 50 points (a point per character, capped).
        int score = Math.min(50, length * 4);
        // Each character class beyond the first adds up to 12 points.
        score += (classes - 1) * 12;
        // A base for having any content at all.
        score += 8;
        // Penalise obvious runs (abc, 123) and single-character repeats.
        if (hasCommonSequence(password)) {
            score -= 20;
        }
        if (isSingleRepeat(password)) {
            score -= 30;
        }
        return Math.max(0, Math.min(100, score));
    }

    /**
     * Maps {@link #strengthScore(String)} onto the coarse {@link Strength} bands
     * the meter renders.
     *
     * @param password the candidate (may be null)
     * @return the strength band, never null
     */
    public static Strength strength(String password) {
        int score = strengthScore(password);
        if (score < 20) {
            return Strength.VERY_WEAK;
        }
        if (score < 40) {
            return Strength.WEAK;
        }
        if (score < 60) {
            return Strength.FAIR;
        }
        if (score < 80) {
            return Strength.STRONG;
        }
        return Strength.VERY_STRONG;
    }

    /** A short human label for a strength band, for the meter. */
    public static String describeStrength(Strength strength) {
        if (strength == null) {
            return "Unknown";
        }
        return switch (strength) {
            case VERY_WEAK -> "Very weak";
            case WEAK -> "Weak";
            case FAIR -> "Fair";
            case STRONG -> "Strong";
            case VERY_STRONG -> "Very strong";
        };
    }

    private static boolean hasCommonSequence(String password) {
        String lower = password.toLowerCase(java.util.Locale.ROOT);
        String[] common = {"password", "qwerty", "123456", "12345", "abc123",
            "letmein", "admin", "welcome", "111111", "iloveyou"};
        for (String word : common) {
            if (lower.contains(word)) {
                return true;
            }
        }
        // Straight ascending / descending runs of four (abcd, 1234, dcba, 4321).
        for (int i = 0; i + 3 < password.length(); i++) {
            char a = password.charAt(i);
            if (a + 1 == password.charAt(i + 1)
                    && a + 2 == password.charAt(i + 2)
                    && a + 3 == password.charAt(i + 3)) {
                return true;
            }
            if (a - 1 == password.charAt(i + 1)
                    && a - 2 == password.charAt(i + 2)
                    && a - 3 == password.charAt(i + 3)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSingleRepeat(String password) {
        if (password.length() < 3) {
            return false;
        }
        char first = password.charAt(0);
        for (int i = 1; i < password.length(); i++) {
            if (password.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }
}
