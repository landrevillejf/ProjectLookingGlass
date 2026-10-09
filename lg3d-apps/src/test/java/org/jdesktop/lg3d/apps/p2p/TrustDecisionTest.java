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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@link TrustDecision} verdicts: first contact when there
 * is no pin, a match when the presented fingerprint equals the pin (across case and
 * separator differences), and a mismatch - the MITM signal - when it differs or when
 * nothing was presented at all. The convenience predicates are checked against each
 * outcome.
 */
class TrustDecisionTest {

    private static final String PINNED = "AA:BB:CC:DD:EE";

    @Test
    @DisplayName("no pin yet is a first contact")
    void firstContact() {
        assertEquals(TrustDecision.FIRST_CONTACT, TrustDecision.evaluate(null, PINNED));
        assertEquals(TrustDecision.FIRST_CONTACT, TrustDecision.evaluate("", PINNED));
        assertEquals(TrustDecision.FIRST_CONTACT, TrustDecision.evaluate("   ", PINNED));
    }

    @Test
    @DisplayName("an equal fingerprint matches, tolerant of case and separators")
    void match() {
        assertEquals(TrustDecision.MATCH, TrustDecision.evaluate(PINNED, PINNED));
        assertEquals(TrustDecision.MATCH, TrustDecision.evaluate(PINNED, PINNED.toLowerCase()));
        assertEquals(TrustDecision.MATCH, TrustDecision.evaluate("AABBCCDDEE", "aa:bb:cc:dd:ee"));
    }

    @Test
    @DisplayName("a different fingerprint is a mismatch")
    void mismatch() {
        assertEquals(TrustDecision.MISMATCH, TrustDecision.evaluate(PINNED, "11:22:33:44:55"));
        assertEquals(TrustDecision.MISMATCH, TrustDecision.evaluate("AABBCCDDEE", "AABBCCDDEF"));
    }

    @Test
    @DisplayName("a missing presented fingerprint is always a mismatch")
    void blankPresentedIsMismatch() {
        assertEquals(TrustDecision.MISMATCH, TrustDecision.evaluate(null, null));
        assertEquals(TrustDecision.MISMATCH, TrustDecision.evaluate(null, ""));
        assertEquals(TrustDecision.MISMATCH, TrustDecision.evaluate(PINNED, "   "));
    }

    @Test
    @DisplayName("the convenience predicates agree with each verdict")
    void predicates() {
        assertTrue(TrustDecision.FIRST_CONTACT.isFirstContact());
        assertTrue(TrustDecision.FIRST_CONTACT.isAllowed());
        assertFalse(TrustDecision.FIRST_CONTACT.needsWarning());

        assertFalse(TrustDecision.MATCH.isFirstContact());
        assertTrue(TrustDecision.MATCH.isAllowed());
        assertFalse(TrustDecision.MATCH.needsWarning());

        assertFalse(TrustDecision.MISMATCH.isFirstContact());
        assertFalse(TrustDecision.MISMATCH.isAllowed());
        assertTrue(TrustDecision.MISMATCH.needsWarning());
    }
}
