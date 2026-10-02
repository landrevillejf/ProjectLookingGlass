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

import java.net.URI;
import java.net.URISyntaxException;

/**
 * The smart address bar's resolution logic, isolated from any UI so it can be
 * unit-tested headless.
 *
 * <p>Given the raw text typed into the location field, {@link #normalize}
 * decides whether it is a URL or a search query and returns an absolute URL the
 * {@code WebEngine} can load:</p>
 * <ul>
 *  <li>text that already carries a scheme ({@code https://}, {@code file:},
 *      {@code about:blank}, ...) is used verbatim (after a syntax check);</li>
 *  <li>text that looks like a host ({@code example.com}, {@code localhost:8080},
 *      an IP, or a single label with a path) becomes an {@code https://} URL;</li>
 *  <li>anything else (contains spaces, or is a bare word) is handed to the
 *      configured {@link SearchEngine}.</li>
 * </ul>
 *
 * <p>The heuristics intentionally favour "search" for ambiguous single tokens so
 * the common case of typing a search phrase works, while a dotted host or an
 * explicit scheme always navigates.</p>
 */
public final class UrlNormalizer {

    /** Schemes that are already absolute and are passed through unchanged. */
    private static final String[] KNOWN_SCHEMES = {
        "http://", "https://", "file:", "ftp://", "about:", "data:",
        "javascript:", "view-source:", "mailto:", "blob:"
    };

    private UrlNormalizer() {
        // no instances
    }

    /**
     * Resolves address-bar text to an absolute URL.
     *
     * @param input  the raw text (may be null/blank)
     * @param engine the search engine for non-URL text (null defaults to
     *               {@link SearchEngine#DUCKDUCKGO})
     * @return an absolute URL, or {@code null} when {@code input} is blank
     */
    public static String normalize(String input, SearchEngine engine) {
        if (input == null) {
            return null;
        }
        String text = input.trim();
        if (text.isEmpty()) {
            return null;
        }
        SearchEngine search = (engine == null) ? SearchEngine.DUCKDUCKGO : engine;
        String lower = text.toLowerCase();
        if (hasKnownScheme(lower)) {
            return syntacticallyValid(text) ? text : search.searchUrl(text);
        }
        if (isProbablyHost(text)) {
            String candidate = "https://" + text;
            return syntacticallyValid(candidate) ? candidate : search.searchUrl(text);
        }
        return search.searchUrl(text);
    }

    /**
     * True when {@code input} looks like a navigable host/URL rather than a
     * search phrase: it carries a known scheme, or has no whitespace and either
     * a dot, a port, or is {@code localhost}.
     *
     * @param input the raw text (may be null)
     * @return true if it should be treated as a URL
     */
    public static boolean isProbablyUrl(String input) {
        if (input == null) {
            return false;
        }
        String text = input.trim();
        if (text.isEmpty()) {
            return false;
        }
        if (hasKnownScheme(text.toLowerCase())) {
            return true;
        }
        return isProbablyHost(text);
    }

    /** True when {@code url} is served over TLS ({@code https://}). */
    public static boolean isSecure(String url) {
        return url != null && url.trim().toLowerCase().startsWith("https://");
    }

    /**
     * Extracts the host of an absolute URL, for display and de-duplication.
     *
     * @param url the URL (may be null or malformed)
     * @return the lower-cased host, or {@code ""} when it cannot be determined
     */
    public static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        try {
            String host = new URI(url.trim()).getHost();
            return (host == null) ? "" : host.toLowerCase();
        } catch (URISyntaxException e) {
            return "";
        }
    }

    /**
     * Builds a {@code view-source:} URL for {@code url} so the engine shows the
     * raw markup instead of rendering it.
     *
     * @param url the page URL (may be null)
     * @return the view-source URL, or {@code null} when {@code url} is blank
     */
    public static String viewSourceOf(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String trimmed = url.trim();
        return trimmed.startsWith("view-source:") ? trimmed : "view-source:" + trimmed;
    }

    private static boolean hasKnownScheme(String lower) {
        for (String scheme : KNOWN_SCHEMES) {
            if (lower.startsWith(scheme)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A token is treated as a host when it has no whitespace and either contains
     * a dot, is {@code localhost} (optionally with a port), or looks like
     * {@code name:port} / {@code name/path}.
     */
    private static boolean isProbablyHost(String text) {
        if (text.indexOf(' ') >= 0 || text.indexOf('\t') >= 0) {
            return false;
        }
        String lower = text.toLowerCase();
        if (lower.startsWith("localhost")) {
            return true;
        }
        if (text.indexOf('.') >= 0) {
            return true;
        }
        // scheme-less "host:port" or "host/path" with a single label.
        return text.indexOf(':') >= 0 || text.indexOf('/') >= 0;
    }

    private static boolean syntacticallyValid(String url) {
        try {
            URI uri = new URI(url);
            return uri.isAbsolute() && uri.getScheme() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
