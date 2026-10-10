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
package org.jdesktop.lg3d.mandela.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.lang.Keywords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The editor-services contract, tested the way a keystroke exercises it: on buffers
 * that are half-typed, on names that are not what they look like, and on receivers
 * whose type the buffer cannot prove.
 *
 * <p>Three of these tests are the load-bearing ones, because they are the failures a
 * tool shipping this API would actually hit. A completion popup that fires inside a
 * comment, a local named {@code json} completed as though it were {@code std.json},
 * and a keyword list that colours {@code get} when the parser never reads it are all
 * confident, plausible and wrong &mdash; and none of them throws, so only a test
 * catches them.</p>
 *
 * <p>Nothing here asserts a specific line number beyond the first few, and no test
 * counts candidates: the vocabulary grows with the library, and a suite that breaks
 * when a module gains a method is a suite an engineer will delete.</p>
 */
class EditorServicesTest {

    /** @return the outline rows as {@code name:kind}, so a failure reads as a list. */
    private static List<String> kinds(List<EditorServices.Entry> outline) {
        List<String> out = new ArrayList<>();
        for (EditorServices.Entry entry : outline) {
            out.add(entry.name() + ":" + entry.kind());
        }
        return out;
    }

    /** @return the 0-based offset just after {@code text} in {@code source} */
    private static int after(String source, String text) {
        return source.indexOf(text) + text.length();
    }

    // -- the vocabulary the language owns -----------------------------------

    @Test
    @DisplayName("keywords() offers reserved and contextual words, and nothing the parser ignores")
    void keywordsAreOnlyWordsTheParserReads() {
        List<String> words = EditorServices.keywords();
        assertTrue(words.containsAll(List.of("fun", "let", "var", "class", "type",
                "enum", "match", "use", "export", "init", "step")),
                () -> "missing a real keyword: " + words);
        // get / set / with / on / out are ordinary identifiers: the parser never
        // looks for them, so colouring them would misread a script's own names.
        assertFalse(words.containsAll(List.of("get", "set", "with", "on", "out")),
                "a word the parser does not read must not be presented as a keyword");
        assertThrows(UnsupportedOperationException.class, () -> words.add("nope"));
    }

    @Test
    @DisplayName("the reserved words are exactly the ones the grammar cannot do without")
    void reservedSetIsDeliberate() {
        // Making a word reserved costs every script that used it as a name, so the
        // set is pinned here instead of counted in a guide: adding a word has to be
        // an edit a reviewer sees, and the two sets must stay disjoint -- a word
        // cannot be both "never an identifier" and "an identifier everywhere else".
        assertEquals(List.of("and", "as", "break", "catch", "class", "continue",
                "defer", "else", "enum", "extends", "false", "finally", "for", "fun",
                "if", "import", "in", "is", "let", "loop", "match", "not", "null",
                "or", "return", "super", "this", "throw", "true", "try", "type",
                "use", "var", "while"),
                Keywords.RESERVED.stream().sorted().toList());
        for (String word : Keywords.CONTEXTUAL) {
            assertFalse(Keywords.RESERVED.contains(word),
                    word + " cannot be reserved and contextual at once");
        }
        assertEquals(Keywords.RESERVED.size() + Keywords.CONTEXTUAL.size(),
                Keywords.ALL.size(), "ALL must not lose a word to a duplicate");
    }

    @Test
    @DisplayName("globals() are the names a program may read without declaring them")
    void globalsComeFromTheLibrary() {
        List<String> globals = EditorServices.globals();
        assertTrue(globals.containsAll(List.of("println", "print", "str", "int",
                "typeOf", "keys")),
                () -> "expected the library's globals, got " + globals);
        // The engine's global table also carries the hooks that make `use` and
        // `import` work. They are legal names for the compiler and nonsense in a
        // popup, and only the popup is what a developer reads.
        assertFalse(globals.contains("__use__"), () -> "got " + globals);
        assertFalse(globals.contains("__import__"), () -> "got " + globals);
        assertEquals(new ArrayList<>(new java.util.TreeSet<>(globals)), globals,
                "a tool's list must be sorted so it is stable between calls");
    }

    @Test
    @DisplayName("moduleNames() lists every module a use statement can ask for")
    void moduleNamesIncludeTheStaticAndGatedModules() {
        assertTrue(EditorServices.moduleNames().containsAll(
                List.of("json", "math", "fs", "env", "time")),
                () -> "got " + EditorServices.moduleNames());
    }

