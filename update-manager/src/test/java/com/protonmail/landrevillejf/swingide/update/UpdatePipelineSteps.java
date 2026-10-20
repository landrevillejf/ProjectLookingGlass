package com.protonmail.landrevillejf.swingide.update;

import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cucumber step definitions for the update pipeline feature. Exercises
 * {@link VersionComparator#isNewerVersion(String, String)} through its
 * BDD surface.
 */
public class UpdatePipelineSteps {

    private boolean newerResult;

    @When("comparing latest {string} against current {string}")
    public void comparingLatestAgainstCurrent(String latest, String current) {
        newerResult = VersionComparator.isNewerVersion(latest, current);
    }

    @Then("the result is newer")
    public void theResultIsNewer() {
        assertTrue(newerResult, "expected latest version to be newer");
    }

    @Then("the result is not newer")
    public void theResultIsNotNewer() {
        assertFalse(newerResult, "expected latest version NOT to be newer");
    }
}
