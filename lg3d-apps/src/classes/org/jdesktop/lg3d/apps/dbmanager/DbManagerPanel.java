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
package org.jdesktop.lg3d.apps.dbmanager;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import org.jdesktop.lg3d.dbmanager.ui.DbManagerMainPanel;

/**
 * The Database Manager content: the {@code db-manager} module's JDBC client
 * (connection profiles, metadata navigator, SQL editor, results grid, CSV export
 * and transaction control) presented as a self-contained panel.
 *
 * <p>This is a plain Swing {@link JPanel} with a no-argument constructor, so it
 * is hosted two ways exactly like the Software Update app: in the 3D desktop the
 * {@link DbManager} wrapper puts it on a {@code SwingNode} inside a {@code Frame3D}
 * via {@code TitledSwingWindow}; in the 2D/Swing desktop {@code Desktop2DAppRegistry}
 * constructs it reflectively and hosts it in an MDI internal frame. It touches no
 * Java 3D, so the 2D path never needs the scene graph.</p>
 *
 * <p>If the {@code db-manager} module cannot be instantiated (for example a
 * missing runtime dependency) the panel degrades to a readable message instead of
 * throwing, so a broken bundle can never take down the window that hosts it.</p>
 */
public class DbManagerPanel extends JPanel {

    /** Panel size in native pixels; the wrapper hands these to TitledSwingWindow. */
    public static final int WIDTH_PX = DbManagerMainPanel.WIDTH_PX;
    public static final int HEIGHT_PX = DbManagerMainPanel.HEIGHT_PX;

    /** The embedded client, or {@code null} when it could not be created. */
    private final DbManagerMainPanel inner;

    public DbManagerPanel() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        DbManagerMainPanel created = null;
        try {
            created = new DbManagerMainPanel();
        } catch (RuntimeException | LinkageError e) {
            // A missing runtime dependency must not escape the constructor:
            // fall back to an explanatory pane instead.
            created = null;
        }
        this.inner = created;

        if (inner == null) {
            add(buildUnavailablePane(), BorderLayout.CENTER);
            return;
        }
        add(inner, BorderLayout.CENTER);
    }

    /** @return the embedded client panel, or {@code null} when unavailable. */
    public DbManagerMainPanel getMainPanel() {
        return inner;
    }

    /** Releases the client's open connections when the host window closes. */
    public void dispose() {
        if (inner != null) {
            inner.dispose();
        }
    }

    private static JPanel buildUnavailablePane() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(Color.WHITE);
        JLabel label = new JLabel(
                "<html><div style='text-align:center;'>"
                + "<h2>Database Manager unavailable</h2>"
                + "<p>The database client could not be started.</p>"
                + "</div></html>",
                SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }
}
