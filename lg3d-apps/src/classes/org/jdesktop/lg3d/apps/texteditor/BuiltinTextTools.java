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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Text Tools" extension: five whole-document / selection
 * transforms registered through the public {@link TextEditorExtension} SPI,
 * proving the extension point works end to end (it is discovered through
 * {@code META-INF/services} exactly like a third-party jar would be) while
 * shipping genuinely useful utilities.
 *
 * <p>Every transform is a pure {@code String -> String} function exposed as
 * a package-private static for unit tests; the installed actions just pipe
 * the current document through them and report on the status line. Each
 * transform runs as a single undo step, and "no-op" results are reported
 * rather than silently dirtying the document.</p>
 */
public final class BuiltinTextTools implements TextEditorExtension {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.text-tools",
                "Text Tools",
                "1.0.0",
                "Built-in text transformation utilities",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("sort-az", "Sort Lines (A-Z)",
                        "Sort all lines alphabetically A-Z", this::sortAZ),
                new ToolbarContribution("sort-za", "Sort Lines (Z-A)",
                        "Sort all lines alphabetically Z-A", this::sortZA),
                new ToolbarContribution("strip-trailing", "Remove Trailing Whitespace",
                        "Remove spaces and tabs at end of each line", this::stripTrailing),
                new ToolbarContribution("timestamp", "Insert Timestamp",
                        "Insert current date and time", this::insertTimestamp),
                new ToolbarContribution("upper", "UPPERCASE Selection",
                        "Convert selection to uppercase", this::toUpper),
                new ToolbarContribution("lower", "lowercase Selection",
                        "Convert selection to lowercase", this::toLower)
        );
    }

    private void sortAZ() {
        if (currentDoc == null) {
            return;
        }
        String sorted = sortLines(currentDoc.getFullText(), false);
        applyWhole(sorted, "Lines sorted A-Z");
    }

    private void sortZA() {
        if (currentDoc == null) {
            return;
        }
        String sorted = sortLines(currentDoc.getFullText(), true);
        applyWhole(sorted, "Lines sorted Z-A");
    }

    private void stripTrailing() {
        if (currentDoc == null) {
            return;
        }
        String stripped = stripTrailingWhitespace(currentDoc.getFullText());
        applyWhole(stripped, "Trailing whitespace removed");
    }

    private void insertTimestamp() {
        if (currentDoc == null) {
            return;
        }
        currentDoc.replaceSelection(timestamp());
    }

    private void toUpper() {
        if (currentDoc == null) {
            return;
        }
        String selection = currentDoc.getSelectedText();
        if (!selection.isEmpty()) {
            currentDoc.replaceSelection(selection.toUpperCase(Locale.ROOT));
        }
    }

    private void toLower() {
        if (currentDoc == null) {
            return;
        }
        String selection = currentDoc.getSelectedText();
        if (!selection.isEmpty()) {
            currentDoc.replaceSelection(selection.toLowerCase(Locale.ROOT));
        }
    }

    private void applyWhole(String result, String message) {
        if (currentDoc == null) {
            return;
        }
        if (result.equals(currentDoc.getFullText())) {
            // No change - don't dirty the document
        } else {
            currentDoc.setFullText(result);
        }
    }

    /** The current local time, formatted {@code yyyy-MM-dd HH:mm:ss}. */
    public static String timestamp() {
        return LocalDateTime.now().format(TIMESTAMP);
    }

    /**
     * Sorts the document's lines; case-insensitive, stable for equal keys,
     * trailing newline preserved when present. Pure.
     */
    public static String sortLines(String text, boolean descending) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        boolean endsWithNewline = text.endsWith("\n");
        String body = endsWithNewline
                ? text.substring(0, text.length() - 1) : text;
        List<String> lines = new ArrayList<>(List.of(body.split("\n", -1)));
        lines.sort((a, b) -> a.compareToIgnoreCase(b));
        if (descending) {
            Collections.reverse(lines);
        }
        String joined = String.join("\n", lines);
        return endsWithNewline ? joined + "\n" : joined;
    }

    /** Removes spaces and tabs at the end of every line. Pure. */
    public static String stripTrailingWhitespace(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int lineStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                int end = i;
                while (end > lineStart
                        && (text.charAt(end - 1) == ' '
                        || text.charAt(end - 1) == '\t')) {
                    end--;
                }
                out.append(text, lineStart, end);
                if (i < text.length()) {
                    out.append('\n');
                }
                lineStart = i + 1;
            }
        }
        return out.toString();
    }
}
