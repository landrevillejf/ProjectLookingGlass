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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The search engines the smart address bar can fall back to when the typed text
 * is not a URL (see {@link UrlNormalizer}). Each entry carries a query template
 * with a single {@value #QUERY_TOKEN} placeholder that is replaced by the
 * URL-encoded search terms.
 *
 * <p>This is deliberately a small, dependency-free registry: adding an engine is
 * one enum constant. The default is {@link #DUCKDUCKGO}, a privacy-respecting
 * engine that needs no API key.</p>
 */
public enum SearchEngine {

    /** DuckDuckGo (privacy-focused, no tracking); the default. */
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q={query}"),
    /** Google. */
    GOOGLE("Google", "https://www.google.com/search?q={query}"),
    /** Bing. */
    BING("Bing", "https://www.bing.com/search?q={query}"),
    /** Startpage (Google results without tracking). */
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query={query}");

    /** Placeholder in a query template replaced by the encoded search terms. */
    public static final String QUERY_TOKEN = "{query}";

    private final String displayName;
    private final String template;

    SearchEngine(String displayName, String template) {
        this.displayName = displayName;
        this.template = template;
    }

    /** @return the human-readable engine name shown in the settings dialog. */
    public String getDisplayName() {
        return displayName;
    }

    /** @return the query template containing {@value #QUERY_TOKEN}. */
    public String getTemplate() {
        return template;
    }

    /**
     * Builds the absolute search URL for {@code query}.
     *
     * @param query the search terms (may be null/blank, yielding the engine home)
     * @return the encoded search URL
     */
    public String searchUrl(String query) {
        String terms = (query == null) ? "" : query.trim();
        if (terms.isEmpty()) {
            // No terms: fall back to the engine's home page rather than an empty
            // query, so "search" with a blank field is a no-op navigation.
            return template.contains(QUERY_TOKEN)
                    ? template.replace(QUERY_TOKEN, "")
                    : template;
        }
        return template.replace(QUERY_TOKEN, encode(terms));
    }

    /**
     * Resolves an engine by name, case-insensitively, falling back to
     * {@link #DUCKDUCKGO} for a null/unknown value (e.g. a hand-edited settings
     * file).
     *
     * @param name the enum or display name
     * @return the matching engine, never null
     */
    public static SearchEngine fromName(String name) {
        if (name != null) {
            String trimmed = name.trim();
            for (SearchEngine engine : values()) {
                if (engine.name().equalsIgnoreCase(trimmed)
                        || engine.displayName.equalsIgnoreCase(trimmed)) {
                    return engine;
                }
            }
        }
        return DUCKDUCKGO;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
