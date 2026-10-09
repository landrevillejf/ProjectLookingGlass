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
import java.util.List;
import java.util.Locale;

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

    /** The extension name shown in the Extensions view. */
    public static final String NAME = "Text Tools";

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void install(EditorContext context) {
        context.addAction("Sort Lines (A-Z)", () -> {
            String sorted = sortLines(context.documentText(), false);
            applyWhole(context, sorted, "Lines sorted A-Z");
        });
        context.addAction("Sort Lines (Z-A)", () -> {
            String sorted = sortLines(context.documentText(), true);
            applyWhole(context, sorted, "Lines sorted Z-A");
        });
        context.addAction("Remove Trailing Whitespace", () -> {
            String stripped = stripTrailingWhitespace(context.documentText());
            applyWhole(context, stripped, "Trailing whitespace removed");
        });
        context.addAction("Insert Timestamp",
                () -> context.replaceSelection(timestamp()));
        context.addAction("UPPERCASE Selection", () -> {
            String selection = context.selectedText();
            if (!selection.isEmpty()) {
                context.replaceSelection(
                        selection.toUpperCase(Locale.ROOT));
                context.showMessage("Selection upper-cased");
            }
        });
        context.addAction("lowercase Selection", () -> {
            String selection = context.selectedText();
            if (!selection.isEmpty()) {
                context.replaceSelection(
                        selection.toLowerCase(Locale.ROOT));
                context.showMessage("Selection lower-cased");
            }
        });
    }

    private static void applyWhole(EditorContext context, String result,
            String message) {
        if (result.equals(context.documentText())) {
            context.showMessage(message + " (no change)");
        } else {
            context.setDocumentText(result);
            context.showMessage(message);
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
