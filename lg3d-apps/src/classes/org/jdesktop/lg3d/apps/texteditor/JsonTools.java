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

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "JSON Tools" extension: minify and pretty-print the whole JSON
 * document with a lightweight character scanner, registered through the public
 * {@link TextEditorExtension} SPI under the {@code Data} category.
 *
 * <p>The transforms are structure-preserving rather than validating: they walk
 * the text tracking string-literal state (honouring backslash escapes) and
 * nesting depth, so whitespace is only added or removed <em>outside</em> string
 * literals. Malformed JSON is therefore passed through with its literals intact
 * instead of being rejected &mdash; the tools reformat, they do not parse. Both
 * helpers are pure {@code String -> String} statics for headless unit tests.</p>
 */
public final class JsonTools implements TextEditorExtension {

    /** Indent width used by the pretty-printer (four spaces per nesting level). */
    private static final String INDENT = "    ";

    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.json-tools",
                "JSON Tools",
                "1.0.0",
                "Minify or pretty-print a JSON document",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public String category() {
        return "Data";
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("json-minify", "Minify JSON",
                        "Remove insignificant whitespace from the JSON document (Ctrl+Alt+X)",
                        () -> applyWhole(JsonTools::minify), "control alt X"),
                new ToolbarContribution("json-prettify", "Prettify JSON",
                        "Indent the JSON document with four spaces per level (Ctrl+Alt+O)",
                        () -> applyWhole(JsonTools::prettify), "control alt O")
        );
    }

    /** Runs {@code fn} over the whole document, writing back only when it changes. */
    private void applyWhole(UnaryOperator<String> fn) {
        if (currentDoc == null) {
            return;
        }
        String text = currentDoc.getFullText();
        if (text.isEmpty()) {
            return;
        }
        String replaced = fn.apply(text);
        if (!replaced.equals(text)) {
            currentDoc.setFullText(replaced);
        }
    }

    // ------------------------------------------------------------------
    // Pure transforms (headless-testable)
    // ------------------------------------------------------------------

    /** Collapse the JSON to a single line, stripping whitespace outside strings. Pure. */
    public static String minify(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Re-indent the JSON with four spaces per nesting level. Pure. */
    public static String prettify(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String compact = minify(text);
        StringBuilder out = new StringBuilder(compact.length() + 16);
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    out.append(c);
                }
                case '{', '[' -> {
                    out.append(c);
                    newlineIndent(out, ++depth);
                }
                case '}', ']' -> {
                    newlineIndent(out, --depth);
                    out.append(c);
                }
                case ',' -> {
                    out.append(c);
                    newlineIndent(out, depth);
                }
                case ':' -> out.append(": ");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static void newlineIndent(StringBuilder out, int depth) {
        out.append('\n');
        out.append(INDENT.repeat(Math.max(0, depth)));
    }
}
