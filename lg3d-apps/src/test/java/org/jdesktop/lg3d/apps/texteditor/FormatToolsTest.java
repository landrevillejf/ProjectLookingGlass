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
 * Headless tests for the bundled {@link FormatTools} extension: the pure
 * tab/space and line-ending transforms, the SPI contract and the whole-document
 * actions driven through a {@link DocumentContext}.
 */
class FormatToolsTest {

    @Test
    @DisplayName("tabsToSpaces expands every tab to the configured width")
    void tabsToSpaces() {
        assertEquals("    a    b", FormatTools.tabsToSpaces("\ta\tb", 4));
        assertEquals("a b", FormatTools.tabsToSpaces("a b", 4));
        assertEquals(null, FormatTools.tabsToSpaces(null, 4));
    }

    @Test
    @DisplayName("spacesToTabs collapses leading spaces, keeping the remainder")
    void spacesToTabs() {
        assertEquals("\ta", FormatTools.spacesToTabs("    a", 4));
        assertEquals("\t  a", FormatTools.spacesToTabs("      a", 4));
        assertEquals("  a", FormatTools.spacesToTabs("  a", 4));
        assertEquals("a\tb", FormatTools.spacesToTabs("a\tb", 4));
        assertEquals("\ta\n\tb\n", FormatTools.spacesToTabs("    a\n    b\n", 4));
        assertEquals(null, FormatTools.spacesToTabs(null, 4));
    }

    @Test
    @DisplayName("normalizeToLf/normalizeToCrlf make line endings consistent")
    void normalizeLineEndings() {
        assertEquals("a\nb\nc", FormatTools.normalizeToLf("a\r\nb\rc"));
        assertEquals("a\r\nb", FormatTools.normalizeToCrlf("a\nb"));
        assertEquals("a\r\nb", FormatTools.normalizeToCrlf("a\r\nb"));
        assertEquals(null, FormatTools.normalizeToLf(null));
    }

    @Test
    @DisplayName("manifest and category declare the shared Code group")
    void manifest() {
        FormatTools tools = new FormatTools();
        TextEditorManifest m = tools.manifest();
        assertNotNull(m);
        assertEquals("lg3d.format-tools", m.getId());
        assertEquals("Formatting Tools", m.getName());
        assertEquals("Code", tools.category());
        assertTrue(m.getPermissions().contains(TextEditorPermission.WRITE));
        assertTrue(m.getPermissions().contains(TextEditorPermission.TOOLBAR));
    }

    @Test
    @DisplayName("toolbarContributions lists four formatting operations")
    void toolbarContributions() {
        var c = new FormatTools().toolbarContributions();
        assertEquals(4, c.size());
        assertEquals("Tabs to Spaces", c.get(0).getLabel());
    }

    @Test
    @DisplayName("the tabs-to-spaces action rewrites the whole document")
    void tabsToSpacesAction() {
        FormatTools tools = new FormatTools();
        String[] holder = {"\tx"};
        Consumer<String> textMutator = s -> holder[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null,
                holder[0], "", textMutator, s -> { }));
        tools.toolbarContributions().get(0).getAction().run();
        assertEquals("    x", holder[0]);
    }

    @Test
    @DisplayName("an already-normalised document is not dirtied")
    void noOpDoesNotDirty() {
        FormatTools tools = new FormatTools();
        String[] holder = {"a\nb\n"};
        Consumer<String> textMutator = s -> holder[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null,
                holder[0], "", textMutator, s -> { }));
        tools.toolbarContributions().get(2).getAction().run(); // Normalize to LF
        assertEquals("a\nb\n", holder[0]);
    }
}
