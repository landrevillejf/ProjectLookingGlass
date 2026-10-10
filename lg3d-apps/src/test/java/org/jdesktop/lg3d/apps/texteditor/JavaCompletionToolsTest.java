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
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link JavaCompletionTools} (Phase 4): the pure
 * completion engine (prefix extraction, receiver typing, import resolution,
 * reflection member listing and ranking) plus the end-to-end publish path
 * driven through capturing {@link EditorSinks} with the READ gate — and the
 * {@link CompletionPanel} strip widget itself. No Swing dialog, popup or
 * process is ever constructed.
 */
class JavaCompletionToolsTest {

    private static final String SRC = String.join("\n",
            "import java.io.File;",              // 1
            "import java.util.List;",            // 2
            "",                                  // 3
            "public class Demo {",               // 4
            "    private int counter = 0;",      // 5
            "    File cfg;",                     // 6
            "    void bumpCounter() { }",        // 7
            "    void use() {",                  // 8
            "        counter = 1;",              // 9
            "    }",                             // 10
            "}");                                // 11

    // -- pure engine -------------------------------------------------------------

    @Test
    @DisplayName("prefixAt returns the identifier run before the caret")
    void prefixExtraction() {
        String s = "int counter = cou";
        assertEquals("cou", JavaCompletionTools.prefixAt(s, s.length()));
        assertEquals("", JavaCompletionTools.prefixAt(s, s.length() - 4));
        assertEquals("counter", JavaCompletionTools.prefixAt("counter = 1", 7));
    }

    @Test
    @DisplayName("a prefix completes over this file's symbols first, keywords after")
    void prefixCompletesFileSymbolsThenKeywords() {
        String s = String.join("\n",
                "class C {",
                "    int counter;",
                "    void m() { cou");
        List<String> out = JavaCompletionTools.completions(s, s.length(), "C.java");
        assertFalse(out.isEmpty(), "expected candidates for 'cou'");
        assertEquals("counter", out.get(0), out.toString());
        assertTrue(out.stream().allMatch(c -> c.toLowerCase().contains("cou")),
                "everything matches the prefix: " + out);
        String syn = "class C { int x; void m() { syn";
        List<String> kw = JavaCompletionTools.completions(syn, syn.length(), "C.java");
        assertTrue(kw.contains("synchronized"), "keywords participate: " + kw);
    }

    @Test
    @DisplayName("a lowercase receiver completes its declared type's members")
    void receiverUsesDeclaredType() {
        String s = "class T { java.io.File cfg; void m() { cfg.}";
        List<String> members = JavaCompletionTools.completions(s, s.length() - 1,
                "T.java");
        assertTrue(members.contains("exists"), "java.io.File members: " + members);
        assertTrue(members.contains("getName"), members.toString());
        assertFalse(members.contains("counter"), "no file symbols leak in");
    }

    @Test
    @DisplayName("a capitalized receiver resolves through imports and java.lang")
    void staticReceiverResolution() {
        List<String> fileMembers = JavaCompletionTools.membersOfReceiver(SRC, "File");
        assertTrue(fileMembers.contains("exists"), fileMembers.toString());
        List<String> stringMembers = JavaCompletionTools.membersOfReceiver(
                "class K { }", "String");
        assertTrue(stringMembers.contains("valueOf"),
                "java.lang fallback: " + stringMembers);
    }

    @Test
    @DisplayName("unresolved receivers and non-Java files complete to nothing")
    void unresolvedReceiversAndKotlin() {
        assertEquals(List.of(), JavaCompletionTools.membersOfReceiver(
                "class U { void m() { zzz.", "zzz"));
        String kt = "fun main() { pri";
        assertEquals(List.of(), JavaCompletionTools.completions(kt, kt.length(), "A.kt"));
        String prose = "just prose, nothing";
        assertEquals(List.of(), JavaCompletionTools.completions(prose, prose.length(), "a.txt"));
    }

    @Test
    @DisplayName("importsOf sees single-type and wildcard imports")
    void importParsing() {
        String s = "import java.io.File;\nimport java.util.*;\nimport static java.lang.Math.PI;\nclass X {}";
        assertEquals("java.io.File", JavaCompletionTools.importsOf(s).get("File"));
        assertEquals("java.util", JavaCompletionTools.importsOf(s).get("*"));
        assertEquals("java.util.List", JavaCompletionTools.resolveType(s, "List"));
        assertEquals("java.lang.String", JavaCompletionTools.resolveType(s, "String"));
        assertNull(JavaCompletionTools.resolveType(s, "NoSuchType"));
    }

    @Test
    @DisplayName("members() lists public methods and fields, sorted and cached")
    void memberListing() {
        List<String> members = JavaCompletionTools.members("java.io.File");
        assertTrue(members.contains("listFiles"));
        assertTrue(members.contains("separator"));
        List<String> again = JavaCompletionTools.members("java.io.File");
        assertEquals(members, again);
        assertEquals(List.of(), JavaCompletionTools.members("no.such.Type"));
    }

