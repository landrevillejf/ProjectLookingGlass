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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Headless tests for {@link JavaDebugTools} (Phase 3): the whole compile ->
 * launch -> suspend -> step -> stop state machine is driven through the
 * {@link JavaDebugTools.Backend}, compile-runner, deliverer and async-runner
 * seams — no JVM is ever spawned here. The real JDI attach path is covered by
 * one opt-in integration test ({@code -Dlg3d.debug.integration=true}).
 */
class JavaDebugToolsTest {

    private static final String SRC = String.join("\n",
            "public class Widget {",     // 1
            "    static int x = 1;",      // 2
            "    public static void main(String[] args) {", // 3
            "        x++;",               // 4
            "    }",                      // 5
            "}");                         // 6

    // -- fakes ----------------------------------------------------------------

    /** Records the launch and hands back a scripted session + live listener. */
    private static final class FakeBackend implements JavaDebugTools.Backend {
        Path classesDir;
        String mainClass;
        int port;
        List<Integer> lines = List.of();
        String sourceFile;
        JavaDebugTools.SessionListener listener;
        FakeSession session = new FakeSession();
        IOException launchFailure;

        @Override
        public JavaDebugTools.Session launch(Path classesDir, String mainClass, int port,
                                             List<Integer> breakpointLines, String sourceFile,
                                             JavaDebugTools.SessionListener listener)
                throws IOException {
            if (launchFailure != null) {
                throw launchFailure;
            }
            this.classesDir = classesDir;
            this.mainClass = mainClass;
            this.port = port;
            this.lines = List.copyOf(breakpointLines);
            this.sourceFile = sourceFile;
            this.listener = listener;
            return session;
        }
    }

    private static final class FakeSession implements JavaDebugTools.Session {
        int resumes;
        int stops;
        JavaDebugTools.StepDepth lastStep;
        boolean alive = true;

        @Override
        public void resume() {
            resumes++;
        }

        @Override
        public void step(JavaDebugTools.StepDepth depth) {
            lastStep = depth;
        }

        @Override
        public void stop() {
            stops++;
            alive = false;
        }

        @Override
        public boolean alive() {
            return alive;
        }
    }

    /** Captures every editor surface the debugger drives. */
    private static final class Harness {
        final List<String> messages = new ArrayList<>();
        final List<String> outputs = new ArrayList<>();      // "title\ntext"
        final List<String> debugStates = new ArrayList<>();
        final List<String> appended = new ArrayList<>();
        final List<List<String>> stacks = new ArrayList<>();
        final List<List<String>> locals = new ArrayList<>();
        final List<List<String>> breakpoints = new ArrayList<>();
        final List<String> navigations = new ArrayList<>();
        List<Integer> gutterLines = List.of();

        EditorContext ctx(TextEditorPermission... perms) {
            Set<TextEditorPermission> granted =
                    (perms.length == 0) ? EnumSet.noneOf(TextEditorPermission.class)
                            : EnumSet.copyOf(List.of(perms));
            EditorSinks sinks = EditorSinks.builder()
                    .showMessage(messages::add)
                    .showOutput((t, b) -> outputs.add(t + "\n" + b))
                    .setDebugState(debugStates::add)
                    .appendDebugOutput(appended::add)
                    .showStack(stacks::add)
                    .showLocals(locals::add)
                    .setBreakpoints(breakpoints::add)
                    .navigate((p, l) -> navigations.add(p + ":" + l))
                    .breakpointsFor(p -> gutterLines)
                    .build();
            return new EditorContext(granted, sinks);
        }
    }

    private static DocumentContext doc(String fileName, String text) {
        return new DocumentContext("/src/" + fileName, fileName, text, "",
                0, 1, 0, 0, 0, s -> { }, s -> { });
    }

    /** Fully wired extension: inline async + inline delivery + fake seams. */
    private static JavaDebugTools started(Harness h, FakeBackend backend,
                                          Toolchain.Result compile,
                                          TextEditorPermission... perms) {
        JavaDebugTools ext = new JavaDebugTools();
        ext.onEditorStarted(h.ctx(perms));
        ext.onDocumentChanged(doc("Widget.java", SRC));
        ext.setBackendForTesting(backend);
        ext.setCompileRunnerForTesting((cmd, dir, timeout) -> compile);
        ext.setDelivererForTesting(Runnable::run);
        ext.setAsyncRunnerForTesting(Runnable::run);
        return ext;
    }

