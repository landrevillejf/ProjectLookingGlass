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
package org.jdesktop.lg3d.apps.securitycenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link AuditEvent}: the constructors, the null-safe / trimming setters,
 * the wall-clock {@link AuditEvent#timestamp()} format and the {@code toString()}
 * rendering used by the Activity list cell.
 */
class AuditEventTest {

    private static final String STAMP_PATTERN = "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}";

    @Test
    @DisplayName("the category constants are the stable JSON values")
    void categoryConstants() {
        assertEquals("scan", AuditEvent.CATEGORY_SCAN);
        assertEquals("definitions", AuditEvent.CATEGORY_DEFINITIONS);
        assertEquals("posture", AuditEvent.CATEGORY_POSTURE);
        assertEquals("privacy", AuditEvent.CATEGORY_PRIVACY);
        assertEquals("vpn", AuditEvent.CATEGORY_VPN);
        assertEquals("cut", AuditEvent.CATEGORY_CUT);
    }

    @Test
    @DisplayName("the no-arg constructor is empty and stamped near now")
    void noArgConstructor() {
        long before = System.currentTimeMillis();
        AuditEvent event = new AuditEvent();
        long after = System.currentTimeMillis();
        assertEquals("", event.getCategory());
        assertEquals("", event.getMessage());
        assertTrue(event.getEpochMillis() >= before && event.getEpochMillis() <= after,
                "a fresh event is stamped at construction time");
    }

    @Test
    @DisplayName("the two-arg constructor trims and stamps near now")
    void twoArgConstructor() {
        AuditEvent event = new AuditEvent("  scan  ", "  found 1 threat  ");
        assertEquals("scan", event.getCategory());
        assertEquals("found 1 threat", event.getMessage());
        assertTrue(event.getEpochMillis() > 0);
    }

    @Test
    @DisplayName("the full constructor keeps the explicit timestamp")
    void fullConstructor() {
        AuditEvent event = new AuditEvent(1234L, "vpn", "tunnel up");
        assertEquals(1234L, event.getEpochMillis());
        assertEquals("vpn", event.getCategory());
        assertEquals("tunnel up", event.getMessage());
    }

    @Test
    @DisplayName("the setters are null-safe and trim")
    void settersNormalise() {
        AuditEvent event = new AuditEvent();
        event.setCategory(null);
        assertEquals("", event.getCategory());
        event.setMessage(null);
        assertEquals("", event.getMessage());
        event.setCategory("  cut  ");
        assertEquals("cut", event.getCategory());
        event.setMessage("  network refused  ");
        assertEquals("network refused", event.getMessage());
        event.setEpochMillis(9999L);
        assertEquals(9999L, event.getEpochMillis());
    }

    @Test
    @DisplayName("the timestamp is a wall-clock string in the system zone")
    void timestampFormat() {
        AuditEvent event = new AuditEvent(1_600_000_000_000L, "scan", "x");
        String stamp = event.timestamp();
        assertTrue(stamp.matches(STAMP_PATTERN), "unexpected stamp format: " + stamp);
        // The same instant always renders identically.
        assertEquals(stamp, new AuditEvent(1_600_000_000_000L, "other", "y").timestamp());
        assertNotEquals(stamp, new AuditEvent(1_600_000_061_000L, "scan", "x").timestamp());
    }

    @Test
    @DisplayName("toString renders stamp, category and message for the list cell")
    void rendering() {
        AuditEvent event = new AuditEvent(1_600_000_000_000L, "posture", "host read");
        String text = event.toString();
        assertTrue(text.contains("[posture]"), text);
        assertTrue(text.contains("host read"), text);
        assertTrue(text.startsWith(event.timestamp()), text);

        AuditEvent blank = new AuditEvent(1_600_000_000_000L, "", "no category");
        assertTrue(blank.toString().contains("[event]"),
                "a blank category renders as a generic event: " + blank);
    }
}
