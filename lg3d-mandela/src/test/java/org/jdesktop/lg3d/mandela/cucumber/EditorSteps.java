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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jdesktop.lg3d.mandela.api.EditorServices;
import org.jdesktop.lg3d.mandela.lang.Keywords;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * The editor-facing vocabulary the language owns: the reserved words a colourer may
 * not treat as identifiers, the globals and modules a completion popup may offer, and
 * the names a half-typed buffer already binds. Steps drive {@link EditorServices} and
 * {@link Keywords} directly, the same door the LSP extension uses.
 */
public class EditorSteps {

    /** The buffer a {@code vocabulary} step reads. */
    private String buffer = "";

    @When("the editor reads the buffer:")
    public void readBuffer(String source) {
        buffer = source;
    }

    @Then("the reserved words are exactly:")
    public void reservedWordsAre(String block) {
        List<String> expected = java.util.Arrays.stream(block.trim().split("\\s+"))
                .filter(word -> !word.isEmpty())
                .sorted()
                .toList();
        List<String> actual = Keywords.RESERVED.stream().sorted().toList();
        assertTrue(actual.equals(expected),
                () -> "reserved set drifted: expected " + expected + " but got " + actual);
    }

    @Then("the keyword list contains {word}")
    public void keywordsContain(String word) {
        assertTrue(EditorServices.keywords().contains(word),
                () -> "the keyword list omitted " + word + ": " + EditorServices.keywords());
    }

    @Then("the keyword list does not contain {word}")
    public void keywordsOmit(String word) {
        assertFalse(EditorServices.keywords().contains(word),
                () -> word + " is not a word the parser reads, so it must not be a keyword");
    }

    @Then("the globals list contains {word}")
    public void globalsContain(String name) {
        assertTrue(EditorServices.globals().contains(name),
                () -> "the globals omitted " + name + ": " + EditorServices.globals());
    }

    @Then("the module list contains {word}")
    public void modulesContain(String module) {
        assertTrue(EditorServices.moduleNames().contains(module),
                () -> "a 'use' cannot ask for " + module + ": " + EditorServices.moduleNames());
    }

    @Then("a {word} value has the member {word}")
    public void typeHasMember(String type, String member) {
        assertTrue(EditorServices.valueMembers(type).contains(member),
                () -> type + " did not carry " + member + ": " + EditorServices.valueMembers(type));
    }

    @Then("the buffer's vocabulary names {word}")
    public void vocabularyNames(String name) {
        List<String> names = EditorServices.vocabulary(buffer);
        assertTrue(names.contains(name),
                () -> "the buffer never surfaced " + name + ": " + names);
    }
}