    private static void run(JavaDebugTools ext, int actionIndex) {
        ext.toolbarContributions().get(actionIndex).getAction().run();
    }

    private static final TextEditorPermission[] ALL_PERMS = {
        TextEditorPermission.READ, TextEditorPermission.FILE_IO,
        TextEditorPermission.DEBUG, TextEditorPermission.TOOLBAR,
    };

    // -- state machine ----------------------------------------------------------

    @Test
    @DisplayName("start compiles, launches on loopback and reports the installed breakpoints")
    void startHappyPath() {
        Harness h = new Harness();
        h.gutterLines = List.of(2, 4);
        FakeBackend backend = new FakeBackend();
        JavaDebugTools ext = started(h, backend, new Toolchain.Result(0, ""), ALL_PERMS);

        run(ext, 0); // start

        assertEquals(JavaDebugTools.State.RUNNING, ext.state());
        assertEquals("Widget", backend.mainClass);
        assertEquals("Widget.java", backend.sourceFile);
        assertEquals(List.of(2, 4), backend.lines);
        assertTrue(backend.port > 0 && backend.port < 65536, "ephemeral port: " + backend.port);
        assertNotNull(backend.classesDir);
        assertTrue(h.breakpoints.get(h.breakpoints.size() - 1).contains("Widget.java:4"),
                h.breakpoints.toString());
        assertTrue(h.messages.stream().anyMatch(m -> m.contains("2 breakpoint(s)")),
                h.messages.toString());
    }

    @Test
    @DisplayName("a failed compile stops before launch and streams javac output")
    void compileFailureAborts() {
        Harness h = new Harness();
        FakeBackend backend = new FakeBackend();
        JavaDebugTools ext = started(h, backend,
                new Toolchain.Result(1, "cannot find symbol"), ALL_PERMS);

        run(ext, 0);

        assertEquals(JavaDebugTools.State.IDLE, ext.state());
        assertNull(backend.mainClass, "launch must never happen");
        assertTrue(h.outputs.stream().anyMatch(o -> o.startsWith("javac\n")), h.outputs.toString());
        assertTrue(h.messages.stream().anyMatch(m -> m.contains("Fix the compile errors")),
                h.messages.toString());
    }

    @Test
    @DisplayName("a launch failure detaches back to idle with the reason")
    void launchFailure() {
        Harness h = new Harness();
        FakeBackend backend = new FakeBackend();
        backend.launchFailure = new IOException("attach timed out");
        JavaDebugTools ext = started(h, backend, new Toolchain.Result(0, ""), ALL_PERMS);

        run(ext, 0);

        assertEquals(JavaDebugTools.State.IDLE, ext.state());
        assertTrue(h.appended.stream().anyMatch(a -> a.contains("attach timed out")),
                h.appended.toString());
    }

    @Test
    @DisplayName("starting again while a session is active is refused with the state")
    void doubleStartRefused() {
        Harness h = new Harness();
        JavaDebugTools ext = started(h, new FakeBackend(),
                new Toolchain.Result(0, ""), ALL_PERMS);
        run(ext, 0);
        h.messages.clear();
        run(ext, 0);
        assertTrue(h.messages.stream().anyMatch(m -> m.contains("already active (running)")),
                h.messages.toString());
    }

    @Test
    @DisplayName("non-Java documents are refused, Kotlin with its own message")
    void nonJavaGuards() {
        Harness h = new Harness();
        JavaDebugTools ext = started(h, new FakeBackend(),
                new Toolchain.Result(0, ""), ALL_PERMS);
        ext.onDocumentChanged(doc("notes.txt", "prose"));
        run(ext, 0);
        assertTrue(h.messages.stream().anyMatch(m -> m.contains("Not a Java file")),
                h.messages.toString());

        ext.onDocumentChanged(doc("App.kt", "fun main() {}"));
        run(ext, 0);
        assertTrue(h.messages.stream().anyMatch(m -> m.contains("Kotlin debugging")),
                h.messages.toString());
    }

    // -- commands ----------------------------------------------------------------

