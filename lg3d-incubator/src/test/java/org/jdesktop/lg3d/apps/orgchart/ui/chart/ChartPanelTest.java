/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.orgchart.ui.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.prefs.Preferences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link ChartPanel}, the 2D/Swing counterpart of Chart3D.
 * Clearing {@code /contacts} first makes the panel import the bundled
 * {@code contacts.xml}, whose four sample contacts all resolve to top-level
 * roots (three name an absent "Juan Soto" manager, Duke names none).
 */
class ChartPanelTest {

    @BeforeEach
    void clear() {
        try {
            if (Preferences.userRoot().nodeExists("/contacts")) {
                Preferences.userRoot().node("/contacts").removeNode();
            }
        } catch (Exception e) {
            // best effort
        }
    }

    @Test
    void loadsTheSharedDirectory() {
        ChartPanel panel = new ChartPanel();
        assertEquals(4, panel.getPersonCount());
        assertEquals(4, panel.getPeople().size());
    }

    @Test
    void buildsRootsForContactsWithoutALiveManager() {
        ChartPanel panel = new ChartPanel();
        // "Juan Soto" is not a node in contacts.xml, so his three reports and
        // manager-less Duke all become top-level roots under the org node.
        assertEquals(4, panel.getRootNode().getChildCount());
    }

    @Test
    void querySelectsAMatchingPerson() {
        ChartPanel panel = new ChartPanel();
        assertTrue(panel.selectByName("Duke"));
        assertTrue(panel.getDetailName().contains("Duke"));
        assertTrue(panel.selectByName("hideya"));
        assertTrue(panel.getDetailName().contains("Hideya"));
    }

    @Test
    void queryWithNoMatchReturnsFalse() {
        ChartPanel panel = new ChartPanel();
        assertFalse(panel.selectByName("nobody-here"));
        assertFalse(panel.selectByName("   "));
        assertFalse(panel.selectByName(null));
    }

    @Test
    void showDetailHandlesNullAndValue() {
        ChartPanel panel = new ChartPanel();
        panel.showDetail(null);
        assertEquals(" ", panel.getDetailName());
        panel.showDetail(panel.getPeople().get(0));
        assertEquals(panel.getPeople().get(0).display, panel.getDetailName());
    }
}
