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
 * Classifies a URL's connection security for the address-bar indicator and the
 * site-info popup: {@link Level#SECURE} (https), {@link Level#NOT_SECURE}
 * (http/ftp), {@link Level#LOCAL} (file or loopback host), {@link Level#INTERNAL}
 * (about/data/blob/view-source/javascript) or {@link Level#UNKNOWN}.
 *
 * <p>It carries a short summary line and an optional warning, both plain text so
 * the Swing layer can render them without further logic. Free of AWT/JavaFX and
 * built on {@link UrlNormalizer}, so it is unit-tested headlessly.</p>
 */
public final class SecurityInfo {

    /** The connection-security level of a URL. */
    public enum Level {
        /** Served over TLS (https) to a non-loopback host. */
        SECURE,
        /** Cleartext (http/ftp) - credentials and content can be intercepted. */
        NOT_SECURE,
        /** A local file or a loopback host. */
        LOCAL,
        /** An internal browser page (about/data/blob/view-source/javascript). */
        INTERNAL,
        /** Blank or unrecognised. */
        UNKNOWN
    }

    private static final String[] INTERNAL_SCHEMES = {
        "about", "data", "blob", "view-source", "javascript"
    };

    private final Level level;
    private final String scheme;
    private final String host;
    private final String summary;
    private final String warning;

    private SecurityInfo(Level level, String scheme, String host, String summary, String warning) {
        this.level = level;
        this.scheme = scheme;
        this.host = host;
        this.summary = summary;
        this.warning = warning;
    }

    /**
     * Classifies {@code url}.
     *
     * @param url the page URL (may be null/blank)
     * @return the security info, never null
     */
    public static SecurityInfo of(String url) {
        if (url == null || url.isBlank()) {
            return new SecurityInfo(Level.UNKNOWN, "", "", "", null);
        }
        String trimmed = url.trim();
        String scheme = schemeOf(trimmed);
        String host = UrlNormalizer.hostOf(trimmed);

        if (isInternal(scheme)) {
            return new SecurityInfo(Level.INTERNAL, scheme, host,
                    "Internal browser page", null);
        }
        if ("file".equals(scheme) || isLoopback(host)) {
            return new SecurityInfo(Level.LOCAL, scheme, host,
                    "file".equals(scheme) ? "Local file" : "Local host", null);
        }
        if ("https".equals(scheme)) {
            return new SecurityInfo(Level.SECURE, scheme, host,
                    "Connection is secure", null);
        }
        if ("http".equals(scheme) || "ftp".equals(scheme)) {
            return new SecurityInfo(Level.NOT_SECURE, scheme, host,
                    "Not secure",
                    "Information you send to or receive from this site is not "
                    + "encrypted and could be read by others.");
        }
        return new SecurityInfo(Level.UNKNOWN, scheme, host, "", null);
    }

    /** @return the security level, never null. */
    public Level getLevel() {
        return level;
    }

    /** @return the lower-cased scheme, or "" when unknown. */
    public String getScheme() {
        return scheme;
    }

    /** @return the lower-cased host, or "" when unknown. */
    public String getHost() {
        return host;
    }

    /** @return a short human summary for the indicator tooltip, never null. */
    public String getSummary() {
        return summary;
    }

    /** @return a longer warning, or null when there is none. */
    public String getWarning() {
        return warning;
    }

    /** @return true only for {@link Level#SECURE}. */
    public boolean isSecure() {
        return level == Level.SECURE;
    }

    private static boolean isInternal(String scheme) {
        for (String s : INTERNAL_SCHEMES) {
            if (s.equals(scheme)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host);
    }

    private static String schemeOf(String url) {
        String lower = url.toLowerCase();
        // view-source:https://... should classify as internal, not by its target.
        int colon = lower.indexOf(':');
        if (colon <= 0) {
            return "";
        }
        String candidate = lower.substring(0, colon);
        // A scheme is alpha followed by alpha/digit/+/-/. per RFC 3986.
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean ok = (i == 0) ? Character.isLetter(c)
                    : (Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.');
            if (!ok) {
                return "";
            }
        }
        // Cross-check with URI so odd inputs do not yield a bogus scheme.
        try {
            String uriScheme = new URI(url).getScheme();
            return (uriScheme == null) ? candidate : uriScheme.toLowerCase();
        } catch (URISyntaxException e) {
            return candidate;
        }
    }
}
