/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the auth-gate policy, tested headless.
 *
 * The gate has one responsibility: turn a Properties bag into an identity or a
 * refusal, given whether auth is enabled. Disabled (the default) must produce
 * the pre-P5.4 anonymous path byte-for-byte; enabled must verify the token and
 * return null for every shape the PasscodeAuthenticator kernel already refuses.
 * The misconfigured case (enabled, no secret) must also refuse -- never
 * silently fall back to anonymous, which is a privilege escalation.
 */
package org.jdesktop.wonderland.server.auth;

import java.util.Properties;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AuthGateTest {

    private static final String SECRET = "a per-deployment secret for tests";

    /** The off-by-default gate that keeps the whole server byte-identical. */
    private static AuthGate off() {
        return new AuthGate(false, "");
    }

    /** The live gate with a real secret. */
    private static AuthGate on() {
        return new AuthGate(true, SECRET);
    }

    private static Properties withToken(String username) {
        PasscodeAuthenticator mint = new PasscodeAuthenticator(SECRET);
        Properties props = new Properties();
        props.setProperty("username", username);
        props.setProperty("token", mint.issue(username));
        return props;
    }

    // ---- disabled (default) ----

    @Test
    void disabledAcceptsAnyUsernameAnonymous() {
        Properties props = new Properties();
        props.setProperty("username", "alice");
        props.setProperty("fullname", "Alice A");
        props.setProperty("email", "alice@example.org");

        WonderlandIdentity id = off().resolve(props);

        assertThat(id).isNotNull();
        assertThat(id.getUsername()).isEqualTo("alice");
        assertThat(id.getFullName()).isEqualTo("Alice A");
        assertThat(id.getEmail()).isEqualTo("alice@example.org");
    }

    @Test
    void disabledFallsBackToAnonymousWhenNoUsername() {
        WonderlandIdentity id = off().resolve(null);

        assertThat(id).isNotNull();
        assertThat(id.getUsername()).isEqualTo("anonymous");
    }

    @Test
    void disabledIgnoresATokenEvenIfOneIsPresent() {
        Properties props = withToken("bob");
        props.setProperty("token", "tampered");

        WonderlandIdentity id = off().resolve(props);

        // The username is read from the property, not the token
        assertThat(id).isNotNull();
        assertThat(id.getUsername()).isEqualTo("bob");
    }

    // ---- enabled ----

    @Test
    void enabledAcceptsAValidToken() {
        Properties props = withToken("carol");

        WonderlandIdentity id = on().resolve(props);

        assertThat(id).isNotNull();
        assertThat(id.getUsername()).isEqualTo("carol");
    }

    @Test
    void enabledRefusesNoToken() {
        Properties props = new Properties();
        props.setProperty("username", "dave");

        assertThat(on().resolve(props)).isNull();
    }

    @Test
    void enabledRefusesAForgedToken() {
        Properties props = new Properties();
        props.setProperty("username", "eve");
        props.setProperty("token", "eve|deadbeef");

        assertThat(on().resolve(props)).isNull();
    }

    @Test
    void enabledRefusesAForeignSecretToken() {
        PasscodeAuthenticator foreign = new PasscodeAuthenticator("other-secret");
        Properties props = new Properties();
        props.setProperty("username", "frank");
        props.setProperty("token", foreign.issue("frank"));

        assertThat(on().resolve(props)).isNull();
    }

    @Test
    void enabledRefusesEmptyToken() {
        Properties props = new Properties();
        props.setProperty("username", "grace");
        props.setProperty("token", "");

        assertThat(on().resolve(props)).isNull();
    }

    @Test
    void enabledRefusesANullAttachBag() {
        // The transport can hand resolve() a null Properties when the attach
        // carried no bag at all; enabled, that is a tokenless login -- refused,
        // never an NPE and never an anonymous fallback.
        assertThat(on().resolve(null)).isNull();
    }

    // ---- misconfigured (enabled, no secret) ----

    @Test
    void enabledNoSecretRefusesEverything() {
        AuthGate misconfigured = new AuthGate(true, "");
        Properties props = withToken("heidi");

        // Even a token that would be valid for SOME secret is refused when the
        // gate has no key at all -- the safe direction is always refusal.
        assertThat(misconfigured.resolve(props)).isNull();
    }

    @Test
    void enabledNullSecretRefusesEverything() {
        // A missing secret property (not merely blank) is the same
        // misconfiguration: no key, so every login is refused at boot-safe.
        AuthGate misconfigured = new AuthGate(true, null);

        assertThat(misconfigured.resolve(withToken("ivan"))).isNull();
    }

    // ---- isActive ----

    @Test
    void isActiveReflectsTheConfiguration() {
        assertThat(off().isActive()).isFalse();
        assertThat(on().isActive()).isTrue();
    }
}
