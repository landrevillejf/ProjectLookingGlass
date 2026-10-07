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
package org.jdesktop.lg3d.apps.webbrowser.ext.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.lg3d.apps.webbrowser.ext.Permission;
import org.jdesktop.lg3d.apps.webbrowser.ext.PopupDecision;
import org.jdesktop.lg3d.apps.webbrowser.ext.PopupRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link PopupBlockerExtension} reference extension. */
class PopupBlockerExtensionTest {

    private final PopupBlockerExtension ext = new PopupBlockerExtension();

    @Test
    @DisplayName("declares only the POPUP permission and a stable id")
    void manifest() {
        assertEquals("lg3d.popup-blocker", ext.manifest().getId());
        assertEquals(java.util.Set.of(Permission.POPUP), ext.manifest().getPermissions());
    }

    @Test
    @DisplayName("blocks popups that were not user-initiated")
    void blocksUnrequested() {
        assertEquals(PopupDecision.BLOCK, ext.onPopup(new PopupRequest("https://a.com", false)));
    }

    @Test
    @DisplayName("allows popups the user asked for")
    void allowsUserInitiated() {
        assertEquals(PopupDecision.ALLOW, ext.onPopup(new PopupRequest("https://a.com", true)));
    }

    @Test
    @DisplayName("a null request is treated as not user-initiated and blocked")
    void nullIsBlocked() {
        assertTrue(ext.onPopup(null) == PopupDecision.BLOCK);
    }
}
