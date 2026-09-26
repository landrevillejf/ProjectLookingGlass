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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure seams of {@link BluetoothStatus}: the {@code bluetoothctl list}
 * / {@code devices} / {@code show} parsing, the {@code rfkill} state, the command
 * builders and the row labels. The {@code bluetoothctl}/{@code rfkill} probes
 * themselves are host-dependent and are exercised only through their
 * graceful-degradation contract, not asserted here.
 */
class BluetoothStatusTest {

    @Test
    @DisplayName("bluetoothctl list yields controllers with mac, name and default flag")
    void parsesControllers() {
        List<BluetoothStatus.Controller> cs = BluetoothStatus.parseControllers(
                "Controller 11:22:33:44:55:66 myhost #1 [default]\n"
              + "Controller AA:BB:CC:DD:EE:FF BlueZ Adapter #2\n"
              + "\n"
              + "not a controller line\n");
        assertEquals(2, cs.size());
        assertEquals("11:22:33:44:55:66", cs.get(0).mac());
        assertEquals("myhost", cs.get(0).name());
        assertTrue(cs.get(0).isDefault());
        assertEquals("BlueZ Adapter", cs.get(1).name(), "a multi-word name is kept whole");
        assertFalse(cs.get(1).isDefault());
        assertTrue(BluetoothStatus.parseControllers(null).isEmpty());
    }

    @Test
    @DisplayName("bluetoothctl devices yields devices, using the mac when unnamed")
    void parsesDevices() {
        List<BluetoothStatus.Device> ds = BluetoothStatus.parseDevices(
                "Device 11:22:33:44:55:66 Some Headphones\n"
              + "Device AA:BB:CC:DD:EE:FF\n"
              + "junk\n");
        assertEquals(2, ds.size());
        assertEquals("11:22:33:44:55:66", ds.get(0).mac());
        assertEquals("Some Headphones", ds.get(0).name());
        assertEquals("AA:BB:CC:DD:EE:FF", ds.get(1).name(), "an unnamed device falls back to its mac");
        assertTrue(BluetoothStatus.parseDevices(null).isEmpty());
    }

    @Test
    @DisplayName("the Powered property is read from bluetoothctl show output")
    void parsesPowered() {
        assertEquals(Boolean.TRUE, BluetoothStatus.parsePowered(
                "Controller 11:22:33:44:55:66 (public)\n"
              + "\tName: myhost\n"
              + "\tPowered: yes\n"
              + "\tDiscoverable: no\n"));
        assertEquals(Boolean.FALSE, BluetoothStatus.parsePowered("\tPowered: no\n"));
        assertNull(BluetoothStatus.parsePowered("\tName: myhost\n"), "no Powered property");
        assertNull(BluetoothStatus.parsePowered(null));
    }

    @Test
    @DisplayName("rfkill list bluetooth reports presence and the soft/hard block flags")
    void parsesRfkill() {
        BluetoothStatus.Rfkill clear = BluetoothStatus.parseRfkill(
                "0: hci0: Bluetooth\n\tSoft blocked: no\n\tHard blocked: no\n");
        assertTrue(clear.present());
        assertFalse(clear.softBlocked());
        assertFalse(clear.hardBlocked());

        BluetoothStatus.Rfkill blocked = BluetoothStatus.parseRfkill(
                "0: hci0: Bluetooth\n\tSoft blocked: yes\n\tHard blocked: no\n");
        assertTrue(blocked.softBlocked());

        assertFalse(BluetoothStatus.parseRfkill(null).present(), "no radio known to rfkill");
        assertFalse(BluetoothStatus.parseRfkill("   ").present());
    }

    @Test
    @DisplayName("the commands target bluetoothctl power / connect / disconnect")
    void commandBuilders() {
        assertArrayEquals(new String[] {"bluetoothctl", "power", "on"},
                BluetoothStatus.powerCommand(true));
        assertArrayEquals(new String[] {"bluetoothctl", "power", "off"},
                BluetoothStatus.powerCommand(false));
        assertArrayEquals(new String[] {"bluetoothctl", "connect", "AA:BB"},
                BluetoothStatus.connectCommand("AA:BB"));
        assertArrayEquals(new String[] {"bluetoothctl", "disconnect", "AA:BB"},
                BluetoothStatus.disconnectCommand("AA:BB"));
    }

    @Test
    @DisplayName("the row labels show name, mac and the default marker")
    void labels() {
        assertEquals("myhost  (11:22:33:44:55:66)  [default]",
                BluetoothStatus.label(new BluetoothStatus.Controller("11:22:33:44:55:66", "myhost", true)));
        assertEquals("Speaker  (AA:BB)",
                BluetoothStatus.label(new BluetoothStatus.Device("AA:BB", "Speaker")));
        assertEquals("", BluetoothStatus.label((BluetoothStatus.Controller) null));
        assertEquals("", BluetoothStatus.label((BluetoothStatus.Device) null));
    }
}
