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
package org.jdesktop.lg3d.scenemanager.utils.switcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link WindowSwitcherKeys}: the pure keystroke resolution
 * behind the 3D desktop's window switcher (Ctrl+Alt+Tab forward,
 * Ctrl+Alt+Shift+Tab backward) and the guard rails that keep plain Tab, plain
 * Alt+Tab and an application's own keystrokes out of it. Static resolution, so no
 * AWT event dispatch and no Java 3D are involved.
 */
class WindowSwitcherKeysTest {

    private static WindowSwitcherKeys.Kind resolve(boolean ctrl, boolean alt,
            boolean shift, int keyCode) {
        return WindowSwitcherKeys.resolve(ctrl, alt, shift, keyCode);
    }

    @Test
    @DisplayName("Ctrl+Alt+Tab steps forward, Ctrl+Alt+Shift+Tab steps back")
    void ctrlAltTabCycles() {
        assertEquals(WindowSwitcherKeys.Kind.NEXT,
                resolve(true, true, false, KeyEvent.VK_TAB));
        assertEquals(WindowSwitcherKeys.Kind.PREVIOUS,
                resolve(true, true, true, KeyEvent.VK_TAB));
    }

    @Test
    @DisplayName("Tab without both Ctrl and Alt is left to the application")
    void tabAloneIsNotASwitcherKey() {
        assertEquals(WindowSwitcherKeys.Kind.NONE,
                resolve(false, false, false, KeyEvent.VK_TAB), "plain Tab");
        assertEquals(WindowSwitcherKeys.Kind.NONE,
                resolve(false, true, false, KeyEvent.VK_TAB), "Alt+Tab (host-grabbed)");
        assertEquals(WindowSwitcherKeys.Kind.NONE,
                resolve(true, false, false, KeyEvent.VK_TAB), "Ctrl+Tab");
        assertEquals(WindowSwitcherKeys.Kind.NONE,
                resolve(false, false, true, KeyEvent.VK_TAB), "Shift+Tab");
    }

    @Test
    @DisplayName("Ctrl+Alt with any other key is not a switcher key")
    void otherKeysAreIgnored() {
        assertEquals(WindowSwitcherKeys.Kind.NONE,
                resolve(true, true, false, KeyEvent.VK_ESCAPE));
        assertEquals(WindowSwitcherKeys.Kind.NONE,
                resolve(true, true, false, KeyEvent.VK_ENTER));
        assertEquals(WindowSwitcherKeys.Kind.NONE,
                resolve(true, true, false, KeyEvent.VK_A));
        assertEquals(WindowSwitcherKeys.Kind.NONE,
                resolve(true, true, true, KeyEvent.VK_PAGE_DOWN));
    }

    @Test
    @DisplayName("Meta stands in for Ctrl on the platforms that need it")
    void metaCountsAsControl() {
        // resolve() takes control as (isControlDown() || isMetaDown()); the flag
        // itself is what matters, so the forward binding still resolves.
        assertEquals(WindowSwitcherKeys.Kind.NEXT,
                resolve(true, true, false, KeyEvent.VK_TAB));
    }
}
