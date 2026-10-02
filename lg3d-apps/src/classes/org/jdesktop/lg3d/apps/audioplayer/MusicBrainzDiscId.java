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
package org.jdesktop.lg3d.apps.audioplayer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Computes a <a href="https://musicbrainz.org/doc/Disc_ID_Calculation">MusicBrainz
 * disc ID</a> from a {@link Toc}. The disc ID is the key the CD database is
 * looked up by, so it must match libdiscid byte for byte.
 *
 * <p>The algorithm hashes an upper-case hex ASCII rendering of the TOC with
 * SHA-1, then Base64-encodes the 20-byte digest using MusicBrainz's URL-safe
 * alphabet (standard Base64 with {@code +} -&gt; {@code .}, {@code /} -&gt;
 * {@code _} and the {@code =} pad -&gt; {@code -}), yielding a fixed 28-character
 * string. The hashed data is: the first track number ({@code %02X}), the last
 * track number ({@code %02X}), then 100 frame offsets ({@code %08X} each) where
 * slot 0 is the lead-out and slots 1..99 are the track offsets - every offset
 * adjusted by the 150-frame lead-in, and unused slots set to 0.</p>
 *
 * <p>Pure and AWT-free, so it is unit-tested against the worked example in the
 * MusicBrainz documentation.</p>
 */
public final class MusicBrainzDiscId {

    /** The 150-frame lead-in added to every raw LBA offset. */
    public static final int LEAD_IN_FRAMES = 150;

    /** The number of frame-offset slots hashed (lead-out + tracks 1..99). */
    static final int OFFSET_SLOTS = 100;

    private MusicBrainzDiscId() {
        // no instances
    }

    /**
     * Computes the disc ID for {@code toc}.
     *
     * @param toc the parsed table of contents
     * @return the 28-character MusicBrainz disc ID, or an empty string when the
     *         TOC is null/empty or SHA-1 is somehow unavailable
     */
    public static String compute(Toc toc) {
        if (toc == null || toc.isEmpty()) {
            return "";
        }
        String idString = idString(toc);
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(idString.getBytes(StandardCharsets.US_ASCII));
            return base64(digest);
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    /**
     * The upper-case hex ASCII string that is fed to SHA-1. Exposed
     * package-private so a test can assert the exact hashed bytes against the
     * documented example.
     */
    static String idString(Toc toc) {
        long[] frameOffset = new long[OFFSET_SLOTS];
        frameOffset[0] = toc.leadOutLba() + LEAD_IN_FRAMES;
        for (Toc.Track t : toc.getTracks()) {
            if (t.number >= 1 && t.number <= 99) {
                frameOffset[t.number] = t.startLba + LEAD_IN_FRAMES;
            }
        }
        StringBuilder sb = new StringBuilder(2 + 2 + OFFSET_SLOTS * 8);
        sb.append(String.format("%02X", toc.getFirstTrack()));
        sb.append(String.format("%02X", toc.lastTrack()));
        for (int i = 0; i < OFFSET_SLOTS; i++) {
            sb.append(String.format("%08X", frameOffset[i]));
        }
        return sb.toString();
    }

    /** MusicBrainz's URL-safe Base64: {@code . _ -} in place of {@code + / =}. */
    static String base64(byte[] digest) {
        return Base64.getEncoder().encodeToString(digest)
                .replace('+', '.')
                .replace('/', '_')
                .replace('=', '-');
    }
}
