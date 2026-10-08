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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Pure helpers behind the Web Browser's blank-page workaround.
 *
 * <p>JavaFX 21's WebKit loader ({@code com.sun.webkit.network.HTTP2Loader}) can
 * only decode {@code Content-Encoding: gzip} and {@code deflate}. When a site
 * answers with any other token -- Brotli, zstd, or a non-standard {@code none}
 * -- the loader logs {@code "Unknown encoding type '...' found, discarding"} at
 * SEVERE and throws the body away, so the load worker still reports
 * {@code SUCCEEDED} but the page renders completely blank. This is an engine
 * limitation that is only fixed in JavaFX 24+ (which needs JDK 22+, beyond this
 * port's JDK 21 pin), so it cannot be patched at the source.</p>
 *
 * <p>The workaround re-fetches such a page with the JDK {@code java.net.http}
 * client -- which, unlike WebKit, does not advertise {@code Accept-Encoding} and
 * so is normally answered with a plain identity body, and can decode gzip /
 * deflate itself when it is not. This class holds every decision that does not
 * need JavaFX so it is unit-tested headlessly: detecting the discarded body from
 * a cheap DOM probe, decoding the transported {@code Content-Encoding}, choosing
 * a charset, and re-injecting a {@code <base>} tag so the recovered document's
 * relative links and images still resolve against the origin (a
 * {@code WebEngine.loadContent} document has no base URL of its own).</p>
 */
public final class EncodingFallback {

    /**
     * Element-count ceiling at or below which a document is treated as an empty
     * skeleton. WebKit builds {@code <html><head></head><body></body></html>}
     * (three elements) when it discards a body; any real page has far more.
     */
    static final int MAX_BLANK_ELEMENT_COUNT = 4;

    /** Sentinels returned by {@link #parseProbe} when the probe is unusable. */
    private static final int UNKNOWN = -1;

    /** Matches a {@code charset=...} parameter in a Content-Type header. */
    private static final Pattern CHARSET =
            Pattern.compile("charset\\s*=\\s*\"?([A-Za-z0-9._:-]+)");

    /** Matches an existing {@code <base ...>} element (any case). */
    private static final Pattern BASE_TAG =
            Pattern.compile("<base\\b", Pattern.CASE_INSENSITIVE);

    /** Matches the opening {@code <head ...>} tag (any case). */
    private static final Pattern HEAD_OPEN =
            Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** Matches the opening {@code <html ...>} tag (any case). */
    private static final Pattern HTML_OPEN =
            Pattern.compile("<html\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** Streaming copy buffer for decompression. */
    private static final int BUFFER_BYTES = 8192;

    private EncodingFallback() {
        // no instances
    }

    /**
     * Parses the {@code "elementCount:textLength"} string returned by the DOM
     * probe script. Tolerant of null / malformed input so a failed script can
     * never be mistaken for a blank page.
     *
     * @param raw the probe result (may be null)
     * @return a two-element array {@code {elementCount, textLength}}, or
     *         {@code {-1, -1}} when the probe could not be read
     */
    public static int[] parseProbe(String raw) {
        if (raw == null) {
            return new int[] {UNKNOWN, UNKNOWN};
        }
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon == raw.length() - 1) {
            return new int[] {UNKNOWN, UNKNOWN};
        }
        try {
            int elements = Integer.parseInt(raw.substring(0, colon).trim());
            int text = Integer.parseInt(raw.substring(colon + 1).trim());
            return new int[] {elements, text};
        } catch (NumberFormatException e) {
            return new int[] {UNKNOWN, UNKNOWN};
        }
    }

    /**
     * True when a finished document is an empty skeleton with no text: the
     * signature of a body WebKit discarded for an undecodable
     * {@code Content-Encoding}. A negative (unknown) count never matches.
     *
     * @param elementCount the number of elements in the document
     * @param textLength   the trimmed length of the body's text content
     * @return true when the page should be treated as blank
     */
    public static boolean isBlankDom(int elementCount, int textLength) {
        return elementCount >= 0 && elementCount <= MAX_BLANK_ELEMENT_COUNT && textLength <= 0;
    }

    /**
     * Decodes a transported body according to its {@code Content-Encoding}.
     * Identity, the non-standard {@code none}, gzip and deflate are handled; any
     * other token (Brotli, zstd, ...) is beyond this build and raises an
     * {@link IOException} so the caller can present an honest error page.
     *
     * @param body            the raw response bytes (may be null)
     * @param contentEncoding the response's Content-Encoding header (may be null)
     * @return the decoded bytes, never null
     * @throws IOException when the encoding is unsupported or the body is corrupt
     */
    public static byte[] decode(byte[] body, String contentEncoding) throws IOException {
        if (body == null) {
            return new byte[0];
        }
        String enc = normalize(contentEncoding);
        switch (enc) {
            case "":
            case "identity":
            case "none":
                return body;
            case "gzip":
            case "x-gzip":
                return gunzip(body);
            case "deflate":
                return inflate(body);
            default:
                throw new IOException("Unsupported Content-Encoding: " + contentEncoding);
        }
    }

    /**
     * Resolves the charset named by a {@code Content-Type} header, defaulting to
     * UTF-8 when it is absent, malformed or unknown to this JVM.
     *
     * @param contentType the response's Content-Type header (may be null)
     * @return the charset to decode the body with, never null
     */
    public static Charset charsetFor(String contentType) {
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (RuntimeException ignored) {
                    // An unknown/illegal charset name falls through to UTF-8.
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Injects a {@code <base href="url">} tag so a document rendered with
     * {@code WebEngine.loadContent} (which has no base URL) still resolves its
     * relative links, scripts and images against the original origin. A document
     * that already declares a {@code <base>} is returned unchanged; when there is
     * no {@code <head>} one is synthesised right after {@code <html>}, and with
     * neither the tag is simply prepended.
     *
     * @param html    the recovered markup (may be null)
     * @param baseUrl the origin URL to make relative references resolve against
     * @return the markup with a base tag ensured, never null
     */
    public static String injectBaseTag(String html, String baseUrl) {
        String doc = (html == null) ? "" : html;
        if (baseUrl == null || baseUrl.isBlank() || doc.isEmpty()) {
            return doc;
        }
        if (BASE_TAG.matcher(doc).find()) {
            return doc;
        }
        String base = "<base href=\"" + Html.escape(baseUrl) + "\">";
        Matcher head = HEAD_OPEN.matcher(doc);
        if (head.find()) {
            return doc.substring(0, head.end()) + base + doc.substring(head.end());
        }
        Matcher htmlTag = HTML_OPEN.matcher(doc);
        if (htmlTag.find()) {
            return doc.substring(0, htmlTag.end()) + "<head>" + base + "</head>"
                    + doc.substring(htmlTag.end());
        }
        return base + doc;
    }

    /**
     * A short, human-readable description of a decoding failure, used as the
     * detail line of the fallback error page.
     *
     * @param t the failure (may be null)
     * @return a non-null message
     */
    public static String describe(Throwable t) {
        if (t == null) {
            return "";
        }
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg.trim();
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static String normalize(String contentEncoding) {
        if (contentEncoding == null) {
            return "";
        }
        return contentEncoding.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return readAll(in);
        }
    }

    private static byte[] inflate(byte[] data) throws IOException {
        // RFC 7230 "deflate" is zlib-wrapped, which is Inflater's default mode.
        try (InflaterInputStream in =
                     new InflaterInputStream(new ByteArrayInputStream(data), new Inflater())) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
