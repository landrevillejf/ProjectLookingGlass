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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jdesktop.lg3d.mandela.cli.MandelaCli;

import io.cucumber.java.After;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * The command line driven the way a terminal drives it: arguments in, an exit code
 * and text out. {@link MandelaCli#execute} returns the code instead of calling
 * {@code System.exit}, which is what lets a scenario assert all five codes and what
 * each one means -- the same seam a desktop console panel embeds.
 */
public class CliSteps {

    /** The code the last command returned. */
    private int code;

    /** Everything the last command wrote to its output stream. */
    private String out;

    /** Everything the last command wrote to its error stream. */
    private String err;

    /** A file some scenarios write so {@code run} and {@code check} have a target. */
    private Path scratch;

    @When("the command line runs:")
    public void run(String argvBlock) {
        String[] argv = java.util.Arrays.stream(argvBlock.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toArray(String[]::new);
        execute(argv);
    }

    @When("the command line checks a program that does not compile")
    public void checkBroken() throws IOException {
        scratch = Files.createTempFile("mandela-cli-", ".mnd");
        Files.writeString(scratch, "let x = \n");
        execute(new String[] {"check", scratch.toString()});
    }

    private void execute(String[] argv) {
        StringWriter shown = new StringWriter();
        StringWriter erred = new StringWriter();
        PrintWriter outWriter = new PrintWriter(shown);
        PrintWriter errWriter = new PrintWriter(erred);
        code = MandelaCli.execute(argv,
                new BufferedReader(new StringReader("")), outWriter, errWriter);
        outWriter.flush();
        errWriter.flush();
        out = shown.toString();
        err = erred.toString();
    }

    @Then("the exit code is {int}")
    public void assertCode(int expected) {
        assertEquals(expected, code, () -> "stdout=" + out + " stderr=" + err);
    }

    @Then("the standard output mentions {string}")
    public void assertOutMentions(String fragment) {
        assertTrue(out.contains(fragment), () -> "stdout was: " + out);
    }

    @Then("the error output mentions {string}")
    public void assertErrMentions(String fragment) {
        assertTrue(err.contains(fragment), () -> "stderr was: " + err);
    }

    @After
    public void tidyScratch() throws IOException {
        if (scratch != null) {
            Files.deleteIfExists(scratch);
            scratch = null;
        }
    }
}
