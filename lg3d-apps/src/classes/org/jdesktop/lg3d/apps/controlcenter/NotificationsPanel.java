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
package org.jdesktop.lg3d.apps.controlcenter;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.displayserver.desktop2d.DoNotDisturb;

/**
 * Notifications panel: the Do Not Disturb switch and the live notification log,
 * over the {@link Desktop2D} control-center hooks. Both are {@link JList}s (never
 * a combo box) so the panel keeps working when hosted offscreen in a
 * {@code SwingNode}. The DND choice persists to {@code DesktopConfig} through the
 * hook, so it survives a restart; the log reflects the running 2D desktop and is
 * empty (with an explanatory status) in 3D mode or headless, where no shell owns
 * a notification model.
 */
public class NotificationsPanel implements ControlPanel {

    /** The Do Not Disturb choices, parallel to {@link #applyDnd()}. */
    static final String[] DND_OPTIONS = {"Off", "On", "On for 1 hour"};

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<String> dndModel = new DefaultListModel<>();
    private final JList<String> dndList = new JList<>(dndModel);
    private final DefaultListModel<String> noteModel = new DefaultListModel<>();
    private final JList<String> noteList = new JList<>(noteModel);
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton applyDnd = new JButton("Apply");

    public NotificationsPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        for (String option : DND_OPTIONS) {
            dndModel.addElement(option);
        }
        dndList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        dndList.setVisibleRowCount(3);
        JScrollPane dndScroll = new JScrollPane(dndList);
        dndScroll.setPreferredSize(new Dimension(150, 90));
        applyDnd.addActionListener(e -> applyDnd());
        JPanel dndPanel = new JPanel(new BorderLayout(4, 4));
        dndPanel.setBorder(BorderFactory.createTitledBorder("Do Not Disturb"));
        dndPanel.add(dndScroll, BorderLayout.CENTER);
        dndPanel.add(applyDnd, BorderLayout.SOUTH);

        noteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        noteList.setVisibleRowCount(8);
        JScrollPane noteScroll = new JScrollPane(noteList);
        noteScroll.setBorder(BorderFactory.createTitledBorder("Recent notifications"));

        JButton markRead = new JButton("Mark all read");
        markRead.addActionListener(e -> {
            Desktop2D.markNotificationsRead();
            reloadNotifications();
        });
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> {
            Desktop2D.clearNotifications();
            reloadNotifications();
        });
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> reloadNotifications());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(markRead);
        buttons.add(clear);
        buttons.add(refresh);

        JPanel right = new JPanel(new BorderLayout(4, 4));
        right.add(noteScroll, BorderLayout.CENTER);
        right.add(buttons, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(dndPanel, BorderLayout.WEST);
        center.add(right, BorderLayout.CENTER);

        root.add(statusLabel, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);

        reload();
    }

    @Override
    public String displayName() {
        return "Notifications";
    }

    @Override
    public javax.swing.Icon icon() {
        return null;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void onShow() {
        reload();
    }

    // ------------------------------------------------------------------

    private void reload() {
        reloadDnd();
        reloadNotifications();
    }

    private void reloadDnd() {
        Desktop2D.DoNotDisturbSnapshot snap = Desktop2D.doNotDisturbSnapshot();
        dndList.setSelectedIndex(dndOptionIndex(snap.enabled(), snap.untilMillis()));
    }

    private void reloadNotifications() {
        Desktop2D.NotificationSnapshot snap = Desktop2D.notificationSnapshot();
        noteModel.clear();
        List<Desktop2D.NotificationEntry> entries = snap.entries();
        for (Desktop2D.NotificationEntry n : entries) {
            noteModel.addElement(format(n));
        }
        if (entries.isEmpty()) {
            statusLabel.setText("No notifications.");
        } else {
            statusLabel.setText(entries.size() + " notification(s); " + snap.unread() + " unread");
        }
    }

    private static String format(Desktop2D.NotificationEntry n) {
        String body = (n.message() == null || n.message().isBlank()) ? "" : " - " + n.message();
        return "[" + n.kind() + "] " + n.title() + body;
    }

    private void applyDnd() {
        switch (dndList.getSelectedIndex()) {
            case 1 -> Desktop2D.setDoNotDisturb(true);
            case 2 -> Desktop2D.setDoNotDisturbFor(DoNotDisturb.ONE_HOUR_MILLIS);
            default -> Desktop2D.setDoNotDisturb(false);
        }
        reloadDnd();
    }

    /**
     * The DND option index for a persisted state: 0 off, 1 on indefinitely, 2 on
     * until a deadline. Pure so it can be unit-tested headless.
     */
    static int dndOptionIndex(boolean enabled, long untilMillis) {
        if (!enabled) {
            return 0;
        }
        return untilMillis > 0L ? 2 : 1;
    }
}
