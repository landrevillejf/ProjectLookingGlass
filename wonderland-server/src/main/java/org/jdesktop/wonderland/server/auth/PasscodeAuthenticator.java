/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the login gate the transport was missing.
 *
 * SessionRegistry.create accepts whatever identity the caller hands it -- the
 * honest state of the port after M3: the wire is real, the session table is
 * real, and "who are you" was still the transport's unanswerable question.
 * This class is the answer in its smallest true form: a stateless bearer
 * token, HMAC-SHA256 over the username under a deployment secret, verified
 * in constant time. It is deliberately NOT an account system -- no password
 * hashing, no user store, no session DB, none of which the desktop needs to
 * stop strangers from joining a world and all of which would be more
 * machinery than evidence. A deployment that wants accounts replaces this
 * class at the same seam (the transport calls verify() before create()); no
 * other code knows how the gate works.
 *
 * The token format is <username>|<hex>: the username in the clear because
 * it IS the claim being signed, and '|' forbidden inside a username because
 * a separator that can appear in a field makes parsing a spoof. verify()
 * returns null for anything it does not like -- a foreign secret, an edited
 * byte, a missing field, a blank token -- and never throws to REFUSE a token,
 * because a login path that distinguishes "malformed" from "wrong signature"
 * in its errors is a login path that tells an attacker what to try next. The
 * one throwable left is the JVM itself missing its mandated HMAC -- that is a
 * broken deployment, not a rejected claimant, and it says so.
 *
 * One limitation, stated not hidden: a bearer token replayed inside its own
 * lifetime IS the holder. This gate answers "who minted this and is the
 * seal intact" -- expiry, rotation and revocation are deployment concerns
 * with a policy knob each, and inventing half a lifecycle here would be
 * worse than admitting none.
 */
package org.jdesktop.wonderland.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;

/** Stateless HMAC passcode tokens for the server's login gate (roadmap P5.4). */
public final class PasscodeAuthenticator {

    private static final String SEPARATOR = "|";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] secret;

    /**
     * @param secret the deployment's shared key (from configuration, never a
     *     source constant -- and never logged). An empty secret is refused:
     *     HMAC over "nothing" is a signature every deployment shares.
     */
    public PasscodeAuthenticator(String secret) {
        Objects.requireNonNull(secret, "the gate needs a key");
        if (secret.isEmpty()) {
            throw new IllegalArgumentException("an empty secret signs everything and nothing");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Mint the token for one username. The name may not contain the
     * separator (see the header) and may not be blank; a null is the
     * programmer error it has always been.
     */
    public String issue(String username) {
        Objects.requireNonNull(username, "a claim needs a claimant");
        if (username.isBlank() || username.contains(SEPARATOR)) {
            throw new IllegalArgumentException("a username is neither blank nor self-splitting");
        }
        return username + SEPARATOR + toHex(sign(username));
    }

    /**
     * The whole verification, the whole error vocabulary: a WonderlandIdentity
     * when the token was minted by THIS secret for THIS name, null for every
     * other input, no exception to refuse any of them. The comparison is
     * MessageDigest.isEqual -- constant-time on the seal, so the check gives
     * away nothing about where a forged byte first differs.
     */
    public WonderlandIdentity verify(String token) {
        if (token == null) {
            return null;
        }
        int cut = token.lastIndexOf(SEPARATOR);
        if (cut <= 0 || cut == token.length() - 1) {
            return null; // no separator, empty name, or empty seal
        }
        String username = token.substring(0, cut);
        String providedSeal = token.substring(cut + 1);
        if (username.isBlank()) {
            return null;
        }
        byte[] expected = sign(username);
        byte[] provided = fromHex(providedSeal);
        if (provided == null || !MessageDigest.isEqual(expected, provided)) {
            return null;
        }
        return new WonderlandIdentity(username, username, null);
    }

    private byte[] sign(String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(username.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException impossible) {
            // HmacSHA256 is a JDK-mandated algorithm; if it is genuinely
            // absent, nothing can sign and the gate cannot answer anything --
            // that is the deployment broken, not a token refused, so it is
            // said loudly rather than dressed up as a null.
            throw new IllegalStateException("no HMAC on this JVM?", impossible);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = HEX[(bytes[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    /** null for anything that is not even-length hex -- never a throw. */
    private static byte[] fromHex(String hex) {
        if (hex.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16);
            int lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
