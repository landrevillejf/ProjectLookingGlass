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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Notifications panel.
 *
 * <p>Building {@link NotificationsPanel} reads the Do Not Disturb state and the
 * notification log through the {@code Desktop2D} control-center hooks, which fall
 * back to the persisted config and an empty log when no 2D shell is running (as in
 * the test JVM). It creates only lightweight Swing components, so constructing it
 * under {@code java.awt.headless=true} is CI-safe.</p>
 */
class NotificationsPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        NotificationsPanel panel = assertDoesNotThrow(NotificationsPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        NotificationsPanel panel = new NotificationsPanel();
        assertEquals("Notifications", panel.displayName());
        assertNull(panel.icon(), "the Notifications panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable Swing component and re-loads on show")
    void componentAndOnShow() {
        NotificationsPanel panel = new NotificationsPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(component, panel.component());
    }

    @Test
    @DisplayName("the DND option index maps the persisted state to Off/On/On-for-an-hour")
    void dndOptionIndexMaps() {
        assertEquals(0, NotificationsPanel.dndOptionIndex(false, 0L), "off");
        assertEquals(0, NotificationsPanel.dndOptionIndex(false, 123L), "off ignores a deadline");
        assertEquals(1, NotificationsPanel.dndOptionIndex(true, 0L), "on indefinitely");
        assertEquals(2, NotificationsPanel.dndOptionIndex(true, 123L), "on until a deadline");
    }
}
