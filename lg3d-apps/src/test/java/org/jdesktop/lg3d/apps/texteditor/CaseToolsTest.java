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

import java.util.List;
import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the bundled {@link CaseTools} extension: the pure
 * selection-case conversions, the {@link CaseTools#words} tokenizer, and the
 * SPI contract wiring the actions through a {@link DocumentContext} selection.
 */
class CaseToolsTest {

    @Test
    @DisplayName("toTitleCase capitalises word starts and lower-cases the rest")
    void titleCase() {
        assertEquals("Hello World", CaseTools.toTitleCase("hello world"));
        assertEquals("Hello World", CaseTools.toTitleCase("HELLO WORLD"));
        assertEquals("Hello-World", CaseTools.toTitleCase("hello-world"));
        assertEquals("Don't Stop", CaseTools.toTitleCase("don't stop"));
        assertEquals("", CaseTools.toTitleCase(""));
        assertEquals(null, CaseTools.toTitleCase(null));
    }

    @Test
    @DisplayName("toSentenceCase upper-cases only the first letter")
    void sentenceCase() {
        assertEquals("Hello WORLD", CaseTools.toSentenceCase("hello WORLD"));
        assertEquals("  Hi there", CaseTools.toSentenceCase("  hi there"));
        assertEquals(null, CaseTools.toSentenceCase(null));
    }

    @Test
    @DisplayName("toCamelCase joins tokens, first lower-cased")
    void camelCase() {
        assertEquals("helloWorld", CaseTools.toCamelCase("hello world"));
        assertEquals("helloWorld", CaseTools.toCamelCase("Hello_World"));
        assertEquals("helloWorld", CaseTools.toCamelCase("helloWorld"));
        assertEquals("", CaseTools.toCamelCase(""));
        assertEquals(null, CaseTools.toCamelCase(null));
    }

    @Test
    @DisplayName("toSnakeCase and toKebabCase join lower-cased tokens")
    void snakeAndKebab() {
        assertEquals("hello_world", CaseTools.toSnakeCase("helloWorld"));
        assertEquals("hello-world", CaseTools.toKebabCase("Hello World"));
        assertEquals("", CaseTools.toSnakeCase(""));
        assertEquals(null, CaseTools.toKebabCase(null));
    }

    @Test
    @DisplayName("words splits on separators and camel humps")
    void words() {
        assertEquals(List.of("hello", "world"), CaseTools.words("hello world"));
        assertEquals(List.of("hello", "world"), CaseTools.words("helloWorld"));
        assertEquals(List.of(), CaseTools.words(""));
        assertEquals(List.of(), CaseTools.words(null));
    }

    @Test
    @DisplayName("manifest declares read/write/toolbar and stable identity")
    void manifest() {
        TextEditorManifest m = new CaseTools().manifest();
        assertNotNull(m);
        assertEquals("lg3d.case-tools", m.getId());
        assertEquals("Case Tools", m.getName());
        assertTrue(m.getPermissions().contains(TextEditorPermission.WRITE));
        assertTrue(m.getPermissions().contains(TextEditorPermission.TOOLBAR));
    }

    @Test
    @DisplayName("toolbarContributions lists five case conversions")
    void toolbarContributions() {
        var c = new CaseTools().toolbarContributions();
        assertEquals(5, c.size());
        assertEquals("Title Case", c.get(0).getLabel());
        assertEquals("camelCase", c.get(2).getLabel());
    }

    @Test
    @DisplayName("toolbar actions convert the selection through DocumentContext")
    void actionsWork() {
        CaseTools tools = new CaseTools();
        var c = tools.toolbarContributions();
        String[] sel = {"hello world"};
        Consumer<String> selectionMutator = s -> sel[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null, "",
                sel[0], s -> { }, selectionMutator));
        c.get(0).getAction().run();
        assertEquals("Hello World", sel[0]);
    }

    @Test
    @DisplayName("an empty selection is a no-op")
    void emptySelectionNoOp() {
        CaseTools tools = new CaseTools();
        String[] sel = {""};
        Consumer<String> selectionMutator = s -> sel[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null, "",
                "", s -> { }, selectionMutator));
        tools.toolbarContributions().get(0).getAction().run();
        assertEquals("", sel[0]);
    }
}
