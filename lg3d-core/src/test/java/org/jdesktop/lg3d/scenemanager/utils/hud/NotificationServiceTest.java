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
package org.jdesktop.lg3d.scenemanager.utils.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jdesktop.lg3d.displayserver.desktop2d.Notification;
import org.jdesktop.lg3d.displayserver.desktop2d.ToastQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link NotificationService}: the log/toast split, the Do
 * Not Disturb gate (errors always pass), toast TTL expiry, dismissal and the
 * change-listener fan-out. A fake clock makes every time-sensitive path
 * deterministic without a live desktop.
 */
class NotificationServiceTest {

    private final AtomicLong now = new AtomicLong(1_000L);
    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(now::get);
    }

    @Test
    void postLogsAndRaisesAToast() {
        Notification n = service.post("Update", "Ready to install",
                Notification.Kind.INFO);

        assertEquals("Update", n.title());
        assertEquals(Notification.Kind.INFO, n.kind());
        assertEquals(1, service.notifications().size());
        assertEquals(1, service.visibleToastCount());
        assertEquals(1, service.unreadCount());
    }

    @Test
    void nullKindDefaultsToInfo() {
        Notification n = service.post("Hi", null, null);
        assertEquals(Notification.Kind.INFO, n.kind());
    }

    @Test
    void blankTitleRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.post("  ", "body", Notification.Kind.INFO));
    }

    @Test
    void doNotDisturbSuppressesInfoButStillLogs() {
        service.setDoNotDisturb(true);
        assertTrue(service.isDoNotDisturb());
        assertTrue(service.isDoNotDisturbActive());

        service.post("Chat", "new message", Notification.Kind.INFO);

        assertEquals(1, service.notifications().size(), "still logged");
        assertEquals(0, service.visibleToastCount(), "toast suppressed");
    }

    @Test
    void errorAlwaysPassesDoNotDisturb() {
        service.setDoNotDisturb(true);
        service.post("Crash", "app died", Notification.Kind.ERROR);
        assertEquals(1, service.visibleToastCount());
    }

    @Test
    void toggleDoNotDisturbFlips() {
        assertFalse(service.isDoNotDisturb());
        service.toggleDoNotDisturb();
        assertTrue(service.isDoNotDisturb());
        service.toggleDoNotDisturb();
        assertFalse(service.isDoNotDisturb());
    }

    @Test
    void timedDoNotDisturbExpires() {
        service.doNotDisturbFor(1_000L);
        assertTrue(service.isDoNotDisturbActive());

        now.addAndGet(1_500L); // past the deadline
        assertFalse(service.isDoNotDisturbActive());

        service.post("Chat", "msg", Notification.Kind.INFO);
        assertEquals(1, service.visibleToastCount(), "no longer suppressed");
    }

    @Test
    void toastsExpireAfterTtlButLogPersists() {
        service.post("A", null, Notification.Kind.INFO);
        assertEquals(1, service.visibleToastCount());

        now.addAndGet(ToastQueue.DEFAULT_TTL_MILLIS + 1);
        assertEquals(0, service.visibleToastCount(), "toast faded");
        assertEquals(1, service.notifications().size(), "log kept");
    }

    @Test
    void visibleToastsAreNewestFirst() {
        service.post("First", null, Notification.Kind.INFO);
        now.incrementAndGet();
        service.post("Second", null, Notification.Kind.INFO);

        List<Notification> visible = service.visibleToasts();
        assertEquals(2, visible.size());
        assertEquals("Second", visible.get(0).title());
        assertEquals("First", visible.get(1).title());
    }

    @Test
    void dismissToastRemovesOnlyThatOne() {
        Notification a = service.post("A", null, Notification.Kind.INFO);
        service.post("B", null, Notification.Kind.INFO);

        assertTrue(service.dismissToast(a.id()));
        assertEquals(1, service.visibleToastCount());
        assertFalse(service.dismissToast(999L), "unknown id");
    }

    @Test
    void clearToastsKeepsLog() {
        service.post("A", null, Notification.Kind.INFO);
        service.clearToasts();
        assertEquals(0, service.visibleToastCount());
        assertEquals(1, service.notifications().size());
    }

    @Test
    void markAllReadClearsBadge() {
        service.post("A", null, Notification.Kind.INFO);
        assertEquals(1, service.unreadCount());
        service.markAllRead();
        assertEquals(0, service.unreadCount());
    }

    @Test
    void clearEmptiesLogAndToasts() {
        service.post("A", null, Notification.Kind.INFO);
        service.clear();
        assertEquals(0, service.notifications().size());
        assertEquals(0, service.visibleToastCount());
        assertEquals(0, service.unreadCount());
    }

    @Test
    void listenersFireOnPostDismissAndDnd() {
        AtomicInteger fires = new AtomicInteger();
        Runnable listener = fires::incrementAndGet;
        service.addListener(listener);

        Notification n = service.post("A", null, Notification.Kind.INFO); // +1
        service.dismissToast(n.id());                                     // +1
        service.toggleDoNotDisturb();                                     // +1 (dnd listener -> fire)

        assertTrue(fires.get() >= 3, "expected at least 3 fires, got " + fires.get());

        service.removeListener(listener);
        int before = fires.get();
        service.post("B", null, Notification.Kind.INFO);
        assertEquals(before, fires.get(), "removed listener no longer fires");
    }

    @Test
    void singletonIsStable() {
        assertTrue(NotificationService.get() == NotificationService.get());
    }
}
