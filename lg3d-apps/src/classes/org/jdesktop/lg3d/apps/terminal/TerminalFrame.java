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

import org.jdesktop.terminal.TerminalSession;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

/**
 * A JFrame hosting a {@link TerminalPanel}, suitable for capture by
 * {@code SwingNodeWindowCapture} and presentation as a 3D desktop window.
 * The frame creates a terminal session on construction, starts it, and
 * disposes the session when the frame closes.
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

        try {
            terminalPanel = new TerminalPanel(rows, columns);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start terminal session", e);
        }
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
}
