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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ToastQueue}: newest-first visibility, expiry once the
 * time-to-live elapses, eviction past the visible cap, dismiss-by-id, clear,
 * the null-push no-op, the accessors and the constructor guards.
 */
class ToastQueueTest {

    private static Notification notification(long id) {
        return new Notification(id, "n" + id, null, null, 0L);
    }

    @Test
    @DisplayName("visible returns the queued toasts newest first")
    void visibleIsNewestFirst() {
        ToastQueue queue = new ToastQueue(1000L, 4);
        queue.push(notification(1L), 0L);
        queue.push(notification(2L), 0L);
        List<Notification> visible = queue.visible(0L);
        assertEquals(2, visible.size());
        assertEquals(2L, visible.get(0).id());
        assertEquals(1L, visible.get(1).id());
    }

    @Test
    @DisplayName("a toast drops out once its time-to-live elapses")
    void expiresAfterTtl() {
        ToastQueue queue = new ToastQueue(1000L, 4);
        queue.push(notification(1L), 0L);
        assertEquals(1, queue.visible(500L).size());
        assertEquals(0, queue.visible(1000L).size());
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("pushing past the visible cap drops the oldest")
    void dropsOldestPastMaxVisible() {
        ToastQueue queue = new ToastQueue(10000L, 2);
        queue.push(notification(1L), 0L);
        queue.push(notification(2L), 0L);
        queue.push(notification(3L), 0L);
        assertEquals(2, queue.size());
        List<Notification> visible = queue.visible(0L);
        assertEquals(3L, visible.get(0).id());
        assertEquals(2L, visible.get(1).id());
    }

    @Test
    @DisplayName("dismiss drops a single toast ahead of its expiry")
    void dismissById() {
        ToastQueue queue = new ToastQueue(1000L, 4);
        queue.push(notification(1L), 0L);
        assertTrue(queue.dismiss(1L));
        assertFalse(queue.dismiss(1L));
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("clear dismisses every toast")
    void clearEmpties() {
        ToastQueue queue = new ToastQueue(1000L, 4);
        queue.push(notification(1L), 0L);
        queue.push(notification(2L), 0L);
        queue.clear();
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    @DisplayName("pushing null is ignored")
    void nullPushIgnored() {
        ToastQueue queue = new ToastQueue(1000L, 4);
        queue.push(null, 0L);
        assertTrue(queue.isEmpty());
    }

    @Test
    @DisplayName("the accessors report the configured ttl and cap")
    void accessors() {
        ToastQueue queue = new ToastQueue(1234L, 3);
        assertEquals(1234L, queue.ttlMillis());
        assertEquals(3, queue.maxVisible());
        assertEquals(ToastQueue.DEFAULT_TTL_MILLIS, new ToastQueue().ttlMillis());
        assertEquals(ToastQueue.DEFAULT_MAX_VISIBLE, new ToastQueue().maxVisible());
    }

    @Test
    @DisplayName("a non-positive ttl or cap is rejected")
    void rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class, () -> new ToastQueue(0L, 4));
        assertThrows(IllegalArgumentException.class, () -> new ToastQueue(1000L, 0));
    }
}
