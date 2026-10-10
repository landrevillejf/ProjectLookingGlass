/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link TodoScanTools}: the pure marker scanner (word-boundary,
 * case-insensitive, one hit per line) and the wrapping "next" rotation, plus the
 * SPI wiring that reports the scan as info diagnostics (DIAGNOSE) and jumps to the
 * next marker (READ). No panel or document mutation is involved.
 */
class TodoScanToolsTest {

    private static final String DOC = String.join("\n",
            "plain line",                 // 1
            "  // TODO: write tests",     // 2
            "/* FIXME corner */",         // 3
            "# NOTE keep",                // 4
            "nothing here",               // 5
            "// HACK: workaround");       // 6

    @Test
    @DisplayName("scanMarkers finds every tag with its line and remainder")
    void scan() {
        List<TodoScanTools.Item> items = TodoScanTools.scanMarkers(DOC);
        assertEquals(4, items.size());
        assertEquals(2, items.get(0).line());
        assertEquals("TODO", items.get(0).tag());
        assertEquals("write tests", items.get(0).text());
        assertEquals("HACK", items.get(3).tag());
        assertEquals(6, items.get(3).line());
    }

    @Test
    @DisplayName("tags match case-insensitively but only at a word boundary")
    void scanBoundary() {
        assertEquals(1, TodoScanTools.scanMarkers("// todo keep").size());
        assertTrue(TodoScanTools.scanMarkers("MYTODO not a tag").isEmpty(),
                "a tag glued to an identifier is not a marker");
        assertEquals(0, TodoScanTools.scanMarkers("").size());
        assertTrue(TodoScanTools.scanMarkers(null).isEmpty());
    }

    @Test
    @DisplayName("nextLine advances past the caret and wraps to the first")
    void nextRotation() {
        List<TodoScanTools.Item> items = TodoScanTools.scanMarkers(DOC);
        assertEquals(2, TodoScanTools.nextLine(items, 1));
        assertEquals(6, TodoScanTools.nextLine(items, 4));
        assertEquals(2, TodoScanTools.nextLine(items, 6), "wraps to the first");
        assertEquals(0, TodoScanTools.nextLine(List.of(), 3), "empty has nowhere to go");
    }

    @Test
    @DisplayName("the manifest is Project category with READ + DIAGNOSE + TOOLBAR")
    void manifest() {
        TodoScanTools ext = new TodoScanTools();
        assertEquals("lg3d.todo", ext.manifest().getId());
        assertEquals("Project", ext.category());
        assertTrue(ext.manifest().getPermissions().containsAll(
                EnumSet.of(TextEditorPermission.READ, TextEditorPermission.DIAGNOSE,
                        TextEditorPermission.TOOLBAR)));
        assertEquals(2, ext.toolbarContributions().size());
        assertNotNull(ext.toolbarContributions().get(0).getIcon());
    }

    // -- SPI end-to-end --------------------------------------------------------

    private static final class Harness {
        final List<String> messages = new ArrayList<>();
        final List<List<Diagnostic>> diagnostics = new ArrayList<>();
        final List<Integer> navigations = new ArrayList<>();

        EditorContext ctx() {
            EditorSinks sinks = EditorSinks.builder()
                    .showMessage(messages::add)
                    .reportDiagnostics((p, ds) -> diagnostics.add(ds))
                    .navigate((p, line) -> navigations.add(line))
                    .build();
            return new EditorContext(EnumSet.of(TextEditorPermission.READ,
                    TextEditorPermission.DIAGNOSE, TextEditorPermission.TOOLBAR), sinks);
        }
    }

    private static DocumentContext docAtLine(String text, int line) {
        return new DocumentContext("/a.txt", "a.txt", text, "",
                0, line, 1, 0, 0, s -> { }, s -> { });
    }

    @Test
    @DisplayName("the scan action reports info diagnostics for each marker")
    void scanAction() {
        Harness h = new Harness();
        TodoScanTools ext = new TodoScanTools();
        ext.onEditorStarted(h.ctx());
        ext.onDocumentOpened(docAtLine(DOC, 1));
        ext.toolbarContributions().get(0).getAction().run();

        assertEquals(1, h.diagnostics.size());
        assertEquals(4, h.diagnostics.get(0).size());
        assertEquals(Diagnostic.Kind.INFO, h.diagnostics.get(0).get(0).kind());
        assertTrue(h.messages.stream().anyMatch(m -> m.contains("marker(s) found")),
                h.messages.toString());
    }

    @Test
    @DisplayName("the next action jumps to the marker after the caret")
    void nextAction() {
        Harness h = new Harness();
        TodoScanTools ext = new TodoScanTools();
        ext.onEditorStarted(h.ctx());
        ext.onDocumentOpened(docAtLine(DOC, 3));
        ext.toolbarContributions().get(1).getAction().run();
        assertEquals(List.of(4), h.navigations);
    }
}
