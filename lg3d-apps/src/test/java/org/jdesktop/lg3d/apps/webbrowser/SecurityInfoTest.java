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
package org.jdesktop.lg3d.apps.webbrowser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for {@link SecurityInfo} classification. */
class SecurityInfoTest {

    @Test
    @DisplayName("https to a public host is SECURE")
    void secure() {
        SecurityInfo info = SecurityInfo.of("https://example.com/path");
        assertSame(SecurityInfo.Level.SECURE, info.getLevel());
        assertTrue(info.isSecure());
        assertEquals("https", info.getScheme());
        assertEquals("example.com", info.getHost());
        assertNull(info.getWarning());
    }

    @Test
    @DisplayName("http is NOT_SECURE and carries a warning")
    void notSecure() {
        SecurityInfo info = SecurityInfo.of("http://example.com/");
        assertSame(SecurityInfo.Level.NOT_SECURE, info.getLevel());
        assertFalse(info.isSecure());
        assertNotNull(info.getWarning());
        assertFalse(info.getWarning().isBlank());
    }

    @Test
    @DisplayName("a file URL is LOCAL")
    void localFile() {
        assertSame(SecurityInfo.Level.LOCAL, SecurityInfo.of("file:///tmp/x.html").getLevel());
    }

    @Test
    @DisplayName("a loopback host is LOCAL even over http")
    void loopback() {
        assertSame(SecurityInfo.Level.LOCAL, SecurityInfo.of("http://localhost:8080/").getLevel());
        assertSame(SecurityInfo.Level.LOCAL, SecurityInfo.of("http://127.0.0.1/").getLevel());
    }

    @Test
    @DisplayName("internal schemes classify as INTERNAL, including view-source targets")
    void internalSchemes() {
        assertSame(SecurityInfo.Level.INTERNAL, SecurityInfo.of("about:blank").getLevel());
        assertSame(SecurityInfo.Level.INTERNAL, SecurityInfo.of("data:text/html,hi").getLevel());
        assertSame(SecurityInfo.Level.INTERNAL,
                SecurityInfo.of("view-source:https://example.com").getLevel());
    }

    @Test
    @DisplayName("null / blank classify as UNKNOWN with empty text")
    void unknown() {
        assertSame(SecurityInfo.Level.UNKNOWN, SecurityInfo.of(null).getLevel());
        assertSame(SecurityInfo.Level.UNKNOWN, SecurityInfo.of("   ").getLevel());
        assertEquals("", SecurityInfo.of(null).getHost());
        assertEquals("", SecurityInfo.of(null).getScheme());
    }

    @Test
    @DisplayName("summary text is always present for a recognised level")
    void summary() {
        assertFalse(SecurityInfo.of("https://example.com").getSummary().isBlank());
        assertFalse(SecurityInfo.of("http://example.com").getSummary().isBlank());
    }
}
