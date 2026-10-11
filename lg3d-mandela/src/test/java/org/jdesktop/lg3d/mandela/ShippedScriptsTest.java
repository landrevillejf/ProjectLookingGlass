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
package org.jdesktop.lg3d.mandela;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The automation scripts the repository ships in <code>scripts/mandela</code> are
 * compiled by the real compiler.
 *
 * <p>These are the language's own dogfood: tools that check documentation links,
 * audit the start menu, compare the release's version references and report the
 * asset weight, written in Mandela and run against this repository. A grammar
 * change that breaks one of them is exactly the drift a language module can cause
 * without noticing, and a script nobody runs silently rots. So the build compiles
 * every {@code .mnd} it finds, which costs milliseconds and cannot be forgotten.</p>
 *
 * <p>Unlike a guide fragment, a shipped script is a complete program: it declares
 * every name it uses and needs no prose around it. Therefore no finding is
 * excused here &mdash; not even {@code undefined-name}, which
 * {@link DocumentationTest} must tolerate. The one name the host supplies is
 * {@code args}, so it is bound before the compile exactly as the CLI's own
 * {@code check} binds it: a script that reads its arguments must compile with or
 * without any on the command line.</p>
 *
 * <p>The second test pins the header conventions that make the directory usable
 * without a wiki: a shebang, a one-line statement of what the script is for, and
 * the invocation including the sandbox it needs. A script whose &ldquo;how to run
 * me&rdquo; line lost its {@code -s} silently stops working outside
 * {@code scripts/mandela}, because that is the default sandbox of {@code run}.</p>
 */
class ShippedScriptsTest {

    /** The shipped scripts, resolved against the repository layout. */
    private static final String SCRIPT_DIR = "scripts/mandela";

    /**
     * The line that makes a {@code .mnd} runnable once a {@code mandela} launcher
     * is on the caller's PATH (see scripts/mandela/README.md for the one-liner).
     */
    private static final String SHEBANG = "#!/usr/bin/env mandela";

    /**
     * A shipped script must compile. Each finding is reported with its rule id so
     * the author sees which contract broke rather than a generic parse message.
     */
    @Test
    @DisplayName("every shipped mandela script compiles")
    void shippedScriptsCompile() {
        List<String> problems = new ArrayList<>();
        for (Script script : scripts()) {
            for (Diagnostic d : compile(script.source(), script.path().toString())) {
                if (d.isError()) {
                    problems.add(d.location() + " [" + d.rule() + "]: " + d.message());
                }
            }
        }
        assertEquals(List.of(), problems,
                "the repository ships a script the compiler refuses");
    }

    /**
     * The header is the script's documentation: shebang, then a comment line
     * naming the file and stating its job, then at least one invocation that says
     * which sandbox to run it in.
     */
    @Test
    @DisplayName("every shipped mandela script documents how to run it")
    void shippedScriptsCarryTheirHeader() {
        List<String> problems = new ArrayList<>();
        for (Script script : scripts()) {
            List<String> lines = List.of(script.source().split("\n", -1));
            String name = script.path().getFileName().toString();
            if (lines.isEmpty() || !lines.get(0).equals(SHEBANG)) {
                problems.add(name + ": first line must be " + SHEBANG);
                continue;
            }
            String intro = "// " + name + " — ";
            if (lines.size() < 2 || !lines.get(1).startsWith(intro)
                    || lines.get(1).length() <= intro.length()) {
                problems.add(name + ": second line must read \"// " + name
                        + " — what it is for\"");
            }
            boolean saysHowToRun = lines.stream().skip(1)
                    .filter(line -> line.trim().startsWith("//"))
                    .anyMatch(line -> line.contains(" -s "));
            if (!saysHowToRun) {
                problems.add(name + ": the header never shows the -s sandbox to run it with");
            }
        }
        assertEquals(List.of(), problems, "a shipped script stopped documenting itself");
    }

    /**
     * The floor exists so that a broken directory lookup cannot turn either test
     * above into a green test that inspected nothing &mdash; the same reason
     * {@link DocumentationTest} counts the blocks it extracts.
     */
    @Test
    @DisplayName("the script directory is really being read")
    void scriptsAreFound() {
        List<Script> found = scripts();
        assertTrue(found.size() >= 4, "only " + found.size() + " scripts were found in "
                + SCRIPT_DIR + "; the locator is probably broken");
    }

    /**
     * Compiles one program on the shape the CLI compiles on.
     *
     * @param source     the program
     * @param sourceName what to call it in a finding
     * @return the compiler's diagnostics
     */
    private static List<Diagnostic> compile(String source, String sourceName) {
        return Mandela.engine().bind("args", new MandelaList()).build()
                .check(source, sourceName);
    }

    /** @return every {@code .mnd} under the shipped directory, ordered by path */
    private static List<Script> scripts() {
        Path dir = scriptDir();
        try (Stream<Path> walk = Files.list(dir)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".mnd"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(path -> new Script(path, read(path)))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the scripts' directory, or a failure naming the layouts that were tried */
    private static Path scriptDir() {
        Path fromModule = Path.of("..", SCRIPT_DIR);
        Path fromRoot = Path.of(SCRIPT_DIR);
        for (Path candidate : List.of(fromModule, fromRoot)) {
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return fail("cannot find " + SCRIPT_DIR + " from " + Path.of("").toAbsolutePath()
                + " (expected the module test task to run with the module as cwd)");
    }

    /**
     * @param path   where the script lives, used as the compiler's source name
     * @param source the program's text
     */
    private record Script(Path path, String source) { }
}
