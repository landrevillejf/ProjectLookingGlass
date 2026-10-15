/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the login gate, headless.
 *
 * The gate has exactly two answers -- an identity or null -- so the tests
 * walk the ways a token can be wrong (edited seal, swapped name, foreign
 * secret, junk shape) and insist every one of them gets the SAME silent
 * refusal, while the one way it can be right gets through. Determinism is
 * asserted too: the seal is a pure function of secret + name, so two JVMs
 * sharing a deployment secret must agree on every claim.
 */
package org.jdesktop.wonderland.server.auth;

import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasscodeAuthenticatorTest {

    private static final String SECRET = "a deployment secret, not a source constant";
    private static final String OTHER_SECRET = "some other deployment";

    private static PasscodeAuthenticator gate() {
        return new PasscodeAuthenticator(SECRET);
    }

    /** The seal of a token: everything after the last separator. */
    private static String sealOf(String token) {
        return token.substring(token.lastIndexOf('|') + 1);
    }

    @Test
    void aMintedTokenVerifiesBackToTheSameName() {
        String token = gate().issue("alice");

        WonderlandIdentity who = gate().verify(token);

        assertThat(who).isNotNull();
        assertThat(who.getUsername()).isEqualTo("alice");
        assertThat(who.getFullName()).isEqualTo("alice");
        assertThat(who.getEmail()).isNull();
    }

    @Test
    void theSealIsAPureFunctionSoIndependentGatesAgree() {
        String one = gate().issue("alice");
        String two = gate().issue("alice");

        assertThat(two).as("same secret, same name, identical token")
                .isEqualTo(one);
        assertThat(new PasscodeAuthenticator(SECRET).verify(one)).isNotNull();
    }

    @Test
    void oneEditedHexDigitIsAForgery() {
        String token = gate().issue("alice");
        String seal = sealOf(token);
        char flipped = seal.endsWith("0") ? '1' : '0';
        String tampered = token.substring(0, token.length() - 1) + flipped;

        assertThat(gate().verify(tampered)).isNull();
    }

    @Test
    void aSealStolenOntoAnotherNameDoesNotVerify() {
        String alice = gate().issue("alice");

        assertThat(gate().verify("mallory|" + sealOf(alice)))
                .as("the seal signs the whole claim, not half of it")
                .isNull();
    }

    @Test
    void aForeignSecretsTokenIsRefused() {
        String token = new PasscodeAuthenticator(OTHER_SECRET).issue("alice");

        assertThat(gate().verify(token)).isNull();
    }

    @Test
    void everyMalformedShapeGetsTheSameSilentNull() {
        String seal = sealOf(gate().issue("alice"));

        assertThat(gate().verify(null)).isNull();
        assertThat(gate().verify("")).isNull();
        assertThat(gate().verify("alice")).isNull();            // no separator
        assertThat(gate().verify("alice|")).isNull();           // empty seal
        assertThat(gate().verify("|" + seal)).isNull();         // empty name
        assertThat(gate().verify(" |" + seal)).isNull();        // blank name
        assertThat(gate().verify("alice|" + seal.substring(1))).isNull(); // odd hex
        assertThat(gate().verify("alice|" + "zz" + seal.substring(2))).isNull(); // non-hex
    }

    @Test
    void namesBeyondAsciiRoundTrip() {
        // The seal is computed over UTF-8 bytes, so an accented or symbol
        // name claims as itself on the other side of the same secret.
        String token = gate().issue("élice☃");

        WonderlandIdentity who = gate().verify(token);

        assertThat(who).isNotNull();
        assertThat(who.getUsername()).isEqualTo("élice☃");
    }

    @Test
    void theGateIsConfiguredAndTheClaimIsWellFormedOrNeitherHappens() {
        assertThatThrownBy(() -> new PasscodeAuthenticator(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PasscodeAuthenticator(""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> gate().issue(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> gate().issue("  "))
                .as("a blank claimant cannot be minted")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gate().issue("ali|ce"))
                .as("a self-splitting username would make parsing a spoof")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
