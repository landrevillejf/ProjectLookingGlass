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
 * Headless construction test for the control center's Language &amp; Region panel.
 *
 * <p>Building {@link LocalePanel} reads the current LANG and the locale list
 * through the {@code LocaleStatus} seam, which degrades to a read-only note on a
 * host without systemd (as in the test JVM). It creates only lightweight Swing
 * components, so constructing it under {@code java.awt.headless=true} is
 * CI-safe.</p>
 */
class LocalePanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        LocalePanel panel = assertDoesNotThrow(LocalePanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        LocalePanel panel = new LocalePanel();
        assertEquals("Language & Region", panel.displayName());
        assertNull(panel.icon(), "the Language & Region panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable Swing component and re-loads on show")
    void componentAndOnShow() {
        LocalePanel panel = new LocalePanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(component, panel.component());
    }

    @Test
    @DisplayName("the locale index preselects the current LANG and nothing otherwise")
    void localeIndexPreselects() {
        List<String> locales = List.of("C.UTF-8", "en_US.UTF-8", "fr_FR.UTF-8");
        assertEquals(1, LocalePanel.localeIndex(locales, "en_US.UTF-8"));
        assertEquals(-1, LocalePanel.localeIndex(locales, "de_DE.UTF-8"), "an unlisted locale");
        assertEquals(-1, LocalePanel.localeIndex(locales, ""), "a blank LANG");
        assertEquals(-1, LocalePanel.localeIndex(locales, null));
        assertEquals(-1, LocalePanel.localeIndex(null, "C.UTF-8"));
        assertEquals(-1, LocalePanel.localeIndex(List.of(), "C.UTF-8"), "an empty list has no rows");
    }
}