    @Test
    @DisplayName("valueMembers() mirrors the library's own tables, in lookup order")
    void valueMembersMatchTheLibrary() {
        List<String> text = EditorServices.valueMembers("Str");
        assertTrue(text.containsAll(List.of("length", "upper", "lower", "trim",
                "split", "replace")),
                () -> "Str members were " + text);
        // A property is read before a method of the same name is sought, so the
        // listing has to put it first or a tool would describe a different lookup.
        assertTrue(text.indexOf("length") < text.indexOf("lower"),
                "properties must precede methods: " + text);
        assertTrue(EditorServices.valueMembers("List").containsAll(
                List.of("map", "filter", "join")),
                () -> "List members were " + EditorServices.valueMembers("List"));
        assertFalse(EditorServices.valueMembers("Map").isEmpty());
        assertFalse(EditorServices.valueMembers("Num").isEmpty());
        assertEquals(EditorServices.valueMembers("Int"),
                EditorServices.valueMembers("Num"), "Int and Num are one family");
        // A Bool carries no members, and an invented type name answers nothing
        // rather than guessing at what the author might have meant.
        assertTrue(EditorServices.valueMembers("Bool").isEmpty());
        assertTrue(EditorServices.valueMembers("Whatever").isEmpty());
        assertTrue(EditorServices.valueMembers(null).isEmpty());
    }

    @Test
    @DisplayName("moduleMembers() names what a script could call, without running it")
    void moduleMembersNameExportedNames() {
        List<String> json = EditorServices.moduleMembers("std.json");
        assertTrue(json.containsAll(List.of("parse", "encode", "stringify", "isValid")),
                () -> "json exports were " + json);
        assertEquals(json, EditorServices.moduleMembers("json"),
                "both spellings of the module name answer the same list");
        // The gated modules are still enumerable on a console-capability host: the
        // grant is asserted when a function runs, never when its name is listed.
        assertTrue(EditorServices.moduleMembers("std.fs").contains("read"),
                () -> "fs exports were " + EditorServices.moduleMembers("std.fs"));
        assertTrue(EditorServices.moduleMembers("nosuchmodule").isEmpty());
        assertTrue(EditorServices.moduleMembers(null).isEmpty());
        assertTrue(EditorServices.moduleMembers("  ").isEmpty());
    }

    // -- outline ------------------------------------------------------------

    @Test
    @DisplayName("outline() lists declarations in source order with a method after its class")
    void outlineListsDeclarations() {
        String source = """
                fun alpha() -> Int { 1 }
                let beta = 2
                var gamma = 3
                class Counter(start: Int = 0) {
                    var total = start

                    fun bump(step: Int = 1) -> Int { total + step }
                }
                type Pair(x: Int, y: Int)
                enum Color { Red, Green }
                for i in 1..3 { i }
                """;
        List<EditorServices.Entry> outline = EditorServices.outline(source);
        assertEquals(List.of("alpha:fun", "beta:let", "gamma:var", "Counter:class",
                "total:field", "bump:method", "Pair:type", "x:field", "y:field",
                "Color:enum", "Red:constant", "Green:constant"),
                kinds(outline));
        assertEquals(1, outline.get(0).line(), "alpha is on the first line");
        assertEquals(3, outline.get(2).line(), "gamma is on the third line");
        assertEquals(4, outline.get(3).line(), "Counter is on the fourth line");
        assertEquals("alpha : fun", outline.get(0).display());
        assertEquals("bare", new EditorServices.Entry("bare", "", 1, 1).display());
    }

    @Test
    @DisplayName("outline() answers empty for an unparsable buffer instead of throwing")
    void outlineIsTotal() {
        for (String broken : List.of("", "   ", "1 +", "let", "class C {",
                "fun f(", "\"unterminated", "} else {")) {
            assertNotNull(EditorServices.outline(broken), broken + " must answer a list");
        }
        assertTrue(EditorServices.outline("1 +").isEmpty());
        assertTrue(EditorServices.outline(null).isEmpty(), "null is an empty buffer");
    }

    @Test
    @DisplayName("a half-typed buffer keeps the outline it already had")
    void outlineSurvivesAnIncompleteDeclaration() {
        String source = "fun keep() -> Int { 1 }\nlet half =\n";
        List<String> names = new ArrayList<>();
        for (EditorServices.Entry entry : EditorServices.outline(source)) {
            names.add(entry.name());
        }
        assertTrue(names.contains("keep"),
                "an outline that vanishes on the first keystroke is worse than a lagging"
                        + " one, got " + names);
    }

    // -- the buffer's own names ---------------------------------------------

