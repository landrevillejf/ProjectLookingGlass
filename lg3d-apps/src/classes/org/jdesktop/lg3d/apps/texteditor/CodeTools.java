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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Code Tools" extension: whole-document line operations
 * registered through the public {@link TextEditorExtension} SPI, the same way
 * {@link BuiltinTextTools} is. It enriches the editor for source/code files -
 * re-indenting, de-duplicating, reversing and numbering blocks of lines - with
 * no dependency on Swing or the panel internals.
 *
 * <p>Every operation is a pure {@code String -> String} function exposed as a
 * static for headless unit tests; the installed toolbar actions simply pipe the
 * current document through the matching function and write the result back, so
 * each runs as a single undo step. A "no-op" result (the text already matches)
 * is reported rather than silently dirtying the document, exactly like the
 * built-in Text Tools.</p>
 */
public final class CodeTools implements TextEditorExtension {

    /** The indent unit inserted by "Indent Lines" and removed by "Outdent Lines". */
    static final String INDENT_UNIT = "    ";

    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.code-tools",
                "Code Tools",
                "1.0.0",
                "Whole-document line operations: indent, dedup, reverse, number",
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
                new ToolbarContribution("code-indent", "Indent Lines",
                        "Add one level of indentation to every non-blank line",
                        this::indent),
                new ToolbarContribution("code-outdent", "Outdent Lines",
                        "Remove one level of indentation from every line",
                        this::outdent),
                new ToolbarContribution("code-drop-blank", "Remove Blank Lines",
                        "Delete every empty or whitespace-only line",
                        this::removeBlankLines),
                new ToolbarContribution("code-unique", "Unique Lines",
                        "Delete duplicate lines, keeping the first occurrence",
                        this::uniqueLines),
                new ToolbarContribution("code-reverse", "Reverse Lines",
                        "Reverse the order of the lines",
                        this::reverseLines),
                new ToolbarContribution("code-number", "Number Lines",
                        "Prefix each line with its 1-based line number",
                        this::numberLines)
        );
    }

    private void indent() {
        applyWhole(indentLines(currentText(), INDENT_UNIT), "Lines indented");
    }

    private void outdent() {
        applyWhole(outdentLines(currentText(), INDENT_UNIT), "Lines outdented");
    }

    private void removeBlankLines() {
        applyWhole(dropBlankLines(currentText()), "Blank lines removed");
    }

    private void uniqueLines() {
        applyWhole(dedupLines(currentText()), "Duplicate lines removed");
    }

    private void reverseLines() {
        applyWhole(reverseLineOrder(currentText()), "Lines reversed");
    }

    private void numberLines() {
        applyWhole(numberLines(currentText()), "Lines numbered");
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

    /**
     * Prefixes every non-blank line with {@code unit}; blank lines are left
     * untouched. A trailing newline is preserved. Pure.
     */
    public static String indentLines(String text, String unit) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> lines = split(text);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank()) {
                lines.set(i, unit + line);
            }
        }
        return join(lines, text.endsWith("\n"));
    }

    /**
     * Removes one indentation level from the start of every line: a leading tab
     * if present, otherwise up to {@code unit.length()} leading spaces. A
     * trailing newline is preserved. Pure.
     */
    public static String outdentLines(String text, String unit) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int spaces = unit.length();
        List<String> lines = split(text);
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, outdentOne(lines.get(i), spaces));
        }
        return join(lines, text.endsWith("\n"));
    }

    /** Strips a single leading tab, or up to {@code spaces} leading spaces. */
    private static String outdentOne(String line, int spaces) {
        if (line.startsWith("\t")) {
            return line.substring(1);
        }
        int cut = 0;
        while (cut < line.length() && cut < spaces && line.charAt(cut) == ' ') {
            cut++;
        }
        return line.substring(cut);
    }

    /**
     * Deletes every empty or whitespace-only line, keeping the rest in order.
     * A trailing newline is preserved when the input ended with one and any
     * content remains. Pure.
     */
    public static String dropBlankLines(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> kept = new ArrayList<>();
        for (String line : split(text)) {
            if (!line.isBlank()) {
                kept.add(line);
            }
        }
        return join(kept, text.endsWith("\n") && !kept.isEmpty());
    }

    /**
     * Removes duplicate lines, keeping the first occurrence and the original
     * order (exact, case-sensitive match). A trailing newline is preserved. Pure.
     */
    public static String dedupLines(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String line : split(text)) {
            if (seen.add(line)) {
                out.add(line);
            }
        }
        return join(out, text.endsWith("\n"));
    }

    /** Reverses the order of the lines. A trailing newline is preserved. Pure. */
    public static String reverseLineOrder(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> lines = split(text);
        java.util.Collections.reverse(lines);
        return join(lines, text.endsWith("\n"));
    }

    /**
     * Prefixes each line with its 1-based line number and a space
     * ({@code "1: "}, {@code "2: "} ...). A trailing newline is preserved. Pure.
     */
    public static String numberLines(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> lines = split(text);
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            out.add((i + 1) + ": " + lines.get(i));
        }
        return join(out, text.endsWith("\n"));
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
