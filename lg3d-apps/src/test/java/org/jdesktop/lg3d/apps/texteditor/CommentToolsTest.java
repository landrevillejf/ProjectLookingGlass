/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link CommentTools}: the language-aware marker lookup and
 * the indentation-preserving add / remove / toggle transforms, plus the SPI
 * wiring that comments the whole document (WRITE via {@code setFullText}) or just
 * the selection (via {@code replaceSelection}).
 */
class CommentToolsTest {

    @Test
    @DisplayName("the marker is read from the Languages catalogue by file name")
    void markerLookup() {
        assertEquals("//", CommentTools.commentMarker("Foo.java"));
        assertEquals("#", CommentTools.commentMarker("script.py"));
        assertEquals("//", CommentTools.commentMarker("/a/b/Main.cpp"));
        assertNull(CommentTools.commentMarker("page.html"), "markup has no line comment");
        assertNull(CommentTools.commentMarker("notes.txt"));
        assertNull(CommentTools.commentMarker(null));
    }

    @Test
    @DisplayName("addComment prepends the marker keeping indentation, skipping blanks")
    void add() {
        assertEquals("// a\n// b", CommentTools.addComment("//", "a\nb"));
        assertEquals("    // indented", CommentTools.addComment("//", "    indented"));
        assertEquals("// a\n\n// b", CommentTools.addComment("//", "a\n\nb"),
                "blank lines stay blank");
        assertEquals("// a", CommentTools.addComment("//", "// a"),
                "already-commented lines are left alone");
    }

    @Test
    @DisplayName("removeComment strips the marker and one optional space")
    void remove() {
        assertEquals("a\nb", CommentTools.removeComment("//", "// a\n// b"));
        assertEquals("indented", CommentTools.removeComment("//", "// indented"),
                "the marker and its single space are removed");
        assertEquals("    x", CommentTools.removeComment("//", "    // x"),
                "leading indent is preserved around the un-commented content");
        assertEquals("plain\ngone", CommentTools.removeComment("//", "plain\n// gone"),
                "an uncommented line stays put; a commented one is stripped");
        assertEquals("", CommentTools.removeComment("//", "//"), "an empty comment uncomments to empty");
    }

    @Test
    @DisplayName("isCommented and toggleComment flip on the whole block")
    void toggle() {
        assertTrue(CommentTools.isCommented("//", "// a\n\n  // b"));
        assertFalse(CommentTools.isCommented("//", "// a\nb"));
        assertFalse(CommentTools.isCommented("//", ""));
        // commented -> removed
        assertEquals("a\nb", CommentTools.toggleComment("//", "// a\n// b"));
        // not fully commented -> added
        assertEquals("// a\n// b", CommentTools.toggleComment("//", "a\n// b"));
    }

    @Test
    @DisplayName("the manifest is Text category with READ + WRITE + TOOLBAR")
    void manifest() {
        CommentTools ext = new CommentTools();
        assertEquals("lg3d.comment", ext.manifest().getId());
        assertEquals("Text", ext.category());
        assertTrue(ext.manifest().getPermissions().containsAll(
                EnumSet.of(TextEditorPermission.READ, TextEditorPermission.WRITE,
                        TextEditorPermission.TOOLBAR)));
        assertEquals(3, ext.toolbarContributions().size());
    }

    // -- SPI wiring -------------------------------------------------------------

    @Test
    @DisplayName("with no selection the toggle acts on the whole document")
    void toggleWholeDocument() {
        CommentTools ext = new CommentTools();
        ext.onEditorStarted(new EditorContext(
                EnumSet.of(TextEditorPermission.READ, TextEditorPermission.WRITE,
                        TextEditorPermission.TOOLBAR), EditorSinks.builder().build()));
        List<String> fullText = new ArrayList<>();
        DocumentContext doc = new DocumentContext("/p/Foo.java", "Foo.java",
                "alpha\nbeta", "", 0, 1, 1, 0, 0, fullText::add, s -> { });
        ext.onDocumentOpened(doc);
        ext.toolbarContributions().get(0).getAction().run(); // toggle-comment
        assertEquals(List.of("// alpha\n// beta"), fullText);
    }

    @Test
    @DisplayName("with a selection only the selected block is rewritten")
    void toggleSelection() {
        CommentTools ext = new CommentTools();
        ext.onEditorStarted(new EditorContext(
                EnumSet.of(TextEditorPermission.READ, TextEditorPermission.WRITE,
                        TextEditorPermission.TOOLBAR), EditorSinks.builder().build()));
        List<String> replaced = new ArrayList<>();
        DocumentContext doc = new DocumentContext("/p/Foo.java", "Foo.java",
                "ignored", "one\ntwo", 0, 1, 1, 0, 7, s -> { }, replaced::add);
        ext.onDocumentOpened(doc);
        ext.toolbarContributions().get(1).getAction().run(); // add-comment
        assertEquals(List.of("// one\n// two"), replaced);
    }

    @Test
    @DisplayName("an un-commentable file type reports rather than corrupting the text")
    void noMarkerNoOp() {
        CommentTools ext = new CommentTools();
        List<String> messages = new ArrayList<>();
        ext.onEditorStarted(new EditorContext(
                EnumSet.of(TextEditorPermission.READ, TextEditorPermission.WRITE,
                        TextEditorPermission.TOOLBAR),
                EditorSinks.builder().showMessage(messages::add).build()));
        List<String> fullText = new ArrayList<>();
        DocumentContext doc = new DocumentContext("/p/a.html", "a.html",
                "<b>x</b>", "", 0, 1, 1, 0, 0, fullText::add, s -> { });
        ext.onDocumentOpened(doc);
        ext.toolbarContributions().get(0).getAction().run();
        assertTrue(fullText.isEmpty());
        assertTrue(messages.stream().anyMatch(m -> m.contains("No line-comment syntax")),
                messages.toString());
    }
}
