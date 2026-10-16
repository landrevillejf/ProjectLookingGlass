/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the auth gate as a policy-wired service, the seam
 * between the transport's "who are you" and the kernel's "is the seal intact".
 *
 * The roadmap says PasscodeAuthenticator is "a tested kernel only" and that
 * wiring it "needs a deployment decision on the no-token case and a configured
 * secret". This class IS that decision:
 *
 *   - When `wonderland.server.auth.enabled` is false (the DEFAULT, so a
 *     developer's loopback bootRun and every existing integration test see the
 *     pre-P5.4 identityFrom path byte-for-byte), resolve() is identityFrom()
 *     and a missing token is anonymous.
 *
 *   - When enabled, resolve() requires a non-null `token` property on the
 *     attach; PasscodeAuthenticator.verify(token) decides. A null answer (no
 *     token, a forged one, a foreign secret) means the transport refuses the
 *     connection -- the socket closes with POLICY_VIOLATION, no session is
 *     minted. A valid token yields the identity it names.
 *
 * The secret is `wonderland.server.auth.secret`, a configuration value that
 * every deployment must set to a unique string. An empty secret when enabled
 * is treated as a misconfiguration and PASSPORT-REFUSES every login rather
 * than signing nothing (PasscodeAuthenticator's own constructor refuses "").
 *
 * Nothing here logs the secret or a raw token -- a secret in a log line is a
 * secret in every downstream system that reads logs, and the gate exists
 * precisely to keep strangers out.
 */
package org.jdesktop.wonderland.server.auth;

import java.util.Properties;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** P5.4 policy seam: decide whether and how the login gate applies. */
@Service
public class AuthGate {

    private static final Logger logger = LoggerFactory.getLogger(AuthGate.class);

    private final boolean enabled;
    private final PasscodeAuthenticator authenticator;

    /**
     * Spring injects the two configuration values. When auth is disabled the
     * authenticator is null (no secret needed, no HMAC wired), which is the
     * whole pre-P5.4 server byte-for-byte.
     */
    public AuthGate(
            @Value("${wonderland.server.auth.enabled:false}") boolean enabled,
            @Value("${wonderland.server.auth.secret:}") String secret) {
        this.enabled = enabled;
        if (enabled) {
            if (secret == null || secret.isEmpty()) {
                // The gate is asked to be live but has no key: every token
                // verification would produce the wrong identity (PasscodeAuthenticator
                // refuses empty secrets by design). Fail loudly at boot, not
                // silently at login.
                logger.warn("auth is enabled but wonderland.server.auth.secret is blank;"
                        + " every login will be refused until the operator sets a secret");
                this.authenticator = null;
            } else {
                this.authenticator = new PasscodeAuthenticator(secret);
            }
        } else {
            this.authenticator = null;
        }
    }

    /**
     * Resolve the identity one attach carries. Returns null when the gate is
     * enabled and the token is absent, malformed, or forged — the transport
     * then closes the socket. Returns the plain username/fullname/email
     * identity when auth is off (the pre-P5.4 path).
     */
    public WonderlandIdentity resolve(Properties properties) {
        if (!enabled) {
            return anonymousFrom(properties);
        }
        // Auth is live. Read the token.
        Properties props = (properties == null) ? new Properties() : properties;
        String token = props.getProperty("token");
        if (token == null || token.isEmpty()) {
            logger.debug("attach without a token, auth enabled -- refused");
            return null;
        }
        if (authenticator == null) {
            // Misconfigured: enabled but no secret. Refuse, never accept.
            logger.warn("auth enabled but no secret configured -- refusing all logins");
            return null;
        }
        WonderlandIdentity id = authenticator.verify(token);
        if (id == null) {
            logger.debug("token verification failed -- refused");
            return null;
        }
        return id;
    }

    /** Whether this gate is actively refusing unauthenticated connections. */
    public boolean isActive() {
        return enabled;
    }

    /** The pre-P5.4 plain-identity path, factored out so both modes share it. */
    private static WonderlandIdentity anonymousFrom(Properties properties) {
        Properties props = (properties == null) ? new Properties() : properties;
        String username = props.getProperty("username", "anonymous");
        return new WonderlandIdentity(username,
                props.getProperty("fullname", username),
                props.getProperty("email", username + "@wonderland.invalid"));
    }
}
