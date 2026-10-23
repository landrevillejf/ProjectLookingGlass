/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jdesktop.lg3d.scenemanager.config.ApplicationDescription;
import org.jdesktop.lg3d.scenemanager.config.LaunchCommand;

/**
 * Step definitions for {@code launch-availability.feature}: the contract that
 * decides whether a start-menu item is shown or self-hides. Driven through the
 * headless-safe seams -- {@link LaunchCommand}'s pure token resolution and
 * {@link ApplicationDescription#isApplicationAvailable} probed with absolute
 * paths only -- so no scenario forks {@code which} or depends on which
 * interpreters the host happens to have installed.
 */
public class LaunchAvailabilitySteps {

    private String command;
    private String[] required;
    private boolean available;

    @Given("the launch command {string}")
    public void theLaunchCommand(final String value) {
        this.command = value;
    }

    @When("the desktop resolves the executables the command requires")
    public void theDesktopResolvesTheRequiredExecutables() {
        this.required = LaunchCommand.requiredExecutables(command);
    }

    @Then("the required executables are {string}")
    public void theRequiredExecutablesAre(final String csv) {
        assertArrayEquals(csv.split(","), required);
    }

    @When("the desktop checks whether the command is available")
    public void theDesktopChecksAvailability() {
        this.available = ApplicationDescription.isApplicationAvailable(command);
    }

    @Then("the command is available")
    public void theCommandIsAvailable() {
        assertTrue(available, "expected available: " + command);
    }

    @Then("the command is not available")
    public void theCommandIsNotAvailable() {
        assertFalse(available, "expected NOT available: " + command);
    }

    @Given("a privilege wrapper that exists and an installer program that exists")
    public void wrapperAndInstallerBothExist() throws Exception {
        Path dir = Files.createTempDirectory("lg3d-avail");
        Path wrapper = dir.resolve("pkexec");
        Files.writeString(wrapper, "#!/bin/sh\n");
        Path installer = dir.resolve("calamares");
        Files.writeString(installer, "#!/bin/sh\n");
        this.command = wrapper + " " + installer;
    }

    @Given("a privilege wrapper that exists but an installer program that does not")
    public void wrapperExistsButInstallerMissing() throws Exception {
        Path dir = Files.createTempDirectory("lg3d-avail");
        Path wrapper = dir.resolve("pkexec");
        Files.writeString(wrapper, "#!/bin/sh\n");
        // The installer path deliberately names a file that is never created: the
        // wrapper is present, so only the missing installer can hide the item.
        this.command = wrapper + " " + dir.resolve("calamares");
    }
}
