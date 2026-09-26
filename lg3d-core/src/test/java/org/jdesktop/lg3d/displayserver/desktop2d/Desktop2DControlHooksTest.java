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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link Desktop2D} control-center hooks on their no-shell path: with
 * no 2D desktop running (the static {@code instance} is null, as it always is in
 * a headless test JVM) every hook either no-ops or returns an empty/default
 * snapshot rather than throwing, and the snapshot records expose their
 * components. The read-only hooks fall back to the persisted {@link DesktopConfig},
 * which these tests set in memory (never {@code save()}) so the user's real
 * preferences are untouched. The live-shell paths (the on-EDT lambdas) and the
 * persisting setters need a realized desktop and are exercised by the offscreen
 * probe, not here. No jogamp scene-graph object is constructed, so the suite is
 * headless-safe.
 */
class Desktop2DControlHooksTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("the DND snapshot falls back to the persisted config with no shell")
    void doNotDisturbSnapshot() {
        cfg.setDoNotDisturbEnabled(true);
        cfg.setDoNotDisturbUntil(0L);
        Desktop2D.DoNotDisturbSnapshot on = Desktop2D.doNotDisturbSnapshot();
        assertTrue(on.enabled());
        assertEquals(0L, on.untilMillis());
        assertTrue(on.active(), "on indefinitely is active");

        cfg.setDoNotDisturbEnabled(false);
        Desktop2D.DoNotDisturbSnapshot off = Desktop2D.doNotDisturbSnapshot();
        assertFalse(off.enabled());
        assertFalse(off.active());
    }

    @Test
    @DisplayName("an expired DND deadline is not active; an indefinite one is")
    void dndActiveBranches() {
        assertFalse(new Desktop2D.DoNotDisturbSnapshot(true, 1L).active(), "deadline in the past");
        assertTrue(new Desktop2D.DoNotDisturbSnapshot(true, 0L).active(), "indefinite");
        assertTrue(new Desktop2D.DoNotDisturbSnapshot(
                true, System.currentTimeMillis() + 60_000L).active(), "deadline in the future");
        assertFalse(new Desktop2D.DoNotDisturbSnapshot(false, 0L).active(), "off");
    }

    @Test
    @DisplayName("the notification hooks are empty and no-op with no shell")
    void notificationHooks() {
        Desktop2D.NotificationSnapshot snap = Desktop2D.notificationSnapshot();
        assertNotNull(snap);
        assertTrue(snap.entries().isEmpty());
        assertEquals(0, snap.unread());
        assertDoesNotThrow(Desktop2D::markNotificationsRead);
        assertDoesNotThrow(Desktop2D::clearNotifications);
    }

    @Test
    @DisplayName("the workspace snapshot falls back to the persisted count with no shell")
    void workspaceSnapshot() {
        cfg.setWorkspaceCount(6);
        Desktop2D.WorkspaceSnapshot snap = Desktop2D.workspaceSnapshot();
        assertEquals(6, snap.count());
        assertEquals(0, snap.current());
        assertEquals(6, snap.windowCounts().size());
        assertTrue(snap.windowCounts().stream().allMatch(n -> n == 0), "no live windows");
        assertDoesNotThrow(() -> Desktop2D.switchWorkspace(2));
    }

    @Test
    @DisplayName("the shortcut hooks no-op and report empty with no shell")
    void shortcutHooks() {
        assertDoesNotThrow(Desktop2D::applyShortcuts);
        Map<String, String> bindings = Desktop2D.shortcutBindings();
        assertNotNull(bindings);
        assertTrue(bindings.isEmpty(), "no live shell -> no bindings reported");
    }

    @Test
    @DisplayName("the snapshot records expose their components")
    void recordsExposeComponents() {
        Desktop2D.NotificationEntry entry = new Desktop2D.NotificationEntry(
                7L, "title", "message", Notification.Kind.WARNING, 123L);
        assertEquals(7L, entry.id());
        assertEquals("title", entry.title());
        assertEquals("message", entry.message());
        assertEquals(Notification.Kind.WARNING, entry.kind());
        assertEquals(123L, entry.timestampMillis());

        Desktop2D.NotificationSnapshot notes =
                new Desktop2D.NotificationSnapshot(List.of(entry), 1);
        assertEquals(1, notes.unread());
        assertEquals(List.of(entry), notes.entries());

        Desktop2D.WorkspaceSnapshot ws =
                new Desktop2D.WorkspaceSnapshot(3, 1, List.of(0, 2, 1));
        assertEquals(3, ws.count());
        assertEquals(1, ws.current());
        assertEquals(List.of(0, 2, 1), ws.windowCounts());
    }
}
