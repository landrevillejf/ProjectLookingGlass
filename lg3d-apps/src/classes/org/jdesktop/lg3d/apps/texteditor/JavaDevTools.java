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
import java.util.function.UnaryOperator;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Java/Kotlin Tools" extension: source-editing helpers registered
 * through the public {@link TextEditorExtension} SPI, exactly like
 * {@link BuiltinTextTools} and {@link CodeTools}, and grouped under the
 * {@code Java/Kotlin} category in the extension manager. It tidies import
 * blocks, escapes/unescapes string literals, and toggles {@code //} line
 * comments on a selection, with no dependency on Swing or the panel internals.
 *
 * <p>Every helper is a pure {@code String -> String} function exposed as a
 * static for headless unit tests. Whole-document operations run through
 * {@code setFullText}; selection operations run through {@code replaceSelection}.
 * A no-op result (nothing changed) never dirties the document, matching the
 * built-in tools.</p>
 */
public final class JavaDevTools implements TextEditorExtension {

    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.java-tools",
                "Java/Kotlin Tools",
                "1.0.0",
                "Sort imports, escape string literals, toggle // comments",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public String category() {
        return "Java/Kotlin";
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("java-sort-imports", "Sort Imports",
                        "Sort the import statements in place, keeping other lines fixed (Ctrl+Alt+C)",
                        this::sortImports, "control alt C"),
                new ToolbarContribution("java-escape", "Escape String Literal",
                        "Escape the selection for use inside a Java/Kotlin string literal",
                        () -> applyToSelection(JavaDevTools::escapeStringLiteral)),
                new ToolbarContribution("java-unescape", "Unescape String Literal",
                        "Expand Java/Kotlin escape sequences in the selection",
                        () -> applyToSelection(JavaDevTools::unescapeStringLiteral)),
                new ToolbarContribution("java-comment", "Comment Out Lines",
                        "Prefix every selected line with // (indentation preserved) (Ctrl+Alt+J)",
                        () -> applyToSelection(JavaDevTools::commentOut), "control alt J"),
                new ToolbarContribution("java-uncomment", "Uncomment Lines",
                        "Remove one leading // from every selected line (Ctrl+Alt+K)",
                        () -> applyToSelection(JavaDevTools::uncomment), "control alt K")
        );
    }

    private void sortImports() {
        if (currentDoc == null) {
            return;
        }
        String text = currentDoc.getFullText();
        String sorted = sortImports(text);
        if (!sorted.equals(text)) {
            currentDoc.setFullText(sorted);
        }
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
    // Pure transforms (headless-testable)
    // ------------------------------------------------------------------

    /**
     * Sorts every {@code import} line into natural order, leaving all other
     * lines (and the import lines' original slots) untouched. Returns the input
     * unchanged when fewer than two import lines exist. A trailing newline is
     * preserved. Pure.
     */
    public static String sortImports(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> lines = split(text);
        List<Integer> slots = new ArrayList<>();
        List<String> imports = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("import ") || trimmed.startsWith("import\t")) {
                slots.add(i);
                imports.add(lines.get(i));
            }
        }
        if (imports.size() < 2) {
            return text;
        }
        imports.sort(String::compareTo);
        for (int k = 0; k < slots.size(); k++) {
            lines.set(slots.get(k), imports.get(k));
        }
        return join(lines, text.endsWith("\n"));
    }

    /**
     * Escapes a raw string so it is safe inside a double-quoted Java/Kotlin
     * literal: {@code \} and {@code "} are backslash-escaped, and control
     * characters become {@code \n}, {@code \r}, {@code \t}. Pure.
     */
    public static String escapeStringLiteral(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Reverses {@link #escapeStringLiteral}: common escape sequences expand to
     * their characters; an unrecognised backslash sequence is left verbatim. Pure.
     */
    public static String unescapeStringLiteral(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char n = text.charAt(++i);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case '"' -> out.append('"');
                    case '\'' -> out.append('\'');
                    case '\\' -> out.append('\\');
                    default -> out.append('\\').append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Prefixes every non-blank, not-already-commented line with {@code "// "},
     * after any leading indentation. Pure.
     */
    public static String commentOut(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> lines = split(text);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.trim().startsWith("//")) {
                continue;
            }
            int indent = leadingWhitespace(line);
            lines.set(i, line.substring(0, indent) + "// " + line.substring(indent));
        }
        return join(lines, text.endsWith("\n"));
    }

    /**
     * Removes one leading {@code "//"} (and an optional following space) from
     * every commented line, after any leading indentation. Pure.
     */
    public static String uncomment(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> lines = split(text);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int indent = leadingWhitespace(line);
            String rest = line.substring(indent);
            if (rest.startsWith("// ")) {
                lines.set(i, line.substring(0, indent) + rest.substring(3));
            } else if (rest.startsWith("//")) {
                lines.set(i, line.substring(0, indent) + rest.substring(2));
            }
        }
        return join(lines, text.endsWith("\n"));
    }

    private static int leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length()
                && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
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
