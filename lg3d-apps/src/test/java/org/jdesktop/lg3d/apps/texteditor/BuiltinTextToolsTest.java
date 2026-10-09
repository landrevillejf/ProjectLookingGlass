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
 * Headless tests for the bundled {@link BuiltinTextTools} extension (pure
 * text transforms plus the new SPI contract with manifest and toolbar contributions).
 */
class BuiltinTextToolsTest {

    @Test
    @DisplayName("sortLines sorts case-insensitively and keeps the trailing newline")
    void sortLines() {
        assertEquals("a\nB\nc", BuiltinTextTools.sortLines("c\na\nB", false));
        assertEquals("c\nB\na", BuiltinTextTools.sortLines("c\na\nB", true));
        assertEquals("a\nb\n", BuiltinTextTools.sortLines("b\na\n", false));
        assertEquals("", BuiltinTextTools.sortLines("", false));
        assertEquals(null, BuiltinTextTools.sortLines(null, false));
    }

    @Test
    @DisplayName("stripTrailingWhitespace cleans spaces and tabs per line")
    void stripTrailingWhitespace() {
        assertEquals("a\nb\tc\n",
                BuiltinTextTools.stripTrailingWhitespace("a  \nb\tc\n"));
        assertEquals("x", BuiltinTextTools.stripTrailingWhitespace("x\t "));
        assertEquals("", BuiltinTextTools.stripTrailingWhitespace(""));
        assertEquals(null, BuiltinTextTools.stripTrailingWhitespace(null));
    }

    @Test
    @DisplayName("timestamp matches the documented format")
    void timestamp() {
        assertTrue(BuiltinTextTools.timestamp()
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    @DisplayName("manifest returns correct metadata")
    void manifest() {
        BuiltinTextTools tools = new BuiltinTextTools();
        TextEditorManifest manifest = tools.manifest();
        assertNotNull(manifest);
        assertEquals("lg3d.text-tools", manifest.getId());
        assertEquals("Text Tools", manifest.getName());
        assertEquals("1.0.0", manifest.getVersion());
        assertTrue(manifest.getPermissions().contains(TextEditorPermission.READ));
        assertTrue(manifest.getPermissions().contains(TextEditorPermission.WRITE));
        assertTrue(manifest.getPermissions().contains(TextEditorPermission.TOOLBAR));
    }

    @Test
    @DisplayName("toolbarContributions returns six Text Tools actions")
    void toolbarContributions() {
        BuiltinTextTools tools = new BuiltinTextTools();
        var contributions = tools.toolbarContributions();
        assertEquals(6, contributions.size());
        assertEquals("Sort Lines (A-Z)", contributions.get(0).getLabel());
        assertEquals("Sort Lines (Z-A)", contributions.get(1).getLabel());
        assertEquals("Remove Trailing Whitespace", contributions.get(2).getLabel());
        assertEquals("Insert Timestamp", contributions.get(3).getLabel());
        assertEquals("UPPERCASE Selection", contributions.get(4).getLabel());
        assertEquals("lowercase Selection", contributions.get(5).getLabel());
    }

    @Test
    @DisplayName("toolbar actions transform the document through DocumentContext")
    void actionsWork() {
        BuiltinTextTools tools = new BuiltinTextTools();
        var contributions = tools.toolbarContributions();

        // Test sort A-Z
        String[] textHolder = new String[1];
        textHolder[0] = "b\na";
        Consumer<String> textMutator = s -> textHolder[0] = s;
        DocumentContext doc = new DocumentContext(null, null, textHolder[0], "",
                textMutator, s -> { });
        tools.onDocumentOpened(doc);
        contributions.get(0).getAction().run();
        assertEquals("a\nb", textHolder[0]);

        // Test strip trailing whitespace
        textHolder[0] = "a  \nb";
        doc = new DocumentContext(null, null, textHolder[0], "",
                textMutator, s -> { });
        tools.onDocumentOpened(doc);
        contributions.get(2).getAction().run();
        assertEquals("a\nb", textHolder[0]);

        // Test UPPERCASE selection
        textHolder[0] = "stamp here";
        String[] selectionHolder = new String[1];
        selectionHolder[0] = "here";
        Consumer<String> selectionMutator = s -> selectionHolder[0] = s;
        doc = new DocumentContext(null, null, textHolder[0], selectionHolder[0],
                textMutator, selectionMutator);
        tools.onDocumentOpened(doc);
        contributions.get(4).getAction().run();
        assertEquals("HERE", selectionHolder[0]);
    }
}
