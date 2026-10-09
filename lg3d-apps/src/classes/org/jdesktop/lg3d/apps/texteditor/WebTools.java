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
package org.jdesktop.lg3d.apps.texteditor;

import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Web Tools" extension: conversion helpers for web/XML-oriented
 * text, registered through the public {@link TextEditorExtension} SPI and
 * grouped under the {@code Web} category. It escapes/unescapes HTML/XML
 * entities and percent-encodes/decodes URLs, always operating on the current
 * selection (so it never rewrites a whole document by surprise).
 *
 * <p>Each helper is a pure {@code String -> String} function exposed as a static
 * for headless unit tests. Actions run through {@code replaceSelection}; an
 * empty selection or an unchanged result never dirties the document.</p>
 */
public final class WebTools implements TextEditorExtension {

    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.web-tools",
                "Web Tools",
                "1.0.0",
                "Escape/unescape HTML entities and percent-encode/decode URLs",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public String category() {
        return "Web";
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("web-escape-html", "Escape HTML Entities",
                        "Turn reserved characters into HTML/XML entities",
                        () -> applyToSelection(WebTools::escapeHtml)),
                new ToolbarContribution("web-unescape-html", "Unescape HTML Entities",
                        "Expand HTML/XML entities into their characters",
                        () -> applyToSelection(WebTools::unescapeHtml)),
                new ToolbarContribution("web-url-encode", "URL Encode",
                        "Percent-encode the selection as a URL component",
                        () -> applyToSelection(WebTools::urlEncode)),
                new ToolbarContribution("web-url-decode", "URL Decode",
                        "Percent-decode the selection (leaves malformed input as-is)",
                        () -> applyToSelection(WebTools::urlDecode))
        );
    }

    /** Runs {@code fn} over the selection, replacing it only when it changes. */
    private void applyToSelection(UnaryOperator<String> fn) {
        if (currentDoc == null) {
            return;
        }
        String selection = currentDoc.getSelectedText();
        if (selection.isEmpty()) {
            return;
        }
        String converted = fn.apply(selection);
        if (!converted.equals(selection)) {
            currentDoc.replaceSelection(converted);
        }
    }

    // ------------------------------------------------------------------
    // Pure conversions (headless-testable)
    // ------------------------------------------------------------------

    /**
     * Escapes the five HTML/XML reserved characters ({@code & < > " '}) into
     * entities. Pure.
     */
    public static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Expands named entities ({@code &amp; &lt; &gt; &quot; &#39; &apos; &nbsp;})
     * and numeric references ({@code &#60;} / {@code &#x3C;}). A single left-to-
     * right scan means {@code "&amp;lt;"} decodes to the literal {@code "&lt;"}
     * rather than double-decoding to {@code "<"}. Unknown references are left
     * untouched. Pure.
     */
    public static String unescapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int semi = text.indexOf(';', i + 1);
            String decoded = (semi < 0) ? null : decodeEntity(text.substring(i + 1, semi));
            if (decoded == null) {
                out.append(c);
                i++;
            } else {
                out.append(decoded);
                i = semi + 1;
            }
        }
        return out.toString();
    }

    private static String decodeEntity(String entity) {
        switch (entity) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return "\u00A0";
            default: break;
        }
        try {
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                return fromCodePoint(Integer.parseInt(entity.substring(2), 16));
            }
            if (entity.startsWith("#")) {
                return fromCodePoint(Integer.parseInt(entity.substring(1)));
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    private static String fromCodePoint(int cp) {
        if (!Character.isValidCodePoint(cp)) {
            return null;
        }
        return new String(Character.toChars(cp));
    }

    /**
     * Percent-encodes the selection as an {@code application/x-www-form-urlencoded}
     * value (a space becomes {@code +}). Pure.
     */
    public static String urlEncode(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    /**
     * Percent-decodes the selection; malformed escape sequences (which would
     * otherwise throw) leave the input unchanged. Pure.
     */
    public static String urlDecode(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }
}
