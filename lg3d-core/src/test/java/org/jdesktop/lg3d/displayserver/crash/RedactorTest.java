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
package org.jdesktop.lg3d.displayserver.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the privacy boundary. Every string that reaches a public
 * issue passes through {@link Redactor}, so a gap here is a leak in the tracker -
 * not in a log file the user keeps. The tests assert both directions: that the
 * things which must go are gone, and that the text around them survives, because
 * a redactor that eats the stack trace produces a report nobody can debug.
 */
class RedactorTest {

    private static final String HOME = "/home/alexandre";
    private static final String USER = "alexandre";

    private static String redact(String text) {
        return Redactor.redact(text, HOME, USER);
    }

    @Test
    @DisplayName("the home directory is shortened, in both written forms")
    void homeDirectoryIsMasked() {
        assertEquals("could not read ~/lg3d/lgconfig.xml",
                redact("could not read " + HOME + "/lg3d/lgconfig.xml"));
        assertTrue(redact("path /home/alexandre/ ends here").contains("~/"));
        assertFalse(redact(HOME + "/secret.txt").contains("alexandre"));
    }

    @Test
    @DisplayName("the account name is masked only as a whole token")
    void userNameIsMaskedOnWordBoundaries() {
        assertEquals("as <user> logged in", redact("as " + USER + " logged in"));
        // A real word that happens to contain the account name must survive: a
        // developer called "ann" does not get "annealing" rewritten.
        assertEquals("alexandrine hair", redact("alexandrine hair"));
        assertEquals("user-name/alexandre-x", redact("user-name/" + USER + "-x"),
                "a hyphen-joined token is one word, not the bare account name");
    }

    @Test
    @DisplayName("a one-character account name is skipped")
    void trivialNameIsNotMasked() {
        // Masking "a" everywhere would destroy the trace to protect nothing.
        assertEquals("a and b", Redactor.redact("a and b", null, "a"));
    }

