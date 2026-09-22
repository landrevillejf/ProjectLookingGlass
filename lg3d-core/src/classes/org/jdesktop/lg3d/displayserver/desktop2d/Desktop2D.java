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
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.JFrame;
import javax.swing.LookAndFeel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;
import javax.swing.plaf.FontUIResource;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
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
    private final WallpaperDesktopPane desktop;
    private final Desktop2DTaskbar taskbar;
    private final Desktop2DMenuConfig.MenuModel menuModel;

    private JPopupMenu startMenu;
    private JPopupMenu documentsMenu;
    private JPopupMenu downloadsMenu;

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

        frame = new JFrame(FRAME_TITLE);
        JPanel content = new JPanel(new BorderLayout());
        content.add(desktop, BorderLayout.CENTER);
        taskbar = new Desktop2DTaskbar(this);
        content.add(taskbar, BorderLayout.SOUTH);
        frame.setContentPane(content);

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
    }

    /** The desktop window (package-visible for diagnostics). */
    JFrame getFrame() {
        return frame;
    }

    /** The MDI pane application windows live in. */
    JDesktopPane getDesktopPane() {
        return desktop;
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
            startMenu = Desktop2DStartMenu.build(menuModel,
                    new Desktop2DStartMenu.Launcher() {
                        @Override
                        public void launch(ItemSpec item) {
                            openApp(item);
                        }
                    });
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
                break;
            case EXTERNAL:
                if (!Desktop2DAppRegistry.launchExternal(item.getCommand())) {
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
     * Hosts a panel application in an internal frame. An already-open window
     * for the same application is brought forward instead of duplicated, the
     * way the 3D desktop's app containers behave.
     */
    private void openPanelApp(ItemSpec item, Path initialDir) {
        String appName = (item.getName() == null || item.getName().isBlank())
                ? Desktop2DAppRegistry.mainClass(item.getCommand())
                : item.getName();
        Desktop2DWindow existing = findWindow(appName);
        if (existing != null) {
            activateWindow(existing);
            return;
        }
        try {
            JComponent panel =
                    Desktop2DAppRegistry.createPanel(item.getCommand(), initialDir);
            Icon icon = Desktop2DStartMenu.icon(item.getIconResource());
            Desktop2DWindow window =
                    new Desktop2DWindow(appName, icon, panel, appName);
            track(window);
            desktop.add(window);
            taskbar.windowOpened(window);
            window.showIn(desktop);
            desktop.revalidate();
            desktop.repaint();
        } catch (Throwable t) {
            // NoClassDefFoundError included: on a 3D-less JVM an app may still
            // drag in a Java 3D class through a shared helper.
            logger.log(Level.WARNING,
                    "Could not start " + appName + " in the 2D desktop", t);
            showMessage("Could not start " + appName,
                    "The application could not run in 2D mode:\n" + t);
        }
    }

    /** Registers the taskbar bookkeeping for {@code window}. */
    private void track(final Desktop2DWindow window) {
        window.addInternalFrameListener(new InternalFrameAdapter() {
            @Override
            public void internalFrameClosed(InternalFrameEvent e) {
                taskbar.windowClosed(window);
            }

            @Override
            public void internalFrameActivated(InternalFrameEvent e) {
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
        uninstallWidgetLayer();
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
     * Re-applies the persisted configuration to the shell. Must run on the EDT.
     */
    private void reapplyConfig() {
        DesktopConfig cfg = DesktopConfig.get();
        applyFontDefaults(cfg);
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

    /** A desktop pane that paints the wallpaper behind the MDI windows. */
    private static final class WallpaperDesktopPane extends JDesktopPane {
        private Image image;

        WallpaperDesktopPane(Image image) {
            this.image = image;
            setOpaque(true);
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
