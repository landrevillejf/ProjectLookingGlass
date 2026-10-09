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
package org.jdesktop.lg3d.apps.p2p;

/**
 * The pure verdict of a trust-on-first-use (TOFU) check: given the fingerprint we
 * have pinned for a peer (if any) and the one it actually presented during the
 * {@link NoiseXXHandshake}, decide whether to proceed, warn, or refuse. This type
 * does no I/O and holds no state, so the trust logic is unit-testable in isolation
 * and reusable by both apps; {@link IdentityStore} supplies the persisted pins.
 *
 * <p>The three outcomes mirror SSH's host-key model:</p>
 * <ul>
 *   <li>{@link #FIRST_CONTACT} - we have never seen this peer, so there is nothing
 *       to compare against. TOFU says: proceed, and remember the fingerprint now so
 *       a future change is detectable.</li>
 *   <li>{@link #MATCH} - the presented fingerprint equals the pin. Proceed quietly.</li>
 *   <li>{@link #MISMATCH} - a pin exists and the presented fingerprint differs. This
 *       is the man-in-the-middle signal: the peer's identity changed since we pinned
 *       it. Refuse by default and require an explicit, loud user override
 *       ({@link IdentityStore#overrideMismatch}).</li>
 * </ul>
 *
 * <p>A missing or blank presented fingerprint is always {@link #MISMATCH}: an
 * unauthenticated peer is never trusted, first contact or not.</p>
 */
public enum TrustDecision {

    /** No pin recorded yet; accept and pin (the "use" in trust-on-first-use). */
    FIRST_CONTACT,

    /** The presented fingerprint matches the stored pin; trusted. */
    MATCH,

    /** The presented fingerprint differs from the stored pin; possible MITM. */
    MISMATCH;

    /**
     * @return true if a connection may proceed without an explicit override
     *         (first contact or a matching pin)
     */
    public boolean isAllowed() {
        return this != MISMATCH;
    }

    /** @return true if the UI must raise a loud man-in-the-middle warning. */
    public boolean needsWarning() {
        return this == MISMATCH;
    }

    /** @return true if this is a first contact that should be pinned. */
    public boolean isFirstContact() {
        return this == FIRST_CONTACT;
    }

    /**
     * Evaluates a presented fingerprint against a stored pin.
     *
     * @param pinned    the fingerprint we previously recorded for this peer, or
     *                  null/blank if we have never pinned it
     * @param presented the fingerprint the peer presented during the handshake
     * @return the verdict; a blank presented fingerprint is always {@link #MISMATCH}
     */
    public static TrustDecision evaluate(String pinned, String presented) {
        if (presented == null || presented.isBlank()) {
            return MISMATCH; // nothing to trust
        }
        if (pinned == null || pinned.isBlank()) {
            return FIRST_CONTACT; // no pin yet: trust on first use
        }
        return P2pCrypto.fingerprintsMatch(pinned, presented) ? MATCH : MISMATCH;
    }
}
