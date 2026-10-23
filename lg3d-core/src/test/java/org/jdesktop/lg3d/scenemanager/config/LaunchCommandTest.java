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
package org.jdesktop.lg3d.scenemanager.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the pure decision behind a start-menu item's self-hide: which tokens of
 * a launch command name an executable that must be installed. The end-to-end
 * cases drive {@link ApplicationDescription#isApplicationAvailable} through
 * absolute paths only, so the assertions are hermetic -- they never fork
 * {@code which} and never depend on which interpreters the host happens to have.
 */
class LaunchCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("an in-JVM launch (java/swingapp/javaws) needs no executable")
    void inJvmLaunches() {
        assertTrue(LaunchCommand.isInJvmLaunch(
                "java org.jdesktop.lg3d.apps.calculator.Calculator"));
        assertTrue(LaunchCommand.isInJvmLaunch("swingapp org.foo.Bar"));
        // The historical startsWith("java") also treats javaws as in-JVM.
        assertTrue(LaunchCommand.isInJvmLaunch("javaws http://host/x.jnlp"));
        assertFalse(LaunchCommand.isInJvmLaunch("firefox"));
        assertFalse(LaunchCommand.isInJvmLaunch("pkexec calamares"));
        assertArrayEquals(new String[0],
                LaunchCommand.requiredExecutables("java org.foo.Bar"));
    }

    @Test
    @DisplayName("only the leading program of a plain command is required")
    void plainCommandRequiresItsFirstTokenOnly() {
        // The regression the narrow rule avoids: the URL argument of the
        // shipped Lg3d Homepage item must never be mistaken for an executable.
        assertArrayEquals(new String[] {"firefox"},
                LaunchCommand.requiredExecutables(
                        "firefox https://github.com/landrevillejf/ProjectLookingGlass"));
        assertArrayEquals(new String[] {"xterm"},
                LaunchCommand.requiredExecutables("xterm"));
    }

    @Test
    @DisplayName("a privilege wrapper also requires the program it elevates")
    void wrapperRequiresTheElevatedProgram() {
        assertArrayEquals(new String[] {"pkexec", "calamares"},
                LaunchCommand.requiredExecutables("pkexec calamares"));
        assertArrayEquals(new String[] {"sudo", "calamares"},
                LaunchCommand.requiredExecutables("sudo -E calamares"));
        assertArrayEquals(new String[] {"/usr/bin/pkexec", "calamares"},
                LaunchCommand.requiredExecutables("/usr/bin/pkexec calamares"));
    }

    @Test
    @DisplayName("a wrapper with no program to elevate requires only itself")
    void wrapperWithNoProgram() {
        assertArrayEquals(new String[] {"pkexec"},
                LaunchCommand.requiredExecutables("pkexec"));
        // Every following token is an option, so there is no program to add.
        assertArrayEquals(new String[] {"sudo"},
                LaunchCommand.requiredExecutables("sudo -E -n"));
    }

    @Test
    @DisplayName("a blank command names nothing runnable")
    void blankCommand() {
        assertArrayEquals(new String[0], LaunchCommand.requiredExecutables(""));
        assertArrayEquals(new String[0], LaunchCommand.requiredExecutables("   "));
    }

    @Test
    @DisplayName("surrounding whitespace and inner runs of spaces are tolerated")
    void whitespaceIsTrimmedAndCollapsed() {
        assertArrayEquals(new String[] {"pkexec", "calamares"},
                LaunchCommand.requiredExecutables("  pkexec    calamares  "));
    }

    @Test
    @DisplayName("wrapper detection matches the base name of every known launcher")
    void privilegeWrapperDetection() {
        for (String w : new String[] {"pkexec", "sudo", "doas", "gksu",
                "gksudo", "kdesu", "kdesudo"}) {
            assertTrue(LaunchCommand.isPrivilegeWrapper(w), w);
            assertTrue(LaunchCommand.isPrivilegeWrapper("/usr/bin/" + w), w);
        }
        assertFalse(LaunchCommand.isPrivilegeWrapper("firefox"));
        assertFalse(LaunchCommand.isPrivilegeWrapper("pkexec-fake"));
        assertFalse(LaunchCommand.isPrivilegeWrapper(""));
    }

    @Test
    @DisplayName("isApplicationAvailable: an in-JVM launch is always available")
    void availabilityOfInJvmLaunch() {
        assertTrue(ApplicationDescription.isApplicationAvailable("java org.foo.Bar"));
        assertTrue(ApplicationDescription.isApplicationAvailable("swingapp org.foo.Bar"));
    }

    @Test
    @DisplayName("isApplicationAvailable: a blank command is not available")
    void availabilityOfBlankCommand() {
        assertFalse(ApplicationDescription.isApplicationAvailable("   "));
    }

    @Test
    @DisplayName("isApplicationAvailable: a single path is present iff the file exists")
    void availabilityOfASinglePath(@TempDir Path dir) throws Exception {
        Path real = dir.resolve("prog");
        Files.writeString(real, "#!/bin/sh\n");
        assertTrue(ApplicationDescription.isApplicationAvailable(real.toString()));
        assertFalse(ApplicationDescription.isApplicationAvailable(
                dir.resolve("missing").toString()));
    }

    @Test
    @DisplayName("isApplicationAvailable: a wrapped installer needs BOTH the wrapper and the installer")
    void availabilityRequiresEveryToken() throws Exception {
        // An absolute-path wrapper whose base name is "pkexec", so the item is
        // treated as privilege-escalated without depending on a real pkexec.
        Path wrapper = tempDir.resolve("pkexec");
        Files.writeString(wrapper, "#!/bin/sh\n");
        Path installer = tempDir.resolve("calamares");
        Files.writeString(installer, "#!/bin/sh\n");

        // Both present -> available.
        assertTrue(ApplicationDescription.isApplicationAvailable(
                wrapper + " " + installer));
        // Installer absent -> NOT available, even though the wrapper is present.
        // This is the A/B that fixes "pkexec calamares" showing on a host with
        // pkexec but no calamares.
        assertFalse(ApplicationDescription.isApplicationAvailable(
                wrapper + " " + tempDir.resolve("calamares-absent")));
    }

    @Test
    @DisplayName("the utility constructor is reachable (coverage of the private ctor)")
    void privateConstructor() throws Exception {
        Constructor<LaunchCommand> ctor = LaunchCommand.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }
}
