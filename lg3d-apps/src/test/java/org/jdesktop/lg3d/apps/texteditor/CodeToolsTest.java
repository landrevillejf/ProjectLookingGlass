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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the bundled {@link CodeTools} extension: the pure
 * whole-document line transforms and the SPI contract (manifest, toolbar
 * contributions, and actions driving a {@link DocumentContext}).
 */
class CodeToolsTest {

    @Test
    @DisplayName("indentLines indents non-blank lines and preserves the trailing newline")
    void indentLines() {
        assertEquals("    a\n    b", CodeTools.indentLines("a\nb", "    "));
        assertEquals("    a\n\n    b", CodeTools.indentLines("a\n\nb", "    "));
        assertEquals("    a\n", CodeTools.indentLines("a\n", "    "));
        assertEquals("", CodeTools.indentLines("", "    "));
        assertEquals(null, CodeTools.indentLines(null, "    "));
    }

    @Test
    @DisplayName("outdentLines removes one tab or up to the unit width in spaces")
    void outdentLines() {
        assertEquals("a\nb", CodeTools.outdentLines("    a\n\tb", "    "));
        assertEquals("a", CodeTools.outdentLines("  a", "    "));
        assertEquals("aaaaaaaaa", CodeTools.outdentLines("aaaaaaaaa", "    "));
        assertEquals(null, CodeTools.outdentLines(null, "    "));
    }

    @Test
    @DisplayName("dropBlankLines deletes empty and whitespace-only lines")
    void dropBlankLines() {
        assertEquals("a\nb\n", CodeTools.dropBlankLines("a\n \nb\n"));
        assertEquals("a\nb", CodeTools.dropBlankLines("a\nb"));
        assertEquals(null, CodeTools.dropBlankLines(null));
    }

    @Test
    @DisplayName("dedupLines keeps the first occurrence in order")
    void dedupLines() {
        assertEquals("a\nb", CodeTools.dedupLines("a\nb\na"));
        assertEquals("a\nb\n", CodeTools.dedupLines("a\nb\na\n"));
        assertEquals(null, CodeTools.dedupLines(null));
    }

    @Test
    @DisplayName("reverseLineOrder reverses the lines")
    void reverseLineOrder() {
        assertEquals("c\nb\na", CodeTools.reverseLineOrder("a\nb\nc"));
        assertEquals(null, CodeTools.reverseLineOrder(null));
    }

    @Test
    @DisplayName("numberLines prefixes 1-based numbers")
    void numberLines() {
        assertEquals("1: a\n2: b", CodeTools.numberLines("a\nb"));
        assertEquals("1: x\n", CodeTools.numberLines("x\n"));
        assertEquals(null, CodeTools.numberLines(null));
    }

    @Test
    @DisplayName("manifest declares read/write/toolbar and stable identity")
    void manifest() {
        CodeTools tools = new CodeTools();
        TextEditorManifest m = tools.manifest();
        assertNotNull(m);
        assertEquals("lg3d.code-tools", m.getId());
        assertEquals("Code Tools", m.getName());
        assertTrue(m.getPermissions().contains(TextEditorPermission.WRITE));
        assertTrue(m.getPermissions().contains(TextEditorPermission.TOOLBAR));
        assertFalse(m.getPermissions().contains(TextEditorPermission.FILE_IO));
    }

    @Test
    @DisplayName("toolbarContributions lists six line operations")
    void toolbarContributions() {
        var c = new CodeTools().toolbarContributions();
        assertEquals(6, c.size());
        assertEquals("Indent Lines", c.get(0).getLabel());
        assertEquals("Number Lines", c.get(5).getLabel());
    }

    @Test
    @DisplayName("toolbar actions pipe the whole document through the transforms")
    void actionsWork() {
        CodeTools tools = new CodeTools();
        var c = tools.toolbarContributions();

        String[] holder = {"a\nb"};
        Consumer<String> textMutator = s -> holder[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null,
                holder[0], "", textMutator, s -> { }));
        c.get(0).getAction().run();
        assertEquals("    a\n    b", holder[0]);

        holder[0] = "x\ny";
        tools.onDocumentOpened(new DocumentContext(null, null,
                holder[0], "", textMutator, s -> { }));
        c.get(5).getAction().run();
        assertEquals("1: x\n2: y", holder[0]);
    }

    @Test
    @DisplayName("a no-op action leaves the document untouched")
    void noOpDoesNotDirty() {
        CodeTools tools = new CodeTools();
        String[] holder = {"c\nb\na"};
        Consumer<String> textMutator = s -> holder[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null,
                holder[0], "", textMutator, s -> { }));
        // "Remove Blank Lines" on content with no blank lines produces an
        // identical string, so applyWhole must not fire the text mutator.
        var c = tools.toolbarContributions();
        c.get(2).getAction().run();
        assertEquals("c\nb\na", holder[0]);
    }
}
