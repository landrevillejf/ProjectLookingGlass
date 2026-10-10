/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link JavaStructureTools} (Phase 2): the AST outline of a
 * sample class (real in-memory javac parse), the regex fallback for broken
 * sources, the Kotlin best-effort outline, caret word extraction, string and
 * comment masking in occurrence scans, and the four toolbar actions exercised
 * end-to-end through capturing {@link EditorSinks} — including the permission
 * gates and a fake rename prompt so no Swing dialog is ever touched.
 */
class JavaStructureToolsTest {

    /** 16-line sample whose symbol lines the tests pin exactly. */
    private static final String JAVA = String.join("\n",
            "package demo;",                    // 1
            "",                                 // 2
            "import java.util.List;",           // 3
            "import java.util.Map;",            // 4
            "import java.util.List;",           // 5 (duplicate)
            "import java.time.Instant;",        // 6 (unused)
            "",                                 // 7
            "public class Widget {",            // 8
            "    private int counter = 0;",     // 9
            "    Widget() { }",                 // 10
            "    void bump() {",                // 11
            "        counter++;",               // 12
            "    }",                            // 13
            "    List<String> names() { return null; }", // 14
            "}",                                // 15
            "class Inner { }");                 // 16

    // -- outline ------------------------------------------------------------

    @Test
    @DisplayName("the AST outline names types, fields, constructors and methods with exact lines")
    void outlineJavaSymbols() {
        List<StructureSymbol> symbols = JavaStructureTools.outlineJava(JAVA, "Widget.java");
        assertTrue(contains(symbols, "Widget", "class", 8), symbols.toString());
        assertTrue(contains(symbols, "counter", "field", 9), symbols.toString());
        assertTrue(contains(symbols, "Widget()", "constructor", 10), symbols.toString());
        assertTrue(contains(symbols, "bump", "method", 11), symbols.toString());
        assertTrue(contains(symbols, "names", "method", 14), symbols.toString());
        assertTrue(contains(symbols, "Inner", "class", 16), symbols.toString());
    }

