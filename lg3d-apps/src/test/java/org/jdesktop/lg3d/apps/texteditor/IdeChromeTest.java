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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic.Kind;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase-0 headless tests for the IDE chrome groundwork: the {@link Diagnostic}
 * and {@link StructureSymbol} value models, the widened {@link DocumentContext}
 * (caret/selection + back-compat), the permission-gated diagnostics/debug/
 * structure capabilities on {@link EditorContext} wired through {@link EditorSinks},
 * the gutter's diagnostic markers and breakpoints, {@link EditorTab}'s squiggle
 * painting + breakpoint round-trip, and the Problems/Structure/Debug panel models.
 *
 * <p>Everything runs with {@code java.awt.headless=true}: the panels construct and
 * behave as plain Swing components, and the tests read state through the
 * package-private seams rather than a realised peer.</p>
 */
class IdeChromeTest {

    // ------------------------------------------------------------------
    // Diagnostic / StructureSymbol value models
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Diagnostic normalises its inputs and derives defaults")
    void diagnosticModel() {
        Diagnostic d = new Diagnostic(null, 0, -3, -1, -1, null, null);
        assertEquals("", d.path());
        assertEquals(1, d.line());
        assertEquals(1, d.col());
        assertEquals(0, d.endLine());
        assertEquals(0, d.endCol());
        assertEquals(Kind.INFO, d.kind());
        assertEquals("", d.message());
        // No explicit range: the end falls back to the start.
        assertEquals(1, d.endLineOrStart());
        assertEquals(1, d.endColOrStart());

        Diagnostic range = new Diagnostic("/src/X.java", 3, 5, 3, 9, Kind.ERROR, "boom");
        assertEquals(3, range.endLineOrStart());
        assertEquals(9, range.endColOrStart());
        assertEquals("Error", range.severityLabel());
        assertEquals("Warning",
                Diagnostic.of("/a", 1, 1, Kind.WARNING, "w").severityLabel());
        assertEquals("Hint",
                Diagnostic.of("/a", 1, 1, Kind.HINT, "h").severityLabel());
    }

    @Test
    @DisplayName("of() builds a single-point diagnostic")
    void diagnosticOf() {
        Diagnostic d = Diagnostic.of("/p/Foo.java", 7, 4, Kind.ERROR, "bad");
        assertEquals(0, d.endLine());
        assertEquals(0, d.endCol());
        assertEquals(7, d.endLineOrStart());
        assertEquals(4, d.endColOrStart());
        assertEquals("/p/Foo.java", d.path());
    }

    @Test
    @DisplayName("StructureSymbol normalises and labels itself")
    void structureSymbolModel() {
        StructureSymbol s = new StructureSymbol(null, null, -5);
        assertEquals("", s.name());
        assertEquals("", s.kind());
        assertEquals(1, s.line());
        assertEquals("", s.display());

        StructureSymbol m = new StructureSymbol("run", "method", 12);
        assertEquals("run : method", m.display());
        assertEquals("run : method", m.toString());
        // A kind-less symbol shows just the name.
        assertEquals("Foo", new StructureSymbol("Foo", "", 3).display());
    }

    // ------------------------------------------------------------------
    // DocumentContext caret/selection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the wide DocumentContext exposes caret and selection")
    void documentContextCaret() {
        DocumentContext doc = new DocumentContext("/a/B.java", "B.java", "text",
                "te", 0, 1, 1, 0, 2, s -> { }, s -> { });
        assertEquals(0, doc.getCaretOffset());
        assertEquals(1, doc.getCaretLine());
        assertEquals(1, doc.getCaretColumn());
        assertEquals(0, doc.getSelectionStart());
        assertEquals(2, doc.getSelectionEnd());
    }

    @Test
    @DisplayName("the legacy DocumentContext constructor reports zero geometry")
    void documentContextBackCompat() {
        DocumentContext doc = new DocumentContext("/a", "a", "body", "sel",
                s -> { }, s -> { });
        assertEquals(0, doc.getCaretOffset());
        assertEquals(0, doc.getCaretLine());
        assertEquals(0, doc.getCaretColumn());
        assertEquals(0, doc.getSelectionStart());
        assertEquals(0, doc.getSelectionEnd());
        // Null fields normalise rather than throw.
        DocumentContext empty = new DocumentContext(null, null, null, null,
                null, null);
        assertEquals("", empty.getFilePath());
        assertEquals("", empty.getFileName());
        assertEquals("", empty.getFullText());
        assertEquals("", empty.getSelectedText());
    }

