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
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyVetoException;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.DesktopManager;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.JFrame;
import javax.swing.JInternalFrame;
import javax.swing.LookAndFeel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.basic.BasicDesktopPaneUI;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.jdesktop.lg3d.utils.schedule.ScheduleService;
import org.jdesktop.lg3d.utils.system.Opener;

/**
 * The conventional-Swing desktop used when Java 3D is unavailable: one
 * undecorated, maximised window holding an MDI {@link JDesktopPane} over the
 * usual lg3d wallpaper, with the Swing {@link Desktop2DTaskbar} along the
 * bottom.
 *
 * <p>Applications come from the same {@code .lgcfg} descriptors the 3D start
 * menu reads ({@link Desktop2DMenuConfig}). Those whose user interface is a
 * plain Swing panel are hosted in internal frames ({@link Desktop2DWindow});
 * conventional Swing apps that insist on their own {@code JFrame} are launched
 * beside the desktop; external executables are started as child processes; and
 * pure Java 3D applications are offered disabled, since there is no scene to
 * render them into.</p>
 *
 * <p>Nothing in this package touches Java 3D, so the shell also starts on a JVM
 * where the Java 3D jars are missing entirely.</p>
 */
public class Desktop2D {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /**
     * Set to {@code true} while the 2D desktop is running. Code that offers
     * Java 3D-only features (the control center's Appearance/Desktop panels)
     * reads it to leave those out instead of failing.
     */
    public static final String MODE_PROPERTY = "lg.desktop2d";

    /** Window title, so the host window manager shows something sensible. */
    static final String FRAME_TITLE = "Project Looking Glass (2D desktop)";

    /** Wallpapers tried, in order, for the desktop backdrop. */
    private static final String[] WALLPAPERS = {
        "resources/Backgrounds/GrandCanyon/GrandCanyon-0.jpg",
        "resources/images/background/DreamLakeReflections.jpg",
    };

    /** Classpath directory the "Change Wallpaper" submenu enumerates. */
    private static final String BG_DIR = "resources/images/background";

    /**
     * Wallpapers offered when {@link #BG_DIR} cannot be listed (e.g. running
     * from a jar). The same fallback the control center's Appearance panel
     * uses, duplicated here so the 2D shell keeps no lg3d-apps dependency.
     */
    private static final List<String> WALLPAPER_FALLBACK = List.of(
            "DreamLakeReflections.jpg",
            "GrandCanyon-0.jpg",
            "Leaves_and_Sky-0.jpg",
            "Stanford-0.jpg");

    /** Terminal executables tried, in order, for the "Open Terminal" entry. */
    private static final String[] TERMINALS = {
        "xterm", "gnome-terminal", "konsole", "xfce4-terminal",
        "mate-terminal", "lxterminal",
    };

    /**
     * The start-menu command that launches the Agenda panel app in the 2D
     * desktop; the same {@code Agenda3D} descriptor the start menu uses, mapped
     * to {@code AgendaPanel} by {@code Desktop2DAppRegistry}'s panel table. Used
     * to open the Agenda at a date double-clicked in the taskbar calendar.
     */
    private static final String AGENDA_COMMAND =
            "java org.jdesktop.lg3d.apps.orgchart.ui.agenda.Agenda3D";

    /**
     * UIManager font-default keys overridden by the desktop configuration. The
     * same list {@code TitledSwingWindow} uses on the 3D desktop, duplicated
     * here so the 2D shell stays free of any demo-apps / Java 3D dependency.
     */
    private static final String[] FONT_KEYS = {
        "Label.font", "Button.font", "ToggleButton.font", "TextField.font",
        "TextArea.font", "ComboBox.font", "List.font", "Table.font",
        "TableHeader.font", "Menu.font", "MenuItem.font", "PopupMenu.font",
        "Panel.font", "Dialog.font", "Frame.font", "TitledBorder.font",
        "OptionPane.font", "CheckBox.font", "RadioButton.font",
        "TabbedPane.font", "Tree.font", "ToolBar.font", "Spinner.font",
        "EditorPane.font", "TextPane.font", "FormattedTextField.font",
        "PasswordField.font", "ToolTip.font"
    };

    /** The running 2D desktop, so settings panels can reach it. Null if none. */
    private static volatile Desktop2D instance;

    private final JFrame frame;

    /** Software brightness wash over the whole desktop (glass pane). */
    private final BrightnessDimmer brightnessDimmer;
    private final WallpaperDesktopPane desktop;
    private final Desktop2DTaskbar taskbar;
    private final Desktop2DMenuConfig.MenuModel menuModel;
    private final WindowCyclerOverlay windowSwitcher;
    private final NotificationModel notifications;
    private final DoNotDisturb dnd;
    private final ToastLayer toastLayer;
    private final SessionManager sessionManager;
    private final RunHistoryStore runHistoryStore;
    private final RunHistory runHistory;

    /**
     * The multiple-workspace (virtual desktop) model: which window lives on
     * which workspace and which workspace is shown now. Built before the
     * taskbar, whose pager reads it.
     */
    private final WorkspaceModel workspaces;

    /**
     * The wallpaper slideshow model and the Swing timer that advances it. The
     * model is built in the constructor; the timer is (re)started in
     * {@link #show()} and stopped in {@link #exit()}, honouring the persisted
     * enable/interval/folder in {@link DesktopConfig}.
     */
    private final WallpaperSlideshow slideshow;
    private Timer slideshowTimer;

    /** Global keyboard-shortcut table and the dispatcher that feeds it. */
    private final ShortcutMap shortcuts = ShortcutMap.defaults();
    private final Shortcuts.Target shortcutActions = new ShortcutActions();
    private KeyEventDispatcher shortcutDispatcher;

    /** True while {@link #restoreSession()} is relaunching windows, to defer saves. */
    private boolean restoring;

    private JPopupMenu startMenu;
    private JPopupMenu documentsMenu;
    private JPopupMenu downloadsMenu;
    private RunDialog runDialog;

