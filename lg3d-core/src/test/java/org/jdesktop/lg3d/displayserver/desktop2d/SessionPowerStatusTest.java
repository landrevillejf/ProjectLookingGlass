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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure command builders of {@link SessionPowerStatus}: the exact
 * argv each action produces and the lock-tool fallback precedence
 * (loginctl &gt; xdg-screensaver &gt; none). Runs headless and never launches a
 * process - only the deterministic builders are exercised, not the probes or
 * the fire-and-forget actions that shell out.
 */
class SessionPowerStatusTest {

    @Test
    @DisplayName("lock prefers loginctl when it is available")
    void lockPrefersLoginctl() {
        assertArrayEquals(new String[] {"loginctl", "lock-session"},
                SessionPowerStatus.lockCommand(true, true));
        assertArrayEquals(new String[] {"loginctl", "lock-session"},
                SessionPowerStatus.lockCommand(true, false));
    }

    @Test
    @DisplayName("lock falls back to xdg-screensaver without loginctl")
    void lockFallsBackToXdgScreensaver() {
        assertArrayEquals(new String[] {"xdg-screensaver", "lock"},
                SessionPowerStatus.lockCommand(false, true));
    }

    @Test
    @DisplayName("lock is null when neither tool is available")
    void lockNullWhenNeitherAvailable() {
        assertNull(SessionPowerStatus.lockCommand(false, false));
    }

    @Test
    @DisplayName("suspend/reboot/poweroff map to their systemctl verbs")
    void powerCommands() {
        assertArrayEquals(new String[] {"systemctl", "suspend"},
                SessionPowerStatus.suspendCommand());
        assertArrayEquals(new String[] {"systemctl", "reboot"},
                SessionPowerStatus.rebootCommand());
        assertArrayEquals(new String[] {"systemctl", "poweroff"},
                SessionPowerStatus.poweroffCommand());
    }
}