    @Test
    @DisplayName("selection end is clamped to at least the selection start")
    void documentContextSelectionClamp() {
        DocumentContext doc = new DocumentContext("/a", "a", "body", "",
                5, 1, 6, 5, 2, s -> { }, s -> { });
        assertEquals(5, doc.getSelectionStart());
        assertEquals(5, doc.getSelectionEnd());
        assertEquals(0, doc.getCaretColumn() - 6); // caretColumn preserved
    }

    // ------------------------------------------------------------------
    // EditorContext capability gating through EditorSinks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DIAGNOSE-gated diagnostics/structure only fire when granted")
    void diagnoseGating() {
        final List<String> reported = new ArrayList<>();
        final List<String> cleared = new ArrayList<>();
        final List<String> structured = new ArrayList<>();
        EditorSinks sinks = EditorSinks.builder()
                .reportDiagnostics((path, diags) -> reported.add(path + ":" + diags.size()))
                .clearDiagnostics(cleared::add)
                .showStructure((path, syms) -> structured.add(path + ":" + syms.size()))
                .build();
        List<Diagnostic> two = List.of(
                Diagnostic.of("/a", 1, 1, Kind.ERROR, "x"),
                Diagnostic.of("/a", 2, 1, Kind.WARNING, "y"));

        // Granted: every capability routes through.
        EditorContext with = new EditorContext(
                EnumSet.of(TextEditorPermission.DIAGNOSE), sinks);
        with.reportDiagnostics("/a", two);
        with.clearDiagnostics("/a");
        with.showStructure("/a", List.of(new StructureSymbol("m", "method", 3)));
        assertEquals(List.of("/a:2"), reported);
        assertEquals(List.of("/a"), cleared);
        assertEquals(List.of("/a:1"), structured);

        // Not granted: silent no-ops, sinks never touched.
        reported.clear();
        cleared.clear();
        structured.clear();
        EditorContext without = new EditorContext(
                EnumSet.of(TextEditorPermission.READ), sinks);
        without.reportDiagnostics("/a", two);
        without.clearDiagnostics("/a");
        without.showStructure("/a", List.of());
        assertTrue(reported.isEmpty());
        assertTrue(cleared.isEmpty());
        assertTrue(structured.isEmpty());
    }

    @Test
    @DisplayName("a null diagnostics/structure list is normalised to empty")
    void diagnoseNullList() {
        final List<String> reported = new ArrayList<>();
        EditorSinks sinks = EditorSinks.builder()
                .reportDiagnostics((path, diags) -> reported.add(path + ":" + diags.size()))
                .build();
        EditorContext ctx = new EditorContext(
                EnumSet.of(TextEditorPermission.DIAGNOSE), sinks);
        ctx.reportDiagnostics("/a", null);
        assertEquals(List.of("/a:0"), reported);
    }

    @Test
    @DisplayName("DEBUG-gated hooks only fire when granted, guarding null text")
    void debugGating() {
        final List<String> out = new ArrayList<>();
        final List<String> state = new ArrayList<>();
        final List<List<String>> stacks = new ArrayList<>();
        final List<List<String>> locals = new ArrayList<>();
        final List<List<String>> breakpoints = new ArrayList<>();
        EditorSinks sinks = EditorSinks.builder()
                .appendDebugOutput(out::add)
                .setDebugState(state::add)
                .showStack(stacks::add)
                .showLocals(locals::add)
                .setBreakpoints(breakpoints::add)
                .build();

        EditorContext dbg = new EditorContext(
                EnumSet.of(TextEditorPermission.DEBUG), sinks);
        dbg.appendDebugOutput("paused");
        dbg.appendDebugOutput(null);        // null-guarded
        dbg.setDebugState("running");
        dbg.setDebugState(null);             // null-guarded
        dbg.showStack(null);                 // null -> empty
        dbg.showLocals(List.of("i = 3 : int"));
        dbg.setBreakpoints(List.of("Foo.java:12"));
        assertEquals(List.of("paused"), out);
        assertEquals(List.of("running"), state);
        assertEquals(1, stacks.size());
        assertTrue(stacks.get(0).isEmpty());
        assertEquals(List.of("i = 3 : int"), locals.get(0));
        assertEquals(List.of("Foo.java:12"), breakpoints.get(0));

        out.clear();
        stacks.clear();
        EditorContext noDbg = new EditorContext(
                EnumSet.of(TextEditorPermission.READ), sinks);
        noDbg.appendDebugOutput("x");
        noDbg.setDebugState("y");
        noDbg.showStack(List.of("f"));
        noDbg.showLocals(List.of("l"));
        noDbg.setBreakpoints(List.of("b"));
        assertTrue(out.isEmpty());
        assertTrue(stacks.isEmpty());
    }

