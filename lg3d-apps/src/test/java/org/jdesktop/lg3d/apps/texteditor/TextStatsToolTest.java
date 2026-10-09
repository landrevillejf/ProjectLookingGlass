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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the bundled {@link TextStatsTool} extension: the pure
 * counters and the read-only reporting path driven through the
 * {@link EditorContext} status line.
 */
class TextStatsToolTest {

    @Test
    @DisplayName("lineCount counts logical lines, ignoring a trailing newline")
    void lineCount() {
        assertEquals(0, TextStatsTool.lineCount(""));
        assertEquals(1, TextStatsTool.lineCount("a\n"));
        assertEquals(2, TextStatsTool.lineCount("a\nb"));
        assertEquals(2, TextStatsTool.lineCount("a\nb\n"));
    }

    @Test
    @DisplayName("wordCount counts runs of non-whitespace")
    void wordCount() {
        assertEquals(0, TextStatsTool.wordCount(""));
        assertEquals(2, TextStatsTool.wordCount("  a  b "));
        assertEquals(3, TextStatsTool.wordCount("a b\nc"));
    }

    @Test
    @DisplayName("charCount is the raw length and null-safe")
    void charCount() {
        assertEquals(0, TextStatsTool.charCount(null));
        assertEquals(3, TextStatsTool.charCount("abc"));
    }

    @Test
    @DisplayName("summary composes the three counters")
    void summary() {
        assertEquals("2 lines, 3 words, 5 characters",
                TextStatsTool.summary("a b\nc"));
    }

    @Test
    @DisplayName("manifest is read-only: READ + TOOLBAR, never WRITE")
    void manifest() {
        TextEditorManifest m = new TextStatsTool().manifest();
        assertEquals("lg3d.text-stats", m.getId());
        assertEquals("Document Stats", m.getName());
        assertTrue(m.getPermissions().contains(TextEditorPermission.READ));
        assertTrue(m.getPermissions().contains(TextEditorPermission.TOOLBAR));
        assertFalse(m.getPermissions().contains(TextEditorPermission.WRITE));
    }

    @Test
    @DisplayName("toolbarContributions lists the single Report action")
    void toolbarContributions() {
        var c = new TextStatsTool().toolbarContributions();
        assertEquals(1, c.size());
        assertEquals("Report Statistics", c.get(0).getLabel());
    }

    @Test
    @DisplayName("the report action writes the summary to the status line")
    void reportsOnToolbarAction() {
        TextStatsTool tool = new TextStatsTool();
        List<String> shown = new ArrayList<>();
        tool.onEditorStarted(new EditorContext(
                EnumSet.of(TextEditorPermission.READ, TextEditorPermission.TOOLBAR),
                shown::add, () -> { }, () -> { }));
        tool.onDocumentOpened(new DocumentContext(null, null, "a b\nc",
                "", s -> { }, s -> { }));
        tool.toolbarContributions().get(0).getAction().run();
        assertEquals(List.of("2 lines, 3 words, 5 characters"), shown);
    }

    @Test
    @DisplayName("saving echoes a summary naming the file")
    void reportsOnSave() {
        TextStatsTool tool = new TextStatsTool();
        List<String> shown = new ArrayList<>();
        tool.onEditorStarted(new EditorContext(
                EnumSet.of(TextEditorPermission.READ), shown::add, () -> { }, () -> { }));
        tool.onDocumentSaved(new DocumentContext("/x/y.txt", "y.txt",
                "hi there now", "", s -> { }, s -> { }));
        assertEquals(1, shown.size());
        assertTrue(shown.get(0).startsWith("Saved y.txt - "), shown.get(0));
        assertTrue(shown.get(0).contains("12 characters"), shown.get(0));
    }

    @Test
    @DisplayName("an un-started tool reports nothing and never throws")
    void tolerantWithoutContext() {
        TextStatsTool tool = new TextStatsTool();
        tool.onDocumentOpened(new DocumentContext(null, null, "text",
                "", s -> { }, s -> { }));
        // editorContext was never supplied, so the action must be a safe no-op
        tool.toolbarContributions().get(0).getAction().run();
    }
}
