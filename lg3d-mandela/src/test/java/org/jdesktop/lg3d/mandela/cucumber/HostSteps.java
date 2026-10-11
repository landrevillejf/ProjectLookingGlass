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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.values.Values;

import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * The embedding API as a host uses it: the {@link Mandela} facade for one-shot
 * {@code eval}, the {@code check} door that reports findings without running, the
 * {@code about} summary a language list shows, and Java values crossing in through the
 * same bridge a binding would use. Every step drives the public facade, never a
 * private seam.
 */
public class HostSteps {

    /** The value the last {@code eval} answered with. */
    private Object answer;

    /** The findings the last {@code check} reported. */
    private List<Diagnostic> findings;

    /** The host names bound before the next run. */
    private final Map<String, Object> bindings = new LinkedHashMap<>();

    @Before
    public void freshHost() {
        bindings.clear();
    }

    @Given("the host binds {word} to the Java list ada and lin")
    public void bindList(String name) {
        bindings.put(name, List.of("ada", "lin"));
    }

    @Given("the host binds {word} to a Java function that doubles its argument")
    public void bindDoubling(String name) {
        bindings.put(name, (Function<Object, Object>) value -> ((Long) value) * 2);
    }

    @When("the host evaluates the program:")
    public void evaluate(String source) {
        answer = bindings.isEmpty()
                ? Mandela.eval(source)
                : Mandela.eval(source, bindings);
    }

    @When("the host checks the program:")
    public void check(String source) {
        findings = Mandela.check(source);
    }

    @Then("the host reads the answer {string}")
    public void assertAnswer(String expected) {
        assertEquals(expected, Values.display(answer));
    }

    @Then("the check is clean")
    public void assertClean() {
        assertTrue(findings.isEmpty(), () -> "a sound program reported " + findings);
    }

    @Then("the check reports the rule {word}")
    public void assertRule(String rule) {
        boolean seen = findings.stream().anyMatch(finding -> rule.equals(finding.rule()));
        assertTrue(seen, () -> "no finding carried rule " + rule + ": " + findings);
    }

    @Then("the engine says its language is {string}")
    public void aboutLanguage(String name) {
        assertEquals(name, Mandela.about().get("language"));
    }

    @Then("the engine says its file extension is {string}")
    public void aboutExtension(String extension) {
        assertEquals(extension, Mandela.about().get("extension"));
    }
}