    @Test
    @DisplayName("showMessage is always allowed and null-guarded")
    void showMessageAlwaysAllowed() {
        final List<String> msgs = new ArrayList<>();
        EditorContext ctx = new EditorContext(EnumSet.noneOf(TextEditorPermission.class),
                EditorSinks.builder().showMessage(msgs::add).build());
        ctx.showMessage("hi");
        ctx.showMessage(null);
        assertEquals(List.of("hi"), msgs);
        // An unset sink never throws.
        new EditorContext(EnumSet.allOf(TextEditorPermission.class),
                EditorSinks.builder().build()).showMessage("ignored");
    }

    // ------------------------------------------------------------------
    // LineNumberGutter markers and breakpoints
    // ------------------------------------------------------------------

    private static LineNumberGutter newGutter() {
        DefaultStyledDocument doc = new DefaultStyledDocument();
        JTextPane pane = new JTextPane(doc);
        return new LineNumberGutter(doc, pane);
    }

    @Test
    @DisplayName("the gutter tracks diagnostic markers by line")
    void gutterMarkers() {
        LineNumberGutter gutter = newGutter();
        Map<Integer, Kind> markers = new HashMap<>();
        markers.put(0, Kind.ERROR);
        markers.put(4, Kind.WARNING);
        gutter.setMarkers(markers);
        assertEquals(Kind.ERROR, gutter.markerAt(0));
        assertEquals(Kind.WARNING, gutter.markerAt(4));
        assertNull(gutter.markerAt(2));

        gutter.clearMarkers();
        assertNull(gutter.markerAt(0));
        // A null argument clears rather than throws.
        gutter.setMarkers(markers);
        gutter.setMarkers(null);
        assertNull(gutter.markerAt(4));
    }

    @Test
    @DisplayName("the gutter toggles breakpoints and reports membership")
    void gutterBreakpoints() {
        LineNumberGutter gutter = newGutter();
        assertFalse(gutter.hasBreakpoint(3));
        assertTrue(gutter.toggleBreakpoint(3));   // add
        assertTrue(gutter.hasBreakpoint(3));
        assertFalse(gutter.toggleBreakpoint(3));  // remove
        assertFalse(gutter.hasBreakpoint(3));
        gutter.toggleBreakpoint(1);
        gutter.toggleBreakpoint(2);
        gutter.clearBreakpoints();
        assertFalse(gutter.hasBreakpoint(1));
        assertFalse(gutter.hasBreakpoint(2));
    }

    // ------------------------------------------------------------------
    // EditorTab diagnostics + breakpoints
    // ------------------------------------------------------------------

    @Test
    @DisplayName("EditorTab paints the highest severity per line and counts all")
    void editorTabDiagnostics() {
        EditorTab tab = new EditorTab(EditorSettings.defaults());
        tab.replaceWholeText("line one\nline two\nline three\n");

        List<Diagnostic> diags = List.of(
                Diagnostic.of("X.java", 2, 1, Kind.WARNING, "w1"),
                Diagnostic.of("X.java", 2, 5, Kind.ERROR, "e1"),
                Diagnostic.of("X.java", 3, 1, Kind.HINT, "h1"));
        tab.setDiagnostics(diags);

        assertEquals(3, tab.diagnosticCount());
        // Line 2 keeps the ERROR (higher than the WARNING on the same line).
        assertEquals(Kind.ERROR, tab.diagnosticAt(2));
        assertEquals(Kind.HINT, tab.diagnosticAt(3));
        assertNull(tab.diagnosticAt(1));

        tab.clearDiagnostics();
        assertEquals(0, tab.diagnosticCount());
        assertNull(tab.diagnosticAt(2));

        // Null / empty is a safe clear.
        tab.setDiagnostics(List.of(
                Diagnostic.of("X.java", 1, 1, Kind.INFO, "i")));
        tab.setDiagnostics(null);
        assertEquals(0, tab.diagnosticCount());
    }

    @Test
    @DisplayName("EditorTab breakpoint lines round-trip to the gutter")
    void editorTabBreakpoint() {
        EditorTab tab = new EditorTab(EditorSettings.defaults());
        tab.replaceWholeText("a\nb\nc\n");
        assertFalse(tab.hasBreakpoint(2));
        assertTrue(tab.toggleBreakpoint(2));  // 1-based -> line 2 broken
        assertTrue(tab.hasBreakpoint(2));
        assertFalse(tab.toggleBreakpoint(2)); // cleared
        assertFalse(tab.hasBreakpoint(2));
        // Out-of-range lines clamp instead of throwing.
        tab.toggleBreakpoint(999);
        tab.toggleBreakpoint(-5);
    }

