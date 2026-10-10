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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic.Kind;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;
import org.jdesktop.lg3d.mandela.lang.Keywords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link MandelaTools}, the bundled Espresso extension for the
 * Mandela language. They cover the four things the extension owns and nothing it
 * borrows: which buffers count as Mandela, how a language diagnostic is mapped
 * onto the editor's value type, how one analysis pass reaches the Problems,
 * Structure and completion surfaces under the permission gates, and what the
 * toolbar actions do &mdash; including the real in-JVM runner, whose sandbox and
 * instruction budget are verified rather than asserted in prose.
 *
 * <p>The completion <em>quality</em> is {@code EditorServicesTest}'s business in
 * the language module; here a candidate list only has to prove it travelled.</p>
 */
class MandelaToolsTest {

    /** A buffer that compiles, prints and declares things worth outlining. */
    private static final String GOOD = """
            class Counter(start: Int = 0) {
                var total = start

                fun bump(step: Int = 1) -> Int {
                    total += step
                    total
                }
            }

            let c = Counter()
            c.bump()
            println(c.total)
            """;

    /** Two real syntax errors, so the mapping is exercised on parser output. */
    private static final String BROKEN = """
            let x = 1 +
            fn unclosed( {
            """;

    /** Compiles cleanly, then fails at run time on the third line. */
    private static final String RUNTIME_BOMB = """
            println("before")
            let xs = [1, 2]
            println(xs[9])
            println("after")
            """;

    /** A caret sitting on a half-typed member call, for the completion surface. */
    private static final String TYPING = """
            let s: Str = "hi"
            s.le""";

    // -- recognition ---------------------------------------------------------

    @Test
    @DisplayName("a .mnd buffer is Mandela whatever its case")
    void recognizesByExtension() {
        assertTrue(MandelaTools.isMandela("hello.mnd", ""));
        assertTrue(MandelaTools.isMandela("HELLO.MND", "println(1)"));
        assertFalse(MandelaTools.isMandela("notes.txt", "println(1)"));
        assertFalse(MandelaTools.isMandela("hello.mnd1", ""));
        assertFalse(MandelaTools.isMandela("hello.mnd.java", ""));
        assertFalse(MandelaTools.isMandela(null, "println(1)"));
    }

    @Test
    @DisplayName("an extension-less shebang script is Mandela; a named file is not")
    void recognizesByShebang() {
        assertTrue(MandelaTools.isMandela("build", "#!/usr/bin/env mandela\nprintln(1)"));
        // A dotted name already declared its own language; the shebang is noise.
        assertFalse(MandelaTools.isMandela("build.sh", "#!/usr/bin/env mandela\n"));
        assertFalse(MandelaTools.isMandela("notes", "#!/bin/sh\necho hi"));
        assertFalse(MandelaTools.isMandela("notes", "println(1)"));
        assertFalse(MandelaTools.isMandela("build", null));
    }

    @Test
    @DisplayName("the language catalogue recognises .mnd as Mandela")
    void theCatalogueKnowsMandela() {
        Language mnd = Languages.forFileName("hello.mnd");
        assertEquals("Mandela", mnd.getName());
        assertEquals("//", mnd.getLineComment());
        assertEquals("/*", mnd.getBlockOpen());
        assertEquals("*/", mnd.getBlockClose());
        assertTrue(mnd.getStringQuotes().contains("\""));
        assertTrue(mnd.getStringQuotes().contains("'"));
        assertFalse(mnd.isMarkup());
        assertFalse(mnd.hasPreprocessor());
        assertTrue(mnd.hasKeywords());
    }

    @Test
    @DisplayName("the catalogue's keywords are the language's own, not a copy")
    void keywordsComeFromTheLanguage() {
        // Single-sourced: Espresso colours exactly what the parser reads, so an
        // ordinary identifier can never be painted as a keyword.
        assertEquals(Keywords.ALL, Languages.forExtension("mnd").getKeywords());
        assertTrue(Keywords.ALL.contains("step"));
        assertFalse(Keywords.ALL.contains("get"));
        assertFalse(Keywords.ALL.contains("with"));
    }

    // -- mapping -------------------------------------------------------------

