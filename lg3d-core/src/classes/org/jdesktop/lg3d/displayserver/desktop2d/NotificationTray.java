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

import java.util.List;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * The taskbar's notification-area button: a "Notifications" label carrying an
 * unread-count badge, opening a popup that lists the notifications the desktop
 * has raised (newest first) with a "Clear all" footer.
 *
 * <p>It is the view over a {@link NotificationModel}, registering itself as a
 * listener so the badge tracks the log live. Opening the popup marks everything
 * read (clearing the badge), each entry dismisses that one notification, and
 * "Clear all" empties the log. Only the button is placed in the taskbar; the
 * popup is built fresh each time it opens so it always reflects the current
 * log.</p>
 */
final class NotificationTray {

    private final NotificationModel model;
    private final JButton button;
    private final JPopupMenu menu;
    /** The model callback, held so {@link #dispose()} removes the same instance. */
    private final Runnable listener;

    /**
     * @param model the desktop's notification log this tray reflects
     */
    NotificationTray(NotificationModel model) {
        this.model = model;
        this.button = new JButton();
        this.button.setToolTipText("Desktop notifications");
        this.button.addActionListener(e -> open());
        this.menu = new JPopupMenu();
        this.listener = this::refresh;
        if (model != null) {
            model.addListener(listener);
        }
        refresh();
    }

    /** The taskbar button to place in the bar's right-hand row. */
    JButton button() {
        return button;
    }

    /** Syncs the button's badge to the model's unread count. */
    void refresh() {
        button.setText(badgeLabel(model == null ? 0 : model.unreadCount()));
    }

    /** Marks the log read, rebuilds the popup and shows it above the button. */
    private void open() {
        markReadAndRebuild();
        int height = menu.getPreferredSize().height;
        menu.show(button, 0, -height);
    }

    /**
     * Marks every notification read (clearing the badge) and rebuilds the popup
     * to list the current log. Split out from {@link #open()} so a test can
     * drive it without showing a popup, which needs a display.
     */
    void markReadAndRebuild() {
        if (model != null) {
            model.markAllRead();
        }
        rebuildMenu();
    }

    private void rebuildMenu() {
        menu.removeAll();
        List<Notification> items =
                (model == null) ? List.of() : model.notifications();
        if (items.isEmpty()) {
            JMenuItem empty = new JMenuItem("No notifications");
            empty.setEnabled(false);
            menu.add(empty);
            return;
        }
        for (Notification n : items) {
            JMenuItem entry = new JMenuItem(entryLabel(n));
            entry.setForeground(NotificationColors.accentFor(n.kind()));
            entry.setToolTipText(n.title());
            entry.addActionListener(e -> remove(n.id()));
            menu.add(entry);
        }
        menu.addSeparator();
        JMenuItem clear = new JMenuItem("Clear all");
        clear.addActionListener(e -> clearAll());
        menu.add(clear);
    }

    private void remove(long id) {
        if (model != null) {
            model.remove(id);
        }
    }

    private void clearAll() {
        if (model != null) {
            model.clear();
        }
    }

    /** Detaches this tray from the model, so the log no longer holds it. */
    void dispose() {
        if (model != null) {
            model.removeListener(listener);
        }
    }

    /**
     * The button label: "Notifications" with an unread badge appended when there
     * are unseen notifications.
     */
    static String badgeLabel(int unread) {
        return (unread > 0) ? "Notifications (" + unread + ")" : "Notifications";
    }

    /**
     * The popup entry text for {@code n}: its title, or "title - message" when it
     * carries a body.
     */
    static String entryLabel(Notification n) {
        if (n == null) {
            return "";
        }
        String message = n.message();
        return (message == null) ? n.title() : n.title() + " \u2014 " + message;
    }
}
