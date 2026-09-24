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
package org.jdesktop.lg3d.apps.help;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.net.URL;
import javax.help.BadIDException;
import javax.help.HelpSet;
import javax.help.JHelp;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * The Help Center content: a JavaHelp ({@code javax.help}) {@link JHelp} viewer
 * presenting the desktop's user guide (table of contents, keyword index and
 * full-text search over the bundled HTML topics).
 *
 * <p>This is a plain Swing {@link JPanel} with a no-argument constructor, so it
 * is hosted two ways: in the 3D desktop the {@link HelpCenter} wrapper puts it on
 * a {@code SwingNode} inside a {@code Frame3D} via {@code TitledSwingWindow}; in
 * the 2D/Swing desktop {@code Desktop2DAppRegistry} constructs it reflectively
 * and hosts it in an MDI internal frame. It touches no Java 3D, so the 2D path
 * never needs the scene graph.</p>
 *
 * <p>A {@code BorderLayout} is used rather than the {@code null} layout of some
 * sibling panels: {@code JHelp} manages its own internal layout and needs a real
 * size, which {@code SwingNode.setHostedSize} supplies (see
 * {@code docs/swingnode.md}). If the HelpSet cannot be loaded the panel degrades
 * to a readable message instead of throwing, so a broken help bundle can never
 * take down the window that hosts it.</p>
 */
public class HelpCenterPanel extends JPanel {

    /** Panel size in native pixels; the wrapper hands these to TitledSwingWindow. */
    public static final int WIDTH_PX = 960;
    public static final int HEIGHT_PX = 640;

    /** Classpath location of the HelpSet, resolved with the context loader. */
    static final String HELPSET_PATH =
            "org/jdesktop/lg3d/apps/help/helpcontent/lg3d-help.hs";

    /** The map target shown first (declared as the HelpSet homeID). */
    static final String HOME_ID = "overview";

    public HelpCenterPanel() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        add(buildContent(), BorderLayout.CENTER);
    }

    /**
     * Builds the {@link JHelp} viewer for the bundled HelpSet, or an explanatory
     * placeholder if the help content is missing or malformed.
     */
    private Component buildContent() {
        HelpSet helpSet = loadHelpSet();
        if (helpSet == null) {
            return buildUnavailablePane();
        }
        try {
            JHelp viewer = new JHelp(helpSet);
            try {
                viewer.setCurrentID(HOME_ID);
            } catch (BadIDException e) {
                // No such target: leave the viewer on its own default topic.
            }
            return viewer;
        } catch (RuntimeException | LinkageError e) {
            // JHelp builds its navigator views eagerly; a corrupt search index
            // or view must not escape as an exception from this constructor.
            return buildUnavailablePane();
        }
    }

    /**
     * Locates and parses the HelpSet from the classpath. Returns {@code null} if
     * it cannot be found or read, so the caller can degrade gracefully.
     */
    private static HelpSet loadHelpSet() {
        try {
            ClassLoader loader = HelpCenterPanel.class.getClassLoader();
            URL url = HelpSet.findHelpSet(loader, HELPSET_PATH);
            if (url == null) {
                return null;
            }
            return new HelpSet(loader, url);
        } catch (javax.help.HelpSetException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** A centred message shown when the help content cannot be presented. */
    private static JPanel buildUnavailablePane() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(Color.WHITE);
        JLabel label = new JLabel(
                "<html><div style='text-align:center;'>"
                + "<h2>Help content unavailable</h2>"
                + "<p>The Help Center could not load its HelpSet.</p>"
                + "</div></html>",
                SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }
}
