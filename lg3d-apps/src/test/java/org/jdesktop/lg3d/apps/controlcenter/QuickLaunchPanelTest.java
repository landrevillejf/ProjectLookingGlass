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
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Quick Launch panel.
 *
 * <p>Building {@link QuickLaunchPanel} reads the pinned and candidate lists
 * through the {@code Desktop2D} control-center hooks, which report empty when no
 * 2D shell is running (as in the test JVM); the pin / un-pin / move / reset
 * mutations are no-ops in that state. It creates only lightweight Swing
 * components ({@code JList} / {@code JButton}, never a combo box), so
 * constructing it under {@code java.awt.headless=true} is CI-safe. The pure
 * {@link QuickLaunchPanel#labelFor} helper is asserted directly.</p>
 */
class QuickLaunchPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        QuickLaunchPanel panel = assertDoesNotThrow(QuickLaunchPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        QuickLaunchPanel panel = new QuickLaunchPanel();
        assertEquals("Quick Launch", panel.displayName());
        assertNull(panel.icon(), "the Quick Launch panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable Swing component and re-loads on show")
    void componentAndOnShow() {
        QuickLaunchPanel panel = new QuickLaunchPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(component, panel.component());
    }

    @Test
    @DisplayName("labelFor prefers the name and falls back to the command")
    void labelForFallsBackToCommand() {
        assertEquals("Mail", QuickLaunchPanel.labelFor(
                new Desktop2D.QuickLaunchItem("Mail", "java mail.Mail", null)));
        assertEquals("java mail.Mail", QuickLaunchPanel.labelFor(
                new Desktop2D.QuickLaunchItem("", "java mail.Mail", null)),
                "an empty name falls back to the command");
        assertEquals("java mail.Mail", QuickLaunchPanel.labelFor(
                new Desktop2D.QuickLaunchItem("   ", "java mail.Mail", null)),
                "a blank name falls back to the command");
        assertEquals("", QuickLaunchPanel.labelFor(null), "a null item is blank");
    }
}
