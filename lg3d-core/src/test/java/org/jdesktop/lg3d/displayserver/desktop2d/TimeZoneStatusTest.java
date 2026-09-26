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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure seams of {@link TimeZoneStatus}: the {@code timedatectl show}
 * property parsing, the boolean reading, the zone-list parsing and the management
 * command builders. The {@code timedatectl} probes themselves are host-dependent
 * and are exercised only through their graceful-degradation contract, not
 * asserted here.
 */
class TimeZoneStatusTest {

    private static final String SHOW =
            "Timezone=America/New_York\n"
          + "LocalTime=Fri 2026-01-02 15:04:05 EST\n"
          + "UniversalTime=Fri 2026-01-02 20:04:05 UTC\n"
          + "NTP=yes\n"
          + "NTPSynchronized=yes\n"
          + "CanNTP=yes\n";

    @Test
    @DisplayName("a Key=value property is read from timedatectl show output")
    void parsesProperty() {
        assertEquals("America/New_York", TimeZoneStatus.parseProperty(SHOW, "Timezone"));
        assertEquals("yes", TimeZoneStatus.parseProperty(SHOW, "NTP"));
        assertEquals("", TimeZoneStatus.parseProperty(SHOW, "Absent"), "a missing key is empty");
        assertEquals("", TimeZoneStatus.parseProperty(null, "Timezone"));
        assertEquals("", TimeZoneStatus.parseProperty(SHOW, null));
    }

    @Test
    @DisplayName("systemd boolean properties read yes/true/1/active as on")
    void parsesBoolean() {
        assertTrue(TimeZoneStatus.parseBoolean("yes"));
        assertTrue(TimeZoneStatus.parseBoolean("true"));
        assertTrue(TimeZoneStatus.parseBoolean("1"));
        assertTrue(TimeZoneStatus.parseBoolean("active"));
        assertFalse(TimeZoneStatus.parseBoolean("no"));
        assertFalse(TimeZoneStatus.parseBoolean(""));
        assertFalse(TimeZoneStatus.parseBoolean(null));
    }

    @Test
    @DisplayName("the current zone, local time and NTP flag come from the show output")
    void parsesShowFields() {
        assertEquals("America/New_York", TimeZoneStatus.parseTimezone(SHOW));
        assertEquals("Fri 2026-01-02 15:04:05 EST", TimeZoneStatus.parseLocalTime(SHOW));
        assertTrue(TimeZoneStatus.parseNtpEnabled(SHOW));
        assertFalse(TimeZoneStatus.parseNtpEnabled("NTP=no\n"), "NTP=no reads as disabled");
    }

    @Test
    @DisplayName("the local time falls back to TimeUSec on systemd without LocalTime")
    void parsesLocalTimeFallback() {
        assertEquals("Sat 2026-09-26 10:59:22 EDT",
                TimeZoneStatus.parseLocalTime("Timezone=America/New_York\n"
                        + "TimeUSec=Sat 2026-09-26 10:59:22 EDT\n"
                        + "RTCTimeUSec=Sat 2026-09-26 14:59:22 UTC\n"),
                "newer systemd reports the wall clock as TimeUSec");
        assertEquals("Fri 2026-01-02 15:04:05 EST",
                TimeZoneStatus.parseLocalTime(SHOW + "TimeUSec=ignored\n"),
                "LocalTime wins when both properties are present");
        assertEquals("", TimeZoneStatus.parseLocalTime("Timezone=UTC\n"),
                "no local-time property at all is empty");
    }

    @Test
    @DisplayName("list-timezones output yields one zone per line, skipping blanks")
    void parsesTimezones() {
        List<String> zones = TimeZoneStatus.parseTimezones(
                "America/New_York\nEurope/Paris\n\n   \nUTC\n");
        assertEquals(3, zones.size());
        assertEquals("America/New_York", zones.get(0));
        assertEquals("UTC", zones.get(2));
        assertTrue(TimeZoneStatus.parseTimezones(null).isEmpty());
        assertTrue(TimeZoneStatus.parseTimezones("").isEmpty());
    }

    @Test
    @DisplayName("the management commands target timedatectl set-timezone / set-ntp")
    void commandBuilders() {
        assertArrayEquals(new String[] {"timedatectl", "set-timezone", "Europe/Paris"},
                TimeZoneStatus.setTimezoneCommand("Europe/Paris"));
        assertArrayEquals(new String[] {"timedatectl", "set-ntp", "true"},
                TimeZoneStatus.setNtpCommand(true));
        assertArrayEquals(new String[] {"timedatectl", "set-ntp", "false"},
                TimeZoneStatus.setNtpCommand(false));
    }
}
