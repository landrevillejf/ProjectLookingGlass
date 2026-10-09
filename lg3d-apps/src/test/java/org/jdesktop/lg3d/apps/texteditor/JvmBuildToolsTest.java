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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link JvmBuildTools} and its {@link Toolchain} helper:
 * the pure command builders, source inspection, stack-trace parsing and
 * output-block editing, plus every toolbar action driven through a
 * synchronous fake {@code Runner} so no real process is ever spawned.
 */
class JvmBuildToolsTest {

    // -- Toolchain: pure command builders ---------------------------------

    @Test
    @DisplayName("command builders emit the exact argv for javac/java/kotlinc/kotlin")
    void commandBuilders() {
        Path javac = Path.of("/opt/jdk/bin/javac");
        Path java = Path.of("/opt/jdk/bin/java");
        Path src = Path.of("/t/Foo.java");
        Path out = Path.of("/t/classes");
        assertEquals(List.of("/opt/jdk/bin/javac", "-d", "/t/classes", "/t/Foo.java"),
                Toolchain.javacCommand(javac, src, out));
        assertEquals(List.of("/opt/jdk/bin/java", "/t/Foo.java"),
                Toolchain.sourceRunCommand(java, src, false));
        assertEquals(List.of("/opt/jdk/bin/java", "-ea", "/t/Foo.java"),
                Toolchain.sourceRunCommand(java, src, true));
        assertEquals(List.of("/usr/bin/kotlinc", "/t/Foo.kt", "-d", "/t/classes"),
                Toolchain.kotlincCommand(Path.of("/usr/bin/kotlinc"), Path.of("/t/Foo.kt"), out));
        assertEquals(List.of("/usr/bin/kotlin", "-cp", "/t/classes", "FooKt"),
                Toolchain.kotlinRunCommand(Path.of("/usr/bin/kotlin"), out, "FooKt"));
    }

    // -- Toolchain: source inspection --------------------------------------

    @Test
    @DisplayName("deriveClassName finds the first declared Java type")
    void deriveClassName() {
        assertEquals("Foo", Toolchain.deriveClassName("public class Foo { }"));
        assertEquals("Bar", Toolchain.deriveClassName("public final class Bar implements Baz {"));
        assertEquals("Point", Toolchain.deriveClassName("record Point(int x, int y) { }"));
        assertEquals("Suit", Toolchain.deriveClassName("public enum Suit { HEARTS }"));
        assertEquals("Greeter", Toolchain.deriveClassName("public interface Greeter { }"));
        assertNull(Toolchain.deriveClassName("fun main() { println(1) }"));
        assertNull(Toolchain.deriveClassName(null));
    }

    @Test
    @DisplayName("kotlinMainClass follows the Foo.kt -> FooKt file-class rule")
    void kotlinMainClass() {
        assertEquals("FooKt", Toolchain.kotlinMainClass("Foo.kt"));
        assertEquals("MainKt", Toolchain.kotlinMainClass("Main.kts"));
        assertEquals("MyAppKt", Toolchain.kotlinMainClass("My App.kt"));
        assertEquals("MainKt", Toolchain.kotlinMainClass(""));
        assertEquals("MainKt", Toolchain.kotlinMainClass(null));
    }

    @Test
    @DisplayName("stack-trace parsing reports the user file line and exception")
    void frameAndExceptionParsing() {
        String trace = "Exception in thread \"main\" java.lang.IllegalStateException: boom\n"
                + "\tat Foo.main(Foo.java:42)";
        assertEquals(42, Toolchain.firstFrameLine("Foo.java", trace).getAsInt());
        assertEquals(7, Toolchain.firstFrameLine("Foo.java",
                "at com.acme.Foo.run(Foo.java:7)").getAsInt());
        assertFalse(Toolchain.firstFrameLine("Foo.java", "at Bar.run(Bar.java:3)").isPresent());
        assertFalse(Toolchain.firstFrameLine(null, "x").isPresent());
        assertEquals("java.lang.IllegalStateException: boom",
                Toolchain.firstExceptionMessage("counting\n" + trace));
        assertEquals("", Toolchain.firstExceptionMessage("just output"));
    }

    // -- Toolchain: output blocks ------------------------------------------

    @Test
    @DisplayName("withTrailingBlock appends, replaces and truncates; remove strips")
    void outputBlocks() {
        String src = "b\na\n";
        String once = Toolchain.withTrailingBlock(src, "javac", "error: ; expected", 200);
        assertEquals("b\na\n\n// ---- javac output ----\n//   error: ; expected\n", once);
        String twice = Toolchain.withTrailingBlock(once, "java", "hi\nthere", 200);
        assertEquals("b\na\n\n// ---- java output ----\n//   hi\n//   there\n", twice);
        assertEquals("b\na\n", Toolchain.removeTrailingBlock(twice));
        assertEquals(src, Toolchain.removeTrailingBlock(src)); // no block -> unchanged
        String capped = Toolchain.withTrailingBlock(src, "java", "1\n2\n3\n4\n5", 3);
        assertTrue(capped.contains("//   1\n"), capped);
        assertFalse(capped.contains("//   4\n"), capped);
        assertTrue(capped.contains("... 2 more line(s)"), capped);
    }

