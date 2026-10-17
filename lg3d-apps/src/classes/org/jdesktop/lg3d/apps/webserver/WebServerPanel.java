/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.webserver;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * The thin 2D-desktop host the {@code Desktop2DAppRegistry} maps the Web Server
 * Console command to: it wraps {@link WebServerConsolePanel} so the very same
 * console opens as an MDI internal frame in the 2D/Swing desktop, exactly as the
 * Database Manager's {@code DbManagerPanel} wraps its module client.
 *
 * <p>The 3D desktop never loads this shim &mdash; its {@link WebServerConsole}
 * wrapper hosts {@link WebServerConsolePanel} directly on a {@code SwingNode}.
 * This class exists only so the registry has a stable, non-throwing panel to
 * build reflectively.</p>
 *
 * <p>If the console cannot be instantiated (a missing runtime dependency, say)
 * the shim degrades to a readable message instead of throwing, so a broken bundle
 * can never take down the window that hosts it.</p>
 */
public class WebServerPanel extends JPanel {

    /** Panel size in native pixels; the host hands these to the MDI frame. */
    public static final int WIDTH_PX = WebServerConsolePanel.WIDTH_PX;
    public static final int HEIGHT_PX = WebServerConsolePanel.HEIGHT_PX;

    /** The embedded console, or {@code null} when it could not be created. */
    private final WebServerConsolePanel inner;

    public WebServerPanel() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        WebServerConsolePanel created = null;
        try {
            created = new WebServerConsolePanel();
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

    /** @return the embedded console panel, or {@code null} when unavailable. */
    public WebServerConsolePanel getMainPanel() {
        return inner;
    }

    /** Releases the console's child process and executor when the host closes. */
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
                + "<h2>Web Server Console unavailable</h2>"
                + "<p>The console could not be started.</p>"
                + "</div></html>",
                SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }
}
