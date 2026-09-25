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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    @DisplayName("a present backlight shows its glyph and is visible")
    void brightnessPresent() {
        indicators.applyBrightness(Optional.of(new BrightnessStatus.Level(60)));
        assertTrue(indicators.brightnessVisible());
        assertEquals("Bri 60%", indicators.brightnessText());
    }

    @Test
    @DisplayName("an absent backlight hides the brightness glyph")
    void brightnessAbsent() {
        indicators.applyBrightness(Optional.of(new BrightnessStatus.Level(60)));
        assertTrue(indicators.brightnessVisible());
        indicators.applyBrightness(Optional.empty());
        assertFalse(indicators.brightnessVisible(), "no backlight means no glyph");
    }

    @Test
    @DisplayName("a present battery carries a gauge icon")
    void batteryGaugeIcon() {
        indicators.applyBattery(Optional.of(new Level(63, true)));
        assertNotNull(indicators.batteryIcon(), "the battery shows a filled gauge");
    }

    @Test
    @DisplayName("a present backlight carries a gauge icon")
    void brightnessGaugeIcon() {
        indicators.applyBrightness(Optional.of(new BrightnessStatus.Level(60)));
        assertNotNull(indicators.brightnessIcon(), "the brightness shows a filled gauge");
    }

    @Test
    @DisplayName("a refused hardware write dims in software and the value sticks")
    void brightnessFallsBackToSoftwareDimAndSticks() {
        // Only meaningful where the backlight is NOT writable: there the slider
        // must still produce a visible, persistent change.
        assumeFalse(BrightnessStatus.isControllable());
        AtomicInteger dimmed = new AtomicInteger(-1);
        indicators.setSoftwareBrightness(dimmed::set);

        indicators.applyUserBrightness(40);
        assertEquals(40, dimmed.get(), "a refused write must reach the software dim");
        assertEquals("Bri 40%", indicators.brightnessText());

        // The poll reads the unchanged hardware value but must not snap back.
        indicators.applyBrightness(Optional.of(new BrightnessStatus.Level(79)), false);
        assertEquals("Bri 40%", indicators.brightnessText(),
                "the dragged value wins while the hardware is not controllable");
    }

    @Test
    @DisplayName("a controllable backlight lets the hardware reading win the poll")
    void brightnessPollReflectsControllableHardware() {
        indicators.applyUserBrightness(40);
        indicators.applyBrightness(Optional.of(new BrightnessStatus.Level(79)), true);
        assertEquals("Bri 79%", indicators.brightnessText(),
                "a writable backlight reports its own value back");
    }
}
