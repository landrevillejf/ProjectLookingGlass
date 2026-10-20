package org.jdesktop.lg3d.apps.help;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cucumber step definitions for the Help Center content feature. Verifies
 * that the JavaHelp bundle resources are present on the classpath.
 */
public class HelpCenterSteps {

    private String helpContentBase;

    @Given("the help content directory")
    public void theHelpContentDirectory() {
        helpContentBase = "org/jdesktop/lg3d/apps/help/helpcontent";
    }

    @Then("the HelpSet file {string} exists")
    public void theHelpSetFileExists(String filename) {
        assertNotNull(getClass().getClassLoader().getResource(helpContentBase + "/" + filename),
                filename + " not found on classpath under " + helpContentBase);
    }

    @Then("the map file {string} exists")
    public void theMapFileExists(String filename) {
        assertNotNull(getClass().getClassLoader().getResource(helpContentBase + "/" + filename),
                filename + " not found on classpath under " + helpContentBase);
    }

    @Then("the table of contents {string} exists")
    public void theTableOfContentsExists(String filename) {
        assertNotNull(getClass().getClassLoader().getResource(helpContentBase + "/" + filename),
                filename + " not found on classpath under " + helpContentBase);
    }
}
