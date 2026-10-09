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
 * Headless tests for the bundled {@link Base64Tools} extension: the pure
 * encode / decode transforms, the SPI contract and the selection actions
 * driven through a {@link DocumentContext}.
 */
class Base64ToolsTest {

    @Test
    @DisplayName("encode round-trips through decode")
    void encodeDecode() {
        assertEquals("aGVsbG8=", Base64Tools.encode("hello"));
        assertEquals("hello", Base64Tools.decode("aGVsbG8="));
        assertEquals("héllo", Base64Tools.decode(Base64Tools.encode("héllo")));
    }

    @Test
    @DisplayName("decode leaves malformed input unchanged and null/empty pass through")
    void decodeGuards() {
        assertEquals("!!!not base64!!!", Base64Tools.decode("!!!not base64!!!"));
        assertEquals(null, Base64Tools.encode(null));
        assertEquals("", Base64Tools.encode(""));
        assertEquals(null, Base64Tools.decode(null));
    }

    @Test
    @DisplayName("manifest declares the Encoding category and write/toolbar permissions")
    void manifest() {
        Base64Tools tools = new Base64Tools();
        TextEditorManifest m = tools.manifest();
        assertNotNull(m);
        assertEquals("lg3d.base64-tools", m.getId());
        assertEquals("Base64 Tools", m.getName());
        assertEquals("Encoding", tools.category());
        assertTrue(m.getPermissions().contains(TextEditorPermission.WRITE));
        assertTrue(m.getPermissions().contains(TextEditorPermission.TOOLBAR));
    }

    @Test
    @DisplayName("toolbarContributions list two actions with declared accelerators")
    void toolbarContributions() {
        var c = new Base64Tools().toolbarContributions();
        assertEquals(2, c.size());
        assertEquals("Encode Base64", c.get(0).getLabel());
        assertEquals("control alt Q", c.get(0).getAccelerator());
        assertEquals("control alt G", c.get(1).getAccelerator());
    }

    @Test
    @DisplayName("the encode action replaces the selection with its Base64 form")
    void encodeAction() {
        Base64Tools tools = new Base64Tools();
        String[] sel = {"hi"};
        Consumer<String> selectionMutator = s -> sel[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null, "",
                sel[0], s -> { }, selectionMutator));
        tools.toolbarContributions().get(0).getAction().run();
        assertEquals("aGk=", sel[0]);
    }

    @Test
    @DisplayName("an empty selection is left untouched")
    void emptySelectionNoOp() {
        Base64Tools tools = new Base64Tools();
        boolean[] replaced = {false};
        tools.onDocumentOpened(new DocumentContext(null, null, "",
                "", s -> { }, s -> replaced[0] = true));
        tools.toolbarContributions().get(0).getAction().run();
        assertFalse(replaced[0], "an empty selection must not trigger a replace");
    }
}
