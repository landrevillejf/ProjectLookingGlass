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

import com.protonmail.landrevillejf.IconManager;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
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
 * "Clear all" empties the log. The popup also carries the Do Not Disturb
 * controls (an on/off checkbox and an "for 1 hour" entry) over an optional
 * {@link DoNotDisturb}; while DND is active the button is prefixed with a
 * {@code [DND]} marker. Only the button is placed in the taskbar; the popup is
 * built fresh each time it opens so it always reflects the current log.</p>
 */
final class NotificationTray {

    /** The unread-count badge colour composited onto the tray glyph. */
    private static final Color BADGE_COLOR = new Color(0xc0392b);

    /**
     * Tray glyph edge: matches the taskbar's chrome buttons so the tray button
     * lines up in height with its neighbours.
     */
    private static final int TRAY_ICON_EDGE = 22;

    private final NotificationModel model;
    private final DoNotDisturb dnd;
    private final JButton button;
    /** The un-badged tray glyph; an unread count is composited over it. */
    private final Icon baseIcon;
    /** Icon-only presentation (text moves to the tooltip); default on. */
    private boolean iconOnly = true;
    private final JPopupMenu menu;
    /** The model callback, held so {@link #dispose()} removes the same instance. */
    private final Runnable listener;
    /** The DND callback, held so {@link #dispose()} removes the same instance. */
    private final Runnable dndListener;

    /**
     * @param model the desktop's notification log this tray reflects
     */
    NotificationTray(NotificationModel model) {
        this(model, null);
    }

    /**
     * @param model the desktop's notification log this tray reflects
     * @param dnd   the desktop's Do Not Disturb state, or null to omit the DND
     *              controls (e.g. in a log-only tray)
     */
    NotificationTray(NotificationModel model, DoNotDisturb dnd) {
        this.model = model;
        this.dnd = dnd;
        this.button = new JButton();
        Icon glyph = IconManager.loadIcon(IconManager.IconCategory.GENERAL, "About", 24, 24);
        this.baseIcon = (glyph == null) ? null
                : IconManager.resizeIcon(glyph, TRAY_ICON_EDGE, TRAY_ICON_EDGE);
        this.button.setIcon(baseIcon);
        this.button.addActionListener(e -> open());
        this.menu = new JPopupMenu();
        this.listener = this::refresh;
        this.dndListener = this::refresh;
        if (model != null) {
            model.addListener(listener);
        }
        if (dnd != null) {
            dnd.addListener(dndListener);
        }
        refresh();
    }

    /** The taskbar button to place in the bar's right-hand row. */
    JButton button() {
        return button;
    }

    /**
     * Switches between icon-only (descriptive text in the tooltip, unread count
     * as a badge on the glyph) and icon-plus-text presentation, re-refreshing.
     */
    void setIconOnly(boolean iconOnly) {
        if (this.iconOnly != iconOnly) {
            this.iconOnly = iconOnly;
            refresh();
        }
    }

    /** Syncs the button's badge to the model's unread count. */
    void refresh() {
        boolean dndActive = dnd != null && dnd.active(System.currentTimeMillis());
        int unread = (model == null) ? 0 : model.unreadCount();
        if (iconOnly) {
            button.setText(null);
            button.setIcon(badgedIcon(baseIcon, unread));
            button.setToolTipText(tooltip(unread, dndActive));
        } else {
            button.setIcon(baseIcon);
            button.setText(badgeLabel(unread, dndActive));
            button.setToolTipText(dndActive
                    ? "Desktop notifications (Do Not Disturb on)"
                    : "Desktop notifications");
        }
    }

    /**
     * The hover text: the unread count and DND state, which icon-only
     * presentation keeps off the button face.
     */
    static String tooltip(int unread, boolean dndActive) {
        StringBuilder sb = new StringBuilder("Desktop notifications");
        if (unread > 0) {
            sb.append(" (").append(unread).append(" unread)");
        }
        if (dndActive) {
            sb.append(" \u2014 Do Not Disturb on");
        }
        return sb.toString();
    }

    /**
     * Composites a small unread-count badge onto the top-right of {@code base}
     * so the at-a-glance signal survives icon-only presentation; with no unread
     * notifications (or no base glyph) the base icon is returned unchanged.
     */
    static Icon badgedIcon(Icon base, int unread) {
        if (base == null || unread <= 0) {
            return base;
        }
        int w = Math.max(1, base.getIconWidth());
        int h = Math.max(1, base.getIconHeight());
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            base.paintIcon(null, g, 0, 0);
            String text = (unread > 9) ? "9+" : Integer.toString(unread);
            int badge = Math.max(8, Math.round(Math.min(w, h) * 0.5f));
            int x = w - badge;
            g.setColor(BADGE_COLOR);
            g.fillOval(x, 0, badge, badge);
            g.setColor(Color.WHITE);
            g.setFont(g.getFont().deriveFont(Font.BOLD, Math.max(7f, badge * 0.62f)));
            FontMetrics metrics = g.getFontMetrics();
            int tx = x + (badge - metrics.stringWidth(text)) / 2;
            int ty = (badge + metrics.getAscent() - metrics.getDescent()) / 2;
            g.drawString(text, tx, ty);
        } finally {
            g.dispose();
        }
        return new ImageIcon(image);
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
        if (dnd != null) {
            addDndControls();
            menu.addSeparator();
        }
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

    /** Adds the Do Not Disturb on/off checkbox and "for 1 hour" entry. */
    private void addDndControls() {
        boolean active = dnd.active(System.currentTimeMillis());
        JCheckBoxMenuItem toggle = new JCheckBoxMenuItem("Do Not Disturb", active);
        toggle.addActionListener(e -> {
            if (dnd.active(System.currentTimeMillis())) {
                dnd.disable();
            } else {
                dnd.enable();
            }
        });
        menu.add(toggle);
        JMenuItem forHour = new JMenuItem("Do Not Disturb for 1 hour");
        forHour.setEnabled(!active);
        forHour.addActionListener(e ->
                dnd.enableFor(DoNotDisturb.ONE_HOUR_MILLIS, System.currentTimeMillis()));
        menu.add(forHour);
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
        if (dnd != null) {
            dnd.removeListener(dndListener);
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
     * The button label with an optional {@code [DND]} marker prefix when Do Not
     * Disturb is active.
     */
    static String badgeLabel(int unread, boolean dndActive) {
        String base = badgeLabel(unread);
        return dndActive ? "[DND] " + base : base;
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
