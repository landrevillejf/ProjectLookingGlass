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
package org.jdesktop.lg3d.mandela.cucumber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.Values;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * The language used inside its own repository: the automation scripts under
 * {@code scripts/mandela} are compiled by the real compiler, and a script handed
 * the arguments the host supplies behaves the way its header promises. These steps
 * mirror {@link org.jdesktop.lg3d.mandela.ShippedScriptsTest} but drive only the
 * public {@link Mandela} facade, because a consumer outside the module cannot reach
 * a test helper. A grammar change that breaks a shipped tool is exactly the silent
 * drift this scenario is here to catch.
 */
public class UsageSteps {

    /** The shipped scripts, resolved against the repository layout. */
    private static final String SCRIPT_DIR = "scripts/mandela";

    /** The answer the last argument-driven run produced. */
    private Object answer;

    @When("a script reads the arguments the host passed:")
    public void runWithArguments(String source) {
        answer = Mandela.eval(source, Map.of("args",
                new MandelaList(List.<Object>of("my-script", "example.com"))));
    }

    @Then("the argument script answers {string}")
    public void assertArgumentAnswer(String expected) {
        assertEquals(expected, Values.display(answer));
    }

    @Then("every shipped automation script compiles")
    public void shippedScriptsCompile() {
        List<String> problems = new ArrayList<>();
        for (Path script : scripts()) {
            String source = read(script);
            for (Diagnostic finding : Mandela.engine()
                    .bind("args", new MandelaList()).build()
                    .check(source, script.getFileName().toString())) {
                if (finding.isError()) {
                    problems.add(finding.location() + " [" + finding.rule() + "]: "
                            + finding.message());
                }
            }
        }
        assertEquals(List.of(), problems,
                "the repository ships a script the compiler refuses");
    }

    @Then("at least {int} automation scripts are read")
    public void assertFloor(int minimum) {
        int found = scripts().size();
        assertTrue(found >= minimum,
                "only " + found + " scripts were found in " + SCRIPT_DIR
                        + "; the locator is probably broken");
    }

    /** @return every {@code .mnd} under the shipped directory, ordered by path */
    private static List<Path> scripts() {
        Path dir = scriptDir();
        try (Stream<Path> walk = Files.list(dir)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".mnd"))
                    .sorted(Comparator.comparing(Path::toString))
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
        return fail("cannot find " + SCRIPT_DIR + " from " + Path.of("").toAbsolutePath());
    }
}
