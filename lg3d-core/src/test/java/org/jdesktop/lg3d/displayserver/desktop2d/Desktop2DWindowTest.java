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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link Desktop2DWindow}'s metadata accessors and the menu bar it now
 * builds for every 2D application window: a single <em>Help</em> menu with an
 * <em>About&nbsp;&lt;App&gt;</em> item wired to the reusable
 * {@link AboutDialog}. A {@code JInternalFrame} and its menus construct without
 * a peer, so this runs headless; the About action is never fired (it would open
 * a modal dialog that needs a display), only asserted present and wired.
 */
class Desktop2DWindowTest {

    private static final String CMD =
            "java org.jdesktop.lg3d.apps.calculator.Calculator";

    /** The single Help &rarr; About item this window's menu bar must carry. */
    private static JMenuItem aboutItem(Desktop2DWindow window) {
        JMenuBar bar = window.getJMenuBar();
        assertNotNull(bar, "every 2D window gets a menu bar");
        assertEquals(1, bar.getMenuCount());
        JMenu help = bar.getMenu(0);
        assertEquals("Help", help.getText());
        assertEquals(1, help.getItemCount());
        return help.getItem(0);
    }

    @Test
    @DisplayName("the full constructor retains every piece of metadata")
    void fullConstructorRetainsMetadata() {
        Desktop2DWindow window = new Desktop2DWindow("Calculator", null,
                new JPanel(), "Calculator", CMD, "resources/icon.png",
                "A scientific calculator");
        assertEquals("Calculator", window.getTitle());
        assertEquals("Calculator", window.getAppName());
        assertEquals(CMD, window.getCommand());
        assertEquals("resources/icon.png", window.getIconResource());
        assertEquals("A scientific calculator", window.getDescription());
    }

    @Test
    @DisplayName("the menu bar carries a Help menu with an About <App> item")
    void menuBarHasHelpAboutItem() {
        Desktop2DWindow window = new Desktop2DWindow("Backup", null,
                new JPanel(), "Backup", CMD, null, "Back up and restore files");
        JMenuItem about = aboutItem(window);
        assertEquals("About Backup", about.getText());
        assertTrue(about.getActionListeners().length > 0,
                "the About item is wired to open the dialog");
    }

    @Test
    @DisplayName("the short constructor still hosts content and gets an About item")
    void shortConstructorGetsAboutItem() {
        JPanel panel = new JPanel();
        Desktop2DWindow window = new Desktop2DWindow("Calc", null, panel, "Calc");
        assertNull(window.getDescription());
        assertNull(window.getCommand());
        assertNull(window.getIconResource());
        assertEquals("About Calc", aboutItem(window).getText());
        assertEquals(1, window.getContentPane().getComponentCount());
        assertSame(panel, window.getContentPane().getComponent(0));
    }

    @Test
    @DisplayName("the six-argument constructor leaves the description null")
    void sixArgConstructorHasNullDescription() {
        Desktop2DWindow window = new Desktop2DWindow("X", null, new JPanel(),
                "X", CMD, "icon.png");
        assertNull(window.getDescription());
        assertEquals(CMD, window.getCommand());
        assertNotNull(window.getJMenuBar());
    }

    @Test
    @DisplayName("a blank title falls back to Application in the About label")
    void blankTitleFallsBackInAboutLabel() {
        Desktop2DWindow window = new Desktop2DWindow("   ", null,
                new JPanel(), "   ");
        assertEquals("About Application", aboutItem(window).getText());
    }
}
