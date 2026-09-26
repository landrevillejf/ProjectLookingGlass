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
package org.jdesktop.lg3d.apps.controlcenter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import javax.swing.JComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Date &amp; Time panel.
 *
 * <p>Building {@link DateTimePanel} reads the time zone, local time, NTP flag and
 * zone list through the {@code TimeZoneStatus} seam, which degrades to a read-only
 * note on a host without systemd (as in the test JVM). It creates only lightweight
 * Swing components, so constructing it under {@code java.awt.headless=true} is
 * CI-safe.</p>
 */
class DateTimePanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        DateTimePanel panel = assertDoesNotThrow(DateTimePanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        DateTimePanel panel = new DateTimePanel();
        assertEquals("Date & Time", panel.displayName());
        assertNull(panel.icon(), "the Date & Time panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable Swing component and re-loads on show")
    void componentAndOnShow() {
        DateTimePanel panel = new DateTimePanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(component, panel.component());
    }

    @Test
    @DisplayName("the zone index preselects the current zone and nothing otherwise")
    void zoneIndexPreselects() {
        List<String> zones = List.of("America/New_York", "Europe/Paris", "UTC");
        assertEquals(1, DateTimePanel.zoneIndex(zones, "Europe/Paris"));
        assertEquals(-1, DateTimePanel.zoneIndex(zones, "Mars/Olympus"), "an unlisted zone");
        assertEquals(-1, DateTimePanel.zoneIndex(zones, ""), "a blank zone");
        assertEquals(-1, DateTimePanel.zoneIndex(zones, null));
        assertEquals(-1, DateTimePanel.zoneIndex(null, "UTC"));
        assertEquals(-1, DateTimePanel.zoneIndex(List.of(), "UTC"), "an empty list has no rows");
    }
}