    @Test
    @DisplayName("vocabulary() reaches body locals, parameters and destructured names")
    void vocabularyCollectsEveryBoundName() {
        String source = """
                fun outer(row: Map) -> Str {
                    let inner = row.label
                    return inner
                }
                for item in 1..3 { item }
                let [left, right] = [1, 2]
                """;
        List<String> names = EditorServices.vocabulary(source);
        assertTrue(names.containsAll(List.of("outer", "row", "inner", "item",
                "left", "right")), () -> "got " + names);
        assertFalse(names.contains("let"), "keywords are not names");
        assertEquals(names.stream().distinct().count(), names.size(),
                "a name is offered once: " + names);
        assertTrue(EditorServices.vocabulary(null).isEmpty());
        assertTrue(EditorServices.vocabulary("???").isEmpty());
    }

    // -- completion ----------------------------------------------------------

    @Test
    @DisplayName("completion ranks the buffer's own names ahead of the library")
    void completionRanksByPrefix() {
        String source = "let totalCost = 1\ntotalC";
        List<String> candidates = EditorServices.complete(source, source.length());
        assertEquals("totalCost", candidates.get(0), () -> "got " + candidates);
    }

    @Test
    @DisplayName("a host's own bindings complete like the language's globals")
    void completionIncludesHostGlobals() {
        String source = "let a = 1\n";
        List<String> candidates = EditorServices.complete(source, source.length(),
                List.of("zeta", "doc"));
        assertEquals("zeta", candidates.get(0),
                "the host's order is a priority order: " + candidates);
        assertTrue(candidates.contains("doc"), () -> "got " + candidates);
        assertTrue(candidates.contains("a"), "the buffer's own names are offered too");
    }

    @Test
    @DisplayName("after a dot the list is the receiver's members, not every name")
    void completionAfterADot() {
        String source = "use std.json\njson.";
        List<String> candidates = EditorServices.complete(source, source.length());
        assertTrue(candidates.containsAll(List.of("parse", "encode")), () -> "got " + candidates);
        assertFalse(candidates.contains("println"),
                "a member list is not the global list: " + candidates);
    }

    @Test
    @DisplayName("std. opens the module list")
    void completionOpensTheModulesAfterStd() {
        List<String> candidates = EditorServices.complete("std.", 4);
        assertTrue(candidates.containsAll(List.of("json", "math", "fs")),
                () -> "got " + candidates);
    }

    @Test
    @DisplayName("a local named json is not the standard library's json module")
    void aLocalNameIsNotAModule() {
        String source = "let json = 1\njson.";
        List<String> candidates = EditorServices.complete(source, source.length());
        assertFalse(candidates.contains("parse"),
                () -> "the buffer never wrote `use std.json`, yet got " + candidates);
    }

    @Test
    @DisplayName("a dot inside a comment does not open a popup")
    void completionStaysQuietInsideAComment() {
        List<String> candidates = EditorServices.complete("// json.pa", 10);
        assertFalse(candidates.contains("parse"), () -> "got " + candidates);
    }

    @Test
    @DisplayName("members are offered only where the buffer proves the receiver's type")
    void memberCompletionIsProvableOnly() {
        String classSource = """
                class Counter(start: Int = 0) {
                    var total = start

                    fun bump(step: Int = 1) -> Int { this.total + step }
                }
                """;
        assertTrue(EditorServices.membersOf("this", classSource)
                .containsAll(List.of("total", "bump")),
                () -> "this. got " + EditorServices.membersOf("this", classSource));
        assertTrue(EditorServices.membersOf("super", classSource).contains("bump"));

        assertTrue(EditorServices.membersOf("Color", "enum Color { Red, Green }")
                .containsAll(List.of("Red", "Green")),
                "an enum is named as itself");

        String constructed = "class Counter(start: Int = 0) {\n    var total = start\n}\n"
                + "let c = Counter(1)\n";
        assertTrue(EditorServices.membersOf("c", constructed).contains("total"),
                () -> "c. got " + EditorServices.membersOf("c", constructed));

        String annotated = "let s: Str = \"x\"\n";
        assertTrue(EditorServices.membersOf("s", annotated).contains("upper"),
                "an annotation is the author telling us the type");

        // Nothing is invented: an unknown receiver, a module that was never used,
        // and a call whose result this tool cannot type all answer empty.
        assertTrue(EditorServices.membersOf("mystery", annotated).isEmpty());
        assertTrue(EditorServices.membersOf(null, annotated).isEmpty());
        assertTrue(EditorServices.membersOf("json", "let json = 1\n").isEmpty());
        assertTrue(EditorServices.membersOf("s", "").isEmpty());
    }

