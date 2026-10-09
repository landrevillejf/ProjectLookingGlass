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
 * The bundled "Case Tools" extension: selection-scoped letter-case
 * conversions registered through the public {@link TextEditorExtension} SPI,
 * complementing the whole-document {@link BuiltinTextTools} and
 * {@link CodeTools}. Converting an identifier or a heading to
 * {@code camelCase}/{@code snake_case}/{@code kebab-case}/{@code Title Case}/
 * {@code Sentence case} is a daily driver for source and prose editing.
 *
 * <p>Each conversion is a pure {@code String -> String} function exposed as a
 * static for headless unit tests; the installed actions run over the current
 * selection only and write the result back through
 * {@link DocumentContext#replaceSelection(String)}, so an empty selection is a
 * no-op and every change is a single undo step.</p>
 */
public final class CaseTools implements TextEditorExtension {

    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.case-tools",
                "Case Tools",
                "1.0.0",
                "Convert the selection to camelCase, snake_case, kebab-case and more",
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
                new ToolbarContribution("case-title", "Title Case",
                        "Capitalize The First Letter Of Every Word",
                        this::title),
                new ToolbarContribution("case-sentence", "Sentence case",
                        "Capitalize The First Letter Only",
                        this::sentence),
                new ToolbarContribution("case-camel", "camelCase",
                        "Join The Words As camelCase",
                        this::camel),
                new ToolbarContribution("case-snake", "snake_case",
                        "Join The Words As snake_case",
                        this::snake),
                new ToolbarContribution("case-kebab", "kebab-case",
                        "Join The Words As kebab-case",
                        this::kebab)
        );
    }

    private void title() {
        applyToSelection(CaseTools::toTitleCase);
    }

    private void sentence() {
        applyToSelection(CaseTools::toSentenceCase);
    }

    private void camel() {
        applyToSelection(CaseTools::toCamelCase);
    }

    private void snake() {
        applyToSelection(CaseTools::toSnakeCase);
    }

    private void kebab() {
        applyToSelection(CaseTools::toKebabCase);
    }

    /** Runs {@code fn} over the selection, replacing it only when it changes. */
    private void applyToSelection(java.util.function.UnaryOperator<String> fn) {
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

    /** Capitalises the first letter of every word; other letters are lower-cased. Pure. */
    public static String toTitleCase(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean atWordStart = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                out.append(atWordStart ? Character.toUpperCase(c)
                        : Character.toLowerCase(c));
                atWordStart = false;
            } else {
                out.append(c);
                // A word starts after any non-letter except an apostrophe, so
                // contractions keep their lowercase ("don't", "o'clock").
                atWordStart = (c != '\'');
            }
        }
        return out.toString();
    }

    /** Capitalises the first letter of the text, leaving the rest untouched. Pure. */
    public static String toSentenceCase(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text);
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (Character.isLetter(c)) {
                out.setCharAt(i, Character.toUpperCase(c));
                break;
            }
        }
        return out.toString();
    }

    /** Joins the words as {@code camelCase}. Pure. */
    public static String toCamelCase(String text) {
        List<String> words = words(text);
        if (words.isEmpty()) {
            return text == null ? null : "";
        }
        StringBuilder out = new StringBuilder(words.get(0));
        for (int i = 1; i < words.size(); i++) {
            out.append(capitalize(words.get(i)));
        }
        return out.toString();
    }

    /** Joins the lower-cased words as {@code snake_case}. Pure. */
    public static String toSnakeCase(String text) {
        return joinWords(text, "_");
    }

    /** Joins the lower-cased words as {@code kebab-case}. Pure. */
    public static String toKebabCase(String text) {
        return joinWords(text, "-");
    }

    private static String joinWords(String text, String sep) {
        List<String> words = words(text);
        if (words.isEmpty()) {
            return text == null ? null : "";
        }
        return String.join(sep, words);
    }

    /**
     * Splits {@code text} into lower-cased word tokens on non-alphanumeric
     * runs and on camel-hump boundaries ({@code "helloWorld"} and
     * {@code "Hello_World"} both yield {@code [hello, world]}). Pure.
     */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(out, cur);
                continue;
            }
            // Start a new word at a lower/digit -> upper camel hump.
            if (Character.isUpperCase(c) && cur.length() > 0) {
                flush(out, cur);
            }
            cur.append(Character.toLowerCase(c));
        }
        flush(out, cur);
        return out;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    /** Upper-cases the first character of a lowercase word token. */
    private static String capitalize(String word) {
        if (word.isEmpty()) {
            return word;
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