    @Test
    @DisplayName("resume, the three steps and stop drive the session in order")
    void commandsDriveSession() {
        Harness h = new Harness();
        FakeBackend backend = new FakeBackend();
        JavaDebugTools ext = started(h, backend, new Toolchain.Result(0, ""), ALL_PERMS);
        run(ext, 0);
        FakeSession session = backend.session;

        run(ext, 1); // resume
        assertEquals(1, session.resumes);
        assertEquals(JavaDebugTools.State.RUNNING, ext.state());

        run(ext, 2);
        assertEquals(JavaDebugTools.StepDepth.OVER, session.lastStep);
        run(ext, 3);
        assertEquals(JavaDebugTools.StepDepth.INTO, session.lastStep);
        run(ext, 4);
        assertEquals(JavaDebugTools.StepDepth.OUT, session.lastStep);

        run(ext, 5); // stop
        assertEquals(1, session.stops);
        assertEquals(JavaDebugTools.State.IDLE, ext.state());
        assertEquals(List.of(), h.breakpoints.get(h.breakpoints.size() - 1));
    }

    @Test
    @DisplayName("session commands without a live session say so instead of acting")
    void commandsWithoutSession() {
        Harness h = new Harness();
        JavaDebugTools ext = started(h, new FakeBackend(),
                new Toolchain.Result(0, ""), ALL_PERMS);
        run(ext, 1); // resume with nothing running
        assertTrue(h.messages.contains("No active debug session"), h.messages.toString());
        assertEquals(JavaDebugTools.State.IDLE, ext.state());
    }

    // -- listener plumbing ----------------------------------------------------------

    @Test
    @DisplayName("a suspend paints stack/locals, flushes output and jumps the caret")
    void suspendUpdatesChrome() {
        Harness h = new Harness();
        FakeBackend backend = new FakeBackend();
        JavaDebugTools ext = started(h, backend, new Toolchain.Result(0, ""), ALL_PERMS);
        run(ext, 0);

        backend.listener.onOutput("hello from the program");
        assertTrue(h.outputs.stream().noneMatch(o -> o.contains("debug target")),
                "output stays buffered until the pause");
        backend.listener.onSuspended("Widget.java", 4,
                List.of("Widget.main (Widget.java:4)"), List.of("args = null : String[]"));

        assertEquals(JavaDebugTools.State.PAUSED, ext.state());
        assertTrue(h.debugStates.get(h.debugStates.size() - 1).contains("Paused at Widget.java:4"),
                h.debugStates.toString());
        assertEquals(List.of("Widget.main (Widget.java:4)"),
                h.stacks.get(h.stacks.size() - 1));
        assertEquals(List.of("args = null : String[]"),
                h.locals.get(h.locals.size() - 1));
        assertTrue(h.outputs.stream().anyMatch(o -> o.contains("hello from the program")),
                "pause flushes the buffered target output");
        assertEquals(":4", h.navigations.get(h.navigations.size() - 1));
    }

    @Test
    @DisplayName("target output flushes early once 200 lines pile up")
    void outputFlushLimit() {
        Harness h = new Harness();
        FakeBackend backend = new FakeBackend();
        JavaDebugTools ext = started(h, backend, new Toolchain.Result(0, ""), ALL_PERMS);
        run(ext, 0);
        for (int i = 0; i < 201; i++) {
            backend.listener.onOutput("line-" + i);
        }
        assertTrue(h.outputs.stream().anyMatch(o -> o.contains("line-199")),
                "200th buffered line triggers a flush");
    }

    @Test
    @DisplayName("VM exit resets the chrome to idle")
    void exitResets() {
        Harness h = new Harness();
        FakeBackend backend = new FakeBackend();
        JavaDebugTools ext = started(h, backend, new Toolchain.Result(0, ""), ALL_PERMS);
        run(ext, 0);
        backend.listener.onExited("Program finished");

        assertEquals(JavaDebugTools.State.IDLE, ext.state());
        assertTrue(h.messages.stream().anyMatch(m -> m.contains("Program finished")),
                h.messages.toString());
        run(ext, 1);
        assertTrue(h.messages.contains("No active debug session"),
                "session is gone after exit: " + h.messages);
    }

    // -- permission gates -------------------------------------------------------------

