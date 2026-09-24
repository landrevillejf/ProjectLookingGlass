/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.displayserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.lg3d.displayserver.DesktopMode.Capability;
import org.jdesktop.lg3d.displayserver.DesktopMode.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the 3D/2D desktop decision: an explicit mode always wins, otherwise a
 * machine that can render 3D keeps the 3D desktop and one that cannot falls back
 * to the conventional Swing desktop (after asking).
 */
class DesktopModeTest {

    @AfterEach
    void clearSimulatedCapability() {
        System.clearProperty(DesktopMode.SIMULATE_NO_3D_PROPERTY);
    }

    @Test
    @DisplayName("lg.fws.mode=2d forces the Swing desktop whatever the hardware")
    void explicit2dAlwaysWins() {
        assertEquals(Mode.TWO_D, DesktopMode.resolve("2d", true, true));
        assertEquals(Mode.TWO_D, DesktopMode.resolve("2D", false, false));
        assertFalse(DesktopMode.requiresConfirmation("2d", false, false),
                "an explicit request must not be confirmed");
    }

    @Test
    @DisplayName("lg.fws.mode=swing forces the Metal look-and-feel Swing desktop")
    void explicitSwingAlwaysWins() {
        assertEquals(Mode.SWING, DesktopMode.resolve("swing", true, true));
        assertEquals(Mode.SWING, DesktopMode.resolve("SWING", false, false));
        assertEquals(Mode.SWING, DesktopMode.resolve("  swing ", true, false));
        assertFalse(DesktopMode.requiresConfirmation("swing", false, false),
                "an explicit request must not be confirmed");
        assertFalse(DesktopMode.requiresConfirmation("swing", true, true));
    }

    @Test
    @DisplayName("lg.fws.mode=3d keeps the historical fail-loudly 3D boot")
    void explicit3dAlwaysWins() {
        assertEquals(Mode.THREE_D, DesktopMode.resolve("3d", true, true));
        assertEquals(Mode.THREE_D, DesktopMode.resolve("3d", false, false));
        assertFalse(DesktopMode.requiresConfirmation("3d", false, false),
                "a forced 3D boot reports the error instead of prompting");
    }

    @Test
    @DisplayName("other mode values (dev, x11, unset) are decided by the probe")
    void autoFollowsCapability() {
        assertEquals(Mode.THREE_D, DesktopMode.resolve(null, true, true));
        assertEquals(Mode.THREE_D, DesktopMode.resolve("dev", true, true));
        assertEquals(Mode.TWO_D, DesktopMode.resolve("dev", false, true));
        assertEquals(Mode.TWO_D, DesktopMode.resolve("dev", true, false));
        assertEquals(Mode.TWO_D, DesktopMode.resolve("x11", false, false));
        // An automatic fallback lands on the MDI desktop, never on --swing.
        assertFalse(DesktopMode.resolve("dev", false, false) == Mode.SWING);
    }

    @Test
    @DisplayName("only an automatic fallback asks the user")
    void confirmationOnlyForFallback() {
        assertFalse(DesktopMode.requiresConfirmation(null, true, true));
        assertFalse(DesktopMode.requiresConfirmation("dev", true, true));
        assertTrue(DesktopMode.requiresConfirmation("dev", false, true));
        assertTrue(DesktopMode.requiresConfirmation(null, true, false));
    }

    @Test
    @DisplayName("whitespace around the mode value is ignored")
    void modeValueIsTrimmed() {
        assertEquals(Mode.TWO_D, DesktopMode.resolve("  2d ", true, true));
        assertEquals(Mode.THREE_D, DesktopMode.resolve(" 3d", true, true));
    }

    @Test
    @DisplayName("Capability reports availability and its reason together")
    void capabilityInvariants() {
        Capability ok = new Capability(true, true, null);
        assertTrue(ok.isJava3dPresent());
        assertTrue(ok.isGlInitOk());
        assertTrue(ok.is3dAvailable());
        assertNull(ok.getReason());

        Capability noJ3d = new Capability(false, false, "no Java 3D");
        assertFalse(noJ3d.is3dAvailable());
        assertEquals("no Java 3D", noJ3d.getReason());

        Capability noGl = new Capability(true, false, "no GL");
        assertFalse(noGl.is3dAvailable());
        assertTrue(noGl.isJava3dPresent());

        // resolve()/requiresConfirmation() accept a Capability directly.
        assertEquals(Mode.THREE_D, DesktopMode.resolve("dev", ok));
        assertEquals(Mode.TWO_D, DesktopMode.resolve("dev", noGl));
        assertTrue(DesktopMode.requiresConfirmation("dev", noJ3d));
    }

    @Test
    @DisplayName("the simulateNo3D property makes the probe report no Java 3D")
    void probeCanSimulateMissingJava3d() {
        System.setProperty(DesktopMode.SIMULATE_NO_3D_PROPERTY, "true");
        Capability capability = DesktopMode.probe();
        assertFalse(capability.isJava3dPresent());
        assertFalse(capability.is3dAvailable());
        assertNotNull(capability.getReason());
        assertTrue(capability.getReason().contains(DesktopMode.SIMULATE_NO_3D_PROPERTY));
    }

    @Test
    @DisplayName("probe() never throws and never claims 3D without a reason-free result")
    void probeIsSafeAndSelfConsistent() {
        Capability capability = DesktopMode.probe();
        assertNotNull(capability);
        if (capability.is3dAvailable()) {
            assertNull(capability.getReason());
        } else {
            assertNotNull(capability.getReason());
        }
    }
}