    @Test
    @DisplayName("an account name holding a regex metacharacter does not explode")
    void metacharacterNameIsLiteral() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            String out = Redactor.redact("file owned by ab* is locked", HOME, "ab*");
            assertTrue(out.contains("<user>"), out);
        });
    }

    @Test
    @DisplayName("e-mail addresses are masked")
    void emailIsMasked() {
        assertEquals("contact <email> for access", redact("contact a.lex+lg@acme.co.uk for access"));
    }

    @Test
    @DisplayName("shaped credentials are masked including GitHub's four prefixes")
    void shapedSecretsAreMasked() {
        assertTrue(redact("token ghp_0123456789abcdef0123456789abcdef0123").contains("<token>"));
        assertTrue(redact("use github_pat_11ABCDEFG0123456789abcdef_0123456789abcdef")
                .contains("<token>"));
        assertTrue(redact("key AKIA1234567890ABCDEF").contains("<token>"));
        // The documented AWS example key: every capital letter is legal in a real
        // access-key id, so a character class narrowed to digits would leak it.
        assertTrue(redact("AKIAIOSFODNN7EXAMPLE").contains("<token>"));
        assertTrue(redact("AIzaSyA1234567890abcdefghijklmnopqrstuv").contains("<token>"));
    }

    @Test
    @DisplayName("an Authorization or Bearer value is masked, label included")
    void authHeadersAreMasked() {
        String out = redact("request failed: Authorization: Basic dXNlcjpwYXNz");
        assertFalse(out.contains("dXNlcjpwYXNz"), out);
        assertFalse(out.contains("Basic"), out);
        assertTrue(redact("header Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig").contains("<token>"));
    }

    @Test
    @DisplayName("a key=value secret keeps its key and loses its value")
    void keyValueSecretKeepsItsName() {
        // The maintainer must still be able to tell WHICH setting leaked; eating the
        // whole line would hide the configuration key that is the actual defect.
        assertEquals("password=<token>", redact("password=hunter2"));
        assertEquals("api-key = <token>", redact("api-key = sk_live_abcdef"));
        assertEquals("token: <token>, retry=3", redact("token: abcdef123456, retry=3"));
    }

    @Test
    @DisplayName("the runtime directory, which names a uid and a session, is masked")
    void runtimeDirectoryIsMasked() {
        assertTrue(redact("cannot bind /run/user/1000/bus").contains("<runtime-dir>"));
        assertTrue(redact("display from /tmp/.X11-unix/X0").contains("<runtime-dir>"));
    }

    @Test
    @DisplayName("null and empty text come back as empty, never null")
    void emptyInputIsSafe() {
        assertEquals("", redact(null));
        assertEquals("", redact(""));
        assertEquals("", Redactor.clipStack(null, 10));
        assertEquals("", Redactor.clipText(null, 10));
        assertEquals("unchanged", redact("unchanged"));
    }

    @Test
    @DisplayName("a short trace is returned untouched")
    void clipStackKeepsShortInputIntact() {
        String trace = "java.lang.NullPointerException\n\tat a.b.c(C.java:1)";
        assertEquals(trace, Redactor.clipStack(trace, 10));
    }

    @Test
    @DisplayName("a long trace keeps its head and says how much was dropped")
    void clipStackKeepsTheHead() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("frame ").append(i);
            if (i < 39) {
                sb.append('\n');
            }
        }
        String out = Redactor.clipStack(sb.toString(), 5);
        assertTrue(out.startsWith("frame 0\nframe 1"), "the failing frame is first");
        assertFalse(out.contains("frame 39"), "the dispatcher tail is what goes");
        assertTrue(out.contains(Redactor.CLIP_MARK));
        assertTrue(out.contains("35 more line(s)"), out);
    }

    @Test
    @DisplayName("a free-text field is cut on a word boundary")
    void clipTextCutsBetweenWords() {
        String text = "alpha beta gamma delta epsilon zeta";
        String out = Redactor.clipText(text, 16);
        assertEquals("alpha beta gamma" + Redactor.CLIP_MARK, out);
    }

    @Test
    @DisplayName("a single oversized word is cut mid-token rather than kept whole")
    void clipTextHandlesOneLongWord() {
        String blob = "A".repeat(500);
        String out = Redactor.clipText(blob, 100);
        assertEquals(100 + Redactor.CLIP_MARK.length(), out.length());
    }

    @Test
    @DisplayName("clipping is idempotent, so an archive rewrite does not stack marks")
    void clippingIsStableAcrossRewrites() {
        // The spool rebuilds a report on every occurrence and CrashReport clips
        // each field again on the way through the builder. A clipper that re-cut its
        // own output would add a marker per rewrite and turn the trace's "35 more
        // line(s)" into "1 more line(s)" - a figure about the clipper, not the bug.
        String longText = "word ".repeat(100);
        String once = Redactor.clipText(longText, 60);
        assertEquals(once, Redactor.clipText(once, 60));
        String trace = "frame 0\n".repeat(39) + "frame 0";
        String cutOnce = Redactor.clipStack(trace, 5);
        assertEquals(cutOnce, Redactor.clipStack(cutOnce, 5));
        assertTrue(cutOnce.contains("35 more line(s)"), cutOnce);
    }

    @Test
    @DisplayName("a hostile 200 KB message is redacted in bounded time")
    void redactionIsLinearNotBacktracking() {
        // The input is the shape that a catastrophic pattern would kill on: a long
        // run of address-like characters with no terminator, repeated with secret
        // keywords interleaved. Every pattern here is linear, so this completes.
        String hostile = ("a".repeat(200) + "@" + "b".repeat(200) + ".")
                .repeat(50) + " authorization: " + "x".repeat(500)
                + " password=" + "y".repeat(500);
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            String out = redact(hostile);
            assertTrue(out.contains("<email>") || out.contains("<token>"), out.substring(0, 200));
            assertFalse(out.contains("password=y"), "the value must not survive");
        });
    }

    @Test
    @DisplayName("redaction is idempotent - a stored report re-read does not re-mask")
    void redactionIsStable() {
        String once = redact("alexandre wrote to " + HOME + "/a.txt at a.lex@acme.com");
        assertEquals(once, redact(once));
    }
}