    /**
     * Builds the desktop shell. Does not show it; call {@link #start()} (or
     * {@link #show()} on the EDT).
     */
    public Desktop2D() {
        System.setProperty(MODE_PROPERTY, "true");
        logger.info("Starting the conventional Swing (2D) desktop");

        menuModel = Desktop2DMenuConfig.load();
        desktop = new WallpaperDesktopPane(wallpaper());
        desktop.setDragMode(JDesktopPane.OUTLINE_DRAG_MODE);
        // The taskbar button is the single representation of a minimised
        // window; stock MDI would also drop a desktop icon on the pane, which
        // shows the icon twice and reads as a second row above the taskbar. The
        // same manager adds snap-to-edge placement while a window is dragged.
        desktop.setDesktopManager(new SnappingDesktopManager());

        // Right-clicking the wallpaper (anywhere not covered by an app window or
        // a widget) opens the desktop context menu. Both press and release are
        // checked because which one is the popup trigger is platform-specific.
        desktop.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showDesktopContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showDesktopContextMenu(e);
            }
        });

        // The notification log feeds both the taskbar tray and the toast
        // overlay; build it before the taskbar, which constructs the tray.
        notifications = new NotificationModel();
        // Do Not Disturb gates the transient toast (never the log). Restored
        // from the persisted desktop config and written back on every change.
        dnd = restoreDoNotDisturb();
        dnd.addListener(this::persistDoNotDisturb);

        // The workspace model must exist before the taskbar builds its pager,
        // which reads the workspace count and current index.
        workspaces = new WorkspaceModel(DesktopConfig.get().getWorkspaceCount());

        frame = new JFrame(FRAME_TITLE);
        JPanel content = new JPanel(new BorderLayout());
        content.add(desktop, BorderLayout.CENTER);
        taskbar = new Desktop2DTaskbar(this);
        content.add(taskbar, BorderLayout.SOUTH);
        frame.setContentPane(content);

        // Software brightness: on a host whose hardware backlight is not
        // writable unprivileged the taskbar slider would otherwise do nothing,
        // so a refused percentage dims the whole desktop through the glass pane
        // instead. The dimmer never intercepts input (contains() is false).
        brightnessDimmer = new BrightnessDimmer();
        frame.setGlassPane(brightnessDimmer);
        taskbar.indicators().setSoftwareBrightness(brightnessDimmer::setPercent);

        Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        frame.setBounds(bounds);
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmExit();
            }
        });

        // Drop the built-in widgets onto the wallpaper, behind the app windows.
        installWidgetLayer();

        // Install the Alt+` window switcher overlay on the desktop pane's popup
        // layer, so it floats above every application window.
        windowSwitcher = new WindowCyclerOverlay(new SwitcherWindowSource());
        windowSwitcher.install(desktop);

        // Install the toast overlay on the popup layer as well, so transient
        // notifications float above the application windows.
        toastLayer = new ToastLayer(new ToastQueue());
        toastLayer.install(desktop);

        // Global keyboard shortcuts (show desktop, snap, terminal, close...),
        // resolved while this frame has the focus.
        installShortcuts();

        // Persistence for the open-window session (which apps were open, and
        // where). Restored in show(), once the desktop pane has its real size.
        sessionManager = new SessionManager();

        // The Alt+F2 run dialog: its command history persists beside the saved
        // session. Alt+F2 itself is bound through the global shortcut table
        // (ShortcutMap's run-dialog action), whose handler calls showRunDialog().
        runHistoryStore = new PrefsRunHistoryStore();
        runHistory = runHistoryStore.load();

        // The wallpaper slideshow model; its timer is started in show(), once
        // the frame is realized. Building it here keeps the field final.
        slideshow = new WallpaperSlideshow(slideshowImages());

        instance = this;
        // Honour any desktop configuration persisted from a previous session
        // (taskbar position/thickness/font/icon scale/auto-hide). The wallpaper
        // stays the bundled default until the user picks one in the control
        // center, exactly as the 3D desktop does.
        reapplyConfig();
    }

    /**
     * Starts the 2D desktop on the event dispatch thread. This is the entry
     * point {@code Main} uses when 3D is unavailable.
     */
    public static void start() {
        installLookAndFeel();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new Desktop2D().show();
            }
        });
    }

    /** Shows the desktop window. Must be called on the EDT. */
    public void show() {
        frame.setVisible(true);
        frame.toFront();
        // The pane now has its real size, so restored windows can be clamped
        // on-screen. Done after the frame is shown rather than in the
        // constructor, where the desktop pane is not yet laid out.
        restoreSession();
        // Restored windows all land on the current workspace; make the MDI frame
        // visibility and the taskbar pager match before the shell is used.
        applyWorkspaceVisibility();
        taskbar.refreshWorkspaces();
        // Start (or leave stopped) the wallpaper slideshow per the config.
        applySlideshowConfig();
        // Honour a persisted daylight/nightlight wallpaper schedule: starting
        // the service applies the wallpaper for the current time and keeps
        // re-checking every minute. It self-gates on the schedule-enabled
        // preference, so this is a no-op when the feature is off.
        ScheduleService.get();
    }

    /** The desktop window (package-visible for diagnostics). */
    JFrame getFrame() {
        return frame;
    }

    /** The MDI pane application windows live in. */
    JDesktopPane getDesktopPane() {
        return desktop;
    }

    /** The desktop's notification log (package-visible for the taskbar tray). */
    NotificationModel getNotificationModel() {
        return notifications;
    }

    /**
     * The multiple-workspace model (package-visible for the taskbar, whose pager
     * and window buttons reflect only the current workspace).
     */
    WorkspaceModel getWorkspaces() {
        return workspaces;
    }

    /**
     * Switches to the workspace at {@code index} (wrapped into range), shows its
     * windows, hides the others and re-syncs the taskbar. Driven by the pager
     * buttons and the workspace shortcuts.
     */
    void switchToWorkspace(int index) {
        workspaces.switchTo(index);
        applyWorkspaceVisibility();
        taskbar.refreshWorkspaces();
    }

    /**
     * True when {@code window} is on the workspace shown now, so its taskbar
     * button and MDI frame should be visible.
     */
    boolean isOnCurrentWorkspace(Desktop2DWindow window) {
        return window != null && workspaces.isOnCurrent(window.getAppName());
    }

    /**
     * Shows the windows on the current workspace and hides those on every other
     * one, without disposing anything: paging only toggles MDI frame visibility.
     * The front-most now-visible window is selected so the desktop is never left
     * with a hidden frame holding the selection.
     */
    private void applyWorkspaceVisibility() {
        Desktop2DWindow front = null;
        for (JInternalFrame candidate : desktop.getAllFrames()) {
            if (candidate instanceof Desktop2DWindow) {
                Desktop2DWindow window = (Desktop2DWindow) candidate;
                boolean here = workspaces.isOnCurrent(window.getAppName());
                window.setVisible(here);
                if (here && front == null && !window.isIcon()) {
                    front = window;
                }
            }
        }
        if (front != null) {
            try {
                front.setSelected(true);
            } catch (java.beans.PropertyVetoException pve) {
                logger.log(Level.FINE, "Could not select " + front.getAppName(), pve);
            }
        }
    }

    /** The desktop's Do Not Disturb state (package-visible for the taskbar tray). */
    DoNotDisturb getDoNotDisturb() {
        return dnd;
    }

    /** A conventional look for a conventional desktop; failure is cosmetic. */
    private static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            logger.log(Level.FINE, "Keeping the default look and feel", e);
        }
    }

    // ------------------------------------------------------------------
    // Desktop widgets
    // ------------------------------------------------------------------

    /**
     * Installs the 2D widget layer on the desktop pane, if the lg3d-widgets
     * module is on the classpath. Done reflectively: lg3d-core cannot depend on
     * lg3d-widgets, and the desktop must still start where that module is
     * absent. The layer loads the very same persisted layout
     * ({@code ~/.config/lg3d/widgets.properties}) the 3D desktop writes, so a
     * widget arrangement carries over between the 3D and 2D desktops.
     */
    private void installWidgetLayer() {
        try {
            Class<?> layer = Class.forName(
                    "org.jdesktop.lg3d.widgets.swing.SwingWidgetLayer");
            Method install = layer.getMethod("install", JDesktopPane.class);
            install.invoke(null, desktop);
        } catch (ClassNotFoundException cnfe) {
            logger.log(Level.FINE,
                    "No widget layer on the classpath; the 2D desktop runs without widgets");
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Could not install the 2D widget layer", t);
        }
    }

    /** Detaches the 2D widget layer, if it was installed. Never throws. */
    private void uninstallWidgetLayer() {
        try {
            Class<?> layer = Class.forName(
                    "org.jdesktop.lg3d.widgets.swing.SwingWidgetLayer");
            layer.getMethod("uninstall").invoke(null);
        } catch (Throwable t) {
            logger.log(Level.FINE, "Could not uninstall the 2D widget layer", t);
        }
    }

    // ------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------

    /** The start menu, built once from the application descriptors. */
    public synchronized JPopupMenu getStartMenu() {
        if (startMenu == null) {
            startMenu = new StartMenuSearch(menuModel,
                    new Desktop2DStartMenu.Launcher() {
                        @Override
                        public void launch(ItemSpec item) {
                            openApp(item);
                        }
                    }).menu();
        }
        return startMenu;
    }

    /** The Documents folder menu. */
    public synchronized JPopupMenu getDocumentsMenu() {
        if (documentsMenu == null) {
            documentsMenu = folderMenu(userFolder("Documents"));
        }
        return documentsMenu;
    }

    /** The Downloads folder menu. */
    public synchronized JPopupMenu getDownloadsMenu() {
        if (downloadsMenu == null) {
            downloadsMenu = folderMenu(userFolder("Downloads"));
        }
        return downloadsMenu;
    }

    private JPopupMenu folderMenu(Path directory) {
        return Desktop2DFolderMenu.create(directory,
                path -> openWithSystem(path),
                path -> openFileManager(path));
    }

    private static Path userFolder(String name) {
        return Paths.get(System.getProperty("user.home"), name);
    }

    // ------------------------------------------------------------------
    // Alt+F2 run dialog
    // ------------------------------------------------------------------

    /**
     * Opens the Alt+F2 run dialog (building it on first use), centred over the
     * desktop. A resolved entry launches a start-menu application or runs an
     * external command; the dialog records it in the persisted history.
     */
    void showRunDialog() {
        if (runDialog == null) {
            runDialog = new RunDialog(menuModel, runHistory, runHistoryStore,
                    new RunDialog.Runner() {
                        @Override
                        public void run(RunResolver.Decision decision) {
                            launchRun(decision);
                        }
                    });
        }
        runDialog.show(frame.getContentPane());
    }

    /** Carries out a resolved run-dialog entry via the normal launch path. */
    private void launchRun(RunResolver.Decision decision) {
        if (decision == null || decision.isNotFound()) {
            return;
        }
        if (decision.isApp()) {
            openApp(decision.item());
        } else if (decision.isCommand()) {
            // Synthesise an ItemSpec so a raw command reuses openApp's external
            // launch path, including its success toast and failure dialog.
            openApp(new ItemSpec(decision.command(), decision.command(),
                    null, null, null));
        }
    }

    // ------------------------------------------------------------------
    // Desktop background context menu
    // ------------------------------------------------------------------

    /**
     * Shows the desktop background context menu at the pointer, but only for a
     * genuine popup trigger. The menu is rebuilt each time so its window
     * arrangement entries reflect the windows currently open.
     */
    private void showDesktopContextMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        JPopupMenu menu = Desktop2DContextMenu.build(
                new ContextMenuActions(), enumerateWallpapers());
        menu.show(desktop, e.getX(), e.getY());
    }

    /** Wires the context-menu entries to this desktop's operations. */
    private final class ContextMenuActions implements Desktop2DContextMenu.Actions {
        @Override
        public boolean isTerminalAvailable() {
            return terminalCommand() != null;
        }

        @Override
        public void openTerminal() {
            String command = terminalCommand();
            if (command != null) {
                Desktop2DAppRegistry.launchExternal(command);
            }
        }

        @Override
        public void openFileManager() {
            Desktop2D.this.openFileManager(userFolder(""));
        }

        @Override
        public void changeWallpaper(URL url) {
            setWallpaper(url);
        }

        @Override
        public void openDesktopSettings() {
            openApp(new ItemSpec("Control Center",
                    "java org.jdesktop.lg3d.apps.controlcenter.ControlCenter",
                    "Configure the desktop", null, null));
        }

        @Override
        public boolean isDoNotDisturbActive() {
            return dnd.active(System.currentTimeMillis());
        }

        @Override
        public void toggleDoNotDisturb() {
            dnd.toggle(System.currentTimeMillis());
        }

        @Override
        public void cascadeWindows() {
            cascade();
        }

        @Override
        public void tileWindows() {
            tile();
        }

        @Override
        public void minimizeAllWindows() {
            setAllIcons(true);
        }

        @Override
        public void restoreAllWindows() {
            setAllIcons(false);
        }

        @Override
        public void refresh() {
            desktop.repaint();
        }

        @Override
        public void exit() {
            confirmExit();
        }

        @Override
        public int windowCount() {
            return desktop.getAllFrames().length;
        }
    }

    /** The first installed terminal executable, or null if none is present. */
    private static String terminalCommand() {
        for (String terminal : TERMINALS) {
            if (Desktop2DAppRegistry.isExternalAvailable(terminal)) {
                return terminal;
            }
        }
        return null;
    }

    /** Staggers every window from the top-left, each offset by a fixed step. */
    private void cascade() {
        JInternalFrame[] frames = desktop.getAllFrames();
        int step = 28;
        int width = Math.max(240, desktop.getWidth() - step * frames.length);
        int height = Math.max(180, desktop.getHeight() - step * frames.length);
        int x = 0;
        int y = 0;
        for (JInternalFrame frame : frames) {
            uniconify(frame);
            frame.setBounds(x, y, width, height);
            frame.toFront();
            x += step;
            y += step;
        }
    }

    /** Lays every window out in a grid filling the desktop pane. */
    private void tile() {
        JInternalFrame[] frames = desktop.getAllFrames();
        int count = frames.length;
        if (count == 0) {
            return;
        }
        int cols = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil((double) count / cols);
        int width = Math.max(1, desktop.getWidth() / cols);
        int height = Math.max(1, desktop.getHeight() / rows);
        for (int i = 0; i < count; i++) {
            JInternalFrame frame = frames[i];
            uniconify(frame);
            frame.setBounds((i % cols) * width, (i / cols) * height, width, height);
            frame.toFront();
        }
    }

    /** Minimises (true) or restores (false) every iconifiable window. */
    private void setAllIcons(boolean icon) {
        for (JInternalFrame frame : desktop.getAllFrames()) {
            if (frame.isIconifiable()) {
                try {
                    frame.setIcon(icon);
                } catch (PropertyVetoException pve) {
                    logger.log(Level.FINE, "Could not change window state", pve);
                }
            }
        }
    }

    /** Restores a minimised window so cascade/tile can position it. */
    private void uniconify(JInternalFrame frame) {
        try {
            if (frame.isIcon()) {
                frame.setIcon(false);
            }
        } catch (PropertyVetoException pve) {
            logger.log(Level.FINE, "Could not restore window", pve);
        }
        frame.setVisible(true);
    }

    // ------------------------------------------------------------------
    // Global keyboard shortcuts
    // ------------------------------------------------------------------

    /**
     * Installs a {@link KeyEventDispatcher} that resolves the desktop's global
     * shortcuts while this frame has the focus. Added to the current
     * {@link KeyboardFocusManager}; removed again in {@link #exit()}.
     */
    private void installShortcuts() {
        shortcutDispatcher = new KeyEventDispatcher() {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                return handleShortcutKey(e);
            }
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(shortcutDispatcher);
    }

    /** Detaches the dispatcher, so the focus manager no longer holds it. */
    private void uninstallShortcuts() {
        if (shortcutDispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removeKeyEventDispatcher(shortcutDispatcher);
            shortcutDispatcher = null;
        }
    }

    /**
     * Handles one key event: only while this desktop's own frame has the focus,
     * a key-press is resolved through {@link #shortcuts} and, when bound,
     * dispatched and consumed. The Alt+` window switcher is deliberately not in
     * the map, so it returns false here and falls through to Swing untouched.
     */
    private boolean handleShortcutKey(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        Component source = e.getComponent();
        if (source == null || SwingUtilities.getWindowAncestor(source) != frame) {
            return false;
        }
        return Shortcuts.dispatch(
                KeyStroke.getKeyStrokeForEvent(e), shortcuts, shortcutActions);
    }

    /** Snaps the selected window to {@code zone} via the snapping manager. */
    private void snapSelected(WindowSnap.Zone zone) {
        JInternalFrame selected = desktop.getSelectedFrame();
        DesktopManager manager = desktop.getDesktopManager();
        if (selected instanceof JComponent
                && manager instanceof SnappingDesktopManager) {
            ((SnappingDesktopManager) manager).applyPendingSnap(selected, zone);
        }
    }

    /** Closes the selected window, if there is one and it is closable. */
    private void closeSelected() {
        JInternalFrame selected = desktop.getSelectedFrame();
        if (selected != null && selected.isClosable()) {
            selected.doDefaultCloseAction();
        }
    }

    /** Maps the shortcut action ids onto this desktop's window operations. */
    private final class ShortcutActions implements Shortcuts.Target {
        @Override
        public void showDesktop() {
            setAllIcons(true);
        }

        @Override
        public void snapLeft() {
            snapSelected(WindowSnap.Zone.LEFT);
        }

        @Override
        public void snapRight() {
            snapSelected(WindowSnap.Zone.RIGHT);
        }

        @Override
        public void snapMaximize() {
            snapSelected(WindowSnap.Zone.MAXIMIZE);
        }

        @Override
        public void runDialog() {
            showRunDialog();
        }

        @Override
        public void openTerminal() {
            String command = terminalCommand();
            if (command != null) {
                Desktop2DAppRegistry.launchExternal(command);
            }
        }

        @Override
        public void closeWindow() {
            closeSelected();
        }

        @Override
        public void workspaceNext() {
            switchToWorkspace(workspaces.next());
        }

        @Override
        public void workspacePrevious() {
            switchToWorkspace(workspaces.previous());
        }

        @Override
        public void moveWindowToWorkspace(int index) {
            JInternalFrame selected = desktop.getSelectedFrame();
            if (selected instanceof Desktop2DWindow) {
                Desktop2DWindow window = (Desktop2DWindow) selected;
                workspaces.assign(window.getAppName(), index);
                applyWorkspaceVisibility();
                taskbar.refreshWorkspaces();
                saveSession();
            }
        }
    }

    /**
     * The bundled wallpapers offered under "Change Wallpaper": the images in
     * {@link #BG_DIR} when that directory can be listed, else the
     * {@link #WALLPAPER_FALLBACK} names. Any name that does not resolve on the
     * classpath is skipped, so the submenu only lists usable backdrops.
     */
    List<Desktop2DContextMenu.Wallpaper> enumerateWallpapers() {
        List<String> names = new ArrayList<>();
        URL dirUrl = Desktop2D.class.getClassLoader().getResource(BG_DIR);
        if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
            try {
                File[] files = new File(dirUrl.toURI()).listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile() && isImage(file.getName())) {
                            names.add(file.getName());
                        }
                    }
                }
            } catch (Exception e) {
                logger.log(Level.FINE, "Could not list the wallpaper directory", e);
                names.clear();
            }
        }
        Collections.sort(names);
        if (names.isEmpty()) {
            names.addAll(WALLPAPER_FALLBACK);
        }
        List<Desktop2DContextMenu.Wallpaper> wallpapers = new ArrayList<>();
        ClassLoader cl = Desktop2D.class.getClassLoader();
        for (String name : names) {
            URL url = cl.getResource(BG_DIR + "/" + name);
            if (url != null) {
                wallpapers.add(new Desktop2DContextMenu.Wallpaper(displayName(name), url));
            }
        }
        return wallpapers;
    }

    /** True for a case-insensitive .jpg/.jpeg/.png filename. */
    static boolean isImage(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    /** The filename without its extension, for a tidier menu label. */
    static String displayName(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0) ? filename.substring(0, dot) : filename;
    }

    /**
     * The ordered image locations the wallpaper slideshow cycles: the images in
     * the configured folder when it names a readable directory holding at least
     * one image, else the bundled wallpapers the "Change Wallpaper" submenu
     * offers. Never null.
     */
    private List<URL> slideshowImages() {
        String folder = DesktopConfig.get().getSlideshowFolder();
        if (folder != null && !folder.isBlank()) {
            List<URL> scanned = scanFolder(new File(folder));
            if (!scanned.isEmpty()) {
                return scanned;
            }
        }
        List<URL> urls = new ArrayList<>();
        for (Desktop2DContextMenu.Wallpaper wallpaper : enumerateWallpapers()) {
            urls.add(wallpaper.url());
        }
        return urls;
    }

    /**
     * The image files directly inside {@code dir}, as URLs sorted by name.
     * Non-image files are skipped and a null, absent or unreadable directory
     * yields an empty list. Package-visible static so the scan is unit-testable
     * against a temporary directory without a live shell.
     */
    static List<URL> scanFolder(File dir) {
        List<URL> urls = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) {
            return urls;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return urls;
        }
        List<File> images = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && isImage(file.getName())) {
                images.add(file);
            }
        }
        images.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (File file : images) {
            try {
                urls.add(file.toURI().toURL());
            } catch (Exception e) {
                logger.log(Level.FINE, "Skipping unreadable wallpaper {0}",
                        file.getName());
            }
        }
        return urls;
    }

    // ------------------------------------------------------------------
    // Launching applications
    // ------------------------------------------------------------------

    /**
     * Runs a start-menu entry: hosts its panel in an internal frame, starts a
     * conventional Swing app, or runs an external command.
     */
    public void openApp(ItemSpec item) {
        if (item == null) {
            return;
        }
        Desktop2DAppRegistry.Kind kind =
                Desktop2DAppRegistry.classify(item.getCommand());
        switch (kind) {
            case PANEL:
                openPanelApp(item, null);
                break;
            case SWING_FRAME:
                logger.log(Level.INFO, "Launching Swing app {0}", item.getName());
                Desktop2DAppRegistry.launchSwingFrame(item.getCommand());
                // A Swing frame opens beside the desktop with no taskbar button,
                // so surface it as a notification the user cannot miss.
                raiseNotification("Launched " + item.getName(),
                        "Running in its own window, outside the desktop",
                        Notification.Kind.INFO);
                break;
            case EXTERNAL:
                if (Desktop2DAppRegistry.launchExternal(item.getCommand())) {
                    // An external process likewise has no taskbar presence.
                    raiseNotification("Launched " + item.getName(), null,
                            Notification.Kind.INFO);
                } else {
                    showMessage("Could not start \"" + item.getName() + "\"",
                            "The command could not be executed:\n"
                            + item.getCommand());
                }
                break;
            case UNAVAILABLE:
            default:
                showMessage(item.getName() + " needs the 3D desktop",
                        Desktop2DAppRegistry.UNAVAILABLE_TOOLTIP
                        + ", which is not available on this machine.");
                break;
        }
    }

    /** Opens the file manager internal frame at {@code directory}. */
    public void openFileManager(Path directory) {
        ItemSpec item = new ItemSpec("File Manager",
                "java org.jdesktop.lg3d.apps.filemanager.FileManager",
                "Browse files and folders", null, null);
        openPanelApp(item, directory);
    }

    /**
     * Opens (or brings forward) the Agenda app navigated to the week containing
     * {@code date}. Invoked when the user double-clicks a day in the taskbar
     * calendar popup; the panel is navigated reflectively through
     * {@link Desktop2DAppRegistry#showDate}, so lg3d-core keeps no compile-time
     * dependency on the Agenda panel in lg3d-incubator.
     */
    public void openAgendaAt(LocalDate date) {
        if (date == null) {
            return;
        }
        ItemSpec item = new ItemSpec("Agenda 3D", AGENDA_COMMAND,
                "3D week agenda", null, "resources/images/icon/agenda3d.png");
        Desktop2DWindow window = openPanelApp(item, null, false);
        if (window != null && window.getContentPane().getComponentCount() > 0) {
            JComponent panel =
                    (JComponent) window.getContentPane().getComponent(0);
            Desktop2DAppRegistry.showDate(panel, date);
        }
    }

    /**
     * Hosts a panel application in an internal frame. An already-open window
     * for the same application is brought forward instead of duplicated, the
     * way the 3D desktop's app containers behave.
     */
    private void openPanelApp(ItemSpec item, Path initialDir) {
        openPanelApp(item, initialDir, false);
    }

    /**
     * Hosts a panel application and returns its window (or the already-open one
     * brought forward, or null if it could not be built). When {@code quiet} is
     * true a launch failure is only logged, never shown in a modal dialog, so
     * restoring a session cannot greet the user with a popup for an app that has
     * since become unavailable.
     */
    private Desktop2DWindow openPanelApp(ItemSpec item, Path initialDir,
                                         boolean quiet) {
        String appName = (item.getName() == null || item.getName().isBlank())
                ? Desktop2DAppRegistry.mainClass(item.getCommand())
                : item.getName();
        Desktop2DWindow existing = findWindow(appName);
        if (existing != null) {
            activateWindow(existing);
            return existing;
        }
        try {
            JComponent panel =
                    Desktop2DAppRegistry.createPanel(item.getCommand(), initialDir);
            Icon icon = AppIcons.iconFor(
                    appName, item.getIconResource(), Desktop2DStartMenu.ICON_SIZE);
            Desktop2DWindow window = new Desktop2DWindow(appName, icon, panel,
                    appName, item.getCommand(), item.getIconResource());
            track(window);
            desktop.add(window);
            // A new window opens on the workspace currently shown.
            workspaces.assign(window.getAppName(), workspaces.current());
            taskbar.windowOpened(window);
            window.showIn(desktop);
            desktop.revalidate();
            desktop.repaint();
            saveSession();
            return window;
        } catch (Throwable t) {
            // NoClassDefFoundError included: on a 3D-less JVM an app may still
            // drag in a Java 3D class through a shared helper.
            logger.log(Level.WARNING,
                    "Could not start " + appName + " in the 2D desktop", t);
            if (!quiet) {
                showMessage("Could not start " + appName,
                        "The application could not run in 2D mode:\n" + t);
            }
            return null;
        }
    }

    /** Registers the taskbar bookkeeping for {@code window}. */
    private void track(final Desktop2DWindow window) {
        windowSwitcher.cycler().touch(window);
        window.addInternalFrameListener(new InternalFrameAdapter() {
            @Override
            public void internalFrameClosed(InternalFrameEvent e) {
                windowSwitcher.cycler().forget(window);
                workspaces.unassign(window.getAppName());
                taskbar.windowClosed(window);
                saveSession();
            }

            @Override
            public void internalFrameActivated(InternalFrameEvent e) {
                windowSwitcher.cycler().touch(window);
                taskbar.windowSelected(window);
            }

            @Override
            public void internalFrameDeactivated(InternalFrameEvent e) {
                taskbar.windowSelected(null);
            }
        });
    }

    private Desktop2DWindow findWindow(String appName) {
        for (javax.swing.JInternalFrame candidate : desktop.getAllFrames()) {
            if (candidate instanceof Desktop2DWindow
                    && ((Desktop2DWindow) candidate).getAppName().equals(appName)) {
                return (Desktop2DWindow) candidate;
            }
        }
        return null;
    }

    /** Brings {@code window} forward, or minimises it if it is already front. */
    public void activateWindow(Desktop2DWindow window) {
        if (window == null) {
            return;
        }
        try {
            if (window.isIcon()) {
                window.setIcon(false);
            } else if (window.isSelected()) {
                // Already the front window: the button minimises it instead.
                window.setIcon(true);
                return;
            }
            window.setVisible(true);
            window.toFront();
            window.setSelected(true);
        } catch (java.beans.PropertyVetoException pve) {
            logger.log(Level.FINE, "Could not activate " + window.getAppName(), pve);
        }
    }

    /**
     * Brings {@code window} forward and gives it the focus <em>without</em> the
     * minimise-on-second-click toggle {@link #activateWindow} applies. This is
     * what the window switcher commits to: selecting a window from the switcher
     * must always raise it, never hide it.
     */
    void focusWindow(Desktop2DWindow window) {
        if (window == null) {
            return;
        }
        try {
            if (window.isIcon()) {
                window.setIcon(false);
            }
            window.setVisible(true);
            window.toFront();
            window.setSelected(true);
        } catch (java.beans.PropertyVetoException pve) {
            logger.log(Level.FINE, "Could not focus " + window.getAppName(), pve);
        }
    }

    // ------------------------------------------------------------------
    // Session persistence (which apps are open, and where)
    // ------------------------------------------------------------------

    /**
     * Persists the current set of open application windows and their placement.
     * A no-op while a session is being restored, so relaunching the saved
     * windows does not repeatedly overwrite the session with a partial one.
     */
    private void saveSession() {
        if (restoring) {
            return;
        }
        sessionManager.save(openPanelWindows());
    }

    /** The application windows currently on the desktop, front-most first. */
    private List<Desktop2DWindow> openPanelWindows() {
        List<Desktop2DWindow> windows = new ArrayList<>();
        for (JInternalFrame candidate : desktop.getAllFrames()) {
            if (candidate instanceof Desktop2DWindow) {
                windows.add((Desktop2DWindow) candidate);
            }
        }
        return windows;
    }

    /**
     * Relaunches the applications open when the desktop last exited and puts
     * their windows back where they were. Runs on the EDT from {@link #show()}.
     * A saved session that references an app which can no longer run is skipped
     * quietly rather than blocking startup with an error dialog.
     */
    private void restoreSession() {
        SessionSnapshot snapshot = sessionManager.load();
        if (snapshot.isEmpty()) {
            return;
        }
        restoring = true;
        int restored = 0;
        try {
            List<WindowRecord> records = snapshot.windows();
            // The snapshot is front-most first (getAllFrames order), so relaunch
            // back-to-front: each window is raised as it opens, and the last one
            // opened is the originally front-most, ending up on top.
            for (int i = records.size() - 1; i >= 0; i--) {
                WindowRecord record = records.get(i);
                ItemSpec item = new ItemSpec(record.appName(), record.command(),
                        null, null, record.iconResource());
                Desktop2DWindow window = openPanelApp(item, null, true);
                if (window != null) {
                    applyRecordedState(window, record);
                    restored++;
                }
            }
        } finally {
            restoring = false;
        }
        saveSession();
        logger.log(Level.INFO,
                "Restored {0} window(s) from the last 2D desktop session",
                Integer.valueOf(restored));
    }

    /**
     * Puts a freshly relaunched window back to its saved size, position and
     * minimised/maximised state. The placement is clamped into the current
     * desktop so a session saved on a larger screen cannot strand a window
     * off-screen.
     */
    private void applyRecordedState(Desktop2DWindow window, WindowRecord record) {
        try {
            if (record.maximized()) {
                window.setMaximum(true);
            } else {
                window.setBounds(record.restoredBounds(
                        new Rectangle(desktop.getSize())));
            }
            if (record.iconified()) {
                window.setIcon(true);
            }
        } catch (PropertyVetoException pve) {
            logger.log(Level.FINE,
                    "Could not restore the state of " + record.appName(), pve);
        }
    }

    /** Opens a file with the user's preferred application (xdg-open). */
    private void openWithSystem(Path path) {
        if (path == null) {
            return;
        }
        if (java.nio.file.Files.isDirectory(path)) {
            openFileManager(path);
            return;
        }
        if (!Opener.open(path)) {
            showMessage("Could not open " + path.getFileName(),
                    "No application is registered for this file type"
                    + " (xdg-open is unavailable or failed).");
        }
    }

    private void showMessage(String title, String message) {
        JOptionPane.showMessageDialog(frame, message, title,
                JOptionPane.WARNING_MESSAGE);
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    /**
     * Raises a desktop notification: adds it to the log the taskbar tray lists
     * and pops it up as a transient toast. Must run on the EDT; the static
     * {@link #postNotification} marshals here from any thread.
     */
    void raiseNotification(String title, String message,
                           Notification.Kind kind) {
        Notification notification = notifications.add(title, message, kind);
        // The log always records it (so the tray/history stay complete); only
        // the transient toast is gated by Do Not Disturb.
        if (!dnd.shouldSuppress(kind, System.currentTimeMillis())) {
            toastLayer.show(notification);
        }
    }

    /** Builds the DND state from the persisted desktop config. */
    private static DoNotDisturb restoreDoNotDisturb() {
        DesktopConfig cfg = DesktopConfig.get();
        return new DoNotDisturb(
                cfg.isDoNotDisturbEnabled(), cfg.getDoNotDisturbUntil());
    }

    /** Writes the current DND state back to the persisted desktop config. */
    private void persistDoNotDisturb() {
        DesktopConfig cfg = DesktopConfig.get();
        cfg.setDoNotDisturbEnabled(dnd.isEnabled());
        cfg.setDoNotDisturbUntil(dnd.untilMillis());
        cfg.save();
    }

    /**
     * Raises a notification on the running 2D desktop, the notification
     * counterpart of {@link #applyDesktopConfig()}. A no-op when the 2D desktop
     * is not running. Safe to call from any thread; the work is done on the EDT.
     */
    static void postNotification(final String title, final String message,
                                 final Notification.Kind kind) {
        final Desktop2D d = instance;
        if (d == null) {
            return;
        }
        Runnable post = new Runnable() {
            @Override
            public void run() {
                d.raiseNotification(title, message, kind);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            post.run();
        } else {
            SwingUtilities.invokeLater(post);
        }
    }

    // ------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------

    /** Asks for confirmation, then leaves the desktop (and the JVM). */
    public void confirmExit() {
        int answer = JOptionPane.showConfirmDialog(frame,
                "Leave the 2D desktop?\nOpen applications will be closed.",
                "Exit Project Looking Glass",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            exit();
        }
    }

    /** Stops the taskbar clock, disposes the window and exits the JVM. */
    public void exit() {
        logger.info("Shutting down the 2D desktop");
        instance = null;
        // Capture the final placement of every open window while the frames are
        // still realized, so the next start reopens them where they were left.
        saveSession();
        windowSwitcher.uninstall();
        uninstallShortcuts();
        toastLayer.uninstall();
        uninstallWidgetLayer();
        if (runDialog != null) {
            runDialog.hide();
        }
        stopSlideshowTimer();
        taskbar.stop();
        frame.setVisible(false);
        frame.dispose();
        System.exit(0);
    }

    // ------------------------------------------------------------------
    // Live configuration (driven by the control center's Desktop panel)
    // ------------------------------------------------------------------

    /**
     * Re-applies {@link DesktopConfig} to the running 2D desktop: the Swing UI
     * font defaults, the taskbar docking edge (top/bottom) and the taskbar's own
     * geometry (thickness, icon scale, font, auto-hide). A no-op when the 2D
     * desktop is not running. Safe to call from any thread; the work is done on
     * the EDT. This is the 2D counterpart of the {@code DesktopConfigChangeEvent}
     * the 3D taskbar listens for.
     */
    public static void applyDesktopConfig() {
        final Desktop2D d = instance;
        if (d == null) {
            return;
        }
        Runnable apply = new Runnable() {
            @Override
            public void run() {
                d.reapplyConfig();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            apply.run();
        } else {
            SwingUtilities.invokeLater(apply);
        }
    }

    /**
     * Sets the desktop backdrop to the image at {@code url}, the 2D counterpart
     * of the {@code BackgroundChangeRequestEvent} the 3D scene manager listens
     * for. A no-op when the 2D desktop is not running or {@code url} is null.
     * The image loads asynchronously; the pane repaints itself as the observer
     * once the pixels arrive. Safe to call from any thread.
     */
    public static void setWallpaper(final URL url) {
        final Desktop2D d = instance;
        if (d == null || url == null) {
            return;
        }
        Runnable set = new Runnable() {
            @Override
            public void run() {
                d.desktop.setImage(
                        java.awt.Toolkit.getDefaultToolkit().createImage(url));
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            set.run();
        } else {
            SwingUtilities.invokeLater(set);
        }
    }

    /**
     * (Re)builds the slideshow model from the persisted folder and starts or
     * stops the timer to match the persisted enable flag and interval. When
     * enabled with at least one image, the first is shown immediately and the
     * rest follow on the interval. Must run on the EDT.
     */
    private void applySlideshowConfig() {
        DesktopConfig cfg = DesktopConfig.get();
        // The folder may have changed since the model was built, so rescan.
        slideshow.setImages(slideshowImages());
        stopSlideshowTimer();
        if (!cfg.isSlideshowEnabled() || slideshow.isEmpty()) {
            return;
        }
        setWallpaper(slideshow.current());
        slideshowTimer = new Timer(cfg.getSlideshowIntervalSec() * 1000,
                e -> advanceSlideshow());
        slideshowTimer.start();
    }

    /** Advances the model one step and applies the image it lands on. EDT. */
    private void advanceSlideshow() {
        if (slideshow != null) {
            setWallpaper(slideshow.next());
        }
    }

    /** Stops and drops the slideshow timer, if it is running. */
    private void stopSlideshowTimer() {
        if (slideshowTimer != null) {
            slideshowTimer.stop();
            slideshowTimer = null;
        }
    }

    /**
     * Turns the wallpaper slideshow on or off on the running 2D desktop and
     * persists the choice. Safe from any thread; when no shell is running the
     * value is still written to {@link DesktopConfig} so the next start honours
     * it. Called by the control center's Appearance panel.
     */
    public static void setSlideshowEnabled(final boolean enabled) {
        DesktopConfig cfg = DesktopConfig.get();
        cfg.setSlideshowEnabled(enabled);
        cfg.save();
        final Desktop2D d = instance;
        if (d != null) {
            onEdt(d::applySlideshowConfig);
        }
    }

    /**
     * Sets and persists the slideshow interval in seconds (clamped by
     * {@link DesktopConfig}), restarting the running slideshow's timer. Safe
     * from any thread; a no-op on the live shell when none is running.
     */
    public static void setSlideshowIntervalSec(final int seconds) {
        DesktopConfig cfg = DesktopConfig.get();
        cfg.setSlideshowIntervalSec(seconds);
        cfg.save();
        final Desktop2D d = instance;
        if (d != null) {
            onEdt(d::applySlideshowConfig);
        }
    }

    /**
     * Sets and persists the slideshow source folder (a directory path, or blank
     * for the bundled wallpapers), rescanning the running slideshow. Safe from
     * any thread; a no-op on the live shell when none is running.
     */
    public static void setSlideshowFolder(final String folder) {
        DesktopConfig cfg = DesktopConfig.get();
        cfg.setSlideshowFolder(folder);
        cfg.save();
        final Desktop2D d = instance;
        if (d != null) {
            onEdt(d::applySlideshowConfig);
        }
    }

    /** Runs {@code task} on the EDT, immediately if already there. */
    private static void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * Re-applies the persisted configuration to the shell. Must run on the EDT.
     */
    private void reapplyConfig() {
        DesktopConfig cfg = DesktopConfig.get();
        applyFontDefaults(cfg);
        // Re-skin the shell with the persisted Metal theme, if the user chose
        // one in the control center. A no-op while none is selected, so the
        // native platform look is kept by default.
        MetalThemeManager.applyStored();
        Container content = frame.getContentPane();
        content.remove(taskbar);
        content.add(taskbar, cfg.getPosition() == DesktopConfig.Position.TOP
                ? BorderLayout.NORTH : BorderLayout.SOUTH);
        taskbar.applyConfig();
        content.revalidate();
        content.repaint();
    }

    /**
     * Pushes the configured Swing UI font (family + size) into the
     * {@link UIManager} defaults so panels built afterwards use it, mirroring
     * {@code TitledSwingWindow.applySwingFontDefaults} on the 3D desktop. While
     * the config still holds the built-in default the active look-and-feel's own
     * fonts are restored, so "reset to defaults" returns to the native look.
     */
    private static void applyFontDefaults(DesktopConfig cfg) {
        boolean isDefault =
                DesktopConfig.DEFAULT_FONT_NAME.equals(cfg.getFontName())
                && cfg.getFontSize() == DesktopConfig.DEFAULT_FONT_SIZE;
        if (isDefault) {
            LookAndFeel laf = UIManager.getLookAndFeel();
            UIDefaults defs = (laf == null) ? null : laf.getDefaults();
            if (defs != null) {
                for (String key : FONT_KEYS) {
                    Object v = defs.get(key);
                    if (v != null) {
                        UIManager.put(key, v);
                    }
                }
            }
            return;
        }
        FontUIResource font = new FontUIResource(
                new Font(cfg.getFontName(), Font.PLAIN, cfg.getFontSize()));
        for (String key : FONT_KEYS) {
            UIManager.put(key, font);
        }
    }

    // ------------------------------------------------------------------
    // Backdrop
    // ------------------------------------------------------------------

    /** The first bundled wallpaper found on the classpath, or null. */
    static Image wallpaper() {
        ClassLoader cl = Desktop2D.class.getClassLoader();
        for (String resource : WALLPAPERS) {
            URL url = cl.getResource(resource);
            if (url == null) {
                continue;
            }
            Image image = java.awt.Toolkit.getDefaultToolkit().createImage(url);
            if (image != null) {
                logger.log(Level.FINE, "2D wallpaper: {0}", resource);
                return image;
            }
        }
        logger.info("No bundled wallpaper found; using a plain backdrop");
        return null;
    }

    /**
     * A desktop pane that paints the wallpaper behind the MDI windows.
     * Feeds the window switcher the live set of application windows and raises
     * the one the user commits to. Enumerates the MDI pane front-most first, so
     * the switcher's MRU snapshot lines up with what is on screen.
     */
    private final class SwitcherWindowSource
            implements WindowCyclerOverlay.WindowSource {
        @Override
        public List<Desktop2DWindow> presentWindows() {
            List<Desktop2DWindow> present = new ArrayList<>();
            for (JInternalFrame candidate : desktop.getAllFrames()) {
                // The switcher lists only the current workspace's windows, so
                // Alt+` cycles within a workspace rather than across all of them.
                if (candidate instanceof Desktop2DWindow
                        && workspaces.isOnCurrent(
                                ((Desktop2DWindow) candidate).getAppName())) {
                    present.add((Desktop2DWindow) candidate);
                }
            }
            return present;
        }

        @Override
        public void focus(Desktop2DWindow window) {
            focusWindow(window);
        }
    }

    /**
     * Keeps a minimised window's icon in exactly one place. Stock MDI drops a
     * desktop icon onto the pane when a frame is iconified; the taskbar button
     * already represents the minimised window, so the desktop icon is hidden.
     * Clicking the taskbar button restores the window via
     * {@link #activateWindow(Desktop2DWindow)}.

     */
    private static final class WallpaperDesktopPane extends JDesktopPane {
        private Image image;

        WallpaperDesktopPane(Image image) {
            this.image = image;
            setOpaque(true);
        }

        /**
         * Pins the plain basic desktop UI. The Synth (GTK) desktop UI installs
         * its own taskbar strip along the bottom of the pane that re-lists
         * minimised windows; on top of the shell's taskbar that shows the
         * minimised icon twice and reads as a second row. The basic UI keeps
         * the MDI behaviour without that strip.
         */
        @Override
        public void updateUI() {
            setUI(new BasicDesktopPaneUI());
        }

        /** Swaps the backdrop image; repaints as the new pixels arrive. */
        void setImage(Image image) {
            this.image = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (image == null) {
                super.paintComponent(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(image, 0, 0, getWidth(), getHeight(), this);
            } finally {
                g2.dispose();
            }
        }
    }
}
