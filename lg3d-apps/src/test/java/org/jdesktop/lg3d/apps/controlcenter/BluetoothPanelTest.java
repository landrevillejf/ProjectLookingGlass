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

import javax.swing.JComponent;
import org.jdesktop.lg3d.displayserver.desktop2d.BluetoothStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Bluetooth panel.
 *
 * <p>Building {@link BluetoothPanel} probes the adapter, devices and rfkill state
 * through the {@code BluetoothStatus} seam, which degrades to a "no adapter" note
 * when {@code bluetoothctl}/{@code rfkill} are absent (as in the headless test
 * JVM). It creates only lightweight Swing components, so constructing it under
 * {@code java.awt.headless=true} is CI-safe. The pure note helper is asserted
 * directly.</p>
 */
class BluetoothPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        BluetoothPanel panel = assertDoesNotThrow(BluetoothPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        BluetoothPanel panel = new BluetoothPanel();
        assertEquals("Bluetooth", panel.displayName());
        assertNull(panel.icon(), "the Bluetooth panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable Swing component and re-loads on show")
    void componentAndOnShow() {
        BluetoothPanel panel = new BluetoothPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(component, panel.component());
    }

    @Test
    @DisplayName("the no-adapter note distinguishes a hard block, a soft block and an absent radio")
    void blockedNoteDistinguishes() {
        assertEquals("Bluetooth is hard-blocked by a hardware switch.",
                BluetoothPanel.blockedNote(new BluetoothStatus.Rfkill(true, true, true)),
                "a hard block wins over a soft block");
        assertEquals("Bluetooth is hard-blocked by a hardware switch.",
                BluetoothPanel.blockedNote(new BluetoothStatus.Rfkill(true, false, true)));
        assertEquals("Bluetooth is soft-blocked (rfkill); unblock it to use the adapter.",
                BluetoothPanel.blockedNote(new BluetoothStatus.Rfkill(true, true, false)));
        assertEquals("No Bluetooth adapter found.",
                BluetoothPanel.blockedNote(new BluetoothStatus.Rfkill(false, false, false)),
                "a present-but-clear radio is simply absent from the controller list");
        assertEquals("No Bluetooth adapter found.", BluetoothPanel.blockedNote(null),
                "no rfkill radio at all");
    }
}
