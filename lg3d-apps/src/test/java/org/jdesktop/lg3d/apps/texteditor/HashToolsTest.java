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
 * Headless tests for the bundled {@link HashTools} extension: the pure hex
 * digest transforms against known vectors, the SPI contract and the
 * selection action driven through a {@link DocumentContext}.
 */
class HashToolsTest {

    @Test
    @DisplayName("digests match the known vectors for \"hello\"")
    void knownVectors() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", HashTools.md5Hex("hello"));
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", HashTools.sha1Hex("hello"));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                HashTools.sha256Hex("hello"));
    }

    @Test
    @DisplayName("null and empty input pass through unchanged")
    void guards() {
        assertEquals(null, HashTools.md5Hex(null));
        assertEquals("", HashTools.sha256Hex(""));
    }

    @Test
    @DisplayName("manifest declares the Encoding category and write/toolbar permissions")
    void manifest() {
        HashTools tools = new HashTools();
        TextEditorManifest m = tools.manifest();
        assertNotNull(m);
        assertEquals("lg3d.hash-tools", m.getId());
        assertEquals("Hash Tools", m.getName());
        assertEquals("Encoding", tools.category());
        assertTrue(m.getPermissions().contains(TextEditorPermission.WRITE));
        assertTrue(m.getPermissions().contains(TextEditorPermission.TOOLBAR));
    }

    @Test
    @DisplayName("toolbarContributions list three actions with accelerators")
    void toolbarContributions() {
        var c = new HashTools().toolbarContributions();
        assertEquals(3, c.size());
        assertEquals("MD5 (Hex)", c.get(0).getLabel());
        assertEquals("control alt 5", c.get(0).getAccelerator());
        assertEquals("control alt 6", c.get(1).getAccelerator());
        assertEquals("control alt 7", c.get(2).getAccelerator());
    }

    @Test
    @DisplayName("the md5 action replaces the selection with its digest")
    void md5Action() {
        HashTools tools = new HashTools();
        String[] sel = {"hello"};
        Consumer<String> selectionMutator = s -> sel[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null, "",
                sel[0], s -> { }, selectionMutator));
        tools.toolbarContributions().get(0).getAction().run();
        assertEquals("5d41402abc4b2a76b9719d911017c592", sel[0]);
    }

    @Test
    @DisplayName("an empty selection is left untouched")
    void emptySelectionNoOp() {
        HashTools tools = new HashTools();
        boolean[] replaced = {false};
        tools.onDocumentOpened(new DocumentContext(null, null, "",
                "", s -> { }, s -> replaced[0] = true));
        tools.toolbarContributions().get(0).getAction().run();
        assertFalse(replaced[0], "an empty selection must not trigger a replace");
    }
}
