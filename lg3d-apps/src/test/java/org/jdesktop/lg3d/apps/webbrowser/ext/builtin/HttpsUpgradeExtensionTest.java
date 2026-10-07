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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.lg3d.apps.webbrowser.ext.NavigationDecision;
import org.jdesktop.lg3d.apps.webbrowser.ext.NavigationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link HttpsUpgradeExtension} reference extension. */
class HttpsUpgradeExtensionTest {

    private final HttpsUpgradeExtension ext = new HttpsUpgradeExtension();

    @Test
    @DisplayName("redirects a plain http navigation to https")
    void upgradesHttp() {
        NavigationDecision d = ext.onNavigate(new NavigationRequest("http://example.com/x", 0));
        assertTrue(d.isRedirect());
        assertEquals("https://example.com/x", d.getTargetUrl());
    }

    @Test
    @DisplayName("leaves an https navigation untouched")
    void leavesHttpsAlone() {
        assertTrue(ext.onNavigate(new NavigationRequest("https://example.com/", 0)).isAllow());
    }

    @Test
    @DisplayName("leaves local development hosts on http")
    void leavesLocalAlone() {
        assertNull(HttpsUpgradeExtension.upgrade("http://localhost:8080/app"));
        assertNull(HttpsUpgradeExtension.upgrade("http://127.0.0.1/x"));
        assertNull(HttpsUpgradeExtension.upgrade("http://192.168.1.5/"));
    }

    @Test
    @DisplayName("non-http schemes and null are not upgraded")
    void otherSchemesUntouched() {
        assertNull(HttpsUpgradeExtension.upgrade("file:///tmp/x"));
        assertNull(HttpsUpgradeExtension.upgrade(null));
        assertTrue(ext.onNavigate(null).isAllow());
    }
}
