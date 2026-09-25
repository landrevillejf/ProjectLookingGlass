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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.IllegalComponentStateException;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.event.ActionListener;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

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

    /** Taskbar chrome icon resources (rescaled by the configured icon scale). */
    private static final String STAR_ICON = "resources/images/icon/star.png";
    private static final String DOCUMENTS_ICON =
            "resources/images/icon/folder-documents.png";
    private static final String DOWNLOADS_ICON =
            "resources/images/icon/folder-downloads.png";

    /** Natural bar height at {@code barScale == 1.0}, in pixels. */
    static final int BASE_BAR_HEIGHT_PX = 34;
    /** Floor so a small bar scale never clips the buttons entirely. */
    static final int MIN_BAR_HEIGHT_PX = 18;
    /** Sliver left on screen when auto-hide has collapsed the bar. */
    static final int COLLAPSED_HEIGHT_PX = 5;
    /** Sentinel height: let the bar pack to the height of its controls. */
    private static final int NATURAL_HEIGHT = -1;
    /** How often the auto-hide poll checks the pointer against the bar. */
    private static final int AUTOHIDE_POLL_MS = 250;

    private final Desktop2D desktop;
    private final JPanel windowButtons;
    private final Map<Desktop2DWindow, JButton> buttons = new LinkedHashMap<>();
    private final JLabel clock = new JLabel();
    private final Timer clockTimer;
    private final CalendarPopup calendar;
    private final TaskbarIndicators indicators;

    private final JButton startButton;
    private final JButton documentsButton;
    private final JButton downloadsButton;
    private final Icon startIconBase;
    private final Icon documentsIconBase;
    private final Icon downloadsIconBase;

    /** Per-component fonts as built, restored when the config font is default. */
    private final Map<Component, Font> originalFonts = new IdentityHashMap<>();

    private Font activeFont;
    private int currentHeight = NATURAL_HEIGHT;
    private boolean autoHide;
    private Timer autoHideTimer;

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

        // Each button row is centred inside a plain GridBagLayout wrapper: a
        // thick bar (barScale > 1) then keeps its buttons on one centred row
        // instead of pinning them to the top and leaving an empty band below
        // that reads as a second row.
        JPanel leftRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        leftRow.setOpaque(false);
        startIconBase = Desktop2DStartMenu.icon(STAR_ICON);
        startButton = new JButton("Start", startIconBase);
        startButton.setToolTipText("Applications");
        startButton.addActionListener(e -> showPopup(desktop.getStartMenu(), startButton));
        leftRow.add(startButton);
        leftRow.add(windowButtons);
        JPanel left = new JPanel(new GridBagLayout());
        left.setOpaque(false);
        left.add(leftRow);
        add(left, BorderLayout.WEST);

        JPanel rightRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        rightRow.setOpaque(false);
        documentsIconBase = Desktop2DStartMenu.icon(DOCUMENTS_ICON);
        documentsButton = folderButton("Documents", documentsIconBase,
                desktop.getDocumentsMenu());
        downloadsIconBase = Desktop2DStartMenu.icon(DOWNLOADS_ICON);
        downloadsButton = folderButton("Downloads", downloadsIconBase,
                desktop.getDownloadsMenu());
        rightRow.add(documentsButton);
        rightRow.add(downloadsButton);
        // Volume/network/battery glyphs sit left of the clock, like a system tray.
        indicators = new TaskbarIndicators();
        rightRow.add(indicators);
        // The notification-area button sits just left of the clock, the way a
        // system tray does; it reflects the desktop's shared notification log.
        NotificationTray notificationTray =
                new NotificationTray(desktop.getNotificationModel(),
                        desktop.getDoNotDisturb());
        rightRow.add(notificationTray.button());
        rightRow.add(clock);
        // Clicking the clock opens a calendar with a small agenda for today.
        calendar = new CalendarPopup(clock, desktop.getNotificationModel());
        JButton exit = new JButton("Exit");
        exit.setToolTipText("Leave the 2D desktop");
        exit.addActionListener(e -> desktop.confirmExit());
        rightRow.add(exit);
        JPanel right = new JPanel(new GridBagLayout());
        right.setOpaque(false);
        right.add(rightRow);
        add(right, BorderLayout.EAST);

        ActionListener tick = e -> updateClock();
        clockTimer = new Timer(CLOCK_INTERVAL_MS, tick);
        clockTimer.setRepeats(true);
        updateClock();
        clockTimer.start();

        captureOriginalFonts(this);
    }

    private JButton folderButton(String label, Icon icon,
                                 final JPopupMenu menu) {
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
        if (activeFont != null) {
            button.setFont(activeFont);
        }
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
        indicators.stop();
        stopAutoHide();
    }

    // ------------------------------------------------------------------
    // Live configuration (taskbar thickness, position, icons, font, auto-hide)
    // ------------------------------------------------------------------

    /**
     * Re-applies {@link DesktopConfig} to this bar: the UI font, the thickness
     * ({@code barScale}), the chrome icon scale ({@code iconScale}) and the
     * auto-hide toggle. Called by {@link Desktop2D#applyDesktopConfig()} when
     * the user hits Apply in the control center, and once at startup to honour
     * the persisted settings. The docking edge (top/bottom) is applied by
     * {@link Desktop2D}, which owns the content pane this bar lives in.
     */
    public void applyConfig() {
        DesktopConfig cfg = DesktopConfig.get();
        float iconScale = clampScale(cfg.getIconScale());

        boolean defaultFont =
                DesktopConfig.DEFAULT_FONT_NAME.equals(cfg.getFontName())
                && cfg.getFontSize() == DesktopConfig.DEFAULT_FONT_SIZE;
        activeFont = defaultFont ? null
                : new Font(cfg.getFontName(), Font.PLAIN,
                        Math.max(1, cfg.getFontSize()));
        applyFonts(this, activeFont);

        startButton.setIcon(scaledIcon(startIconBase, iconScale));
        documentsButton.setIcon(scaledIcon(documentsIconBase, iconScale));
        downloadsButton.setIcon(scaledIcon(downloadsIconBase, iconScale));

        // The bar hugs its controls: it carries no thickness of its own, so it
        // is always roughly as tall as the buttons it holds. Only auto-hide
        // overrides the height, collapsing the bar to a sliver.
        autoHide = cfg.isAutoHide();
        if (autoHide) {
            startAutoHide();
            updateAutoHide();
        } else {
            stopAutoHide();
            setBarHeight(NATURAL_HEIGHT);
        }
        revalidate();
        repaint();
    }

    private void setBarHeight(int height) {
        if (currentHeight == height) {
            return;
        }
        currentHeight = height;
        setPreferredSize(height < 0 ? null : new Dimension(0, height));
        Container parent = getParent();
        if (parent != null) {
            parent.revalidate();
        }
        repaint();
    }

    private void startAutoHide() {
        if (autoHideTimer == null) {
            autoHideTimer = new Timer(AUTOHIDE_POLL_MS, e -> updateAutoHide());
            autoHideTimer.setRepeats(true);
        }
        autoHideTimer.start();
    }

    private void stopAutoHide() {
        if (autoHideTimer != null) {
            autoHideTimer.stop();
        }
    }

    /** Expands the bar while the pointer is over it, collapses it otherwise. */
    private void updateAutoHide() {
        setBarHeight(isPointerOverBar() ? NATURAL_HEIGHT : COLLAPSED_HEIGHT_PX);
    }

    private boolean isPointerOverBar() {
        if (!isShowing()) {
            return false;
        }
        try {
            PointerInfo info = MouseInfo.getPointerInfo();
            if (info == null) {
                return false;
            }
            Point origin = getLocationOnScreen();
            return new Rectangle(origin, getSize()).contains(info.getLocation());
        } catch (IllegalComponentStateException e) {
            return false;
        }
    }

    private void applyFonts(Container parent, Font configured) {
        for (Component comp : parent.getComponents()) {
            Font f = (configured != null) ? configured : originalFonts.get(comp);
            if (f != null) {
                comp.setFont(f);
            }
            if (comp instanceof Container) {
                applyFonts((Container) comp, configured);
            }
        }
    }

    private void captureOriginalFonts(Container parent) {
        for (Component comp : parent.getComponents()) {
            originalFonts.put(comp, comp.getFont());
            if (comp instanceof Container) {
                captureOriginalFonts((Container) comp);
            }
        }
    }

    private static Icon scaledIcon(Icon base, float scale) {
        if (base == null) {
            return null;
        }
        if (scale == 1.0f || !(base instanceof ImageIcon)) {
            return base;
        }
        Image image = ((ImageIcon) base).getImage();
        return new ImageIcon(image.getScaledInstance(
                scaledSize(base.getIconWidth(), scale),
                scaledSize(base.getIconHeight(), scale),
                Image.SCALE_SMOOTH));
    }

    /** Clamps a user scale into the {@link DesktopConfig} range. */
    static float clampScale(float scale) {
        if (Float.isNaN(scale)) {
            return 1.0f;
        }
        return Math.max(DesktopConfig.MIN_SCALE,
                Math.min(DesktopConfig.MAX_SCALE, scale));
    }

    /** The bar pixel height for a {@code barScale}, never below the floor. */
    static int barHeightFor(float barScale) {
        return Math.max(MIN_BAR_HEIGHT_PX,
                Math.round(BASE_BAR_HEIGHT_PX * clampScale(barScale)));
    }

    /** Scales an icon edge, clamped and never below one pixel. */
    static int scaledSize(int base, float scale) {
        return Math.max(1, Math.round(base * clampScale(scale)));
    }
}
