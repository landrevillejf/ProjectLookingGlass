/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The conventional Swing desktop: the same MDI shell as {@link Desktop2D} - one
 * maximised window whose {@link JDesktopPane} hosts each application in a
 * {@link JInternalFrame} - but wearing the <em>Metal</em> look and feel.
 *
 * <p><strong>Why the windows are {@code JInternalFrame}s, not top-level
 * {@code JFrame}s.</strong> A {@code JFrame} is a native, top-level window owned
 * by the host window manager: it cannot be a child of a {@code JDesktopPane},
 * and minimising it iconifies it into the <em>host</em> taskbar, outside the
 * desktop. The only Swing window that lives <em>inside</em> a
 * {@code JDesktopPane} - and so stays within the desktop, cascades with its
 * siblings, and minimises into an icon on the desktop rather than escaping it -
 * is {@code JInternalFrame}. That is stock Swing (the classic MDI pattern), not
 * custom chrome, so using it is the opposite of reinventing the wheel: it is the
 * component Swing provides for exactly this "windows integrated into a desktop
 * pane" job.</p>
 *
 * <p>Everything is inherited from {@link Desktop2D} unchanged - the wallpaper
 * {@code JDesktopPane}, the internal-frame hosting and de-duplication
 * ({@link Desktop2DWindow}), the start menu, the Documents/Downloads folder
 * menus, the taskbar with its per-window buttons, external-command and
 * conventional-Swing-app launches, and exit handling. This subclass adds only
 * the Metal look and feel and its own window title, so the two flavours cannot
 * drift apart. {@code --2d} keeps the host system look and feel.</p>
 */
public class DesktopSwing extends Desktop2D {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** Window title, so the host window manager shows something sensible. */
    static final String FRAME_TITLE_SWING = "Project Looking Glass (Swing desktop)";

    /**
     * Builds the Swing desktop shell. Does not show it; call {@link #start()}
     * (or {@link #show()} on the EDT).
     */
    public DesktopSwing() {
        super();
        getFrame().setTitle(FRAME_TITLE_SWING);
        logger.info("Starting the conventional Swing desktop (Metal look and feel)");
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
     * GTK/Synth system look and feel {@link Desktop2D} installs for {@code --2d}).
     */
    private static void installMetalLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception e) {
            logger.log(Level.FINE, "Keeping the default look and feel", e);
        }
    }
}
