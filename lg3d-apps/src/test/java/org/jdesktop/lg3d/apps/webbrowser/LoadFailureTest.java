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
package org.jdesktop.lg3d.apps.webbrowser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for {@link LoadFailure} classification. */
class LoadFailureTest {

    @Test
    @DisplayName("a null throwable classifies as UNKNOWN, never null")
    void nullThrowable() {
        LoadFailure f = LoadFailure.classify(null, "https://a.com");
        assertNotNull(f);
        assertSame(LoadFailure.Reason.UNKNOWN, f.getReason());
        assertEquals("https://a.com", f.getUrl());
    }

    @Test
    @DisplayName("typed exceptions map to their reason")
    void typedMatches() {
        assertSame(LoadFailure.Reason.DNS_FAILURE,
                LoadFailure.classify(new UnknownHostException("nope"), null).getReason());
        assertSame(LoadFailure.Reason.CONNECTION_REFUSED,
                LoadFailure.classify(new ConnectException("refused"), null).getReason());
        assertSame(LoadFailure.Reason.TIMEOUT,
                LoadFailure.classify(new SocketTimeoutException("slow"), null).getReason());
        assertSame(LoadFailure.Reason.TIMEOUT,
                LoadFailure.classify(new InterruptedIOException("interrupted"), null).getReason());
        assertSame(LoadFailure.Reason.TLS_ERROR,
                LoadFailure.classify(new SSLException("bad cert"), null).getReason());
        assertSame(LoadFailure.Reason.MALFORMED_URL,
                LoadFailure.classify(new URISyntaxException("x", "bad"), null).getReason());
    }

    @Test
    @DisplayName("a typed cause buried in the chain is still found")
    void walksCauseChain() {
        RuntimeException wrapper =
                new RuntimeException("wrapper", new IllegalStateException(
                        new UnknownHostException("deep")));
        assertSame(LoadFailure.Reason.DNS_FAILURE,
                LoadFailure.classify(wrapper, "https://a.com").getReason());
    }

    @Test
    @DisplayName("a self-referential cause chain does not loop forever")
    void cyclicCauseIsSafe() {
        RuntimeException a = new RuntimeException("timed out");
        RuntimeException b = new RuntimeException(a);
        a.initCause(b);
        assertNotNull(LoadFailure.classify(a, null));
    }

    @Test
    @DisplayName("message text is used when no type matches")
    void messageFallback() {
        assertSame(LoadFailure.Reason.CANCELLED,
                LoadFailure.classify(new RuntimeException("Cancelled"), null).getReason());
        assertSame(LoadFailure.Reason.DNS_FAILURE,
                LoadFailure.classify(new RuntimeException(
                        "Temporary failure in name resolution"), null).getReason());
        assertSame(LoadFailure.Reason.TLS_ERROR,
                LoadFailure.classify(new RuntimeException("SSL handshake failed"), null).getReason());
        assertSame(LoadFailure.Reason.CONNECTION_REFUSED,
                LoadFailure.classify(new RuntimeException("Connection refused"), null).getReason());
        assertSame(LoadFailure.Reason.MALFORMED_URL,
                LoadFailure.classify(new RuntimeException("Malformed URL"), null).getReason());
    }

    @Test
    @DisplayName("an HTTP status in the message classifies as client/server error")
    void statusInMessage() {
        assertSame(LoadFailure.Reason.HTTP_SERVER_ERROR,
                LoadFailure.classify(new RuntimeException("server said 503"), null).getReason());
        assertSame(LoadFailure.Reason.HTTP_CLIENT_ERROR,
                LoadFailure.classify(new RuntimeException("got 404 not found"), null).getReason());
    }

    @Test
    @DisplayName("fromHttpStatus maps 4xx/5xx and leaves others UNKNOWN")
    void fromHttpStatus() {
        assertSame(LoadFailure.Reason.HTTP_CLIENT_ERROR,
                LoadFailure.fromHttpStatus(404, "u").getReason());
        assertSame(LoadFailure.Reason.HTTP_SERVER_ERROR,
                LoadFailure.fromHttpStatus(500, "u").getReason());
        assertSame(LoadFailure.Reason.UNKNOWN,
                LoadFailure.fromHttpStatus(200, "u").getReason());
    }

    @Test
    @DisplayName("retryable flags match the reason's policy")
    void retryPolicy() {
        assertTrue(LoadFailure.of(LoadFailure.Reason.DNS_FAILURE, "u", null).isRetryable());
        assertTrue(LoadFailure.of(LoadFailure.Reason.TIMEOUT, "u", null).isRetryable());
        assertFalse(LoadFailure.of(LoadFailure.Reason.TLS_ERROR, "u", null).isRetryable());
        assertFalse(LoadFailure.of(LoadFailure.Reason.MALFORMED_URL, "u", null).isRetryable());
    }

    @Test
    @DisplayName("of() is null-safe on reason/url/detail and exposes presentation text")
    void ofIsSafe() {
        LoadFailure f = LoadFailure.of(null, null, null);
        assertSame(LoadFailure.Reason.UNKNOWN, f.getReason());
        assertEquals("", f.getUrl());
        assertEquals("", f.getDetail());
        assertNotNull(f.getTitle());
        assertNotNull(f.getExplanation());
        assertNotNull(f.getTips());
    }

    @Test
    @DisplayName("describe yields a readable reason for a transfer exception")
    void describe() {
        assertTrue(LoadFailure.describe(new SocketTimeoutException("timed out"))
                .contains(LoadFailure.Reason.TIMEOUT.getTitle()));
        assertEquals("", LoadFailure.describe(null));
    }
}