    @Test
    @DisplayName("findOnPath locates an executable and skips misses")
    void findOnPath(@TempDir Path dir) throws Exception {
        Path fake = dir.resolve("kotlinc");
        Files.writeString(fake, "#!/bin/sh\n");
        fake.toFile().setExecutable(true);
        assertEquals(Optional.of(fake),
                Toolchain.findOnPath("kotlinc", "nonexistent:" + dir + ":other"));
        assertTrue(Toolchain.findOnPath("nope", dir.toString()).isEmpty());
        assertTrue(Toolchain.findOnPath("kotlinc", null).isEmpty());
        // A directory named like the tool is not a match.
        assertTrue(Toolchain.findOnPath(dir.getFileName().toString(),
                dir.getParent().toString()).isEmpty());
    }

    @Test
    @DisplayName("the running JDK carries javac and java for the build actions")
    void jdkToolsPresent() {
        assertTrue(Toolchain.jdkTool("javac").isPresent());
        assertTrue(Toolchain.jdkTool("java").isPresent());
        assertTrue(Toolchain.jdkTool("no-such-tool").isEmpty());
    }

    // -- Actions through the synchronous Runner seam ------------------------

    /** Records every command and delivers queued results synchronously. */
    private static final class FakeRunner implements JvmBuildTools.Runner {
        final List<List<String>> commands = new ArrayList<>();
        final Deque<Toolchain.Result> replies = new ArrayDeque<>();
        Toolchain.Result fallback = new Toolchain.Result(0, "");

        @Override
        public void run(List<String> command, Path workDir, long timeoutMs,
                        java.util.function.Consumer<Toolchain.Result> done) {
            commands.add(command);
            Toolchain.Result r = replies.isEmpty() ? fallback : replies.poll();
            done.accept(r);
            assertFalse(command.isEmpty());
            assertTrue(Files.isDirectory(workDir), "commands run inside the staging dir");
        }
    }

    private List<String> msgs;
    private String[] doc;
    private FakeRunner runner;

    private JvmBuildTools wire(String initialText) {
        JvmBuildTools tools = new JvmBuildTools();
        msgs = new ArrayList<>();
        doc = new String[] {initialText};
        tools.onEditorStarted(new EditorContext(
                EnumSet.allOf(TextEditorPermission.class), msgs::add, () -> { }, () -> { }));
        tools.onDocumentOpened(new DocumentContext(null, null, doc[0], "",
                s -> doc[0] = s, s -> { }));
        runner = new FakeRunner();
        tools.setRunnerForTesting(runner);
        return tools;
    }

    private void act(JvmBuildTools tools, int action) {
        tools.toolbarContributions().get(action).getAction().run();
    }

    @Test
    @DisplayName("compile failure reports status and appends the error block")
    void compileJavaFailure() {
        JvmBuildTools tools = wire("public class Foo { int x = ; }\n");
        runner.fallback = new Toolchain.Result(1, "Foo.java:1: error: ';' expected");
        act(tools, 0);
        assertEquals(1, runner.commands.size());
        assertTrue(runner.commands.get(0).get(0).endsWith("javac"));
        assertTrue(doc[0].contains("// ---- javac output ----"), doc[0]);
        assertTrue(doc[0].contains("//   Foo.java:1: error: ';' expected"), doc[0]);
        assertTrue(msgs.stream().anyMatch(m -> m.contains("failed (exit 1)")), msgs.toString());
    }

    @Test
    @DisplayName("compile success is status-only and never touches the document")
    void compileJavaSuccess() {
        JvmBuildTools tools = wire("public class Foo { }\n");
        act(tools, 0);
        assertEquals("public class Foo { }\n", doc[0]);
        assertTrue(msgs.contains("Foo compiled — OK"), msgs.toString());
    }

    @Test
    @DisplayName("run java captures program output into the block")
    void runJava() {
        JvmBuildTools tools = wire("public class Foo { public static void main(String[] a) { } }\n");
        runner.fallback = new Toolchain.Result(0, "hello from main");
        act(tools, 1);
        assertTrue(doc[0].contains("// ---- java output ----\n//   hello from main\n"), doc[0]);
        assertTrue(msgs.stream().anyMatch(m -> m.contains("exited 0")), msgs.toString());
    }

    @Test
    @DisplayName("debug java reports the exception and the user line")
    void debugJavaException() {
        JvmBuildTools tools = wire("public class Foo { }\n");
        runner.fallback = new Toolchain.Result(1,
                "Exception in thread \"main\" java.lang.IllegalStateException: boom\n"
                        + "\tat Foo.main(Foo.java:42)");
        act(tools, 2);
        assertTrue(msgs.stream().anyMatch(m -> m.contains("java.lang.IllegalStateException: boom")
                && m.contains("Foo.java:42")), msgs.toString());
        assertTrue(doc[0].contains("// ---- java debug output ----"), doc[0]);
    }