    @Test
    @DisplayName("a broken Java source still outlines via the regex fallback")
    void outlineJavaFallbackOnBroken() {
        String broken = "class Half {\n    void doWork() {\n        // missing brace\n";
        List<StructureSymbol> symbols = JavaStructureTools.outlineJava(broken, "Half.java");
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("Half")),
                "fallback should still see the type: " + symbols);
    }

    @Test
    @DisplayName("the Kotlin outline sees types, functions and properties")
    void outlineKotlinSymbols() {
        String kt = String.join("\n",
                "class Greeter {",                        // 1
                "    fun greet(): String {",              // 2
                "        val name = \"world\"",           // 3
                "        return name",                    // 4
                "    }",                                  // 5
                "}",                                      // 6
                "fun main() { println(Greeter().greet()) }"); // 7
        List<StructureSymbol> symbols = JavaStructureTools.outlineKotlin(kt);
        assertTrue(symbols.stream().anyMatch(
                s -> s.name().strip().equals("Greeter") && s.kind().equals("class")),
                symbols.toString());
        assertTrue(symbols.stream().anyMatch(
                s -> s.name().strip().equals("greet") && s.kind().equals("function")),
                symbols.toString());
        assertTrue(symbols.stream().anyMatch(
                s -> s.name().strip().equals("main") && s.line() == 7),
                symbols.toString());
    }

    private static boolean contains(List<StructureSymbol> symbols, String name,
                                    String kind, int line) {
        return symbols.stream().anyMatch(s ->
                s.name().strip().equals(name) && s.kind().equals(kind) && s.line() == line);
    }

    // -- caret + occurrence primitives ----------------------------------------

    @Test
    @DisplayName("identifierAt picks the word under or just before the caret")
    void identifierAtCaret() {
        String s = "alpha beta";
        assertEquals("alpha", JavaStructureTools.identifierAt(s, 0));
        assertEquals("alpha", JavaStructureTools.identifierAt(s, 2));
        assertEquals("alpha", JavaStructureTools.identifierAt(s, 5), "caret at word end");
        assertEquals("beta", JavaStructureTools.identifierAt(s, 6));
        assertEquals("beta", JavaStructureTools.identifierAt(s, 10));
        assertEquals("foo", JavaStructureTools.identifierAt("foo()", 3), "caret at word end");
        assertEquals("", JavaStructureTools.identifierAt("foo()", 4), "caret between brackets");
        assertEquals("", JavaStructureTools.identifierAt("foo", 99));
        assertEquals("", JavaStructureTools.identifierAt("", 0));
    }

    @Test
    @DisplayName("lineAtOffset counts newlines before the offset")
    void lineAtOffset() {
        String s = "a\nb\nc";
        assertEquals(0, JavaStructureTools.lineAtOffset(s, 0));
        assertEquals(1, JavaStructureTools.lineAtOffset(s, 2));
        assertEquals(2, JavaStructureTools.lineAtOffset(s, 4));
    }

    @Test
    @DisplayName("occurrence scans skip strings, chars and comments")
    void occurrencesAreMasked() {
        String s = "int counter = 1; // counter here\n"
                + "String t = \"counter\";\n"
                + "int counterX = 2; char u = 'c';\n"
                + "int counter2 = counter;\n";
        List<Integer> offsets = JavaStructureTools.identifierOffsets(s, "counter");
        assertEquals(2, offsets.size(), "declaration + final use, comment/string excluded: "
                + offsets);
        assertEquals(4, (int) offsets.get(0));
        assertFalse(offsets.contains(s.indexOf("// counter")), "comment must be masked");
        assertFalse(offsets.contains(s.indexOf("\"counter\"")), "string must be masked");
    }

    // -- actions end-to-end through the sinks ----------------------------------

    /** Captures every editor surface the extension can drive. */
    private static final class Harness {
        final List<List<StructureSymbol>> structures = new ArrayList<>();
        final List<String> navigations = new ArrayList<>();
        final List<String> messages = new ArrayList<>();

        EditorContext ctx(TextEditorPermission... perms) {
            Set<TextEditorPermission> granted =
                    (perms.length == 0) ? EnumSet.noneOf(TextEditorPermission.class)
                            : EnumSet.copyOf(List.of(perms));
            EditorSinks sinks = EditorSinks.builder()
                    .showMessage(messages::add)
                    .showStructure((p, s) -> structures.add(s))
                    .navigate((p, l) -> navigations.add(p + ":" + l))
                    .build();
            return new EditorContext(granted, sinks);
        }

        int lastNavigationLine() {
            String last = navigations.get(navigations.size() - 1);
            return Integer.parseInt(last.substring(last.lastIndexOf(':') + 1));
        }
    }

    private static DocumentContext doc(String text, int caret, int caretLine) {
        return doc(text, caret, caretLine, s -> { });
    }

    private static DocumentContext doc(String text, int caret, int caretLine,
                                       Consumer<String> mutator) {
        return new DocumentContext("/src/Widget.java", "Widget.java", text, "",
                caret, caretLine, 0, caret, caret, mutator, s -> { });
    }

    /** Starts the extension, installs the doc, and returns it plus the harness. */
    private static JavaStructureTools startedWith(Harness h, DocumentContext doc,
                                                  TextEditorPermission... perms) {
        JavaStructureTools ext = new JavaStructureTools();
        ext.onEditorStarted(h.ctx(perms));
        ext.onDocumentChanged(doc);
        return ext;
    }

    private static void run(JavaStructureTools ext, int actionIndex) {
        ext.toolbarContributions().get(actionIndex).getAction().run();
    }

    @Test
    @DisplayName("opening a Java document publishes its outline to the Structure sink")
    void refreshPublishesOutline() {
        Harness h = new Harness();
        startedWith(h, doc(JAVA, 0, 1), TextEditorPermission.READ,
                TextEditorPermission.DIAGNOSE);
        assertEquals(1, h.structures.size());
        assertTrue(h.structures.get(0).stream()
                .anyMatch(s -> s.name().strip().equals("Widget")));
    }

    @Test
    @DisplayName("a non-JVM document clears the outline instead of leaving it stale")
    void refreshClearsNonJvm() {
        Harness h = new Harness();
        JavaStructureTools ext = new JavaStructureTools();
        ext.onEditorStarted(h.ctx(TextEditorPermission.READ, TextEditorPermission.DIAGNOSE));
        ext.onDocumentChanged(new DocumentContext("/notes.txt", "notes.txt", "prose", "",
                0, 1, 0, 0, 0, s -> { }, s -> { }));
        assertEquals(List.of(), h.structures.get(0));
    }

    @Test
    @DisplayName("go-to-definition jumps the caret to the field's declaring line")
    void goToDefinitionJumps() {
        Harness h = new Harness();
        int caret = JAVA.indexOf("counter++;") + 2; // inside the use on line 12
        JavaStructureTools ext = startedWith(h, doc(JAVA, caret, 12),
                TextEditorPermission.READ, TextEditorPermission.DIAGNOSE);
        run(ext, 0);
        assertEquals(1, h.navigations.size());
        assertEquals(9, h.lastNavigationLine(), "the field declaration is on line 9");
    }

    @Test
    @DisplayName("find-occurrences jumps to the next use and summarizes the lines")
    void findOccurrencesJumpsToNext() {
        Harness h = new Harness();
        int caret = JAVA.indexOf("counter = 0") + 2; // on the declaration (line 9)
        JavaStructureTools ext = startedWith(h, doc(JAVA, caret, 9),
                TextEditorPermission.READ, TextEditorPermission.DIAGNOSE);
        run(ext, 1);
        assertEquals(12, h.lastNavigationLine());
        assertTrue(h.messages.stream().anyMatch(m -> m.contains("2 occurrence(s)")),
                h.messages.toString());
    }

    @Test
    @DisplayName("rename replaces every code occurrence in one document write")
    void renameSymbolRewrites() {
        Harness h = new Harness();
        AtomicReference<String> rewritten = new AtomicReference<>();
        int caret = JAVA.indexOf("bump") + 1; // on the method name (line 11)
        JavaStructureTools ext = startedWith(h,
                doc(JAVA, caret, 11, rewritten::set),
                TextEditorPermission.READ, TextEditorPermission.DIAGNOSE);
        ext.setRenamePromptForTesting(current -> "increase");
        run(ext, 2);
        assertTrue(rewritten.get().contains("void increase()"), rewritten.get());
        assertFalse(rewritten.get().contains("bump"));
    }

    @Test
    @DisplayName("rename keeps the document untouched for a cancel or an invalid name")
    void renameRejectsBadInput() {
        Harness h = new Harness();
        AtomicReference<String> rewritten = new AtomicReference<>();
        int caret = JAVA.indexOf("bump") + 1;
        JavaStructureTools ext = startedWith(h,
                doc(JAVA, caret, 11, rewritten::set),
                TextEditorPermission.READ, TextEditorPermission.DIAGNOSE);
        ext.setRenamePromptForTesting(current -> null); // cancelled
        run(ext, 2);
        assertNull(rewritten.get());
        ext.setRenamePromptForTesting(current -> "2 bad name"); // invalid identifier
        run(ext, 2);
        assertNull(rewritten.get(), "an invalid identifier must not rewrite the doc");
    }

    @Test
    @DisplayName("organize imports drops unused, dedupes and sorts (statics first)")
    void organizeImportsRewritesBlock() {
        String src = String.join("\n",
                "package p;",
                "",
                "import java.util.Map;",
                "import static java.lang.Integer.MAX_VALUE;",
                "import java.io.*;",
                "import java.util.List;",
                "import java.util.Map;",
                "import java.time.Instant;",
                "",
                "class C {",
                "    List<String> l;",
                "    Map<String, String> m;",
                "    int x = MAX_VALUE;",
                "}",
                "");
        String out = JavaStructureTools.organizeImports(src);
        List<String> importLines = out.lines()
                .filter(l -> l.startsWith("import")).toList();
        assertEquals(List.of(
                "import static java.lang.Integer.MAX_VALUE;",
                "import java.io.*;",
                "import java.util.List;",
                "import java.util.Map;"), importLines);
        assertTrue(out.endsWith("\n"), "trailing newline style is preserved");
    }

    @Test
    @DisplayName("a file without imports is left exactly as it was")
    void organizeImportsNoop() {
        String src = "class A { int x; }\n";
        assertEquals(src, JavaStructureTools.organizeImports(src));
    }

    @Test
    @DisplayName("actions without the needed grants are permission no-ops")
    void actionsRespectPermissions() {
        Harness h = new Harness();
        int caret = JAVA.indexOf("counter++;") + 2;
        // No READ: navigation is gated. No DIAGNOSE: the outline is gated.
        JavaStructureTools ext = startedWith(h, doc(JAVA, caret, 12));
        run(ext, 0);
        assertTrue(h.navigations.isEmpty(), "no navigate without READ");
        assertTrue(h.structures.isEmpty(), "no outline without DIAGNOSE");
    }

    @Test
    @DisplayName("navigateTo clamps a 0 line to 1 and drops the call without READ")
    void navigateToGating() {
        Harness h = new Harness();
        EditorContext withRead = h.ctx(TextEditorPermission.READ);
        withRead.navigateTo("F.java", 0);
        assertEquals("F.java:1", h.navigations.get(0));

        Harness h2 = new Harness();
        h2.ctx().navigateTo("F.java", 5); // no grants
        assertTrue(h2.navigations.isEmpty());
    }
}
