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
import java.util.List;
import org.jdesktop.lg3d.utils.system.SecurityService.PolicyOperation;
import org.jdesktop.lg3d.utils.system.SecurityService.PolicyProbes;
import org.jdesktop.lg3d.utils.system.SecurityService.PolicyTool;

/**
 * Step definitions for {@code selinux-policy.feature}: the observable contract of
 * the gap-G2 SELinux policy seam, driven entirely through the headless-safe pure
 * seam - {@link SecurityService#isPolicyToolPresent}, {@link SecurityService#toolFor},
 * {@link SecurityService#buildPolicyCommand}, {@link SecurityService#isToken},
 * {@link SecurityService#isPath}, {@link SecurityService#countAvcDenials},
 * {@link SecurityService#summarizeAudit2Allow} and the {@code runPolicy*} guards.
 * No SELinux tool is installed and no process is spawned (the execution steps only
 * reach the guards that return before {@link ProcessRunner} / {@link PrivilegedRunner}),
 * so the scenarios run in the ordinary headless {@code test} task.
 *
 * <p>Gherkin passes a double-quoted argument verbatim, so the {@code \n} the
 * feature writes to shape a control character or multi-line tool output arrives as
 * a literal backslash sequence; {@link #unescape(String)} turns it back into the
 * character the validators and parsers expect.</p>
 */
public class SelinuxPolicySteps {

    private PolicyProbes probes;
    private PolicyOperation operation;
    private boolean present;
    private boolean destructive;
    private boolean valueAccepted;
    private PolicyTool drivingTool;
    private List<String> vector;
    private String summary;
    private int denialCount;
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
        // Unescape first so a Gherkin literal "\n" reaches the validators as the
        // real control character they reject (the same convention validateValue,
        // countAvc and summarizeDraft already follow).
        return unescape(csv).split(",", -1);
    }

    private static PolicyProbes toProbes(final String csv) {
        boolean semanage = false;
        boolean restorecon = false;
        boolean semodule = false;
        boolean loadPolicy = false;
        boolean ausearch = false;
        boolean audit2allow = false;
        if (csv != null && !csv.isBlank()) {
            for (String raw : csv.split(",")) {
                switch (raw.trim()) {
                    case "semanage":
                        semanage = true;
                        break;
                    case "restorecon":
                        restorecon = true;
                        break;
                    case "semodule":
                        semodule = true;
                        break;
                    case "load_policy":
                        loadPolicy = true;
                        break;
                    case "ausearch":
                        ausearch = true;
                        break;
                    case "audit2allow":
                        audit2allow = true;
                        break;
                    default:
                        break;
                }
            }
        }
        return new PolicyProbes(semanage, restorecon, semodule, loadPolicy, ausearch, audit2allow);
    }

    // ------------------------------------------------------------------
    // Detection

    @Given("selinux policy probes {string}")
    public void policyProbes(final String csv) {
        this.probes = toProbes(csv);
    }

    @When("the policy tool {string} is checked for presence")
    public void checkPolicyPresence(final String tool) {
        this.present = SecurityService.isPolicyToolPresent(probes, PolicyTool.valueOf(tool));
    }

    @Then("the policy tool is present")
    public void thenPolicyPresent() {
        assertTrue(present);
    }

    @Then("the policy tool is not present")
    public void thenPolicyNotPresent() {
        assertFalse(present);
    }

    // ------------------------------------------------------------------
    // Operation -> tool mapping

    @Given("the policy operation {string}")
    public void thePolicyOperation(final String op) {
        this.operation = PolicyOperation.valueOf(op);
    }

    @When("the tool that drives it is queried")
    public void queryDrivingTool() {
        this.drivingTool = SecurityService.toolFor(operation);
    }

    @Then("the driving tool is {string}")
    public void thenDrivingTool(final String expected) {
        assertEquals(PolicyTool.valueOf(expected), drivingTool);
    }

    // ------------------------------------------------------------------
    // Command vectors and confirmation class

    @When("the policy vector is built with parameters {string}")
    public void buildPolicyVector(final String csv) {
        this.vector = SecurityService.buildPolicyCommand(operation, params(csv));
    }

    @Then("the selinux vector is {string}")
    public void thenSelinuxVector(final String expectedCsv) {
        assertEquals(List.of(expectedCsv.split(",")), vector);
    }

    @Then("the selinux vector is empty")
    public void thenSelinuxVectorEmpty() {
        assertTrue(vector.isEmpty());
    }

    @When("the policy confirmation class is queried")
    public void queryPolicyClass() {
        this.destructive = operation.isMutating();
    }

    @Then("the policy operation is destructive")
    public void thenDestructive() {
        assertTrue(destructive);
    }

    @Then("the policy operation is not destructive")
    public void thenNotDestructive() {
        assertFalse(destructive);
    }

    // ------------------------------------------------------------------
    // Parameter validation

    @When("the token {string} is validated as a {string}")
    public void validateValue(final String value, final String kind) {
        String v = unescape(value);
        this.valueAccepted = "token".equals(kind)
                ? SecurityService.isToken(v)
                : SecurityService.isPath(v);
    }

    @Then("the value is accepted")
    public void thenValueAccepted() {
        assertTrue(valueAccepted);
    }

    @Then("the value is rejected")
    public void thenValueRejected() {
        assertFalse(valueAccepted);
    }

    // ------------------------------------------------------------------
    // Output parsing

    @When("the AVC output {string} is counted")
    public void countAvc(final String out) {
        this.denialCount = SecurityService.countAvcDenials(unescape(out));
    }

    @Then("the AVC denial count is {int}")
    public void thenDenialCount(final int expected) {
        assertEquals(expected, denialCount);
    }

    @When("the audit2allow draft {string} is summarised")
    public void summarizeDraft(final String text) {
        this.summary = SecurityService.summarizeAudit2Allow(unescape(text)).describe();
    }

    @Then("the draft summary is {string}")
    public void thenDraftSummary(final String expected) {
        assertEquals(expected, summary);
    }

    // ------------------------------------------------------------------
    // Execution guards (return before any process is spawned)

    @When("the policy read helper is asked to run {string} with {string}")
    public void runPolicyRead(final String op, final String csv) {
        this.readResult = SecurityService.runPolicyRead(PolicyOperation.valueOf(op), params(csv));
    }

    @Then("the policy read helper did not start a process")
    public void thenPolicyReadDidNotStart() {
        assertFalse(readResult.isStarted());
    }

    @When("the policy mutating helper is asked to run {string} with {string}")
    public void runPolicyMutating(final String op, final String csv) {
        this.mutatingResult =
                SecurityService.runPolicyMutating(PolicyOperation.valueOf(op), params(csv));
    }

    @Then("the policy mutating helper reported an error")
    public void thenPolicyMutatingError() {
        assertEquals(PrivilegedRunner.Status.ERROR, mutatingResult.getStatus());
    }
}
