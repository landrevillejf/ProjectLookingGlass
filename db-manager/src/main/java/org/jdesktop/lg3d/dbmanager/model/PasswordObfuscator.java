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
package org.jdesktop.lg3d.dbmanager.model;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Reversible obfuscation for the optional saved database passwords.
 *
 * <p><strong>This is not encryption.</strong> It only stops passwords from
 * appearing as readable plaintext in the on-disk profile store, matching the
 * convenience tier of typical desktop database tools. Anyone with the file and
 * this class can recover the value, which is why saving passwords is
 * <em>off by default</em> ({@link AppSettings#isAllowSavePasswords()}) and the
 * UI warns the user before enabling it. For real secrecy a user should decline
 * to save and type the password on each connect.</p>
 *
 * <p>Obfuscated values are stored with a {@value #PREFIX} marker so a plain
 * (legacy) value can be distinguished from an obfuscated one on read.</p>
 */
public final class PasswordObfuscator {

    /** Marker prefix on an obfuscated value. */
    static final String PREFIX = "obf1:";

    /** A fixed, non-secret rolling key; obfuscation only, not security. */
    private static final byte[] KEY = "lg3d-dbmanager".getBytes(StandardCharsets.UTF_8);

    private PasswordObfuscator() {
    }

    /**
     * Obfuscates a password.
     *
     * @param plain the plaintext password; {@code null} passes through
     * @return the marked, Base64-encoded form, or {@code null}
     */
    public static String obfuscate(String plain) {
        if (plain == null) {
            return null;
        }
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        xor(data);
        return PREFIX + Base64.getEncoder().encodeToString(data);
    }

    /**
     * Recovers a password from its stored form.
     *
     * @param stored an obfuscated ({@value #PREFIX}-marked) or legacy plain value
     * @return the plaintext password, or {@code null}
     */
    public static String deobfuscate(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            xor(data);
            return new String(data, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // A corrupt/edited value: return empty rather than throwing, so a
            // hand-edited profiles.json cannot break the whole app on load.
            return "";
        }
    }

    /** @return {@code true} when the value carries the obfuscation marker. */
    public static boolean isObfuscated(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private static void xor(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            data[i] ^= KEY[i % KEY.length];
        }
    }
}