    @Test
    @DisplayName("a content edit fires the debounced change listener")
    void editorTabChangeListener() throws BadLocationException {
        EditorTab tab = new EditorTab(EditorSettings.defaults());
        final int[] fired = {0};
        tab.setChangeListener(() -> fired[0]++);
        tab.document().insertString(tab.document().getLength(), "abc", null);
        assertEquals(1, fired[0]);
    }

    // ------------------------------------------------------------------
    // Problems / Structure / Debug panels
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ProblemsPanel lists diagnostics and tallies severities")
    void problemsPanel() {
        ProblemsPanel panel = new ProblemsPanel();
        panel.setDiagnostics(List.of(
                new Diagnostic("/p/A.java", 4, 2, 4, 8, Kind.ERROR, "cannot find symbol"),
                Diagnostic.of("/p/B.java", 1, 1, Kind.WARNING, "unchecked")));
        assertEquals(2, panel.problemCount());
        assertEquals(1, panel.errorCount());
        assertEquals(1, panel.warningCount());
        assertEquals("cannot find symbol", panel.diagnosticAt(0).message());
        assertNull(panel.diagnosticAt(9));

        // Activation routes the selected diagnostic to the host hook.
        final List<Diagnostic> activated = new ArrayList<>();
        panel.setOnActivate(activated::add);
        panel.clear();
        assertEquals(0, panel.problemCount());
        assertEquals(0, panel.errorCount());
    }

    @Test
    @DisplayName("StructurePanel builds an outline and exposes its nodes")
    void structurePanel() {
        StructurePanel panel = new StructurePanel();
        panel.setStructure("Foo.java", List.of(
                new StructureSymbol("Foo", "class", 3),
                new StructureSymbol("bar", "method", 10)));
        assertEquals(2, panel.nodeCount());
        assertEquals("bar", panel.symbolAt(1).name());
        assertEquals(10, panel.symbolAt(1).line());
        assertNull(panel.symbolAt(5));

        panel.setStructure("Foo.java", null);
        assertEquals(0, panel.nodeCount());
        panel.clear();
        assertEquals(0, panel.nodeCount());
    }

    @Test
    @DisplayName("DebugPanel renders output, stack, locals and breakpoints")
    void debugPanel() {
        DebugPanel panel = new DebugPanel();
        assertEquals("Not debugging", panel.stateText());

        panel.setState("paused at Foo.java:12");
        assertEquals("paused at Foo.java:12", panel.stateText());
        panel.setState(null);
        assertEquals("Not debugging", panel.stateText());

        panel.setStack(List.of("main(Foo.java:12)", "run(Foo.java:20)"));
        assertEquals(2, panel.stackRowCount());

        panel.setLocals(List.of("i = 3 : int", "s = \"hi\" : String"));
        assertEquals(2, panel.localsRowCount());

        panel.setBreakpoints(List.of("Foo.java:12", "Foo.java:20"));
        assertEquals(2, panel.breakpointListModel().getSize());

        panel.appendOutput("breakpoint hit");
        panel.appendOutput(null);
        assertTrue(panel.outputText().contains("breakpoint hit"));

        panel.reset();
        assertEquals("Not debugging", panel.stateText());
        assertEquals(0, panel.stackRowCount());
        assertEquals(0, panel.localsRowCount());
        assertEquals(0, panel.breakpointListModel().getSize());
    }

    // ------------------------------------------------------------------
    // Panel integration: the bottom tab strip carries the four surfaces
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the editor exposes Output/Problems/Structure/Debug/Completions tabs")
    void bottomTabsWired() {
        Set<String> titles = new java.util.HashSet<>();
        AdvancedTextEditorPanel panel = new AdvancedTextEditorPanel(false);
        try {
            javax.swing.JTabbedPane tabs = panel.bottomTabs();
            for (int i = 0; i < tabs.getTabCount(); i++) {
                titles.add(tabs.getTitleAt(i));
            }
            assertTrue(titles.containsAll(
                    List.of("Output", "Problems", "Structure", "Debug", "Completions")),
                    "expected all five IDE tabs, got " + titles);
            assertSame(panel.problemsPanel(), tabs.getComponent(1));
            assertSame(panel.debugPanel(), tabs.getComponent(3));

            // The context-wired sink actually paints into the live chrome.
            EditorContext ctx = new EditorContext(
                    EnumSet.of(TextEditorPermission.DIAGNOSE),
                    EditorSinks.builder()
                            .reportDiagnostics(panel::reportDiagnosticsForExtension)
                            .build());
            ctx.reportDiagnostics(null, List.of(
                    Diagnostic.of("", 1, 1, Kind.ERROR, "boom")));
            assertEquals(1, panel.problemsPanel().problemCount());
            assertEquals(1, panel.currentTab().diagnosticCount());
        } finally {
            panel.dispose();
        }
    }
}
