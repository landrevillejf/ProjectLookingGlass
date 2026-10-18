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

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * A JFrame hosting a {@link TerminalPanel}, suitable for capture by
 * {@code SwingNodeWindowCapture} and presentation as a 3D desktop window.
 * The frame opens the shell through the panel and ends the session when the
 * frame closes.
 *
 * <p>A terminal that cannot start still gets a window, showing the reason: this
 * constructor runs on the event-dispatch thread, where an exception becomes the
 * desktop's uncaught-exception handler - a crash report for an environment
 * problem, and no terminal for the user.</p>
 */
public class TerminalFrame extends JFrame {

    private final TerminalPanel terminalPanel;

    /**
     * Creates a new terminal frame with default dimensions (24 rows × 80 columns).
     */
    public TerminalFrame() {
        this(24, 80);
    }

    /**
     * Creates a new terminal frame with the specified dimensions.
     *
     * @param rows    initial number of rows
     * @param columns initial number of columns
     */
    public TerminalFrame(int rows, int columns) {
        super("Terminal");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        terminalPanel = new TerminalPanel(rows, columns);
        add(terminalPanel, BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                terminalPanel.dispose();
            }
        });
    }

    /**
     * Returns the terminal panel hosted by this frame.
     */
    public TerminalPanel getTerminalPanel() {
        return terminalPanel;
    }

    /**
     * Releases the shell and its pty. Called on dispose so a window closed by the
     * window manager - not by the close button's listener - still ends its shell.
     */
    @Override
    public void dispose() {
        terminalPanel.dispose();
        super.dispose();
    }
}
