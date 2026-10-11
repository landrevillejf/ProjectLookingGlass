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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.api.MandelaScriptEngineFactory;
import org.jdesktop.lg3d.mandela.values.Values;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * The JSR-223 door. A tool that already embeds {@code javax.script} reaches Mandela
 * through the platform registry with no new dependency, so these steps go through
 * {@link Mandela#scriptEngine()} and the SPI exactly as such a tool does -- never the
 * language's own facade for running a program.
 */
public class ScriptEngineSteps {

    /** The engine the last step built, kept so a follow-up step can read it back. */
    private ScriptEngine engine;

    /** The value the last {@code eval} returned, in host form. */
    private Object answer;

    @When("the JSR-223 engine evaluates:")
    public void evaluate(String source) {
        engine = Mandela.scriptEngine();
        try {
            answer = engine.eval(source);
        } catch (ScriptException problem) {
            fail("the engine refused a program it should run: " + problem.getMessage());
        }
    }

    @Then("it answers {string}")
    public void assertAnswer(String expected) {
        assertEquals(expected, Values.display(answer));
    }

    @Then("the registry finds the engine by {word}")
    public void registryFinds(String kind) {
        ScriptEngineManager manager =
                new ScriptEngineManager(Mandela.class.getClassLoader());
        ScriptEngine found = switch (kind) {
            case "name" -> manager.getEngineByName("mandela");
            case "extension" -> manager.getEngineByExtension("mnd");
            case "mime" -> manager.getEngineByMimeType("text/x-mandela");
            default -> throw new IllegalArgumentException("no such lookup: " + kind);
        };
        assertNotNull(found, () -> "the registry did not find the engine by " + kind);
    }

    @Then("the factory advertises the language {string}")
    public void factoryAdvertises(String name) {
        assertEquals(name, new MandelaScriptEngineFactory().getLanguageName());
    }
}
