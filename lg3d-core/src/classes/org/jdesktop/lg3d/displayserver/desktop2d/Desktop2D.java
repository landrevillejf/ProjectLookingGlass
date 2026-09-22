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
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
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

    private final JFrame frame;
    private final JDesktopPane desktop;
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
     *
     * <p>This is the one window-hosting step the {@link DesktopSwing} flavour
     * replaces (it opens a top-level {@code JFrame} instead), so it is
     * {@code protected}; everything else - menu building, the taskbar, external
     * and Swing-app launches, exit - is shared unchanged.</p>
     */
    protected void openPanelApp(ItemSpec item, Path initialDir) {
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

    protected void showMessage(String title, String message) {
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
        taskbar.stop();
        frame.setVisible(false);
        frame.dispose();
        System.exit(0);
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
        private final Image image;

        WallpaperDesktopPane(Image image) {
            this.image = image;
            setOpaque(true);
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
