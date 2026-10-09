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
 * Headless tests for the bundled {@link WebTools} extension: the pure HTML /
 * URL conversions, the SPI contract and the selection-driven actions.
 */
class WebToolsTest {

    @Test
    @DisplayName("escapeHtml turns reserved characters into entities")
    void escapeHtml() {
        assertEquals("&lt;tag&gt;", WebTools.escapeHtml("<tag>"));
        assertEquals("&amp;&lt;", WebTools.escapeHtml("&<"));
        assertEquals("&quot;", WebTools.escapeHtml("\""));
        assertEquals("&#39;", WebTools.escapeHtml("'"));
        assertEquals("plain", WebTools.escapeHtml("plain"));
        assertEquals(null, WebTools.escapeHtml(null));
    }

    @Test
    @DisplayName("unescapeHtml expands named and numeric refs in a single pass")
    void unescapeHtml() {
        assertEquals("<tag>", WebTools.unescapeHtml("&lt;tag&gt;"));
        assertEquals("&", WebTools.unescapeHtml("&amp;"));
        assertEquals("'", WebTools.unescapeHtml("&#39;"));
        assertEquals("<", WebTools.unescapeHtml("&#60;"));
        assertEquals("<", WebTools.unescapeHtml("&#x3C;"));
        // single pass: no double-decoding of "&amp;lt;"
        assertEquals("&lt;", WebTools.unescapeHtml("&amp;lt;"));
        // unknown reference is left verbatim
        assertEquals("&foo;", WebTools.unescapeHtml("&foo;"));
        assertEquals(null, WebTools.unescapeHtml(null));
    }

    @Test
    @DisplayName("urlEncode/urlDecode round-trip percent escapes")
    void urlCodec() {
        assertEquals("a+b%26c", WebTools.urlEncode("a b&c"));
        assertEquals("a b&c", WebTools.urlDecode("a+b%26c"));
        // a lone, malformed escape decodes back to the input rather than throwing
        assertEquals("%", WebTools.urlDecode("%"));
        assertEquals("", WebTools.urlEncode(""));
        assertEquals(null, WebTools.urlDecode(null));
    }

    @Test
    @DisplayName("manifest and category declare the Web group")
    void manifest() {
        WebTools tools = new WebTools();
        TextEditorManifest m = tools.manifest();
        assertNotNull(m);
        assertEquals("lg3d.web-tools", m.getId());
        assertEquals("Web Tools", m.getName());
        assertEquals("Web", tools.category());
        assertTrue(m.getPermissions().contains(TextEditorPermission.WRITE));
        assertTrue(m.getPermissions().contains(TextEditorPermission.TOOLBAR));
    }

    @Test
    @DisplayName("toolbarContributions lists four web conversions")
    void toolbarContributions() {
        var c = new WebTools().toolbarContributions();
        assertEquals(4, c.size());
        assertEquals("Escape HTML Entities", c.get(0).getLabel());
    }

    @Test
    @DisplayName("the escape action converts the selection only")
    void escapeAction() {
        WebTools tools = new WebTools();
        String[] sel = {"<b>"};
        Consumer<String> selectionMutator = s -> sel[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null, "",
                sel[0], s -> { }, selectionMutator));
        tools.toolbarContributions().get(0).getAction().run();
        assertEquals("&lt;b&gt;", sel[0]);
    }

    @Test
    @DisplayName("an empty selection is a no-op")
    void emptySelectionNoOp() {
        WebTools tools = new WebTools();
        String[] sel = {""};
        Consumer<String> selectionMutator = s -> sel[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null, "",
                "", s -> { }, selectionMutator));
        tools.toolbarContributions().get(0).getAction().run();
        assertEquals("", sel[0]);
    }
}
