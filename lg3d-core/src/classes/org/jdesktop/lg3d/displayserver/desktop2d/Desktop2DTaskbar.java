/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * The 2D desktop's taskbar: a conventional Swing bar along the bottom of the
 * desktop window, standing in for the glassy 3D taskbar.
 *
 * <p>Left to right: the start-menu button, one button per open application
 * window, then on the right the Documents and Downloads folder menus, the clock
 * and Exit - the same pieces the 3D taskbar carries, minus the 3D-only
 * background switcher.</p>
 */
public class Desktop2DTaskbar extends JPanel {

    /** Clock refresh interval. */
    private static final int CLOCK_INTERVAL_MS = 1000;

    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    private final Desktop2D desktop;
    private final JPanel windowButtons;
    private final Map<Desktop2DWindow, JButton> buttons = new LinkedHashMap<>();
    private final JLabel clock = new JLabel();
    private final Timer clockTimer;

    /**
     * @param desktop the shell this bar belongs to (supplies the menus and the
     *                window/exit handling)
     */
    public Desktop2DTaskbar(final Desktop2D desktop) {
        super(new BorderLayout());
        this.desktop = desktop;
        setBorder(new EmptyBorder(2, 4, 2, 4));

        windowButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        windowButtons.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        left.setOpaque(false);
        JButton start = new JButton("Start",
                Desktop2DStartMenu.icon("resources/images/icon/star.png"));
        start.setToolTipText("Applications");
        start.addActionListener(e -> showPopup(desktop.getStartMenu(), start));
        left.add(start);
        left.add(windowButtons);
        add(left, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        right.setOpaque(false);
        right.add(folderButton("Documents",
                "resources/images/icon/folder-documents.png",
                desktop.getDocumentsMenu()));
        right.add(folderButton("Downloads",
                "resources/images/icon/folder-downloads.png",
                desktop.getDownloadsMenu()));
        right.add(clock);
        JButton exit = new JButton("Exit");
        exit.setToolTipText("Leave the 2D desktop");
        exit.addActionListener(e -> desktop.confirmExit());
        right.add(exit);
        add(right, BorderLayout.EAST);

        ActionListener tick = e -> updateClock();
        clockTimer = new Timer(CLOCK_INTERVAL_MS, tick);
        clockTimer.setRepeats(true);
        updateClock();
        clockTimer.start();
    }

    private JButton folderButton(String label, String iconResource,
                                 final JPopupMenu menu) {
        Icon icon = Desktop2DStartMenu.icon(iconResource);
        JButton button = new JButton(label, icon);
        button.setToolTipText("Recently modified files in ~/" + label);
        button.addActionListener(e -> showPopup(menu, button));
        return button;
    }

    /** Shows {@code menu} just above {@code anchor}. */
    private static void showPopup(JPopupMenu menu, JButton anchor) {
        if (menu == null) {
            return;
        }
        int height = menu.getPreferredSize().height;
        menu.show(anchor, 0, -height);
    }

    private void updateClock() {
        LocalTime now = LocalTime.now();
        clock.setText(now.format(CLOCK_FORMAT));
        clock.setToolTipText(now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    /** Adds the taskbar button for a newly opened application window. */
    public void windowOpened(final Desktop2DWindow window) {
        if (window == null || buttons.containsKey(window)) {
            return;
        }
        JButton button = new JButton(window.getAppName(), window.getFrameIcon());
        button.setToolTipText(window.getTitle());
        button.setFocusable(false);
        button.addActionListener(e -> desktop.activateWindow(window));
        buttons.put(window, button);
        windowButtons.add(button);
        windowButtons.revalidate();
        windowButtons.repaint();
    }

    /** Removes the taskbar button of a closed application window. */
    public void windowClosed(Desktop2DWindow window) {
        JButton button = (window == null) ? null : buttons.remove(window);
        if (button == null) {
            return;
        }
        windowButtons.remove(button);
        windowButtons.revalidate();
        windowButtons.repaint();
    }

    /** Highlights the button of the window that currently has the focus. */
    public void windowSelected(Desktop2DWindow window) {
        for (Map.Entry<Desktop2DWindow, JButton> entry : buttons.entrySet()) {
            entry.getValue().setSelected(entry.getKey() == window);
        }
    }

    /** Stops the clock timer; called when the desktop shuts down. */
    public void stop() {
        clockTimer.stop();
    }
}
