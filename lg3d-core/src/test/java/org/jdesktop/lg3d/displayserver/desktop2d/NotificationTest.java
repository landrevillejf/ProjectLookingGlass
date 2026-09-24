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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link Notification}: field storage, the blank-message-to-null and
 * null-kind-to-INFO normalisations, rejection of a blank title, and value
 * equality / hashing / toString.
 */
class NotificationTest {

    @Test
    @DisplayName("the constructor stores every field verbatim")
    void storesFields() {
        Notification n = new Notification(7L, "Title", "Body",
                Notification.Kind.WARNING, 123L);
        assertEquals(7L, n.id());
        assertEquals("Title", n.title());
        assertEquals("Body", n.message());
        assertEquals(Notification.Kind.WARNING, n.kind());
        assertEquals(123L, n.timestampMillis());
    }

    @Test
    @DisplayName("a blank or null message is normalised to null")
    void blankMessageBecomesNull() {
        assertNull(new Notification(1L, "t", "   ", null, 0L).message());
        assertNull(new Notification(1L, "t", null, null, 0L).message());
    }

    @Test
    @DisplayName("a null kind defaults to INFO")
    void nullKindDefaultsToInfo() {
        assertEquals(Notification.Kind.INFO,
                new Notification(1L, "t", null, null, 0L).kind());
    }

    @Test
    @DisplayName("a blank or null title is rejected")
    void blankTitleRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Notification(1L, "  ", null, null, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new Notification(1L, null, null, null, 0L));
    }

    @Test
    @DisplayName("equality and hashing consider every field")
    void equalsAndHashCode() {
        Notification a = new Notification(1L, "t", "m", Notification.Kind.ERROR, 5L);
        Notification b = new Notification(1L, "t", "m", Notification.Kind.ERROR, 5L);
        Notification differentId = new Notification(2L, "t", "m",
                Notification.Kind.ERROR, 5L);
        Notification differentKind = new Notification(1L, "t", "m",
                Notification.Kind.INFO, 5L);
        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, differentId);
        assertNotEquals(a, differentKind);
        assertNotEquals(a, "not a notification");
    }

    @Test
    @DisplayName("toString mentions the id and title for diagnostics")
    void toStringMentionsTitle() {
        String text = new Notification(3L, "Hello", null, null, 0L).toString();
        assertTrue(text.contains("Hello"));
        assertTrue(text.contains("3"));
    }
}
