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
 * Headless construction test for the control center's Schedule panel.
 *
 * <p>Building {@link SchedulePanel} reads the schedule fields from
 * {@code DesktopConfig} and enumerates the bundled wallpapers (falling back to
 * a fixed list when the background directory is not on the classpath, as in the
 * test JVM). It creates only lightweight Swing components - no top-level window
 * - so constructing it under {@code java.awt.headless=true} (set by the module's
 * test task) is CI-safe and needs no display.</p>
 */
class SchedulePanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        SchedulePanel panel = assertDoesNotThrow(SchedulePanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        SchedulePanel panel = new SchedulePanel();
        assertEquals("Schedule", panel.displayName());
        assertNull(panel.icon(), "the Schedule panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a Swing component and re-loads on show")
    void componentAndOnShow() {
        SchedulePanel panel = new SchedulePanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        // The component is stable across shows (created once, reused).
        assertEquals(component, panel.component());
    }
}
