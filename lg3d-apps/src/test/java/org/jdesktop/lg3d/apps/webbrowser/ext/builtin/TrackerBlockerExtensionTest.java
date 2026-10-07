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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.jdesktop.lg3d.apps.webbrowser.ext.NavigationDecision;
import org.jdesktop.lg3d.apps.webbrowser.ext.NavigationRequest;
import org.jdesktop.lg3d.apps.webbrowser.ext.PageContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link TrackerBlockerExtension} reference extension. */
class TrackerBlockerExtensionTest {

    private final TrackerBlockerExtension ext = new TrackerBlockerExtension();

    @Test
    @DisplayName("vetoes a navigation to a blocked tracker host")
    void blocksTracker() {
        assertTrue(ext.onNavigate(
                new NavigationRequest("https://www.doubleclick.net/ad", 0)).isBlock());
        assertTrue(ext.onNavigate(
                new NavigationRequest("http://google-analytics.com/collect", 0)).isBlock());
    }

    @Test
    @DisplayName("allows an ordinary navigation")
    void allowsNormal() {
        NavigationDecision d = ext.onNavigate(new NavigationRequest("https://example.com/", 0));
        assertTrue(d.isAllow());
    }

    @Test
    @DisplayName("a look-alike host that is not under a blocked domain is allowed")
    void notFooledByLookalike() {
        assertFalse(TrackerBlockerExtension.isBlockedHost("notdoubleclick.net.evil.com"));
        assertFalse(TrackerBlockerExtension.isBlockedHost("example.com"));
    }

    @Test
    @DisplayName("host extraction strips scheme, port, path, query and userinfo")
    void hostOf() {
        assertEquals("a.com", TrackerBlockerExtension.hostOf("https://a.com/x?y=1"));
        assertEquals("a.com", TrackerBlockerExtension.hostOf("http://a.com:8080/x"));
        assertEquals("a.com", TrackerBlockerExtension.hostOf("https://user@a.com/x"));
        assertEquals("", TrackerBlockerExtension.hostOf(""));
    }

    @Test
    @DisplayName("injects the hide-ads content script on page load")
    void injectsContentScript() {
        AtomicInteger ran = new AtomicInteger();
        ext.onPageLoaded(new PageContext("https://example.com", "t", js -> {
            assertTrue(js.contains("display:none"), "the injected script hides ad containers");
            ran.incrementAndGet();
        }));
        assertEquals(1, ran.get());
    }

    @Test
    @DisplayName("a null navigation request is allowed, never blocked")
    void nullAllowed() {
        assertTrue(ext.onNavigate(null).isAllow());
    }
}
