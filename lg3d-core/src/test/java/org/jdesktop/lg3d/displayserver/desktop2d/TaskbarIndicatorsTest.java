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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.jdesktop.lg3d.displayserver.desktop2d.BatteryStatus.Level;
import org.jdesktop.lg3d.displayserver.desktop2d.NetworkStatus.Kind;
import org.jdesktop.lg3d.displayserver.desktop2d.NetworkStatus.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link TaskbarIndicators}' {@code applyX} seams with injected readings,
 * so no platform probe or display is needed. The panel is constructed headless
 * and its polling timers are stopped after each test.
 */
class TaskbarIndicatorsTest {

    private TaskbarIndicators indicators;

    @BeforeEach
    void setUp() {
        indicators = new TaskbarIndicators();
        indicators.stop();
    }

    @AfterEach
    void tearDown() {
        indicators.stop();
    }

    @Test
    @DisplayName("a present battery shows its glyph and is visible")
    void batteryPresent() {
        indicators.applyBattery(Optional.of(new Level(63, true)));
        assertTrue(indicators.batteryVisible());
        assertEquals("Bat 63% +", indicators.batteryText());
    }

    @Test
    @DisplayName("an absent battery hides its glyph")
    void batteryAbsent() {
        indicators.applyBattery(Optional.of(new Level(10, false)));
        assertTrue(indicators.batteryVisible());
        indicators.applyBattery(Optional.empty());
        assertFalse(indicators.batteryVisible(), "no battery means no glyph");
    }

    @Test
    @DisplayName("the network glyph tracks the link kind")
    void networkStates() {
        indicators.applyNetwork(new State(true, Kind.ETHERNET));
        assertEquals("Net ==", indicators.networkText());
        indicators.applyNetwork(new State(true, Kind.WIFI));
        assertEquals("Net ))", indicators.networkText());
        indicators.applyNetwork(State.offline());
        assertEquals("Net --", indicators.networkText());
    }

    @Test
    @DisplayName("a present volume shows its glyph and is visible")
    void volumePresent() {
        indicators.applyVolume(Optional.of(new VolumeStatus.Level(30, false)));
        assertTrue(indicators.volumeVisible());
        assertEquals("Vol 30%", indicators.volumeText());
    }

    @Test
    @DisplayName("a muted volume shows the mute glyph")
    void volumeMuted() {
        indicators.applyVolume(Optional.of(new VolumeStatus.Level(30, true)));
        assertEquals("Vol x", indicators.volumeText());
    }

    @Test
    @DisplayName("an unavailable master control hides the volume glyph")
    void volumeAbsent() {
        indicators.applyVolume(Optional.of(new VolumeStatus.Level(10, false)));
        assertTrue(indicators.volumeVisible());
        indicators.applyVolume(Optional.empty());
        assertFalse(indicators.volumeVisible());
    }
}
