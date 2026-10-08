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

/**
 * Minimal, dependency-free HTML escaping shared by the browser's own generated
 * pages (the load-failure {@link ErrorPage} and the {@link ReaderExtractor}
 * reader view). Every value interpolated into a page the {@code WebEngine}
 * renders is passed through {@link #escape} so a hostile URL, title or page
 * fragment can never inject markup or script (XSS) into the chrome we build.
 *
 * <p>Free of AWT/JavaFX so it is unit-tested headlessly.</p>
 */
public final class Html {

    private Html() {
        // no instances
    }

    /**
     * Escapes the five characters that are significant in HTML text and
     * attributes. {@code null} becomes the empty string.
     *
     * @param text the raw text (may be null)
     * @return the escaped text, safe to embed in HTML, never null
     */
    public static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&#39;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
