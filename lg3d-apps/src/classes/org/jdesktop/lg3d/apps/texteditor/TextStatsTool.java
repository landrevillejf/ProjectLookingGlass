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
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Document Stats" extension: a read-only companion that reports
 * line, word and character counts to the editor status line. Unlike the
 * transforming extensions ({@link BuiltinTextTools}, {@link CodeTools},
 * {@link CaseTools}) it needs only {@link TextEditorPermission#READ} and
 * {@link TextEditorPermission#TOOLBAR} - it never writes the document - and it
 * exercises the {@link TextEditorExtension#onEditorStarted} hook and the
 * {@link EditorContext} facade, so it is a second worked example of the SPI for
 * extension authors.
 *
 * <p>It keeps the last {@link EditorContext} (for the status line) and the last
 * {@link DocumentContext} (for the text), reports on demand from its toolbar
 * button, and echoes a short summary whenever a document is saved.</p>
 */
public final class TextStatsTool implements TextEditorExtension {

    private EditorContext editorContext;
    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.text-stats",
                "Document Stats",
                "1.0.0",
                "Report line, word and character counts on the status line",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public void onEditorStarted(EditorContext ctx) {
        this.editorContext = ctx;
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public void onDocumentSaved(DocumentContext doc) {
        this.currentDoc = doc;
        String name = doc.getFileName().isEmpty() ? "Untitled" : doc.getFileName();
        report("Saved " + name + " - " + summary(doc.getFullText()));
    }

    @Override
    public String category() {
        return "Analysis";
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("stats-report", "Report Statistics",
                        "Show line, word and character counts",
                        this::reportCurrent)
        );
    }

    private void reportCurrent() {
        if (currentDoc == null) {
            return;
        }
        report(summary(currentDoc.getFullText()));
    }

    /** Writes {@code message} to the status line when the editor context is live. */
    private void report(String message) {
        if (editorContext != null) {
            editorContext.showMessage(message);
        }
    }

    // ------------------------------------------------------------------
    // Pure counters (headless-testable)
    // ------------------------------------------------------------------

    /** The human-readable counts line: {@code "N lines, M words, K characters"}. Pure. */
    public static String summary(String text) {
        return lineCount(text) + " lines, " + wordCount(text)
                + " words, " + charCount(text) + " characters";
    }

    /** Number of lines; a trailing newline does not add an empty final line. Pure. */
    public static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        if (!text.endsWith("\n")) {
            lines++;
        }
        return lines;
    }

    /** Number of runs of non-whitespace characters. Pure. */
    public static int wordCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                words++;
                inWord = true;
            }
        }
        return words;
    }

    /** Total character count, including whitespace and newlines. Pure. */
    public static int charCount(String text) {
        return (text == null) ? 0 : text.length();
    }
}