    @Test
    @DisplayName("without DEBUG the state machine runs but no debug surface is painted")
    void debugSurfacesGated() {
        Harness h = new Harness();
        h.gutterLines = List.of(4);
        FakeBackend backend = new FakeBackend();
        JavaDebugTools ext = started(h, backend, new Toolchain.Result(0, ""),
                TextEditorPermission.READ, TextEditorPermission.FILE_IO,
                TextEditorPermission.TOOLBAR); // no DEBUG

        run(ext, 0);

        assertEquals(List.of(), backend.lines, "breakpoints are invisible without DEBUG");
        assertTrue(h.debugStates.isEmpty(), h.debugStates.toString());
        assertTrue(h.appended.isEmpty(), h.appended.toString());
        assertTrue(h.breakpoints.isEmpty(), h.breakpoints.toString());
    }

    @Test
    @DisplayName("the manifest declares the debugger and only the permissions it uses")
    void manifestShape() {
        JavaDebugTools ext = new JavaDebugTools();
        assertEquals("lg3d.java-debug", ext.manifest().getId());
        assertEquals("Java/Kotlin", ext.category());
        assertTrue(ext.manifest().getPermissions().contains(TextEditorPermission.DEBUG));
        assertEquals(6, ext.toolbarContributions().size());
    }

    // -- pure builders -----------------------------------------------------------------

    @Test
    @DisplayName("debugLaunchArgs pins the loopback-only suspend=y command line")
    void launchArgs() {
        List<String> args = JavaDebugTools.debugLaunchArgs(
                Path.of("/jdk/bin/java"), Path.of("/tmp/classes"), "Widget", 12345);
        assertEquals("/jdk/bin/java", args.get(0));
        assertEquals("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,"
                + "address=127.0.0.1:12345", args.get(1));
        assertEquals("-ea", args.get(2));
        assertEquals("Widget", args.get(args.size() - 1));
    }

    @Test
    @DisplayName("reserveLoopbackPort hands out a real ephemeral port")
    void reservePort() throws Exception {
        int port = JavaDebugTools.reserveLoopbackPort();
        assertTrue(port > 0 && port < 65536, "port=" + port);
    }

    @Test
    @DisplayName("every state has a lowercase label")
    void stateLabels() {
        assertEquals("idle", JavaDebugTools.label(JavaDebugTools.State.IDLE));
        assertEquals("compiling", JavaDebugTools.label(JavaDebugTools.State.COMPILING));
        assertEquals("paused", JavaDebugTools.label(JavaDebugTools.State.PAUSED));
    }

    // -- opt-in real JDI integration -----------------------------------------------------

    @Test
    @DisplayName("the real backend attaches, runs to completion and reports stdout")
    @EnabledIfSystemProperty(named = "lg3d.debug.integration", matches = "true")
    void jdiEndToEnd() throws Exception {
        Path work = Files.createTempDirectory("lg3d-jdi-");
        Path source = work.resolve("Hello.java");
        Files.writeString(source,
                "public class Hello { public static void main(String[] a) "
                        + "{ System.out.println(\"hi from jdi\"); } }",
                StandardCharsets.UTF_8);
        var javac = Toolchain.jdkTool("javac").orElseThrow();
        Toolchain.Result compiled = Toolchain.execute(
                Toolchain.javacCommand(javac, source, work.resolve("classes")),
                work, 60_000);
        assertEquals(0, compiled.exitCode(), compiled.output());

        CountDownLatch printed = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        JavaDebugTools.SessionListener listener = new JavaDebugTools.SessionListener() {
            @Override
            public void onSuspended(String s, int l, List<String> f, List<String> v) { }

            @Override
            public void onResumed() { }

            @Override
            public void onOutput(String line) {
                if (line.contains("hi from jdi")) {
                    printed.countDown();
                }
            }

            @Override
            public void onExited(String why) {
                finished.countDown();
            }
        };
        int port = JavaDebugTools.reserveLoopbackPort();
        JavaDebugTools.Session session = new JdiDebugBackend().launch(
                work.resolve("classes"), "Hello", port, List.of(), "Hello.java", listener);
        try {
            assertTrue(finished.await(30, TimeUnit.SECONDS), "VM did not exit in time");
            assertEquals(0, printed.getCount(), "stdout never reached the listener");
        } finally {
            session.stop();
        }
    }
}
