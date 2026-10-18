/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file except in
 * compliance with the License. A copy of the License is available at
 * http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.terminal;

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.terminal.TerminalSession;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

/**
 * A Swing panel hosting a {@link TerminalSession}, suitable for embedding in
 * the lg3d 3D desktop via {@code TitledSwingWindow} / {@code SwingNode}, or
 * for use in a plain 2D MDI container.
 *
 * <p>The panel creates a terminal session on construction, starts the shell,
 * and exposes the terminal's display component. When the panel is removed
 * from its parent, {@link #dispose()} should be called to release the PTY
 * and kill the shell process.</p>
 */
public class TerminalPanel extends JPanel {
    
    private static final int DEFAULT_ROWS = 24;
    private static final int DEFAULT_COLUMNS = 80;
    
    private final TerminalSession session;
    
    /**
     * Creates a new terminal panel with default dimensions (24×80).
     *
     * @throws IOException if the shell cannot be launched
     */
    public TerminalPanel() throws IOException {
        this(DEFAULT_ROWS, DEFAULT_COLUMNS);
    }
    
    /**
     * Creates a new terminal panel with the specified dimensions.
     *
     * @param rows    the number of rows
     * @param columns the number of columns
     * @throws IOException if the shell cannot be launched
     */
    public TerminalPanel(int rows, int columns) throws IOException {
        super(new BorderLayout());
        this.session = new TerminalSession(rows, columns);
        this.session.start();
        add(session.getDisplay(), BorderLayout.CENTER);
        setPreferredSize(session.getDisplay().getPreferredSize());
    }
    
    /** Returns the underlying terminal session. */
    public TerminalSession getSession() {
        return session;
    }
    
    /** Returns true if the terminal's shell is still running. */
    public boolean isRunning() {
        return session.isRunning();
    }
    
    /**
     * Releases all resources: kills the shell process, closes the PTY, and
     * disposes the display. Must be called when the panel is no longer needed.
     */
    public void dispose() {
        try {
            session.close();
        } catch (IOException e) {
            // Best-effort cleanup
        }
    }
    
    /**
     * Convenience factory: creates a {@code Frame3D} hosting a new terminal
     * panel, suitable for the lg3d 3D desktop.
     *
     * @param title the window title
     * @return the new Frame3D
     * @throws IOException if the shell cannot be launched
     */
    public static org.jdesktop.lg3d.wg.Frame3D createTitledWindow(String title) throws IOException {
        TerminalPanel panel = new TerminalPanel();
        return TitledSwingWindow.show(title, panel, 
                panel.session.getDisplay().getPreferredSize().width,
                panel.session.getDisplay().getPreferredSize().height);
    }
}
