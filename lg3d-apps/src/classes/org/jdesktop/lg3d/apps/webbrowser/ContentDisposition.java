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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Parses a {@code Content-Disposition} response header to recover the server's
 * suggested file name, so downloads are named correctly instead of guessing from
 * the URL path. Supports both the plain {@code filename="..."} form and the
 * RFC&nbsp;5987 extended {@code filename*=UTF-8''percent-encoded} form; per
 * RFC&nbsp;6266 the extended form wins when both are present.
 *
 * <p>The result is always reduced to a single safe path segment (any directory
 * components a malicious header might carry are dropped). Free of AWT/JavaFX so
 * the parsing is unit-tested headlessly.</p>
 */
public final class ContentDisposition {

    private ContentDisposition() {
        // no instances
    }

    /**
     * Extracts the suggested file name from a {@code Content-Disposition} value.
     *
     * @param header the raw header value (may be null)
     * @return a safe single-segment file name, or {@code null} when the header
     *         carries none
     */
    public static String fileName(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String extended = parameter(header, "filename*");
        if (extended != null) {
            String decoded = decodeExtended(extended);
            String safe = sanitize(decoded);
            if (safe != null) {
                return safe;
            }
        }
        String plain = parameter(header, "filename");
        if (plain != null) {
            return sanitize(unquote(plain));
        }
        return null;
    }

    /**
     * Finds a (case-insensitive) parameter value in a {@code ;}-separated header,
     * returning the raw value token (still quoted / encoded) or null.
     */
    private static String parameter(String header, String name) {
        // Split on ';' but respect quoted strings so a ';' inside quotes survives.
        int i = 0;
        int n = header.length();
        while (i < n) {
            int start = i;
            boolean inQuote = false;
            while (i < n) {
                char c = header.charAt(i);
                if (c == '"') {
                    inQuote = !inQuote;
                } else if (c == ';' && !inQuote) {
                    break;
                }
                i++;
            }
            String segment = header.substring(start, i).trim();
            i++; // skip the ';' (or step past end)
            int eq = segment.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = segment.substring(0, eq).trim();
            if (key.equalsIgnoreCase(name)) {
                return segment.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /** Strips a single layer of surrounding double quotes and unescapes {@code \"}. */
    private static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
            return v.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return v;
    }

    /**
     * Decodes an RFC 5987 extended value: {@code charset'language'percent-encoded}.
     * The charset and language are advisory; the payload is percent-decoded as
     * UTF-8 (falling back to the raw text if it is not a valid extended value).
     */
    private static String decodeExtended(String value) {
        String v = unquote(value);
        int first = v.indexOf('\'');
        int second = (first < 0) ? -1 : v.indexOf('\'', first + 1);
        if (first < 0 || second < 0) {
            return v;
        }
        String encoded = v.substring(second + 1);
        return percentDecode(encoded);
    }

    /** Percent-decodes a string as UTF-8, leaving invalid escapes verbatim. */
    private static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            // Write non-percent bytes as UTF-8 so multi-byte chars survive.
            byte[] raw = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
            out.write(raw, 0, raw.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Reduces a candidate name to a single safe path segment: drops any
     * directory components, strips control characters and path separators, and
     * rejects blank or {@code .}/{@code ..} results.
     *
     * @return the safe name, or null when nothing usable remains
     */
    private static String sanitize(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) {
            n = n.substring(slash + 1);
        }
        // Remove control characters and anything unsafe for a file name.
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return null;
        }
        return cleaned;
    }
}
