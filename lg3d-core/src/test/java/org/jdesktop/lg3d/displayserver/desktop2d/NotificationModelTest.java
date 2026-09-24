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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link NotificationModel}: newest-first ordering and increasing ids,
 * capacity eviction, the unread count (never more than what is retained),
 * remove/clear/markAllRead, listener add/remove, the injectable clock, and the
 * constructor's capacity guard.
 */
class NotificationModelTest {

    @Test
    @DisplayName("add prepends, assigns increasing ids and stamps the clock")
    void addPrependsNewestFirst() {
        NotificationModel model = new NotificationModel(10, () -> 100L);
        Notification first = model.add("a", null, null);
        Notification second = model.add("b", null, null);
        assertEquals(1L, first.id());
        assertEquals(2L, second.id());
        assertEquals(100L, first.timestampMillis());
        List<Notification> logged = model.notifications();
        assertSame(second, logged.get(0));
        assertSame(first, logged.get(1));
        assertEquals(2, model.size());
        assertEquals(2, model.unreadCount());
    }

    @Test
    @DisplayName("adding past capacity evicts the oldest")
    void evictsOldestPastCapacity() {
        NotificationModel model = new NotificationModel(2, () -> 0L);
        Notification oldest = model.add("a", null, null);
        model.add("b", null, null);
        model.add("c", null, null);
        assertEquals(2, model.size());
        assertFalse(model.notifications().contains(oldest));
    }

    @Test
    @DisplayName("the unread count never exceeds the retained size")
    void unreadCountCappedAtSize() {
        NotificationModel model = new NotificationModel(1, () -> 0L);
        model.add("a", null, null);
        model.add("b", null, null);
        model.add("c", null, null);
        assertEquals(1, model.size());
        assertEquals(1, model.unreadCount());
    }

    @Test
    @DisplayName("markAllRead clears the unread count")
    void markAllReadClearsUnread() {
        NotificationModel model = new NotificationModel();
        model.add("a", null, null);
        assertEquals(1, model.unreadCount());
        model.markAllRead();
        assertEquals(0, model.unreadCount());
    }

    @Test
    @DisplayName("remove drops one notification and decrements unread")
    void removeDropsAndDecrements() {
        NotificationModel model = new NotificationModel();
        Notification a = model.add("a", null, null);
        model.add("b", null, null);
        assertTrue(model.remove(a.id()));
        assertEquals(1, model.size());
        assertEquals(1, model.unreadCount());
        assertFalse(model.remove(999L));
    }

    @Test
    @DisplayName("clear empties the log and the unread count")
    void clearEmpties() {
        NotificationModel model = new NotificationModel();
        model.add("a", null, null);
        model.clear();
        assertTrue(model.isEmpty());
        assertEquals(0, model.unreadCount());
    }

    @Test
    @DisplayName("clearing an already-empty log does not fire listeners")
    void clearOnEmptyIsSilent() {
        NotificationModel model = new NotificationModel();
        int[] fires = {0};
        model.addListener(() -> fires[0]++);
        model.clear();
        assertEquals(0, fires[0]);
    }

    @Test
    @DisplayName("listeners fire on add/markAllRead/remove and stop after removal")
    void listenersFireOnChange() {
        NotificationModel model = new NotificationModel();
        int[] fires = {0};
        Runnable listener = () -> fires[0]++;
        model.addListener(listener);
        Notification a = model.add("a", null, null);
        assertEquals(1, fires[0]);
        model.markAllRead();
        assertEquals(2, fires[0]);
        model.remove(a.id());
        assertEquals(3, fires[0]);
        model.removeListener(listener);
        model.add("b", null, null);
        assertEquals(3, fires[0]);
    }

    @Test
    @DisplayName("the notifications view is unmodifiable")
    void notificationsIsUnmodifiable() {
        NotificationModel model = new NotificationModel();
        model.add("a", null, null);
        assertThrows(UnsupportedOperationException.class,
                () -> model.notifications().add(
                        new Notification(9L, "x", null, null, 0L)));
    }

    @Test
    @DisplayName("a non-positive capacity is rejected")
    void rejectsBadCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new NotificationModel(0, () -> 0L));
    }

    @Test
    @DisplayName("a null clock falls back to the wall clock")
    void nullClockFallsBackToSystem() {
        NotificationModel model = new NotificationModel(5, null);
        assertTrue(model.add("a", null, null).timestampMillis() > 0L);
    }

    @Test
    @DisplayName("a null listener is ignored rather than stored")
    void nullListenerIgnored() {
        NotificationModel model = new NotificationModel();
        model.addListener(null);
        model.add("a", null, null);
        assertEquals(1, model.size());
    }
}
