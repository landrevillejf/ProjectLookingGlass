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
 * The bundled "Markdown Tools" extension: toggles inline Markdown emphasis on
 * the selection (bold, italic, inline code) and bullet-list markers across the
 * selected lines, registered through the public {@link TextEditorExtension}
 * SPI under the {@code Markdown} category. Each helper is a pure
 * {@code String -> String} toggle exposed as a static for headless unit tests;
 * applying a toggle to already-marked text removes the markers, so the actions
 * are idempotent two-way switches. Nothing dirtyies the document on a no-op and
 * there is no dependency on Swing or the panel internals.
 */
public final class MarkdownTools implements TextEditorExtension {

    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.markdown-tools",
                "Markdown Tools",
                "1.0.0",
                "Toggle bold, italic, inline code and bullet-list markers",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public String category() {
        return "Markdown";
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("md-bold", "Toggle Bold",
                        "Wrap/unwrap the selection in ** (Ctrl+Alt+H)",
                        () -> applyToSelection(MarkdownTools::toggleBold), "control alt H"),
                new ToolbarContribution("md-italic", "Toggle Italic",
                        "Wrap/unwrap the selection in * (Ctrl+Alt+F)",
                        () -> applyToSelection(MarkdownTools::toggleItalic), "control alt F"),
                new ToolbarContribution("md-code", "Toggle Inline Code",
                        "Wrap/unwrap the selection in backticks (Ctrl+Alt+Y)",
                        () -> applyToSelection(MarkdownTools::toggleInlineCode), "control alt Y"),
                new ToolbarContribution("md-bullet", "Toggle Bullet List",
                        "Add or remove \"- \" markers on the selected lines (Ctrl+Alt+Z)",
                        () -> applyToSelection(MarkdownTools::toggleBulletList), "control alt Z")
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
    // Pure transforms (headless-testable)
    // ------------------------------------------------------------------

    /** Wraps/unwraps {@code text} in {@code **}. Null/empty input is unchanged. Pure. */
    public static String toggleBold(String text) {
        return toggle(text, "**");
    }

    /** Wraps/unwraps {@code text} in a single {@code *}. Null/empty input is unchanged. Pure. */
    public static String toggleItalic(String text) {
        return toggle(text, "*");
    }

    /** Wraps/unwraps {@code text} in backticks. Null/empty input is unchanged. Pure. */
    public static String toggleInlineCode(String text) {
        return toggle(text, "`");
    }

    /**
     * Generic marker toggle: when {@code text} is already fully wrapped in
     * {@code marker} (and long enough to hold a non-empty body), the markers are
     * stripped; otherwise they are prepended and appended. A bold body wrapped
     * with a single-char italic marker is left alone to avoid corruption. Pure.
     */
    private static String toggle(String text, String marker) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int m = marker.length();
        boolean wrapped = text.length() >= 2 * m
                && text.startsWith(marker) && text.endsWith(marker);
        if (wrapped) {
            // Guard against stripping a marker that only wraps a same-marker pair
            // (e.g. italic "*" applied to "**x**"), which would corrupt bold text.
            String inner = text.substring(m, text.length() - m);
            if (m == 1 && inner.startsWith("*")) {
                return marker + text + marker;
            }
            return inner;
        }
        return marker + text + marker;
    }

    /**
     * Toggles a {@code "- "} bullet marker on every non-blank line of
     * {@code text}: when all non-blank lines already start with a bullet, one
     * bullet is removed from each; otherwise a bullet is added to each. Blank
     * lines are left untouched and a trailing newline is preserved. Pure.
     */
    public static String toggleBulletList(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        boolean trailingNewline = text.endsWith("\n");
        String body = trailingNewline ? text.substring(0, text.length() - 1) : text;
        String[] lines = body.split("\n", -1);

        boolean anyContent = false;
        boolean allBulleted = true;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            anyContent = true;
            if (!isBulleted(line)) {
                allBulleted = false;
                break;
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            if (line.isBlank()) {
                out.append(line);
            } else if (anyContent && allBulleted) {
                out.append(stripBullet(line));
            } else {
                out.append("- ").append(line);
            }
        }
        return trailingNewline ? out.append('\n').toString() : out.toString();
    }

    /** @return true when {@code line} begins with a {@code "- "} or a lone {@code "-"} bullet. */
    private static boolean isBulleted(String line) {
        return line.startsWith("- ") || line.equals("-");
    }

    /** Removes one leading bullet marker from {@code line}. */
    private static String stripBullet(String line) {
        if (line.startsWith("- ")) {
            return line.substring(2);
        }
        return line.equals("-") ? "" : line;
    }
}