    @Test
    @DisplayName("rank orders starts-with before contains and caps the strip")
    void ranking() {
        List<String> pool = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            pool.add("aa" + i);
        }
        pool.add("xxaa");
        List<String> out = JavaCompletionTools.rank("aa", pool);
        assertEquals(JavaCompletionTools.MAX_CANDIDATES, out.size());
        assertTrue(out.get(0).startsWith("aa"), out.toString());
        List<String> ordered = JavaCompletionTools.rank("ab",
                List.of("xab", "abc", "aB"));
        assertEquals("abc", ordered.get(0), "case-sensitive start wins: " + ordered);
    }

    @Test
    @DisplayName("the keyword set carries the control vocabulary")
    void keywords() {
        assertTrue(JavaCompletionTools.KEYWORDS.contains("synchronized"));
        assertTrue(JavaCompletionTools.KEYWORDS.contains("record"));
        assertTrue(JavaCompletionTools.KEYWORDS.contains("var"));
    }

    // -- strip widget ---------------------------------------------------------------

    @Test
    @DisplayName("the completion strip paints, pre-selects and accepts")
    void stripActivatePath() {
        CompletionPanel strip = new CompletionPanel();
        List<String> accepted = new ArrayList<>();
        strip.setOnActivate(accepted::add);
        strip.setCompletions("Demo.java", List.of("counter", "continue"));

        assertEquals(2, strip.rowCount());
        assertEquals("counter", strip.selectedCompletion(), "first row pre-selected");
        assertEquals("Demo.java (2)", strip.titleText());
        strip.activateSelected();
        assertEquals(List.of("counter"), accepted);

        strip.clear();
        assertEquals(0, strip.rowCount());
        assertNull(strip.selectedCompletion());
        strip.activateSelected();
        assertEquals(1, accepted.size(), "nothing to accept on an empty strip");
    }

    // -- end-to-end through the SPI ---------------------------------------------------

    /** Captures completion publishes and status messages. */
    private static final class Harness {
        final List<String> messages = new ArrayList<>();
        final List<List<String>> publishes = new ArrayList<>();

        EditorContext ctx(TextEditorPermission... perms) {
            Set<TextEditorPermission> granted =
                    (perms.length == 0) ? EnumSet.noneOf(TextEditorPermission.class)
                            : EnumSet.copyOf(List.of(perms));
            EditorSinks sinks = EditorSinks.builder()
                    .showMessage(messages::add)
                    .showCompletions((p, list) -> publishes.add(list))
                    .build();
            return new EditorContext(granted, sinks);
        }
    }

    private static DocumentContext doc(String text, int caret) {
        return new DocumentContext("/src/Demo.java", "Demo.java", text, "",
                caret, 1, 0, caret, caret, s -> { }, s -> { });
    }

    @Test
    @DisplayName("document changes at a prefix publish candidates through the READ sink")
    void publishOnDocumentChange() {
        Harness h = new Harness();
        JavaCompletionTools ext = new JavaCompletionTools();
        ext.onEditorStarted(h.ctx(TextEditorPermission.READ));
        String partial = SRC + "\n// cou";
        ext.onDocumentChanged(doc(partial, partial.length()));

        List<String> published = h.publishes.get(h.publishes.size() - 1);
        assertTrue(published.contains("counter"), published.toString());
        assertTrue(published.contains("bumpCounter"), published.toString());
    }

    @Test
    @DisplayName("without READ the strip is never painted")
    void publishGatedOnRead() {
        Harness h = new Harness();
        JavaCompletionTools ext = new JavaCompletionTools();
        ext.onEditorStarted(h.ctx(TextEditorPermission.TOOLBAR));
        String partial = SRC + "\n// cou";
        ext.onDocumentChanged(doc(partial, partial.length()));
        assertTrue(h.publishes.isEmpty(), h.publishes.toString());
    }

    @Test
    @DisplayName("the toolbar action publishes and counts the candidates")
    void actionPublishesAndReports() {
        Harness h = new Harness();
        JavaCompletionTools ext = new JavaCompletionTools();
        ext.onEditorStarted(h.ctx(TextEditorPermission.READ,
                TextEditorPermission.TOOLBAR));
        String withDot = SRC + "\n// cfg.";
        ext.onDocumentChanged(doc(withDot, withDot.length()));
        h.publishes.clear();
        ext.toolbarContributions().get(0).getAction().run();

        assertTrue(h.publishes.get(0).contains("exists"), h.publishes.toString());
        assertTrue(h.messages.stream().anyMatch(m -> m.contains("completion(s)")),
                h.messages.toString());
    }

    @Test
    @DisplayName("typing away from an identifier clears the strip")
    void nonIdentifierCaretClears() {
        Harness h = new Harness();
        JavaCompletionTools ext = new JavaCompletionTools();
        ext.onEditorStarted(h.ctx(TextEditorPermission.READ));
        String s = "class Z { int counter; void m() { } }";
        ext.onDocumentChanged(doc(s, 0)); // caret before 'class': empty prefix, pool = keywords? no: prefix "" + receiver none
        // an empty prefix with no receiver publishes the full pool; a lone '.' publishes nothing
        String after = "class Z { int x = 1.5 }";
        ext.onDocumentChanged(doc(after, after.indexOf('5') + 1));
        assertTrue(h.publishes.get(h.publishes.size() - 1).isEmpty(),
                "number literal dot completes to nothing");
    }

    @Test
    @DisplayName("the manifest declares the completion engine with READ + TOOLBAR")
    void manifestShape() {
        JavaCompletionTools ext = new JavaCompletionTools();
        assertEquals("lg3d.java-completion", ext.manifest().getId());
        assertEquals("Java/Kotlin", ext.category());
        assertTrue(ext.manifest().getPermissions().contains(TextEditorPermission.READ));
        assertEquals(1, ext.toolbarContributions().size());
    }
}