    @Test
    @DisplayName("completing a buffer full of calls runs none of them")
    void completionNeverRunsTheProgram() {
        String source = """
                use std.fs
                let boom = fs.read("/etc/passwd")
                let die = undef()
                boom.""";
        List<String> candidates = assertDoesNotThrow(
                () -> EditorServices.complete(source, after(source, "boom.")));
        assertTrue(candidates.isEmpty(),
                () -> "fs.read returns a value this tool cannot type, so nothing is"
                        + " knowable and nothing may be run: " + candidates);
        assertTrue(EditorServices.membersOf("boom", source).isEmpty());
        assertFalse(EditorServices.membersOf("die", source).contains("read"));
    }

    @Test
    @DisplayName("a caret outside the buffer and an oversized list are both handled")
    void completionIsBoundedAndForgiving() {
        String source = "let x = 1\n";
        assertDoesNotThrow(() -> EditorServices.complete(source, 99));
        assertTrue(EditorServices.complete(null, 0).isEmpty());
        assertTrue(EditorServices.complete("", 0).isEmpty());
        assertTrue(EditorServices.complete(source, -1).isEmpty());

        String crowd = "let a1 = 1\nlet a2 = 2\n";
        List<String> capped = assertDoesNotThrow(
                () -> EditorServices.complete(crowd + "a", crowd.length() + 1));
        assertTrue(capped.size() <= EditorServices.MAX_CANDIDATES,
                () -> "returned " + capped.size());
    }

    @Test
    @DisplayName("prefixAt() reads the identifier run before the caret")
    void prefixAtIsTheTypedRun() {
        assertEquals("total", EditorServices.prefixAt("let totalCost = total", 23));
        assertEquals("a", EditorServices.prefixAt("let a", 5));
        // A digit is part of a name, so the run after `= ` is the literal's own
        // characters; a popup opened there is the editor's decision, not this
        // function's.
        assertEquals("1", EditorServices.prefixAt("let a = 1", 9));
        assertEquals("", EditorServices.prefixAt("let a = ", 8));
    }

    @Test
    @DisplayName("rank() dedupes, prefers the exact spelling and caps the list")
    void rankOrdersThePool() {
        List<String> typed = List.of("printer", "println", "Print", "printer");
        // A lower-case run matches everything case-insensitively and keeps the
        // caller's priority order, which is how the buffer's own names stay ahead.
        assertEquals(List.of("printer", "println", "Print"),
                EditorServices.rank("pr", typed));
        // Typing the capital is a statement about which one is meant: an author who
        // wrote `Pr` means the type Print, not the local printer.
        assertEquals(List.of("Print", "printer", "println"),
                EditorServices.rank("Pr", typed));
        assertTrue(EditorServices.rank("", List.of()).isEmpty());
        assertTrue(EditorServices.rank(null, List.of("println")).contains("println"));

        List<String> crowd = new ArrayList<>();
        for (int i = 0; i < EditorServices.MAX_CANDIDATES + 40; i++) {
            crowd.add("name" + i);
        }
        assertEquals(EditorServices.MAX_CANDIDATES,
                EditorServices.rank("", crowd).size(), "a popup cannot scroll forever");
    }

    // -- the engine behind the lookups ---------------------------------------

    @Test
    @DisplayName("the vocabulary survives the engine being dropped and rebuilt")
    void hostIsLazyAndRebuilt() {
        List<String> before = EditorServices.globals();
        EditorServices.resetHostForTesting();
        assertEquals(before, EditorServices.globals(),
                "the tables are constants, so a rebuild answers the same questions");
    }

    @Test
    @DisplayName("the language's identity is one source of truth")
    void identityMatchesTheLanguage() {
        assertEquals(Mandela.LANGUAGE_NAME, EditorServices.languageName());
        assertEquals(Mandela.FILE_EXTENSION, EditorServices.fileExtension());
        assertEquals("Mandela", EditorServices.languageName());
        assertEquals("mnd", EditorServices.fileExtension());
    }

    @Test
    @DisplayName("every starter template is a program the language accepts")
    void templatesActuallyRun() {
        var templates = EditorServices.starterTemplates();
        assertTrue(templates.keySet().containsAll(List.of("script", "function", "class")),
                () -> "got " + templates.keySet());
        for (String name : templates.keySet()) {
            String body = templates.get(name);
            assertFalse(body.isBlank(), name + " is empty");
            List<Diagnostic> errors = Mandela.check(body, name + ".mnd").stream()
                    .filter(finding -> finding.severity() == Diagnostic.Severity.ERROR)
                    .toList();
            assertTrue(errors.isEmpty(), () -> name + " must parse: " + errors);
        }
        // The script template is the one a new file gets, and a shebang the lexer
        // refuses would leave every fresh buffer red.
        assertTrue(templates.get("script").startsWith("#!"),
                "a script should be executable on its own");
    }
}
