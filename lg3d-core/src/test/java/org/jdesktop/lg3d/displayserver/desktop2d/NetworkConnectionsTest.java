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
 * Covers the pure seams of {@link NetworkConnections}: the {@code nmcli -t}
 * parsing (including the escaped-colon unescaping), the connect / disconnect
 * command builders and the row label. The nmcli probes are host-dependent and
 * are exercised only through their graceful-degradation contract, not asserted.
 */
class NetworkConnectionsTest {

    @Test
    @DisplayName("nmcli -t output yields one connection per line with active derived from device")
    void parsesConnections() {
        List<NetworkConnections.Connection> conns = NetworkConnections.parseConnections(
                "HomeWifi:802-11-wireless:wlp2s0\n"
              + "Wired 1:802-3-ethernet:\n"
              + "\n");
        assertEquals(2, conns.size());
        assertEquals("HomeWifi", conns.get(0).name());
        assertEquals("802-11-wireless", conns.get(0).type());
        assertEquals("wlp2s0", conns.get(0).device());
        assertTrue(conns.get(0).active(), "a bound device means active");
        assertEquals("Wired 1", conns.get(1).name());
        assertEquals("", conns.get(1).device());
        assertFalse(conns.get(1).active(), "an empty device means inactive");
    }

    @Test
    @DisplayName("an escaped colon inside a name is not a field separator")
    void unescapesColonInName() {
        List<NetworkConnections.Connection> conns = NetworkConnections.parseConnections(
                "My\\:Net:802-11-wireless:wlp2s0\n");
        assertEquals(1, conns.size());
        assertEquals("My:Net", conns.get(0).name());
        assertEquals("wlp2s0", conns.get(0).device());
    }

    @Test
    @DisplayName("null/blank/malformed input yields no connections")
    void parsesDegenerateInput() {
        assertTrue(NetworkConnections.parseConnections(null).isEmpty());
        assertTrue(NetworkConnections.parseConnections("").isEmpty());
        assertTrue(NetworkConnections.parseConnections("onlyonefield\n").isEmpty());
    }

    @Test
    @DisplayName("splitFields separates on unescaped colons only")
    void splitFields() {
        List<String> f = NetworkConnections.splitFields("a:b\\:c:d");
        assertEquals(3, f.size());
        assertEquals("a", f.get(0));
        assertEquals("b:c", f.get(1));
        assertEquals("d", f.get(2));
    }

    @Test
    @DisplayName("the connect / disconnect commands target nmcli connection up/down")
    void commandBuilders() {
        assertArrayEquals(new String[] {"nmcli", "connection", "up", "id", "HomeWifi"},
                NetworkConnections.activateCommand("HomeWifi"));
        assertArrayEquals(new String[] {"nmcli", "connection", "down", "id", "HomeWifi"},
                NetworkConnections.deactivateCommand("HomeWifi"));
    }

    @Test
    @DisplayName("the row label shows type and active device or inactive")
    void labelShowsState() {
        assertEquals("HomeWifi  (802-11-wireless, wlp2s0)", NetworkConnections.label(
                new NetworkConnections.Connection("HomeWifi", "802-11-wireless", "wlp2s0", true)));
        assertEquals("Wired 1  (802-3-ethernet, inactive)", NetworkConnections.label(
                new NetworkConnections.Connection("Wired 1", "802-3-ethernet", "", false)));
        assertEquals("", NetworkConnections.label(null));
    }
}