    @Test
    @DisplayName("every severity maps onto its editor counterpart")
    void mapsEverySeverity() {
        assertEquals(Kind.ERROR,
                MandelaTools.kind(org.jdesktop.lg3d.mandela.lang.Diagnostic.Severity.ERROR));
        assertEquals(Kind.WARNING,
                MandelaTools.kind(org.jdesktop.lg3d.mandela.lang.Diagnostic.Severity.WARNING));
        assertEquals(Kind.INFO,
                MandelaTools.kind(org.jdesktop.lg3d.mandela.lang.Diagnostic.Severity.INFO));
        assertEquals(Kind.HINT,
                MandelaTools.kind(org.jdesktop.lg3d.mandela.lang.Diagnostic.Severity.HINT));
        // An unknown urgency is the safe one: show it as an error, never hide it.
        assertEquals(Kind.ERROR, MandelaTools.kind(null));
    }

    @Test
    @DisplayName("map keeps the range and names the rule that fired")
    void mapKeepsRangeAndRule() {
        org.jdesktop.lg3d.mandela.lang.Diagnostic finding =
                new org.jdesktop.lg3d.mandela.lang.Diagnostic("p.mnd", 3, 5, 3, 9,
                        org.jdesktop.lg3d.mandela.lang.Diagnostic.Severity.WARNING,
                        "variable is never used", "unused-variable");
        Diagnostic mapped = MandelaTools.map("/src/p.mnd", finding);
        assertEquals("/src/p.mnd", mapped.path());
        assertEquals(3, mapped.line());
        assertEquals(5, mapped.col());
        assertEquals(3, mapped.endLine());
        assertEquals(9, mapped.endCol());
        assertEquals(Kind.WARNING, mapped.kind());
        assertEquals("variable is never used [unused-variable]", mapped.message());

        // A rule-less finding must not grow empty brackets.
        org.jdesktop.lg3d.mandela.lang.Diagnostic bare =
                new org.jdesktop.lg3d.mandela.lang.Diagnostic("p.mnd", 1, 1, 1, 1,
                        org.jdesktop.lg3d.mandela.lang.Diagnostic.Severity.ERROR,
                        "something is wrong", "");
        assertEquals("something is wrong", MandelaTools.map("/src/p.mnd", bare).message());
    }

    // -- analyse: one pass, three surfaces ----------------------------------

    @Test
    @DisplayName("analyse turns the real parser error into a gutter diagnostic")
    void analyseReportsTheParserError() {
        MandelaTools.Analysis found = MandelaTools.analyse(BROKEN, "broken.mnd",
                "/src/broken.mnd", 0);
        assertEquals(1, found.diagnostics().size(), "got " + found.diagnostics());
        Diagnostic d = found.diagnostics().get(0);
        assertEquals(Kind.ERROR, d.kind());
        assertEquals(2, d.line());
        assertEquals(4, d.col());
        assertEquals("/src/broken.mnd", d.path());
        assertTrue(d.message().contains("[syntax]"), d.message());
    }

    @Test
    @DisplayName("analyse outlines what the buffer declares")
    void analyseOutlinesTheBuffer() {
        MandelaTools.Analysis found = MandelaTools.analyse(GOOD, "counter.mnd",
                "/src/counter.mnd", 0);
        assertTrue(found.diagnostics().isEmpty(), "clean buffer reported "
                + found.diagnostics());
        assertEquals(List.of("Counter : class", "total : field", "bump : method",
                "c : let"), found.structure().stream().map(StructureSymbol::display)
                .collect(Collectors.toList()));
    }

