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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import javax.swing.Icon;
import javax.swing.ImageIcon;
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
        tray.setIconOnly(false);   // assert the icon+text presentation
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
        tray.setIconOnly(false);
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
        tray.setIconOnly(false);
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
        tray.setIconOnly(false);
        assertEquals("Notifications", tray.button().getText());
        dnd.enable();                       // fires the tray listener
        assertEquals("[DND] Notifications", tray.button().getText());
        dnd.disable();
        assertEquals("Notifications", tray.button().getText());
        tray.dispose();
    }

    @Test
    @DisplayName("icon-only (the default) hides the text and badges the glyph")
    void iconOnlyIsTheDefault() {
        NotificationModel model = new NotificationModel();
        NotificationTray tray = new NotificationTray(model);
        assertNull(tray.button().getText(),
                "icon-only presentation carries no button text");
        assertEquals("Desktop notifications", tray.button().getToolTipText());
        model.add("a", null, null);
        assertEquals("Desktop notifications (1 unread)",
                tray.button().getToolTipText(),
                "the unread count moves into the tooltip");
        assertNotNull(tray.button().getIcon());
        // Switching to text mode restores the label and drops the badge text.
        tray.setIconOnly(false);
        assertEquals("Notifications (1)", tray.button().getText());
        tray.dispose();
    }

    @Test
    @DisplayName("the icon-only tooltip reports the unread count and DND state")
    void tooltipText() {
        assertEquals("Desktop notifications", NotificationTray.tooltip(0, false));
        assertEquals("Desktop notifications (2 unread)",
                NotificationTray.tooltip(2, false));
        assertEquals("Desktop notifications \u2014 Do Not Disturb on",
                NotificationTray.tooltip(0, true));
        assertEquals("Desktop notifications (2 unread) \u2014 Do Not Disturb on",
                NotificationTray.tooltip(2, true));
    }

    @Test
    @DisplayName("badgedIcon returns the base glyph unless there is something unread")
    void badgedIcon() {
        Icon base = new ImageIcon(new BufferedImage(24, 24,
                BufferedImage.TYPE_INT_ARGB));
        assertSame(base, NotificationTray.badgedIcon(base, 0),
                "no unread notifications means no badge");
        assertSame(base, NotificationTray.badgedIcon(base, -3));
        assertNull(NotificationTray.badgedIcon(null, 4),
                "a null base stays null rather than throwing");
        Icon badged = NotificationTray.badgedIcon(base, 3);
        assertTrue(badged instanceof ImageIcon, "a badge composites a new image");
        assertEquals(base.getIconWidth(), badged.getIconWidth());
        assertEquals(base.getIconHeight(), badged.getIconHeight());
    }
}
