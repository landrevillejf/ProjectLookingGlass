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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link NotificationTray}: the badge-label and entry-label formatting,
 * that the button's badge tracks the model's unread count live, that opening
 * the popup marks everything read and clears the badge, and that a null model
 * is tolerated. The popup itself is not shown (that needs a display); the
 * observable model and button state are asserted instead.
 */
class NotificationTrayTest {

    @Test
    @DisplayName("the badge appends the unread count only when non-zero")
    void badgeLabel() {
        assertEquals("Notifications", NotificationTray.badgeLabel(0));
        assertEquals("Notifications (3)", NotificationTray.badgeLabel(3));
        assertEquals("Notifications", NotificationTray.badgeLabel(-1));
    }

    @Test
    @DisplayName("an entry shows the title, or title and body when present")
    void entryLabel() {
        assertEquals("Title", NotificationTray.entryLabel(
                new Notification(1L, "Title", null, null, 0L)));
        assertEquals("Title \u2014 body", NotificationTray.entryLabel(
                new Notification(1L, "Title", "body", null, 0L)));
        assertEquals("", NotificationTray.entryLabel(null));
    }

    @Test
    @DisplayName("the button badge tracks the model's unread count live")
    void buttonTracksUnread() {
        NotificationModel model = new NotificationModel();
        NotificationTray tray = new NotificationTray(model);
        assertEquals("Notifications", tray.button().getText());
        model.add("a", null, null);
        assertEquals("Notifications (1)", tray.button().getText());
        model.add("b", null, null);
        assertEquals("Notifications (2)", tray.button().getText());
        tray.dispose();
    }

    @Test
    @DisplayName("opening the tray marks everything read and clears the badge")
    void openMarksAllRead() {
        NotificationModel model = new NotificationModel();
        NotificationTray tray = new NotificationTray(model);
        model.add("a", "body", Notification.Kind.WARNING);
        model.add("b", null, null);
        assertEquals(2, model.unreadCount());
        tray.markReadAndRebuild();
        assertEquals(0, model.unreadCount());
        assertEquals("Notifications", tray.button().getText());
        tray.dispose();
    }

    @Test
    @DisplayName("a null model is tolerated")
    void nullModelIsSafe() {
        NotificationTray tray = new NotificationTray(null);
        assertEquals("Notifications", tray.button().getText());
        tray.markReadAndRebuild();
        tray.dispose();
        assertEquals("Notifications", tray.button().getText());
    }

    @Test
    @DisplayName("the DND badge prefix is applied only when active")
    void dndBadgeLabel() {
        assertEquals("Notifications", NotificationTray.badgeLabel(0, false));
        assertEquals("[DND] Notifications", NotificationTray.badgeLabel(0, true));
        assertEquals("[DND] Notifications (2)", NotificationTray.badgeLabel(2, true));
    }

    @Test
    @DisplayName("the button shows the DND marker while Do Not Disturb is on")
    void buttonTracksDnd() {
        NotificationModel model = new NotificationModel();
        DoNotDisturb dnd = new DoNotDisturb();
        NotificationTray tray = new NotificationTray(model, dnd);
        assertEquals("Notifications", tray.button().getText());
        dnd.enable();                       // fires the tray listener
        assertEquals("[DND] Notifications", tray.button().getText());
        dnd.disable();
        assertEquals("Notifications", tray.button().getText());
        tray.dispose();
    }
}
