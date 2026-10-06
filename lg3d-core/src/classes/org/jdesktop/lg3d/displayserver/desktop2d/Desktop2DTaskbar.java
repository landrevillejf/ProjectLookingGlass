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
import java.awt.Color;
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
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import com.protonmail.landrevillejf.IconManager;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

/**
 * The 2D desktop's taskbar: a conventional Swing bar along the bottom of the
 * desktop window, standing in for the glassy 3D taskbar.
 *
 * <p>Left to right: the start-menu button, the pinned quick-launch shortcuts
 * (the 2D counterpart of the 3D taskbar's left-aligned {@code shortcuts} strip),
 * one button per open application window, then on the right the Documents and
 * Downloads folder menus, the clock and Exit - the same pieces the 3D taskbar
 * carries, minus the 3D-only background switcher.</p>
 */
public class Desktop2DTaskbar extends JPanel {

    /** Clock refresh interval. */
    private static final int CLOCK_INTERVAL_MS = 1000;

    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm");

    /** Taskbar chrome icon resources (rescaled by the configured icon scale). */
    private static final String STAR_ICON = "resources/images/icon/star.png";

    /** Natural edge of a quick-launch shortcut icon, before the config scale. */
    private static final int QUICKLAUNCH_ICON_BASE_PX = 22;

    /** Highlight painted on the quick-launch strip while a launcher hovers. */
    private static final Color QUICKLAUNCH_DROP_HIGHLIGHT = new Color(0x3d6da8);
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

    /**
     * The pinned quick-launch strip and the model behind it. The strip sits
     * between the start button and the running-window buttons, mirroring the 3D
     * taskbar's left-aligned {@code shortcuts} container; it is rebuilt whenever
     * the model fires a change (a pin, un-pin or reorder) or the icon scale
     * changes, and the current scale is kept so a rebuild draws the right size.
     */
    private final JPanel quickLaunchBar;
    private final QuickLaunchModel quickLaunch;
    private float quickLaunchIconScale = 1.0f;
    private final JLabel clock = new JLabel();
    private final Timer clockTimer;
    private final CalendarPopup calendar;
    private final TaskbarIndicators indicators;
    private final WorkspacePager workspacePager;

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
        // The pinned quick-launch strip sits between Start and the running-window
        // buttons, exactly where the 3D taskbar keeps its shortcut icons.
        quickLaunch = desktop.getQuickLaunchModel();
        quickLaunchBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        quickLaunchBar.setOpaque(false);
        leftRow.add(quickLaunchBar);
        JSeparator divider = new JSeparator(JSeparator.VERTICAL);
        divider.setPreferredSize(new Dimension(3, QUICKLAUNCH_ICON_BASE_PX));
        leftRow.add(divider);
        leftRow.add(windowButtons);
        JPanel left = new JPanel(new GridBagLayout());
        left.setOpaque(false);
        left.add(leftRow);
        add(left, BorderLayout.WEST);

        // Rebuild the strip on every pin/un-pin/reorder, and draw it once now.
        quickLaunch.addListener(this::rebuildQuickLaunch);
        rebuildQuickLaunch();
        // Accept launchers dragged out of the Application Launcher frame.
        installQuickLaunchDrop();

