/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.presentoire.format;

/**
 * The XML text escaping the pure-Java writers (the native {@code content.xml}
 * and the OpenDocument {@code .odp}) share, so both emit well-formed markup for
 * any string a slide carries.
 *
 * <p>Besides the five predefined entities, a newline becomes {@code &#10;}, a
 * carriage return {@code &#13;} and a tab {@code &#9;}. Numeric character
 * references survive a parser's line-ending normalisation, so a multi-line text
 * block keeps its exact breaks through a write-then-read round trip - something a
 * literal newline in the source would not guarantee.</p>
 */
final class Xml {

    private Xml() {
        // no instances
    }

    /**
     * Escapes a string for use as XML character data or a double-quoted
     * attribute value.
     *
     * @param value the raw text, may be null
     * @return the escaped text, never null (null yields the empty string)
     */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':  out.append("&amp;");  break;
                case '<':  out.append("&lt;");   break;
                case '>':  out.append("&gt;");   break;
                case '"':  out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                case '\n': out.append("&#10;");  break;
                case '\r': out.append("&#13;");  break;
                case '\t': out.append("&#9;");   break;
                default:   out.append(c);        break;
            }
        }
        return out.toString();
    }
}
