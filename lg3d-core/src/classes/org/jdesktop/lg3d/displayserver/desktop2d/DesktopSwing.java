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

import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;

/**
 * The conventional Swing desktop: the same shell, menus, taskbar and
 * application registry as {@link Desktop2D}, but every application opens in its
 * own <em>top-level {@link JFrame}</em> decorated and managed by the host window
 * manager, and the whole desktop wears the Metal look and feel.
 *
 * <p>This is the "just use Swing" flavour the {@code --swing} launcher option
 * selects ({@code lg.fws.mode=swing}). Where {@link Desktop2D} ({@code --2d})
 * hosts panel applications as {@code JInternalFrame}s inside the desktop's
 * {@code JDesktopPane} - a self-contained MDI window manager - this flavour
 * leaves window management to the host: a real {@code JFrame} gets the native
 * title bar, minimise/maximise/close, taskbar entry and Alt-Tab for free, so
 * none of that is reimplemented here.</p>
 *
 * <p>Everything that is not window chrome is inherited unchanged: the desktop
 * background is still a {@code JDesktopPane} painted with the lg3d wallpaper,
 * the start menu / Documents / Downloads menus come from the same
 * {@code .lgcfg} descriptors, external commands still run as child processes,
 * conventional Swing apps that insist on their own {@code JFrame} still launch
 * beside the desktop, and pure Java 3D applications are still offered disabled.
 * The only override is {@link #openPanelApp}, which wraps an application's Swing
 * panel in a {@code JFrame} instead of an internal frame.</p>
 *
 * <p>Application frames use {@link JFrame#DISPOSE_ON_CLOSE}, never
 * {@code EXIT_ON_CLOSE}: they share the desktop's JVM, so exiting on close would
 * tear the whole desktop down.</p>
 */
public class DesktopSwing extends Desktop2D {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** Window title, so the host window manager shows something sensible. */
    static final String FRAME_TITLE_SWING = "Project Looking Glass (Swing desktop)";

    /** Cascade offset between consecutively opened application frames. */
    private static final int CASCADE_STEP = 28;

    /** Margin from the desktop's top-left corner for the first frame. */
    private static final int EDGE_MARGIN = 40;

    /**
     * Application frames currently open, keyed by application name, so a second
     * launch of the same application brings its frame forward instead of opening
     * a duplicate - the same de-duplication {@link Desktop2D} does for internal
     * frames. Entries are removed when the frame is closed.
     */
    private final Map<String, JFrame> openFrames = new LinkedHashMap<>();

    /**
     * Builds the Swing desktop shell. Does not show it; call {@link #start()}
     * (or {@link #show()} on the EDT).
     */
    public DesktopSwing() {
        super();
        getFrame().setTitle(FRAME_TITLE_SWING);
        logger.info("Starting the conventional Swing desktop "
                + "(top-level JFrames, Metal look and feel)");
    }

    /**
     * Starts the Swing desktop on the event dispatch thread. This is the entry
     * point {@code Main} uses for {@code lg.fws.mode=swing}.
     */
    public static void start() {
        installMetalLookAndFeel();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new DesktopSwing().show();
            }
        });
    }

    /**
     * Installs the Metal look and feel. The cross-platform look and feel
     * {@code UIManager} reports <em>is</em> Metal: a conventional, pure-Java,
     * non-Synth look that renders identically on every platform (unlike the
     * GTK/Synth system look and feel {@link Desktop2D} uses).
     */
    private static void installMetalLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            logger.log(Level.FINE, "Keeping the default look and feel", e);
        }
    }

    /**
     * Opens an application's Swing panel in its own top-level {@code JFrame},
     * or brings the already-open frame for that application forward.
     */
    @Override
    protected void openPanelApp(ItemSpec item, Path initialDir) {
        if (item == null) {
            return;
        }
        final String appName = (item.getName() == null || item.getName().isBlank())
                ? Desktop2DAppRegistry.mainClass(item.getCommand())
                : item.getName();
        JFrame existing = openFrames.get(appName);
        if (existing != null) {
            existing.setExtendedState(JFrame.NORMAL);
            existing.setVisible(true);
            existing.toFront();
            return;
        }
        try {
            JComponent panel =
                    Desktop2DAppRegistry.createPanel(item.getCommand(), initialDir);
            // A panel with its own "Close" toolbar button disposes its frame.
            Desktop2DAppRegistry.setCloseCallback(panel, new Runnable() {
                @Override
                public void run() {
                    JFrame frame = openFrames.get(appName);
                    if (frame != null) {
                        frame.dispose();
                    }
                }
            });

            JFrame frame = new JFrame(appName);
            Icon icon = Desktop2DStartMenu.icon(item.getIconResource());
            if (icon instanceof ImageIcon) {
                frame.setIconImage(((ImageIcon) icon).getImage());
            }
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().add(panel);
            frame.pack();
            frame.setLocation(cascadeLocation());
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    openFrames.remove(appName);
                }
            });
            openFrames.put(appName, frame);
            frame.setVisible(true);
            frame.toFront();
        } catch (Throwable t) {
            // NoClassDefFoundError included: on a 3D-less JVM an app may still
            // drag in a Java 3D class through a shared helper.
            logger.log(Level.WARNING,
                    "Could not start " + appName + " in the Swing desktop", t);
            showMessage("Could not start " + appName,
                    "The application could not run without 3D:\n" + t);
        }
    }

    /** Offsets each new frame from the ones already open, wrapping at eight. */
    private Point cascadeLocation() {
        int offset = (openFrames.size() % 8) * CASCADE_STEP;
        return new Point(EDGE_MARGIN + offset, EDGE_MARGIN + offset);
    }
}
