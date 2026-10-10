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
import java.util.Locale;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Comment" extension (Espresso Phase 6): a language-aware
 * line-comment toggle that reads the current file's comment syntax from the
 * editor's own {@link Languages} catalogue and adds, removes or flips the marker
 * on the selected lines (or the whole document when nothing is selected).
 *
 * <p>The Phase-6 plan also floated fold / unfold quick-actions; those are
 * deliberately <em>not</em> shipped here because the editor's text widget is a
 * plain {@code JTextPane} with no folding model, and bolt-on folding would be a
 * cosmetic illusion rather than a real feature. The plan states the bundled set
 * is "proposed, not fixed", so this extension contributes the comment half of
 * that item robustly and drops the un-buildable half.</p>
 *
 * <p><b>Testability.</b> Comment detection and the add / remove / toggle
 * transforms are pure {@code (marker, text) -> text} statics, so the exact
 * indentation-preserving behaviour is unit-tested headless; the extension body
 * only resolves the marker and pipes the current document (or selection)
 * through them.</p>
 */
public final class CommentTools implements TextEditorExtension {

    private EditorContext editor;
    private DocumentContext currentDoc;

    /** Public no-arg constructor: required by the {@link java.util.ServiceLoader} SPI. */
    public CommentTools() {
    }

    // -- SPI -------------------------------------------------------------------

    @Override
    public TextEditorManifest manifest() {
        return new TextEditorManifest(
                "lg3d.comment",
                "Comment Toggle",
                "1.0.0",
                "Language-aware add / remove / toggle of line comments on the "
                        + "selection or whole document",
                "Project Looking Glass",
                EnumSet.of(
                        TextEditorPermission.READ,
                        TextEditorPermission.WRITE,
                        TextEditorPermission.TOOLBAR)
        );
    }

    @Override
    public String category() {
        return "Text";
    }

    @Override
    public void onEditorStarted(EditorContext ctx) {
        this.editor = ctx;
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public void onDocumentChanged(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("toggle-comment", "Toggle Comment",
                        "Add or remove the line comment on the selected lines (Ctrl+Alt+/)",
                        () -> apply(Mode.TOGGLE), "control alt SLASH",
                        EditorGlyphs.toolbarIcon(EditorGlyphs.Glyph.LIGHTBULB)),
                new ToolbarContribution("add-comment", "Comment Lines",
                        "Force-add the line comment to the selected lines",
                        () -> apply(Mode.ADD), null,
                        EditorGlyphs.toolbarIcon(EditorGlyphs.Glyph.CHECK)),
                new ToolbarContribution("remove-comment", "Uncomment Lines",
                        "Force-remove the line comment from the selected lines",
                        () -> apply(Mode.REMOVE), null,
                        EditorGlyphs.toolbarIcon(EditorGlyphs.Glyph.HASH))
        );
    }

    // -- wiring ----------------------------------------------------------------

    private enum Mode { TOGGLE, ADD, REMOVE }

    private void apply(Mode mode) {
        EditorContext ctx = editor;
        DocumentContext doc = currentDoc;
        if (ctx == null || doc == null) {
            return;
        }
        String marker = commentMarker(doc.getFileName());
        if (marker == null) {
            ctx.showMessage("No line-comment syntax for this file type");
            return;
        }
        String selection = doc.getSelectedText();
        String target = (selection != null && !selection.isEmpty())
                ? selection : doc.getFullText();
        String result = switch (mode) {
            case TOGGLE -> toggleComment(marker, target);
            case ADD -> addComment(marker, target);
            case REMOVE -> removeComment(marker, target);
        };
        if (result.equals(target)) {
            ctx.showMessage("No comment change");
            return;
        }
        if (selection != null && !selection.isEmpty()) {
            doc.replaceSelection(result);
        } else {
            doc.setFullText(result);
        }
    }

    // -- pure engine (headless-testable) ---------------------------------------

    /**
     * @param fileName the file name or path (may be null)
     * @return the language's line-comment introducer, or null when the file type
     *         has no line-comment syntax
     */
    public static String commentMarker(String fileName) {
        Language lang = Languages.forFileName(fileName);
        String marker = (lang == null) ? null : lang.getLineComment();
        return (marker == null || marker.isBlank()) ? null : marker;
    }

    /** @return true when every non-blank line already carries {@code marker}. */
    public static boolean isCommented(String marker, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        boolean sawContent = false;
        for (String line : text.split("\n", -1)) {
            String trimmed = stripLeading(line);
            if (trimmed.isEmpty()) {
                continue;
            }
            sawContent = true;
            if (!startsWithComment(marker, trimmed)) {
                return false;
            }
        }
        return sawContent;
    }

    /**
     * Flips the comment state: removes it when every content line is commented,
     * otherwise adds it to every content line.
     */
    public static String toggleComment(String marker, String text) {
        return isCommented(marker, text) ? removeComment(marker, text)
                : addComment(marker, text);
    }

    /** Prepends {@code marker + " "} to each non-blank line, keeping indentation. */
    public static String addComment(String marker, String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + lines.length * (marker.length() + 1));
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            String trimmed = stripLeading(line);
            if (trimmed.isEmpty() || startsWithComment(marker, trimmed)) {
                out.append(line);
            } else {
                out.append(leadingWhitespace(line)).append(marker).append(' ').append(trimmed);
            }
        }
        return out.toString();
    }

    /** Removes a leading {@code marker} (and one optional space) from each line. */
    public static String removeComment(String marker, String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            String indent = leadingWhitespace(line);
            String trimmed = stripLeading(line);
            if (startsWithComment(marker, trimmed)) {
                String rest = trimmed.substring(marker.length());
                if (rest.startsWith(" ")) {
                    rest = rest.substring(1);
                }
                // A fully-uncommented blank line loses its trailing indent too.
                out.append(rest.isEmpty() ? "" : indent + rest);
            } else {
                out.append(line);
            }
        }
        return out.toString();
    }

    private static boolean startsWithComment(String marker, String trimmed) {
        return trimmed.regionMatches(true, 0, marker, 0, marker.length());
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static String stripLeading(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(i);
    }

    // -- test seam -------------------------------------------------------------

    /** @return the marker resolved for {@code fileName}, lower-cased for stability. */
    static String debugMarker(String fileName) {
        String m = commentMarker(fileName);
        return (m == null) ? null : m.toLowerCase(Locale.ROOT);
    }
}
