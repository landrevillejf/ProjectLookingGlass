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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Formatting Tools" extension: whitespace and line-ending
 * clean-up for source files, registered through the public
 * {@link TextEditorExtension} SPI and grouped under the {@code Code} category
 * alongside {@link CodeTools}. It converts between tabs and spaces and
 * normalises line endings to Unix (LF) or Windows (CRLF).
 *
 * <p>Every operation is a pure {@code String -> String} function exposed as a
 * static for headless unit tests; the toolbar actions pipe the whole document
 * through the matching function and write it back, so a no-op result never
 * dirties the document.</p>
 */
public final class FormatTools implements TextEditorExtension {

    /** The number of spaces a tab expands to (and a group of spaces collapses from). */
    static final int TAB_WIDTH = 4;

    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.format-tools",
                "Formatting Tools",
                "1.0.0",
                "Convert tabs/spaces and normalise LF or CRLF line endings",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public String category() {
        return "Code";
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("fmt-tabs-to-spaces", "Tabs to Spaces",
                        "Expand every tab into " + TAB_WIDTH + " spaces",
                        () -> applyWhole(tabsToSpaces(currentText(), TAB_WIDTH), "Tabs expanded")),
                new ToolbarContribution("fmt-spaces-to-tabs", "Spaces to Tabs",
                        "Collapse leading spaces into tabs (groups of " + TAB_WIDTH + ")",
                        () -> applyWhole(spacesToTabs(currentText(), TAB_WIDTH), "Indentation tabbed")),
                new ToolbarContribution("fmt-lf", "Normalize Line Endings (LF)",
                        "Convert CRLF/CR endings to Unix LF",
                        () -> applyWhole(normalizeToLf(currentText()), "Line endings set to LF")),
                new ToolbarContribution("fmt-crlf", "Normalize Line Endings (CRLF)",
                        "Convert LF/CR endings to Windows CRLF",
                        () -> applyWhole(normalizeToCrlf(currentText()), "Line endings set to CRLF"))
        );
    }

    private String currentText() {
        return (currentDoc == null) ? "" : currentDoc.getFullText();
    }

    private void applyWhole(String result, String message) {
        if (currentDoc == null) {
            return;
        }
        if (!result.equals(currentDoc.getFullText())) {
            currentDoc.setFullText(result);
        }
    }

    // ------------------------------------------------------------------
    // Pure transforms (headless-testable)
    // ------------------------------------------------------------------

    /** Expands every tab character to {@code width} spaces. Pure. */
    public static String tabsToSpaces(String text, int width) {
        if (text == null || text.isEmpty() || width <= 0) {
            return text;
        }
        String spaces = " ".repeat(width);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c == '\t' ? spaces : String.valueOf(c));
        }
        return out.toString();
    }

    /**
     * Converts each line's leading spaces into tabs: a full {@code width} group
     * becomes one tab and any remainder stays as spaces; only leading whitespace
     * is touched. A trailing newline is preserved. Pure.
     */
    public static String spacesToTabs(String text, int width) {
        if (text == null || text.isEmpty() || width <= 0) {
            return text;
        }
        List<String> lines = split(text);
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, leadingSpacesToTabs(lines.get(i), width));
        }
        return join(lines, text.endsWith("\n"));
    }

    private static String leadingSpacesToTabs(String line, int width) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        if (n == 0) {
            return line;
        }
        return "\t".repeat(n / width) + " ".repeat(n % width) + line.substring(n);
    }

    /** Rewrites every CRLF or lone CR to LF. Pure. */
    public static String normalizeToLf(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Rewrites every line ending to CRLF (existing lone CRs included). Pure. */
    public static String normalizeToCrlf(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return normalizeToLf(text).replace("\n", "\r\n");
    }

    /** Splits into lines, dropping the trailing empty element a final newline yields. */
    private static List<String> split(String text) {
        String body = text.endsWith("\n")
                ? text.substring(0, text.length() - 1) : text;
        return new ArrayList<>(List.of(body.split("\n", -1)));
    }

    /** Joins lines with newlines, re-appending the trailing newline when asked. */
    private static String join(List<String> lines, boolean trailingNewline) {
        String joined = String.join("\n", lines);
        return trailingNewline ? joined + "\n" : joined;
    }
}
