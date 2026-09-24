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

import org.jdesktop.lg3d.displayserver.desktop2d.NetworkStatus.Kind;
import org.jdesktop.lg3d.displayserver.desktop2d.NetworkStatus.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link NetworkStatus}'s pure classification and formatting: the
 * up/wifi decision, the interface-name heuristic, and the glyph/tooltip text.
 * No {@link java.net.NetworkInterface} walk is exercised, so the suite is
 * deterministic and headless.
 */
class NetworkStatusTest {

    @Test
    @DisplayName("no up non-loopback interface means offline")
    void classifiesOffline() {
        State state = NetworkStatus.classify(false, true);
        assertFalse(state.online());
        assertEquals(Kind.OFFLINE, state.kind());
    }

    @Test
    @DisplayName("an up interface with no wifi name is ethernet")
    void classifiesEthernet() {
        State state = NetworkStatus.classify(true, false);
        assertTrue(state.online());
        assertEquals(Kind.ETHERNET, state.kind());
    }

    @Test
    @DisplayName("an up interface flagged wifi is wifi")
    void classifiesWifi() {
        State state = NetworkStatus.classify(true, true);
        assertTrue(state.online());
        assertEquals(Kind.WIFI, state.kind());
    }

    @Test
    @DisplayName("offline() reports offline regardless of kind")
    void offlineSingleton() {
        State state = State.offline();
        assertFalse(state.online());
        assertEquals(Kind.OFFLINE, state.kind());
    }

    @Test
    @DisplayName("wireless interface names are recognised")
    void detectsWifiNames() {
        assertTrue(NetworkStatus.isWifiName("wlan0"));
        assertTrue(NetworkStatus.isWifiName("wlp3s0"));
        assertTrue(NetworkStatus.isWifiName("WLAN1"));
        assertTrue(NetworkStatus.isWifiName("ath0"));
    }

    @Test
    @DisplayName("wired and null names are not wifi")
    void rejectsNonWifiNames() {
        assertFalse(NetworkStatus.isWifiName("eth0"));
        assertFalse(NetworkStatus.isWifiName("enp0s25"));
        assertFalse(NetworkStatus.isWifiName(null));
    }

    @Test
    @DisplayName("each kind has a distinct glyph, and null reads offline")
    void glyphFormatting() {
        assertEquals("Net --", NetworkStatus.glyph(null));
        assertEquals("Net --", NetworkStatus.glyph(State.offline()));
        assertEquals("Net ==", NetworkStatus.glyph(new State(true, Kind.ETHERNET)));
        assertEquals("Net ))", NetworkStatus.glyph(new State(true, Kind.WIFI)));
    }

    @Test
    @DisplayName("label gives a human tooltip for each state")
    void labelFormatting() {
        assertEquals("Network: offline", NetworkStatus.label(null));
        assertEquals("Network: offline", NetworkStatus.label(State.offline()));
        assertEquals("Network: ethernet", NetworkStatus.label(new State(true, Kind.ETHERNET)));
        assertEquals("Network: wi-fi", NetworkStatus.label(new State(true, Kind.WIFI)));
    }
}
