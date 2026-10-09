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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the bundled {@link MarkdownTools} extension: the pure
 * emphasis / bullet toggles, the SPI contract and the selection actions driven
 * through a {@link DocumentContext}.
 */
class MarkdownToolsTest {

    @Test
    @DisplayName("toggleBold wraps and unwraps a ** pair")
    void toggleBold() {
        assertEquals("**x**", MarkdownTools.toggleBold("x"));
        assertEquals("x", MarkdownTools.toggleBold("**x**"));
        assertEquals(null, MarkdownTools.toggleBold(null));
        assertEquals("", MarkdownTools.toggleBold(""));
    }

    @Test
    @DisplayName("toggleItalic wraps a single * and leaves bold text alone")
    void toggleItalic() {
        assertEquals("*x*", MarkdownTools.toggleItalic("x"));
        assertEquals("x", MarkdownTools.toggleItalic("*x*"));
        // bold body is not corrupted into a bare "x"
        assertEquals("***x***", MarkdownTools.toggleItalic("**x**"));
    }

    @Test
    @DisplayName("toggleInlineCode wraps and unwraps backticks")
    void toggleInlineCode() {
        assertEquals("`x`", MarkdownTools.toggleInlineCode("x"));
        assertEquals("x", MarkdownTools.toggleInlineCode("`x`"));
    }

    @Test
    @DisplayName("toggleBulletList adds and removes a leading - marker")
    void toggleBulletList() {
        assertEquals("- a\n- b", MarkdownTools.toggleBulletList("a\nb"));
        assertEquals("a\nb", MarkdownTools.toggleBulletList("- a\n- b"));
        assertEquals("- a\n\n- b", MarkdownTools.toggleBulletList("a\n\nb"));
        assertEquals("a\nb\n", MarkdownTools.toggleBulletList("- a\n- b\n"));
        assertEquals(null, MarkdownTools.toggleBulletList(null));
    }

    @Test
    @DisplayName("manifest declares the Markdown category and write/toolbar permissions")
    void manifest() {
        MarkdownTools tools = new MarkdownTools();
        TextEditorManifest m = tools.manifest();
        assertNotNull(m);
        assertEquals("lg3d.markdown-tools", m.getId());
        assertEquals("Markdown Tools", m.getName());
        assertEquals("Markdown", tools.category());
        assertTrue(m.getPermissions().contains(TextEditorPermission.WRITE));
        assertTrue(m.getPermissions().contains(TextEditorPermission.TOOLBAR));
    }

    @Test
    @DisplayName("toolbarContributions list four toggles with accelerators")
    void toolbarContributions() {
        var c = new MarkdownTools().toolbarContributions();
        assertEquals(4, c.size());
        assertEquals("Toggle Bold", c.get(0).getLabel());
        assertEquals("control alt H", c.get(0).getAccelerator());
        assertEquals("control alt Z", c.get(3).getAccelerator());
    }

    @Test
    @DisplayName("the bold action wraps the selection")
    void boldAction() {
        MarkdownTools tools = new MarkdownTools();
        String[] sel = {"word"};
        Consumer<String> selectionMutator = s -> sel[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null, "",
                sel[0], s -> { }, selectionMutator));
        tools.toolbarContributions().get(0).getAction().run();
        assertEquals("**word**", sel[0]);
    }

    @Test
    @DisplayName("an already-wrapped selection toggles back to plain")
    void boldActionTogglesOff() {
        MarkdownTools tools = new MarkdownTools();
        String[] sel = {"**word**"};
        Consumer<String> selectionMutator = s -> sel[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null, "",
                sel[0], s -> { }, selectionMutator));
        tools.toolbarContributions().get(0).getAction().run();
        assertEquals("word", sel[0]);
    }
}