    @Test
    @DisplayName("a clean debug run says so and leaves the document alone")
    void debugJavaClean() {
        JvmBuildTools tools = wire("public class Foo { public static void main(String[] a) { } }\n");
        act(tools, 2);
        assertEquals("public class Foo { public static void main(String[] a) { } }\n", doc[0]);
        assertTrue(msgs.stream().anyMatch(m -> m.contains("Debug run clean")), msgs.toString());
    }

    @Test
    @DisplayName("an empty document or a missing class never spawns a process")
    void guards() {
        JvmBuildTools tools = wire("");
        act(tools, 0);
        assertEquals(0, runner.commands.size());
        assertTrue(msgs.stream().anyMatch(m -> m.contains("the document is empty")),
                msgs.toString());

        JvmBuildTools tools2 = wire("fun main() { }\n");
        FakeRunner r2 = new FakeRunner();
        tools2.setRunnerForTesting(r2);
        act(tools2, 0); // Compile Java on a class-less document
        assertEquals(0, r2.commands.size());
        assertTrue(msgs.stream().anyMatch(m -> m.contains("no class")), msgs.toString());
    }

    @Test
    @DisplayName("kotlin actions degrade to a status message when kotlinc is missing")
    void kotlinMissing() {
        JvmBuildTools tools = wire("fun main() { println(1) }\n");
        tools.setKotlinToolForTesting(Optional::empty);
        act(tools, 3);
        act(tools, 4);
        assertEquals(0, runner.commands.size());
        assertEquals(2, msgs.stream().filter(m -> m.contains("not on PATH")).count(),
                msgs.toString());
    }

    @Test
    @DisplayName("run kotlin chains compile then launch with the file-class main")
    void runKotlinChain(@TempDir Path dir) throws Exception {
        JvmBuildTools tools = wire("fun main() { }\n");
        Path kotlinc = dir.resolve("kotlinc");
        Files.writeString(kotlinc, "#!/bin/sh\n");
        kotlinc.toFile().setExecutable(true);
        tools.setKotlinToolForTesting(() -> Optional.of(kotlinc));
        runner.replies.add(new Toolchain.Result(0, ""));          // compile ok
        runner.replies.add(new Toolchain.Result(0, "kotlin ran")); // run ok
        act(tools, 4);
        assertEquals(2, runner.commands.size());
        assertEquals(kotlinc.toString(), runner.commands.get(1).get(0),
                "falls back to the kotlinc path when no kotlin launcher sits beside it");
        assertEquals("MainKt", runner.commands.get(1).get(runner.commands.get(1).size() - 1));
        assertTrue(doc[0].contains("// ---- kotlin output ----\n//   kotlin ran\n"), doc[0]);
    }

    @Test
    @DisplayName("clean output removes the trailing block only when one exists")
    void cleanOutput() {
        JvmBuildTools tools = wire("x\n\n// ---- java output ----\n//   hi\n");
        act(tools, 5);
        assertEquals("x\n", doc[0]);
        assertTrue(msgs.stream().anyMatch(m -> m.contains("removed the output block")),
                msgs.toString());

        JvmBuildTools tools2 = wire("x\n");
        List<String> msgs2 = new ArrayList<>();
        tools2.onEditorStarted(new EditorContext(
                EnumSet.allOf(TextEditorPermission.class), msgs2::add, () -> { }, () -> { }));
        String[] doc2 = {"x\n"};
        tools2.onDocumentOpened(new DocumentContext(null, null, doc2[0], "",
                s -> doc2[0] = s, s -> { }));
        act(tools2, 5);
        assertEquals("x\n", doc2[0]);
        assertTrue(msgs2.stream().anyMatch(m -> m.contains("no output block")), msgs2.toString());
    }

    // -- SPI -----------------------------------------------------------------

    @Test
    @DisplayName("the manifest declares the Java/Kotlin category and six plain actions")
    void manifestAndContributions() {
        JvmBuildTools tools = new JvmBuildTools();
        TextEditorManifest m = tools.manifest();
        assertEquals("lg3d.jvm-build-tools", m.getId());
        assertEquals("JVM Build Tools", m.getName());
        assertEquals("Java/Kotlin", tools.category());
        assertTrue(m.getPermissions().contains(TextEditorPermission.FILE_IO));
        var c = tools.toolbarContributions();
        assertEquals(6, c.size());
        assertTrue(c.stream().allMatch(a -> a.getAccelerator().isEmpty()),
                "build actions claim no Ctrl+Alt slot");
        assertEquals("Compile Java", c.get(0).getLabel());
        assertEquals("Clean Output", c.get(5).getLabel());
    }
}
