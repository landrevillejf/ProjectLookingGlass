package org.jdesktop.lg3d.displayserver.crash;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cucumber step definitions for the crash-reporting feature. Exercises
 * {@link CrashSignature} through its BDD surface.
 */
public class CrashReportingSteps {

    private Throwable exception;
    private String firstSignature;
    private String secondSignature;

    @Given("an uncaught exception of type {string} with message {string}")
    public void anUncaughtExceptionOfType(String className, String message) {
        exception = switch (className) {
            case "java.lang.NullPointerException" -> new NullPointerException(message);
            case "java.lang.IllegalStateException" -> new IllegalStateException(message);
            default -> new RuntimeException(message);
        };
    }

    @When("the crash signature is computed")
    public void theCrashSignatureIsComputed() {
        firstSignature = CrashSignature.of(exception);
    }

    @When("the crash signature is computed again")
    public void theCrashSignatureIsComputedAgain() {
        secondSignature = CrashSignature.of(exception);
    }

    @Then("the signature is a non-empty hexadecimal string")
    public void theSignatureIsNonEmptyHex() {
        assertNotNull(firstSignature);
        assertFalse(firstSignature.isBlank());
        assertTrue(firstSignature.matches("[0-9a-f]+"));
    }

    @Then("both signatures are identical")
    public void bothSignaturesAreIdentical() {
        assertEquals(firstSignature, secondSignature);
    }
}
