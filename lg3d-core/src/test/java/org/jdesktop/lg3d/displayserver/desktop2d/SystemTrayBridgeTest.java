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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Image;
import java.util.Optional;
import org.jdesktop.lg3d.displayserver.desktop2d.NetworkStatus.Kind;
import org.jdesktop.lg3d.displayserver.desktop2d.NetworkStatus.State;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SystemTrayBridge} headlessly. A real {@code SystemTray} cannot
 * be constructed in a test, so the bridge is exercised through its null-tray
 * seam - reproducing an unsupported host, where every lifecycle call must no-op
 * without throwing - and through the pure image/tooltip helpers that select each
 * tray glyph.
 */
class SystemTrayBridgeTest {

    @Test
    @DisplayName("a null-tray bridge reports it is not mirroring")
    void nullTrayIsNotMirroring() {
        SystemTrayBridge bridge = new SystemTrayBridge(null);
        assertFalse(bridge.isMirroring());
    }

    @Test
    @DisplayName("the default bridge mirrors exactly when the host has a tray")
    void defaultBridgeMatchesSupport() {
        SystemTrayBridge bridge = new SystemTrayBridge();
        assertEquals(SystemTrayBridge.supported(), bridge.isMirroring());
    }

    @Test
    @DisplayName("every lifecycle call no-ops on an unsupported host")
    void lifecycleIsInertWithoutTray() {
        SystemTrayBridge bridge = new SystemTrayBridge(null);
        assertDoesNotThrow(bridge::start);
        assertDoesNotThrow(() -> bridge.applyConfig(DesktopConfig.get()));
        assertDoesNotThrow(bridge::refreshVolume);
        assertDoesNotThrow(bridge::refreshNetwork);
        assertDoesNotThrow(bridge::stop);
        assertFalse(bridge.isMirroring());
    }

    @Test
    @DisplayName("a null config is tolerated")
    void nullConfigIsSafe() {
        SystemTrayBridge bridge = new SystemTrayBridge(null);
        assertDoesNotThrow(() -> bridge.applyConfig(null));
    }

    @Test
    @DisplayName("the volume tray image and tooltip track the reading")
    void volumeHelpers() {
        Image image = SystemTrayBridge.volumeImage(
                Optional.of(new VolumeStatus.Level(40, false)));
        assertNotNull(image);
        assertEquals(SystemTrayBridge.ICON_SIZE, image.getWidth(null));
        assertEquals("Volume: 40%", SystemTrayBridge.volumeTooltip(
                Optional.of(new VolumeStatus.Level(40, false))));
        assertEquals("Volume: muted", SystemTrayBridge.volumeTooltip(
                Optional.of(new VolumeStatus.Level(40, true))));
    }

    @Test
    @DisplayName("an absent or null volume still yields an image and a tooltip")
    void volumeHelpersNullSafe() {
        assertNotNull(SystemTrayBridge.volumeImage(Optional.empty()));
        assertNotNull(SystemTrayBridge.volumeImage(null));
        assertEquals("Volume: unavailable",
                SystemTrayBridge.volumeTooltip(Optional.empty()));
        assertEquals("Volume: unavailable", SystemTrayBridge.volumeTooltip(null));
    }

    @Test
    @DisplayName("the network tray image and tooltip track the link state")
    void networkHelpers() {
        Image wifi = SystemTrayBridge.networkImage(new State(true, Kind.WIFI));
        assertNotNull(wifi);
        assertEquals(SystemTrayBridge.ICON_SIZE, wifi.getWidth(null));
        assertEquals("Network: wi-fi",
                SystemTrayBridge.networkTooltip(new State(true, Kind.WIFI)));
        assertEquals("Network: ethernet",
                SystemTrayBridge.networkTooltip(new State(true, Kind.ETHERNET)));
        assertEquals("Network: offline",
                SystemTrayBridge.networkTooltip(State.offline()));
    }

    @Test
    @DisplayName("a null network state reads as offline")
    void networkHelpersNullSafe() {
        assertNotNull(SystemTrayBridge.networkImage(null));
        assertEquals("Network: offline", SystemTrayBridge.networkTooltip(null));
    }
}
