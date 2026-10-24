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
package org.jdesktop.lg3d.utils.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.utils.system.HidsService.Operation;
import org.jdesktop.lg3d.utils.system.HidsService.Probes;
import org.jdesktop.lg3d.utils.system.HidsService.Tool;

/**
 * Step definitions for {@code hids.feature}: the observable contract of the
 * §4.10 host-intrusion-detection seam, driven entirely through the headless-safe
 * pure seam - {@link HidsService#isPresent}, {@link HidsService#installedTools},
 * {@link HidsService#buildCommand}, the {@code parse*} family and the
 * {@code run*} guards. No HIDS tool is installed and no process is spawned (the
 * execution steps only reach the guards that return before {@link ProcessRunner}
 * / {@link PrivilegedRunner}), so the scenarios run in the ordinary headless
 * {@code test} task.
 *
 * <p>Gherkin passes a double-quoted argument verbatim, so the {@code \n} and
 * {@code \t} the feature writes to shape multi-line tool output arrive as
 * literal backslash sequences; {@link #unescape(String)} turns them back into
 * the control characters the parsers expect.</p>
 */
public class HidsServiceSteps {

    private Probes probes;
    private Operation operation;
    private boolean present;
    private boolean mutating;
    private List<Tool> installed;
    private List<String> vector;
    private String summary;
    private int eventCount;
    private ProcessRunner.Result readResult;
    private PrivilegedRunner.PrivilegedResult mutatingResult;

    /** Restores the control characters a Gherkin string literal cannot carry. */
    private static String unescape(final String s) {
        if (s == null) {
            return null;
        }
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
    }

    private static String[] params(final String csv) {
        if (csv == null || csv.isBlank()) {
            return new String[0];
        }
        return csv.split(",", -1);
    }

    private static Probes toProbes(final String csv) {
        boolean f2b = false;
        boolean rkh = false;
        boolean lyn = false;
        boolean ctl = false;
        boolean sea = false;
        if (csv != null && !csv.isBlank()) {
            for (String raw : csv.split(",")) {
                switch (raw.trim()) {
                    case HidsService.FAIL2BAN_CLIENT:
                        f2b = true;
                        break;
                    case HidsService.RKHUNTER:
                        rkh = true;
                        break;
                    case HidsService.LYNIS:
                        lyn = true;
                        break;
                    case HidsService.AUDITCTL:
                        ctl = true;
                        break;
                    case HidsService.AUSEARCH:
                        sea = true;
                        break;
                    default:
                        break;
                }
            }
        }
        return new Probes(f2b, rkh, lyn, ctl, sea);
    }

    // ------------------------------------------------------------------
    // Detection

    @Given("hids probes {string}")
    public void hidsProbes(final String csv) {
        this.probes = toProbes(csv);
    }

    @When("the tool {string} is checked for presence")
    public void checkPresence(final String tool) {
        this.present = HidsService.isPresent(probes, Tool.valueOf(tool));
    }

    @Then("the tool is present")
    public void thenPresent() {
        assertTrue(present);
    }

    @Then("the tool is not present")
    public void thenNotPresent() {
        assertFalse(present);
    }

    @When("the installed tools are listed")
    public void listInstalled() {
        this.installed = HidsService.installedTools(probes);
    }

    @Then("the installed tools are {string} in order")
    public void thenInstalledOrder(final String expectedCsv) {
        List<String> names = new ArrayList<>();
        for (Tool t : installed) {
            names.add(t.name());
        }
        assertEquals(List.of(expectedCsv.split(",")), names);
    }

    // ------------------------------------------------------------------
    // Command vectors and confirmation class

    @Given("the hids operation {string}")
    public void theOperation(final String op) {
        this.operation = Operation.valueOf(op);
    }

    @When("its vector is built with parameters {string}")
    public void buildVector(final String csv) {
        this.vector = HidsService.buildCommand(operation, params(csv));
    }

    @Then("the hids vector is {string}")
    public void thenVector(final String expectedCsv) {
        assertEquals(List.of(expectedCsv.split(",")), vector);
    }

    @Then("the hids vector is empty")
    public void thenVectorEmpty() {
        assertTrue(vector.isEmpty());
    }

    @When("its confirmation class is queried")
    public void queryClass() {
        this.mutating = operation.isMutating();
    }

    @Then("the operation is mutating")
    public void thenMutating() {
        assertTrue(mutating);
    }

    @Then("the operation is not mutating")
    public void thenNotMutating() {
        assertFalse(mutating);
    }

    // ------------------------------------------------------------------
    // Output parsing

    @When("the fail2ban status output {string} is parsed")
    public void parseF2bStatus(final String out) {
        this.summary = HidsService.parseFail2BanStatus(unescape(out)).describe();
    }

    @Then("the fail2ban summary is {string}")
    public void thenF2bStatus(final String expected) {
        assertEquals(expected, summary);
    }

    @When("the fail2ban jail output {string} is parsed")
    public void parseF2bJail(final String out) {
        this.summary = HidsService.parseFail2BanJail(unescape(out)).describe();
    }

    @Then("the jail summary is {string}")
    public void thenF2bJail(final String expected) {
        assertEquals(expected, summary);
    }

    @When("the rkhunter output {string} is parsed")
    public void parseRkhunter(final String out) {
        this.summary = HidsService.parseRkhunter(unescape(out)).describe();
    }

    @Then("the rkhunter summary is {string}")
    public void thenRkhunter(final String expected) {
        assertEquals(expected, summary);
    }

    @When("the lynis output {string} is parsed")
    public void parseLynis(final String out) {
        this.summary = HidsService.parseLynis(unescape(out)).describe();
    }

    @Then("the lynis summary is {string}")
    public void thenLynis(final String expected) {
        assertEquals(expected, summary);
    }

    @When("the auditd output {string} is parsed")
    public void parseAuditd(final String out) {
        this.summary = HidsService.parseAuditdStatus(unescape(out)).describe();
    }

    @Then("the auditd summary is {string}")
    public void thenAuditd(final String expected) {
        assertEquals(expected, summary);
    }

    @When("the ausearch output {string} is counted")
    public void countAusearch(final String out) {
        this.eventCount = HidsService.countAusearchEvents(unescape(out));
    }

    @Then("the event count is {int}")
    public void thenEventCount(final int expected) {
        assertEquals(expected, eventCount);
    }

    // ------------------------------------------------------------------
    // Execution guards (return before any process is spawned)

    @When("the read helper is asked to run {string}")
    public void runRead(final String op) {
        this.readResult = HidsService.runRead(Operation.valueOf(op));
    }

    @Then("the read helper did not start a process")
    public void thenReadDidNotStart() {
        assertFalse(readResult.isStarted());
    }

    @When("the mutating helper is asked to run {string}")
    public void runMutating(final String op) {
        this.mutatingResult = HidsService.runMutating(Operation.valueOf(op));
    }

    @Then("the mutating helper reported an error")
    public void thenMutatingError() {
        assertEquals(PrivilegedRunner.Status.ERROR, mutatingResult.getStatus());
    }
}
