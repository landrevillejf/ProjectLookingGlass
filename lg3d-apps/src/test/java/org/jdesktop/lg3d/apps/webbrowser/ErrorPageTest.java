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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for {@link ErrorPage} markup and escaping. */
class ErrorPageTest {

    @Test
    @DisplayName("a null failure renders a generic unknown-error page")
    void nullFailure() {
        String html = ErrorPage.html(null);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains(Html.escape(LoadFailure.Reason.UNKNOWN.getTitle())),
                "the reason title is shown, HTML-escaped");
    }

    @Test
    @DisplayName("the content type constant is text/html")
    void contentType() {
        assertEquals("text/html", ErrorPage.CONTENT_TYPE);
    }

    @Test
    @DisplayName("a retryable failure with a URL shows a Try again anchor to it")
    void retryAnchorPresent() {
        LoadFailure f = LoadFailure.of(LoadFailure.Reason.DNS_FAILURE,
                "https://example.com/page", "host not found");
        String html = ErrorPage.html(f);
        assertTrue(html.contains("class=\"btn\""));
        assertTrue(html.contains("href=\"https://example.com/page\""));
        assertTrue(html.contains("host not found"));
    }

    @Test
    @DisplayName("a non-retryable failure shows no Try again anchor")
    void noRetryForNonRetryable() {
        LoadFailure f = LoadFailure.of(LoadFailure.Reason.TLS_ERROR, "https://bad.example", null);
        assertFalse(ErrorPage.html(f).contains("class=\"btn\""));
    }

    @Test
    @DisplayName("a blank URL suppresses both the URL block and the anchor")
    void blankUrl() {
        LoadFailure f = LoadFailure.of(LoadFailure.Reason.UNKNOWN, "", null);
        String html = ErrorPage.html(f);
        assertFalse(html.contains("class=\"btn\""), "no retry anchor without a URL");
        assertFalse(html.contains("class=\"url\""));
    }

    @Test
    @DisplayName("a hostile URL/detail is escaped so it cannot inject markup (XSS)")
    void escapesInterpolatedValues() {
        String evil = "https://x/\"><script>alert(1)</script>";
        LoadFailure f = LoadFailure.of(LoadFailure.Reason.UNKNOWN, evil, evil);
        String html = ErrorPage.html(f);
        assertFalse(html.contains("<script>alert(1)</script>"),
                "the raw script tag must never reach the page");
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    @DisplayName("recovery tips for the reason are listed")
    void tipsListed() {
        LoadFailure f = LoadFailure.of(LoadFailure.Reason.DNS_FAILURE, "https://a.example", null);
        String html = ErrorPage.html(f);
        for (String tip : f.getTips()) {
            assertTrue(html.contains(Html.escape(tip)), "missing tip: " + tip);
        }
    }

    @Test
    @DisplayName("a private-mode cut renders its shield page with no retry anchor")
    void torCutPage() {
        LoadFailure f = TorCutGuard.failure("https://example.com");
        String html = ErrorPage.html(f);
        assertTrue(html.contains(Html.escape(LoadFailure.Reason.TOR_CUT.getTitle())),
                "the cut title is shown, HTML-escaped");
        assertTrue(html.contains("&#128737;"), "the cut page carries the shield icon");
        assertFalse(html.contains("class=\"btn\""), "a cut is not retryable");
        for (String tip : f.getTips()) {
            assertTrue(html.contains(Html.escape(tip)), "missing cut tip: " + tip);
        }
    }
}
