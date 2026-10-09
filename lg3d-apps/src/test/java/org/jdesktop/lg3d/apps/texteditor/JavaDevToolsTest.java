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
 * Headless tests for the bundled {@link JavaDevTools} extension: the pure
 * import-sorting / literal-escaping / comment-toggle transforms, the SPI
 * contract (manifest, category, toolbar contributions) and the actions driven
 * through a {@link DocumentContext}.
 */
class JavaDevToolsTest {

    @Test
    @DisplayName("sortImports sorts import lines in place, leaving others fixed")
    void sortImports() {
        assertEquals("import java.io.File;\nimport java.util.List;\nclass A",
                JavaDevTools.sortImports(
                        "import java.util.List;\nimport java.io.File;\nclass A"));
        // fewer than two imports is a no-op
        assertEquals("import a.X;\nclass A",
                JavaDevTools.sortImports("import a.X;\nclass A"));
        assertEquals("a\nb\n", JavaDevTools.sortImports("a\nb\n"));
        assertEquals(null, JavaDevTools.sortImports(null));
    }

    @Test
    @DisplayName("escapeStringLiteral escapes quotes, backslashes and control chars")
    void escapeLiteral() {
        assertEquals("a\\\\b", JavaDevTools.escapeStringLiteral("a\\b"));
        assertEquals("a\\\"b", JavaDevTools.escapeStringLiteral("a\"b"));
        assertEquals("a\\nb", JavaDevTools.escapeStringLiteral("a\nb"));
        assertEquals("a\\tb", JavaDevTools.escapeStringLiteral("a\tb"));
        assertEquals(null, JavaDevTools.escapeStringLiteral(null));
    }

    @Test
    @DisplayName("unescapeStringLiteral reverses escape sequences and keeps unknown ones")
    void unescapeLiteral() {
        assertEquals("a\nb", JavaDevTools.unescapeStringLiteral("a\\nb"));
        assertEquals("a\\b", JavaDevTools.unescapeStringLiteral("a\\\\b"));
        assertEquals("a\"b", JavaDevTools.unescapeStringLiteral("a\\\"b"));
        assertEquals("a\\qb", JavaDevTools.unescapeStringLiteral("a\\qb"));
        assertEquals("round trip", JavaDevTools.unescapeStringLiteral(
                JavaDevTools.escapeStringLiteral("round trip")));
    }

    @Test
    @DisplayName("commentOut/uncomment toggle // while preserving indentation")
    void commentToggle() {
        assertEquals("// a\n// b", JavaDevTools.commentOut("a\nb"));
        assertEquals("    // code", JavaDevTools.commentOut("    code"));
        assertEquals("// already", JavaDevTools.commentOut("// already"));
        assertEquals("a", JavaDevTools.uncomment("// a"));
        assertEquals("a", JavaDevTools.uncomment("//a"));
        assertEquals("    code", JavaDevTools.uncomment("    // code"));
        assertEquals("a", JavaDevTools.uncomment("a"));
        assertEquals(null, JavaDevTools.commentOut(null));
    }

    @Test
    @DisplayName("manifest and category declare the Java/Kotlin group")
    void manifest() {
        JavaDevTools tools = new JavaDevTools();
        TextEditorManifest m = tools.manifest();
        assertNotNull(m);
        assertEquals("lg3d.java-tools", m.getId());
        assertEquals("Java/Kotlin Tools", m.getName());
        assertEquals("Java/Kotlin", tools.category());
        assertTrue(m.getPermissions().contains(TextEditorPermission.WRITE));
        assertTrue(m.getPermissions().contains(TextEditorPermission.TOOLBAR));
    }

    @Test
    @DisplayName("toolbarContributions lists five Java/Kotlin actions")
    void toolbarContributions() {
        var c = new JavaDevTools().toolbarContributions();
        assertEquals(5, c.size());
        assertEquals("Sort Imports", c.get(0).getLabel());
    }

    @Test
    @DisplayName("the sort-imports action rewrites the whole document")
    void sortImportsAction() {
        JavaDevTools tools = new JavaDevTools();
        String[] holder = {"import java.util.List;\nimport java.io.File;"};
        Consumer<String> textMutator = s -> holder[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null,
                holder[0], "", textMutator, s -> { }));
        tools.toolbarContributions().get(0).getAction().run();
        assertEquals("import java.io.File;\nimport java.util.List;", holder[0]);
    }

    @Test
    @DisplayName("the escape action converts the selection")
    void escapeAction() {
        JavaDevTools tools = new JavaDevTools();
        String[] sel = {"a\nb"};
        Consumer<String> selectionMutator = s -> sel[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null, "",
                sel[0], s -> { }, selectionMutator));
        tools.toolbarContributions().get(1).getAction().run();
        assertEquals("a\\nb", sel[0]);
    }
}
