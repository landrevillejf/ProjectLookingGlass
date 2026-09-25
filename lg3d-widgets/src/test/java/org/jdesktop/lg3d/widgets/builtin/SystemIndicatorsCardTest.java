/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.widgets.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.displayserver.desktop2d.BatteryStatus;
import org.jdesktop.lg3d.displayserver.desktop2d.BrightnessStatus;
import org.jdesktop.lg3d.displayserver.desktop2d.NetworkStatus;
import org.jdesktop.lg3d.displayserver.desktop2d.VolumeStatus;
import org.jdesktop.lg3d.widgets.builtin.SystemIndicatorsCard.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the pure row assembly in {@link SystemIndicatorsCard}:
 * cluster ordering, the graceful omission of absent hardware, the "no
 * indicators" fallback, the muted-volume/no-gauge rule and the battery colour
 * hand-off to {@link BatteryStatus#color}. The card ticks and paints without a
 * live platform (every seam guards its probe), so the identity/tick path is
 * exercised too.
 */
class SystemIndicatorsCardTest {

    private static final NetworkStatus.State WIFI =
            new NetworkStatus.State(true, NetworkStatus.Kind.WIFI);
    private static final NetworkStatus.State OFFLINE = NetworkStatus.State.offline();
    private static final VolumeStatus.Level VOL = new VolumeStatus.Level(42, false);
    private static final VolumeStatus.Level MUTED = new VolumeStatus.Level(42, true);
    private static final BrightnessStatus.Level BRI = new BrightnessStatus.Level(60);
    private static final BatteryStatus.Level BAT = new BatteryStatus.Level(85, false);

    @Test
    @DisplayName("all four present -> four rows in cluster order")
    void allPresentInClusterOrder() {
        List<Row> rows = SystemIndicatorsCard.rows(WIFI, VOL, BRI, BAT);
        assertEquals(4, rows.size());
        assertEquals(NetworkStatus.glyph(WIFI), rows.get(0).text());
        assertEquals(VolumeStatus.glyph(VOL), rows.get(1).text());
        assertEquals(BrightnessStatus.glyph(BRI), rows.get(2).text());
        assertEquals(BatteryStatus.glyph(BAT), rows.get(3).text());
    }

    @Test
    @DisplayName("percentage rows carry their percent; network has no gauge")
    void percentsAndGauges() {
        List<Row> rows = SystemIndicatorsCard.rows(WIFI, VOL, BRI, BAT);
        assertEquals(SystemIndicatorsCard.NO_GAUGE, rows.get(0).percent(), "network: no bar");
        assertEquals(42, rows.get(1).percent(), "volume");
        assertEquals(60, rows.get(2).percent(), "brightness");
        assertEquals(85, rows.get(3).percent(), "battery");
    }

    @Test
    @DisplayName("a muted volume drops its gauge bar")
    void mutedVolumeHasNoGauge() {
        List<Row> rows = SystemIndicatorsCard.rows(WIFI, MUTED, null, null);
        Row volume = rows.get(1);
        assertEquals("Vol x", volume.text());
        assertEquals(SystemIndicatorsCard.NO_GAUGE, volume.percent());
    }

    @Test
    @DisplayName("the battery row defers its colour to BatteryStatus.color")
    void batteryColourHandoff() {
        List<Row> rows = SystemIndicatorsCard.rows(null, null, null, BAT);
        assertEquals(BatteryStatus.color(BAT), rows.get(0).color());
    }

    @Test
    @DisplayName("online vs offline network rows differ in colour")
    void networkColourReflectsLinkState() {
        Row on = SystemIndicatorsCard.rows(WIFI, null, null, null).get(0);
        Row off = SystemIndicatorsCard.rows(OFFLINE, null, null, null).get(0);
        assertEquals("Net ))", on.text());
        assertEquals("Net --", off.text());
        assertNotEquals(on.color(), off.color());
    }

    @Test
    @DisplayName("absent hardware is omitted, not blanked")
    void absentIndicatorsAreOmitted() {
        List<Row> rows = SystemIndicatorsCard.rows(OFFLINE, null, null, null);
        assertEquals(1, rows.size());
        assertEquals(NetworkStatus.glyph(OFFLINE), rows.get(0).text());
    }

    @Test
    @DisplayName("nothing present -> one dim placeholder row, never empty")
    void allAbsentFallsBackToPlaceholder() {
        List<Row> rows = SystemIndicatorsCard.rows(null, null, null, null);
        assertEquals(1, rows.size());
        assertEquals("No indicators", rows.get(0).text());
        assertEquals(SystemIndicatorsCard.NO_GAUGE, rows.get(0).percent());
    }

    @Test
    @DisplayName("the card reports its identity and ticks headless without throwing")
    void cardIdentityAndHeadlessTick() {
        SystemIndicatorsCard card = new SystemIndicatorsCard();
        assertEquals("indicators", card.id());
        assertEquals(5000L, card.tickPeriodMillis());
        assertEquals(160, card.getPreferredSize().width);
        assertEquals(130, card.getPreferredSize().height);

        card.attach(null, null, null);
        card.tick();   // every probe is guarded; safe with no battery/backlight/sound
        card.start();
        card.stop();
        assertTrue(true);
    }
}