        JPanel rightRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        rightRow.setOpaque(false);
        documentsIconBase = Desktop2DStartMenu.icon(DOCUMENTS_ICON);
        documentsButton = folderButton("documents", documentsIconBase,
                desktop.getDocumentsMenu());
        downloadsIconBase = Desktop2DStartMenu.icon(DOWNLOADS_ICON);
        downloadsButton = folderButton("downloads", downloadsIconBase,
                desktop.getDownloadsMenu());
        rightRow.add(documentsButton);
        rightRow.add(downloadsButton);
        // The workspace pager sits left of the indicators: numbered buttons that
        // switch the desktop's current workspace and show each one's window count.
        workspacePager = new WorkspacePager(desktop.getWorkspaces(),
                desktop::switchToWorkspace);
        rightRow.add(workspacePager);
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
        // Double-clicking a day in that calendar opens the Agenda at that date.
        calendar.setOnOpenDate(desktop::openAgendaAt);
        JButton exit = new JButton("");
        exit.setIcon(IconManager.loadIcon(IconManager.IconCategory.GENERAL,"Stop",24,24));
        exit.setToolTipText("Leave the desktop");
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
        button.addMouseListener(new WindowPinPopup(button, window));
        buttons.put(window, button);
        // A window's button shows only while its workspace is the current one.
        button.setVisible(desktop.isOnCurrentWorkspace(window));
        windowButtons.add(button);
        windowButtons.revalidate();
        windowButtons.repaint();
        workspacePager.refresh();
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
        workspacePager.refresh();
    }

    /** Highlights the button of the window that currently has the focus. */
    public void windowSelected(Desktop2DWindow window) {
        for (Map.Entry<Desktop2DWindow, JButton> entry : buttons.entrySet()) {
            entry.getValue().setSelected(entry.getKey() == window);
        }
    }

    /**
     * Re-syncs the workspace UI after the current workspace changes: refreshes
     * the pager highlight and counts, and shows only the buttons of the windows
     * on the now-current workspace. Called by {@link Desktop2D} on a switch.
     */
    public void refreshWorkspaces() {
        workspacePager.refresh();
        for (Map.Entry<Desktop2DWindow, JButton> entry : buttons.entrySet()) {
            entry.getValue().setVisible(desktop.isOnCurrentWorkspace(entry.getKey()));
        }
        windowButtons.revalidate();
        windowButtons.repaint();
    }

    /**
     * Re-resolves every taskbar icon against the currently active icon pack:
     * the pinned quick-launch strip and each open window's button (whose icon
     * follows the window's freshly refreshed frame icon). Called by
     * {@link Desktop2D} after the icon cache is cleared when the user switches
     * packs, so the bar updates live rather than on the next restart.
     */
    public void refreshIcons() {
        rebuildQuickLaunch();
        for (Map.Entry<Desktop2DWindow, JButton> entry : buttons.entrySet()) {
            Desktop2DWindow window = entry.getKey();
            window.refreshFrameIcon();
            entry.getValue().setIcon(window.getFrameIcon());
        }
        windowButtons.revalidate();
        windowButtons.repaint();
    }

    // ------------------------------------------------------------------
    // Pinned quick-launch shortcuts (2D counterpart of the 3D taskbar strip)
    // ------------------------------------------------------------------

    /**
     * Rebuilds the quick-launch strip from the model: one icon button per pinned
     * application, a left-click launching it through the same
     * {@link Desktop2D#openApp} path the start menu uses and a right-click
     * offering the reorder / un-pin affordances the 3D taskbar's shortcut icons
     * give. Invoked on every model change and whenever the icon scale changes.
     */
    private void rebuildQuickLaunch() {
        quickLaunchBar.removeAll();
        List<QuickLaunchEntry> entries = quickLaunch.entries();
        int px = scaledSize(QUICKLAUNCH_ICON_BASE_PX, quickLaunchIconScale);
        for (int i = 0; i < entries.size(); i++) {
            QuickLaunchEntry entry = entries.get(i);
            JButton button = new JButton(
                    AppIcons.iconFor(entry.name(), entry.iconResource(), px));
            button.setToolTipText(entry.name());
            button.setFocusable(false);
            if (activeFont != null) {
                button.setFont(activeFont);
            }
            button.addActionListener(e -> desktop.openApp(entry.toItemSpec()));
            button.addMouseListener(new QuickLaunchPopup(button, entry, i));
            quickLaunchBar.add(button);
        }
        // Keep a drop zone even when nothing is pinned, so a launcher dragged
        // from the Application Launcher always has somewhere to land.
        quickLaunchBar.setPreferredSize(entries.isEmpty()
                ? new Dimension(px + 6, px) : null);
        quickLaunchBar.revalidate();
        quickLaunchBar.repaint();
    }

    /**
     * Makes the quick-launch strip a drop target for launchers dragged out of
     * the Application Launcher frame ({@code org.jdesktop.lg3d.apps.launcher}).
     * A drop pins the dragged launcher through the same
     * {@link QuickLaunchModel#pin} path the window right-click popup uses, so a
     * freshly created launcher can be pinned without first appearing in the
     * start menu. The strip highlights while an acceptable drag hovers over it.
     */
    private void installQuickLaunchDrop() {
        new DropTarget(quickLaunchBar, DnDConstants.ACTION_COPY_OR_MOVE,
                new QuickLaunchDrop(), true);
    }

    /** Highlights (or clears) the strip to signal it will accept a drop. */
    private void highlightQuickLaunch(final boolean on) {
        SwingUtilities.invokeLater(() -> {
            quickLaunchBar.setOpaque(on);
            quickLaunchBar.setBackground(on ? QUICKLAUNCH_DROP_HIGHLIGHT : null);
            quickLaunchBar.repaint();
        });
    }

    /**
     * Pins a launcher dragged from the Application Launcher onto the strip, on
     * the EDT. A blank command (nothing to launch) is ignored.
     */
    private void pinDroppedLauncher(final Desktop2D.QuickLaunchItem item) {
        if (item == null) {
            return;
        }
        final String command = item.command();
        if (command == null || command.isBlank()) {
            return;
        }
        final String name = (item.name() == null || item.name().isBlank())
                ? command : item.name();
        final String iconResource = item.iconResource();
        SwingUtilities.invokeLater(() -> quickLaunch.pin(
                new ItemSpec(name, command, null, null, iconResource)));
    }

    /**
     * Accepts {@link Desktop2D#QUICK_LAUNCH_FLAVOR} drops onto the strip. The
     * drag data is a same-JVM {@link Desktop2D.QuickLaunchItem} reference, so
     * the drop reads it directly and defers the model mutation to the EDT.
     */
    private final class QuickLaunchDrop extends DropTargetAdapter {

        @Override
        public void dragEnter(final DropTargetDragEvent dtde) {
            if (supported(dtde)) {
                dtde.acceptDrag(DnDConstants.ACTION_COPY_OR_MOVE);
                highlightQuickLaunch(true);
            } else {
                dtde.rejectDrag();
            }
        }

        @Override
        public void dragOver(final DropTargetDragEvent dtde) {
            if (supported(dtde)) {
                dtde.acceptDrag(DnDConstants.ACTION_COPY_OR_MOVE);
            } else {
                dtde.rejectDrag();
            }
        }

        @Override
        public void dropActionChanged(final DropTargetDragEvent dtde) {
            dragOver(dtde);
        }

        @Override
        public void dragExit(final DropTargetEvent dte) {
            highlightQuickLaunch(false);
        }

        @Override
        public void drop(final DropTargetDropEvent dtde) {
            Desktop2D.QuickLaunchItem item = null;
            try {
                if (!dtde.isDataFlavorSupported(Desktop2D.QUICK_LAUNCH_FLAVOR)) {
                    dtde.rejectDrop();
                    return;
                }
                dtde.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE);
                Object data = dtde.getTransferable()
                        .getTransferData(Desktop2D.QUICK_LAUNCH_FLAVOR);
                if (data instanceof Desktop2D.QuickLaunchItem) {
                    item = (Desktop2D.QuickLaunchItem) data;
                }
                dtde.dropComplete(true);
            } catch (UnsupportedFlavorException | IOException e) {
                dtde.rejectDrop();
            } finally {
                highlightQuickLaunch(false);
            }
            pinDroppedLauncher(item);
        }

        private boolean supported(final DropTargetDragEvent dtde) {
            return dtde.isDataFlavorSupported(Desktop2D.QUICK_LAUNCH_FLAVOR);
        }
    }

    /**
     * The right-click popup on a quick-launch button. The index is captured when
     * the strip is built; because every mutation rebuilds the strip, the captured
     * index always matches the entry's current position while the popup is live.
     */
    private final class QuickLaunchPopup extends MouseAdapter {
        private final JButton anchor;
        private final QuickLaunchEntry entry;
        private final int index;

        QuickLaunchPopup(JButton anchor, QuickLaunchEntry entry, int index) {
            this.anchor = anchor;
            this.entry = entry;
            this.index = index;
        }

        @Override
        public void mousePressed(MouseEvent e) {
            maybeShow(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            maybeShow(e);
        }

        private void maybeShow(MouseEvent e) {
            if (!e.isPopupTrigger()) {
                return;
            }
            JPopupMenu menu = new JPopupMenu();
            JMenuItem open = new JMenuItem("Launch");
            open.addActionListener(a -> desktop.openApp(entry.toItemSpec()));
            menu.add(open);
            menu.addSeparator();
            JMenuItem moveLeft = new JMenuItem("Move Left");
            moveLeft.setEnabled(index > 0);
            moveLeft.addActionListener(a -> quickLaunch.move(index, index - 1));
            menu.add(moveLeft);
            JMenuItem moveRight = new JMenuItem("Move Right");
            moveRight.setEnabled(index < quickLaunch.size() - 1);
            moveRight.addActionListener(a -> quickLaunch.move(index, index + 1));
            menu.add(moveRight);
            menu.addSeparator();
            JMenuItem unpin = new JMenuItem("Unpin from Taskbar");
            unpin.addActionListener(a -> quickLaunch.unpin(entry.command()));
            menu.add(unpin);
            menu.show(anchor, e.getX(), e.getY());
        }
    }

    /**
     * The right-click popup on a running window's taskbar button: a single entry
     * that pins the application to the quick-launch strip, or un-pins it when it
     * is already there. This is how a user curates the strip beyond the seeded
     * defaults, mirroring pinning a running app to a conventional taskbar.
     */
    private final class WindowPinPopup extends MouseAdapter {
        private final JButton anchor;
        private final Desktop2DWindow window;

        WindowPinPopup(JButton anchor, Desktop2DWindow window) {
            this.anchor = anchor;
            this.window = window;
        }

        @Override
        public void mousePressed(MouseEvent e) {
            maybeShow(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            maybeShow(e);
        }

        private void maybeShow(MouseEvent e) {
            if (!e.isPopupTrigger()) {
                return;
            }
            String command = window.getCommand();
            if (command == null || command.isBlank()) {
                // A window with no launch identity (an ad hoc folder) cannot be
                // pinned, so there is nothing to offer.
                return;
            }
            boolean pinned = quickLaunch.isPinned(command);
            JPopupMenu menu = new JPopupMenu();
            JMenuItem toggle = new JMenuItem(
                    pinned ? "Unpin from Taskbar" : "Pin to Taskbar");
            toggle.addActionListener(a -> {
                if (pinned) {
                    quickLaunch.unpin(command);
                } else {
                    quickLaunch.pin(new ItemSpec(window.getAppName(), command,
                            null, null, window.getIconResource()));
                }
            });
            menu.add(toggle);
            menu.show(anchor, e.getX(), e.getY());
        }
    }

    /** The system-indicator cluster (volume, brightness, network, battery). */
    TaskbarIndicators indicators() {
        return indicators;
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
        // The quick-launch shortcut icons follow the same configured scale.
        quickLaunchIconScale = iconScale;
        rebuildQuickLaunch();

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