    @Test
    @DisplayName("an unparsable buffer still outlines the declarations it reached")
    void outlineSurvivesBrokenSyntax() {
        MandelaTools.Analysis found = MandelaTools.analyse(BROKEN, "broken.mnd",
                "/src/broken.mnd", 0);
        assertEquals(List.of("x : let"), found.structure().stream()
                .map(StructureSymbol::display).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("analyse carries the candidates for the caret it was given")
    void analyseCarriesCompletions() {
        MandelaTools.Analysis found = MandelaTools.analyse(TYPING, "t.mnd",
                "/src/t.mnd", TYPING.length());
        assertTrue(found.completions().contains("length"), found.completions().toString());
    }

    @Test
    @DisplayName("analysis compiles the buffer but never runs it")
    void analysisNeverRunsTheProgram() {
        // RUNTIME_BOMB dies at run time; if analysis executed it, the pass would
        // either throw or report an error. Both panels must stay quiet.
        MandelaTools.Analysis found = MandelaTools.analyse(RUNTIME_BOMB, "bomb.mnd",
                "/src/bomb.mnd", 0);
        assertTrue(found.diagnostics().isEmpty(), found.diagnostics().toString());
        assertEquals(List.of("xs : let"), found.structure().stream()
                .map(StructureSymbol::display).collect(Collectors.toList()));
    }

    // -- wiring: the three panels and the gates ------------------------------

    /** Records everything the editor side of the seam is handed. */
    private static final class Capture {
        final List<List<Diagnostic>> reports = new ArrayList<>();
        final List<List<StructureSymbol>> structures = new ArrayList<>();
        final List<List<String>> completions = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final Map<String, String> outputs = new LinkedHashMap<>();

        EditorContext ctx(TextEditorPermission... granted) {
            EditorSinks sinks = EditorSinks.builder()
                    .showMessage(messages::add)
                    .showOutput((title, body) -> outputs.put(title, body))
                    .reportDiagnostics((path, diags) -> reports.add(diags))
                    .showStructure((path, syms) -> structures.add(syms))
                    .showCompletions((path, cands) -> completions.add(cands))
                    .build();
            Set<TextEditorPermission> grantedSet = EnumSet.noneOf(TextEditorPermission.class);
            grantedSet.addAll(List.of(granted));
            return new EditorContext(grantedSet, sinks);
        }

        String lastMessage() {
            return messages.isEmpty() ? "<none>" : messages.get(messages.size() - 1);
        }

        boolean sawMessage(String needle) {
            return messages.stream().anyMatch(m -> m.contains(needle));
        }
    }

    private static DocumentContext doc(String fileName, String text) {
        return doc(fileName, text, s -> { });
    }

    private static DocumentContext doc(String fileName, String text,
                                       Consumer<String> writer) {
        return new DocumentContext("/src/" + fileName, fileName, text, "",
                text.length(), 1, 1, text.length(), text.length(), writer, s -> { });
    }

    /** An extension wired to a capturing editor and a same-thread scheduler. */
    private static MandelaTools wired(Capture cap, TextEditorPermission... granted) {
        MandelaTools ext = new MandelaTools();
        ext.setSchedulerForTesting(MandelaTools.syncScheduler());
        ext.onEditorStarted(cap.ctx(granted));
        return ext;
    }

    private static final Set<TextEditorPermission> ALL_GRANTS = EnumSet.of(
            TextEditorPermission.READ, TextEditorPermission.WRITE,
            TextEditorPermission.FILE_IO, TextEditorPermission.TOOLBAR,
            TextEditorPermission.DIAGNOSE);

    @Test
    @DisplayName("opening a Mandela document fills Problems, Structure and completion")
    void openedDocumentFillsThreePanels() {
        Capture cap = new Capture();
        MandelaTools ext = wired(cap, ALL_GRANTS.toArray(new TextEditorPermission[0]));
        ext.onDocumentOpened(doc("broken.mnd", BROKEN));

        assertEquals(1, cap.reports.size());
        assertEquals(Kind.ERROR, cap.reports.get(0).get(0).kind());
        assertEquals(1, cap.structures.size());
        assertEquals("x", cap.structures.get(0).get(0).name());
        assertEquals(1, cap.completions.size());
    }

    @Test
    @DisplayName("a foreign document clears all three panels")
    void foreignDocumentClears() {
        Capture cap = new Capture();
        MandelaTools ext = wired(cap, ALL_GRANTS.toArray(new TextEditorPermission[0]));
        ext.onDocumentOpened(doc("notes.md", "# Mandela\nnotes about the language"));

        assertEquals(1, cap.reports.size());
        assertTrue(cap.reports.get(0).isEmpty(), "must clear, not stick");
        assertEquals(1, cap.structures.size());
        assertTrue(cap.structures.get(0).isEmpty());
        assertEquals(1, cap.completions.size());
        assertTrue(cap.completions.get(0).isEmpty());
    }

    @Test
    @DisplayName("a failed analysis clears the panels instead of painting stale work")
    void nullAnalysisClears() {
        Capture cap = new Capture();
        MandelaTools ext = new MandelaTools();
        // Deliver a null result the way the production scheduler does when the
        // computation threw.
        ext.setSchedulerForTesting((compute, onEdt) -> onEdt.accept(null));
        ext.onEditorStarted(cap.ctx(ALL_GRANTS.toArray(new TextEditorPermission[0])));
        ext.onDocumentOpened(doc("a.mnd", GOOD));

        assertEquals(1, cap.reports.size());
        assertTrue(cap.reports.get(0).isEmpty());
        assertTrue(cap.structures.get(0).isEmpty());
        assertTrue(cap.completions.get(0).isEmpty());
    }

    @Test
    @DisplayName("a superseded analysis is dropped by the generation guard")
    void staleAnalysisIsDropped() {
        Capture cap = new Capture();
        AtomicReference<Consumer<MandelaTools.Analysis>> pending = new AtomicReference<>();
        MandelaTools ext = new MandelaTools();
        ext.setSchedulerForTesting((Supplier<MandelaTools.Analysis> compute,
                                    Consumer<MandelaTools.Analysis> onEdt) -> {
            compute.get();      // the analysis itself is synchronous; only delivery waits
            pending.set(onEdt);
        });
        ext.onEditorStarted(cap.ctx(ALL_GRANTS.toArray(new TextEditorPermission[0])));

        ext.onDocumentChanged(doc("a.mnd", BROKEN));                 // gen 1
        Consumer<MandelaTools.Analysis> stale = pending.get();
        ext.onDocumentChanged(doc("a.mnd", GOOD));                   // gen 2
        stale.accept(MandelaTools.Analysis.EMPTY);
        assertTrue(cap.reports.isEmpty(), "the stale generation must not paint");

        pending.get().accept(MandelaTools.analyse(GOOD, "a.mnd", "/src/a.mnd", 0));
        assertEquals(1, cap.reports.size());
        assertEquals(4, cap.structures.get(0).size());
    }

    @Test
    @DisplayName("each panel only fills when its permission was granted")
    void permissionGatesDecideWhatPaints() {
        Capture diagnoseOnly = new Capture();
        MandelaTools a = new MandelaTools();
        a.setSchedulerForTesting(MandelaTools.syncScheduler());
        a.onEditorStarted(diagnoseOnly.ctx(TextEditorPermission.DIAGNOSE));
        a.onDocumentOpened(doc("a.mnd", BROKEN));
        assertEquals(1, diagnoseOnly.reports.size());
        assertEquals(1, diagnoseOnly.structures.size());
        assertTrue(diagnoseOnly.completions.isEmpty(),
                "completion needs READ and must not reach the host without it");

        Capture readOnly = new Capture();
        MandelaTools b = new MandelaTools();
        b.setSchedulerForTesting(MandelaTools.syncScheduler());
        b.onEditorStarted(readOnly.ctx(TextEditorPermission.READ));
        b.onDocumentOpened(doc("a.mnd", BROKEN));
        assertTrue(readOnly.reports.isEmpty(), "DIAGNOSE gates the Problems panel");
        assertTrue(readOnly.structures.isEmpty());
        assertEquals(1, readOnly.completions.size());
    }

    @Test
    @DisplayName("document hooks before the editor starts are inert, not fatal")
    void hooksBeforeStartupAreSafe() {
        MandelaTools ext = new MandelaTools();
        ext.onDocumentOpened(doc("a.mnd", BROKEN));
        ext.onDocumentChanged(doc("a.mnd", BROKEN));
        ext.onDocumentSaved(doc("a.mnd", BROKEN));
        ext.onEditorStopping(null);
        // No editor means no sink to prove it on; reaching here is the assertion.
    }

    // -- identity ------------------------------------------------------------

    @Test
    @DisplayName("the manifest asks for exactly what the actions use")
    void manifestDeclaresItsNeeds() {
        MandelaTools ext = new MandelaTools();
        TextEditorManifest m = ext.manifest();
        assertEquals(MandelaTools.ID, m.getId());
        assertEquals("Mandela", m.getName());
        assertEquals("1.0.0", m.getVersion());
        assertEquals("Project Looking Glass", m.getAuthor());
        assertTrue(m.getDescription().contains(".mnd"), m.getDescription());
        assertEquals(ALL_GRANTS, m.getPermissions());
        assertEquals("Mandela", ext.category());
    }

    @Test
    @DisplayName("the services file registers the extension for ServiceLoader")
    void registeredAsAService() {
        List<String> ids = new ArrayList<>();
        for (TextEditorExtension ext : ServiceLoader.load(TextEditorExtension.class)) {
            ids.add(ext.manifest().getId());
        }
        assertTrue(ids.contains(MandelaTools.ID),
                "MandelaTools must be discoverable, saw " + ids);
    }

    @Test
    @DisplayName("four contributions, distinct ids, no accelerator reused")
    void toolbarContributionsAreDistinct() {
        List<ToolbarContribution> bars = new MandelaTools().toolbarContributions();
        assertEquals(4, bars.size());
        assertEquals(List.of("mandela-run", "mandela-check", "mandela-bytecode",
                "mandela-new"), bars.stream().map(ToolbarContribution::getId)
                .collect(Collectors.toList()));
        for (ToolbarContribution c : bars) {
            assertNotNull(c.getAction(), c.getId());
            assertFalse(c.getLabel().isBlank(), c.getId());
            assertFalse(c.getTooltip().isBlank(), c.getId());
        }
        List<String> keys = bars.stream().map(ToolbarContribution::getAccelerator)
                .filter(k -> !k.isEmpty()).collect(Collectors.toList());
        assertEquals(3, keys.size(), "the template action is deliberately unbound");
        assertEquals(keys.stream().distinct().count(), keys.size(),
                "two actions must not claim one shortcut");
        assertTrue(keys.contains("control alt shift M"));
        assertTrue(keys.contains("control alt shift A"));
        assertTrue(keys.contains("control alt shift B"));
    }

    // -- toolbar actions -----------------------------------------------------

    private static Runnable action(MandelaTools ext, String id) {
        return ext.toolbarContributions().stream()
                .filter(c -> c.getId().equals(id))
                .findFirst().orElseThrow().getAction();
    }

    @Test
    @DisplayName("Run hands the captured console to the Output panel")
    void runActionPaintsOutput() {
        Capture cap = new Capture();
        MandelaTools ext = wired(cap, ALL_GRANTS.toArray(new TextEditorPermission[0]));
        AtomicInteger runs = new AtomicInteger();
        ext.setRunnerForTesting((source, name, done) -> {
            runs.incrementAndGet();
            assertEquals(GOOD, source);
            assertEquals("counter.mnd", name);
            done.accept(new MandelaTools.RunResult("1\n", ""));
        });
        ext.onDocumentOpened(doc("counter.mnd", GOOD));
        action(ext, "mandela-run").run();

        assertEquals(1, runs.get());
        assertEquals("1\n", cap.outputs.get("mandela output"));
        assertTrue(cap.sawMessage("finished"), cap.lastMessage());
    }

    @Test
    @DisplayName("Run refuses a foreign buffer and an empty one without spawning work")
    void runActionRefusesForeignBuffers() {
        Capture cap = new Capture();
        MandelaTools ext = wired(cap, ALL_GRANTS.toArray(new TextEditorPermission[0]));
        AtomicInteger runs = new AtomicInteger();
        ext.setRunnerForTesting((source, name, done) -> runs.incrementAndGet());

        ext.onDocumentOpened(doc("notes.txt", "println(1)"));
        action(ext, "mandela-run").run();
        assertTrue(cap.sawMessage("not a Mandela script"), cap.lastMessage());

        ext.onDocumentOpened(doc("empty.mnd", "   \n"));
        action(ext, "mandela-run").run();
        assertTrue(cap.sawMessage("the document is empty"), cap.lastMessage());

        assertEquals(0, runs.get(), "nothing may be executed in either case");
        assertTrue(cap.outputs.isEmpty(), cap.outputs.keySet().toString());
    }

    @Test
    @DisplayName("the run message names a failing script, not a success")
    void runActionReportsAFailure() {
        Capture cap = new Capture();
        MandelaTools ext = wired(cap, ALL_GRANTS.toArray(new TextEditorPermission[0]));
        ext.setRunnerForTesting((source, name, done) ->
                done.accept(new MandelaTools.RunResult("before\n", "IndexError: boom\n")));
        ext.onDocumentOpened(doc("bomb.mnd", RUNTIME_BOMB));
        action(ext, "mandela-run").run();

        assertEquals("before\nIndexError: boom\n", cap.outputs.get("mandela output"));
        assertTrue(cap.sawMessage("stopped"), cap.lastMessage());
    }

    @Test
    @DisplayName("Check says how many problems it put in the panel")
    void checkActionCountsWhatItPaints() {
        Capture cap = new Capture();
        MandelaTools ext = wired(cap, ALL_GRANTS.toArray(new TextEditorPermission[0]));
        ext.onDocumentOpened(doc("broken.mnd", BROKEN));
        action(ext, "mandela-check").run();
        // Opening the buffer already refreshed the panel once; Check paints it a
        // second time and puts the same count on the status line the user sees.
        assertEquals(2, cap.reports.size());
        assertEquals(1, cap.reports.get(1).size());
        assertTrue(cap.sawMessage("1 problem(s)"), cap.lastMessage());

        ext.onDocumentOpened(doc("good.mnd", GOOD));
        action(ext, "mandela-check").run();
        assertTrue(cap.sawMessage("no problems found"), cap.lastMessage());

        ext.onDocumentOpened(doc("notes.txt", "x"));
        action(ext, "mandela-check").run();
        assertTrue(cap.sawMessage("not a Mandela script"), cap.lastMessage());
    }

    @Test
    @DisplayName("Bytecode dumps the instruction stream and says so on a broken buffer")
    void bytecodeActionDumpsOrExplains() {
        Capture cap = new Capture();
        MandelaTools ext = wired(cap, ALL_GRANTS.toArray(new TextEditorPermission[0]));
        ext.onDocumentOpened(doc("counter.mnd", GOOD));
        action(ext, "mandela-bytecode").run();
        String dump = cap.outputs.get("mandela bytecode output");
        assertNotNull(dump);
        assertTrue(dump.contains("DEFINE_CLASS"), dump);
        assertTrue(cap.sawMessage("Output panel"), cap.lastMessage());

        cap.outputs.clear();
        ext.onDocumentOpened(doc("broken.mnd", BROKEN));
        action(ext, "mandela-bytecode").run();
        assertTrue(cap.sawMessage("does not compile"), cap.lastMessage());
        assertTrue(String.valueOf(cap.outputs.get("mandela bytecode output"))
                .contains("expected an end of statement"));
    }

    @Test
    @DisplayName("the template goes into an empty buffer, and only with WRITE")
    void templateNeedsWriteAndAnEmptyBuffer() {
        Capture cap = new Capture();
        StringBuilder written = new StringBuilder();
        MandelaTools ext = wired(cap, ALL_GRANTS.toArray(new TextEditorPermission[0]));
        ext.onDocumentOpened(doc("new.mnd", "", written::append));
        action(ext, "mandela-new").run();

        assertTrue(written.length() > 0, "the buffer should have received the script");
        assertTrue(written.toString().contains("Hello from Mandela"), written.toString());
        assertTrue(cap.sawMessage("starter script inserted"), cap.lastMessage());

        // Without WRITE a gated call is a silent no-op, so the text goes to the
        // Output panel instead of a success message the user could not verify.
        Capture gated = new Capture();
        MandelaTools ro = wired(gated, TextEditorPermission.READ,
                TextEditorPermission.FILE_IO, TextEditorPermission.TOOLBAR,
                TextEditorPermission.DIAGNOSE);
        StringBuilder untouched = new StringBuilder();
        ro.onDocumentOpened(doc("new.mnd", "", untouched::append));
        action(ro, "mandela-new").run();
        assertEquals(0, untouched.length(), "no WRITE grant means no buffer write");
        assertTrue(String.valueOf(gated.outputs.get("mandela template output"))
                .contains("Hello from Mandela"));

        // A buffer with text in it is never overwritten, whatever the grants.
        Capture full = new Capture();
        MandelaTools third = wired(full, ALL_GRANTS.toArray(new TextEditorPermission[0]));
        StringBuilder clobbered = new StringBuilder();
        third.onDocumentOpened(doc("work.mnd", GOOD, clobbered::append));
        action(third, "mandela-new").run();
        assertEquals(0, clobbered.length(), "a buffer with text is off limits");
        assertTrue(full.sawMessage("only an empty document"), full.lastMessage());
    }

    @Test
    @DisplayName("actions with no editor and no document do nothing at all")
    void actionsWithoutAnEditorAreInert() {
        MandelaTools ext = new MandelaTools();
        // No editor was started and no document was opened: every action must
        // notice and return. Reaching the end of this test without an exception
        // is the assertion.
        ext.toolbarContributions().forEach(c -> c.getAction().run());
    }

    // -- the real in-JVM runner ---------------------------------------------

    @Test
    @DisplayName("consoleText joins output and failure on a line boundary")
    void consoleTextSeparatesTheTwoChannels() {
        assertEquals("", MandelaTools.consoleText(
                new MandelaTools.RunResult("", "")));
        assertEquals("only output\n", MandelaTools.consoleText(
                new MandelaTools.RunResult("only output\n", "")));
        assertEquals("only failure\n", MandelaTools.consoleText(
                new MandelaTools.RunResult("", "only failure\n")));
        assertEquals("printed\nbleeding\n", MandelaTools.consoleText(
                new MandelaTools.RunResult("printed", "bleeding\n")));
        assertEquals("printed\nbleeding\n", MandelaTools.consoleText(
                new MandelaTools.RunResult("printed\n", "bleeding\n")));
    }

    @Test
    @DisplayName("runInProcess prints a good program and reports no failure")
    void runInProcessExecutes() {
        MandelaTools.RunResult r = MandelaTools.runInProcess(GOOD, "counter.mnd");
        assertFalse(r.failed(), r.failure());
        assertEquals("1\n", r.output());
        assertEquals("", r.failure());
    }

    @Test
    @DisplayName("runInProcess keeps the half-output a failing program printed")
    void runInProcessKeepsPartialOutput() {
        MandelaTools.RunResult r = MandelaTools.runInProcess(RUNTIME_BOMB, "bomb.mnd");
        assertTrue(r.failed());
        assertEquals("before\n", r.output());
        assertTrue(r.failure().contains("index 9"), r.failure());
        assertFalse(r.output().contains("after"), "the program stopped at line 3");
    }

    @Test
    @DisplayName("a compile error is a failure with nothing printed")
    void runInProcessReportsCompileErrors() {
        MandelaTools.RunResult r = MandelaTools.runInProcess(BROKEN, "broken.mnd");
        assertTrue(r.failed());
        assertEquals("", r.output());
        assertTrue(r.failure().contains("broken.mnd:2:4"), r.failure());
    }

    @Test
    @DisplayName("the sandbox denies the filesystem to a run buffer")
    void runInProcessIsSandboxed() {
        MandelaTools.RunResult r = MandelaTools.runInProcess(
                "use std.fs\nprintln(fs.read(\"/etc/hostname\"))\n", "snoop.mnd");
        assertTrue(r.failed());
        assertEquals("", r.output(), "no host file may reach the Output panel");
    }

    @Test
    @DisplayName("a runaway loop is cut off by the instruction budget")
    void runInProcessIsBounded() {
        // Without a budget this test would hang the suite; with one it returns in
        // well under a second. That is what keeps the Run button safe on the EDT.
        MandelaTools.RunResult r = MandelaTools.runInProcess(
                "var i = 0\nloop {\n  i = i + 1\n}\n", "spin.mnd");
        assertTrue(r.failed());
        assertTrue(r.failure().contains("instructions"), r.failure());
    }

    @Test
    @DisplayName("each run starts from a clean global table")
    void runsDoNotInheritGlobals() {
        MandelaTools.runInProcess("let secret = 42\n", "a.mnd");
        MandelaTools.RunResult second = MandelaTools.runInProcess(
                "println(secret)\n", "b.mnd");
        assertTrue(second.failed(), "the previous run's bindings must not leak");
    }
}
