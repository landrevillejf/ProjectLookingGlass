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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.Values;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * The executable half of the language's Gherkin specification.
 *
 * <p>Every step drives the real engine through the public {@link Mandela} facade
 * (the same door a host uses), never a private helper: a scenario that could only
 * pass against a test seam would not be the living documentation it is meant to be.
 * One instance exists per scenario, so the fields below are that scenario's scratch
 * space, reset by the natural lifecycle.</p>
 */
public class LanguageSteps {

    /** The value the last {@code run} produced, or {@code null} before any run. */
    private Object result;

    /** The error the last {@code run} raised, when the scenario expects one. */
    private MandelaError raised;

    /** Everything the program wrote to stdout, captured so a scenario can read it. */
    private final StringBuilder stdout = new StringBuilder();

    /** The diagnostics the last {@code parse} produced. */
    private List<Diagnostic> findings;

    /** The sandbox the next {@code run} builds on; {@code console()} until changed. */
    private Capabilities caps = Capabilities.console();

    /** A scratch directory a desktop scenario may read and write, cleaned after it. */
    private Path scratch;

    // -- choosing the engine --------------------------------------------------

    @Given("a {word} sandbox engine")
    public void chooseEngine(String profile) {
        caps = switch (profile) {
            case "console" -> Capabilities.console();
            case "webPage", "page" -> Capabilities.webPage();
            case "open" -> Capabilities.open();
            default -> throw new IllegalArgumentException(
                    "no such documented profile: " + profile);
        };
    }

    /** The same choice written with the "an" article, so a profile reads as prose. */
    @Given("an {word} sandbox engine")
    public void chooseEngineWithArticle(String profile) {
        chooseEngine(profile);
    }

    /**
     * A real app's shape: files confined to one directory that exists only for the
     * scenario, seeded with the {@code note.txt} the library cases read. The
     * directory is removed in {@link #tidySandbox()}, so the suite leaves nothing on
     * disk.
     */
    @Given("a desktop sandbox engine over a temporary directory")
    public void desktopSandbox() throws IOException {
        scratch = Files.createTempDirectory("mandela-cucumber-");
        Files.writeString(scratch.resolve("note.txt"), "alpha\nbeta\n");
        caps = Capabilities.desktop(scratch);
    }

    // -- running programs -----------------------------------------------------

    @When("the program runs:")
    public void runProgram(String source) {
        stdout.setLength(0);
        raised = null;
        try {
            result = Mandela.engine(caps)
                    .output(stdout::append)
                    .error(s -> { /* stderr captured only where a step asks */ })
                    .build()
                    .run(source, "scenario.mnd");
        } catch (MandelaError error) {
            raised = error;
            result = null;
        }
    }

    /**
     * Runs a single expression written inline in a step, so an outline can tabulate
     * many expressions against their answers on one line each. Multi-line programs and
     * snippets carrying quotes use {@link #runProgram(String)} instead.
     */
    @When("the one-line program {string} runs")
    public void runOneLine(String source) {
        runProgram(source);
    }

    @Then("its value is {string}")
    public void assertValue(String expected) {
        assertNotNull(result, () -> stdout + " (program failed: "
                + (raised == null ? "no error" : raised.getMessage()) + ")");
        assertEquals(expected, Values.display(result));
    }

    @Then("it prints {string}")
    public void assertPrints(String expected) {
        assertEquals(expected, stdout.toString());
    }

    @Then("the program raises a {word} error")
    public void assertKind(String kind) {
        assertNotNull(raised, "the program finished, but a failure was expected");
        assertEquals(kind, raised.kind());
    }

    @Then("the failure mentions {string}")
    public void assertFailureMessage(String fragment) {
        assertNotNull(raised, "the program finished, but a failure was expected");
        assertTrue(raised.getMessage().contains(fragment), () ->
                "message was: " + raised.getMessage() + " (expected a hint of '" + fragment + "')");
    }

    // -- reading the sandbox the scenario is standing in ----------------------

    @Then("the sandbox does not allow the JVM type {string}")
    public void assertJvmDenied(String type) {
        assertFalse(caps.allowsJvm(type), () -> caps.describe() + " granted " + type);
    }

    @Then("the sandbox describes a {word} grant")
    public void assertDescribes(String token) {
        assertTrue(caps.describe().contains(token),
                () -> "the sandbox grants were " + caps.describe());
    }

    @After
    public void tidySandbox() throws IOException {
        if (scratch == null) {
            return;
        }
        try (var walk = Files.walk(scratch)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException leftBehind) {
                            // A scratch file the OS will not release is not worth
                            // failing a scenario over; the JVM reaps the temp tree.
                        }
                    });
        }
        scratch = null;
    }

    // -- parsing programs -----------------------------------------------------

    @When("the snippet parses:")
    public void parseSnippet(String source) {
        findings = Mandela.check(source, "snippet.mnd");
    }

    @Then("the snippet is accepted")
    public void assertAccepted() {
        // A snippet that abbreviates the program may legitimately carry an
        // undefined-name finding (a value the prose introduced), mirroring
        // DocumentationTest's rule; anything else is a syntax error.
        for (Diagnostic finding : findings) {
            if (!"undefined-name".equals(finding.rule())) {
                fail("unexpected diagnostic: " + finding);
            }
        }
    }

    @Then("the parser refuses it, mentioning {string}")
    public void assertRefused(String fragment) {
        assertTrue(hasErrors(), "the snippet parsed, but it should not have");
        assertTrue(mentions(fragment), () -> findings + " (expected a hint of '" + fragment + "')");
    }

    /** @return true when any finding is a real syntax error, not just an undefined name */
    private boolean hasErrors() {
        for (Diagnostic finding : findings) {
            if (!"undefined-name".equals(finding.rule())) {
                return true;
            }
        }
        return false;
    }

    /** @param fragment the words one finding's message must contain @return true when a finding says it */
    private boolean mentions(String fragment) {
        for (Diagnostic finding : findings) {
            if (finding.message().contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
