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
 * Headless tests for the bundled {@link JsonTools} extension: the pure
 * minify / prettify scanners (including string-literal awareness), the SPI
 * contract and the whole-document action driven through a {@link DocumentContext}.
 */
class JsonToolsTest {

    @Test
    @DisplayName("minify strips whitespace outside string literals")
    void minify() {
        assertEquals("{\"a\":1,\"b\":[1,2]}", JsonTools.minify("{ \"a\" : 1,  \"b\" : [1, 2] }"));
        // Whitespace inside a literal must survive.
        assertEquals("{\"msg\":\"hello  world\"}", JsonTools.minify("{ \"msg\" : \"hello  world\" }"));
        // Escaped quote does not end the literal.
        assertEquals("{\"q\":\"a\\\"b c\"}", JsonTools.minify("{ \"q\" : \"a\\\"b c\" }"));
    }

    @Test
    @DisplayName("prettify indents by nesting depth and is idempotent")
    void prettify() {
        assertEquals("{\n    \"a\": 1\n}", JsonTools.prettify("{\"a\":1}"));
        assertEquals("{\n    \"a\": {\n        \"b\": 1\n    }\n}",
                JsonTools.prettify("{ \"a\" : { \"b\" : 1 } }"));
        // Prettify over an already-pretty document changes nothing.
        String pretty = JsonTools.prettify("{\"a\":1}");
        assertEquals(pretty, JsonTools.prettify(pretty));
    }

    @Test
    @DisplayName("null and empty input pass through unchanged")
    void guards() {
        assertEquals(null, JsonTools.minify(null));
        assertEquals("", JsonTools.minify(""));
        assertEquals(null, JsonTools.prettify(null));
        assertEquals("", JsonTools.prettify(""));
    }

    @Test
    @DisplayName("manifest declares the Data category and write/toolbar permissions")
    void manifest() {
        JsonTools tools = new JsonTools();
        TextEditorManifest m = tools.manifest();
        assertNotNull(m);
        assertEquals("lg3d.json-tools", m.getId());
        assertEquals("JSON Tools", m.getName());
        assertEquals("Data", tools.category());
        assertTrue(m.getPermissions().contains(TextEditorPermission.WRITE));
        assertTrue(m.getPermissions().contains(TextEditorPermission.TOOLBAR));
    }

    @Test
    @DisplayName("toolbarContributions list two actions with accelerators")
    void toolbarContributions() {
        var c = new JsonTools().toolbarContributions();
        assertEquals(2, c.size());
        assertEquals("Minify JSON", c.get(0).getLabel());
        assertEquals("control alt X", c.get(0).getAccelerator());
        assertEquals("control alt O", c.get(1).getAccelerator());
    }

    @Test
    @DisplayName("the prettify action rewrites the whole document")
    void prettifyAction() {
        JsonTools tools = new JsonTools();
        String[] doc = {"{\"a\":1}"};
        Consumer<String> textMutator = s -> doc[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null, doc[0],
                "", textMutator, s -> { }));
        tools.toolbarContributions().get(1).getAction().run();
        assertEquals("{\n    \"a\": 1\n}", doc[0]);
    }
}
