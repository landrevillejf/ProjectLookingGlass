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
package org.jdesktop.terminal;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

/**
 * Standalone launcher for the terminal application. Opens a JFrame containing
 * a {@link TerminalSession} display, running the user's default shell.
 *
 * <p>Usage: {@code java -jar lg3d-terminal.jar [rows] [columns]}</p>
 *
 * <p>Default dimensions are 24 rows × 80 columns. The terminal fills the
 * entire window; closing the window terminates the session and exits.</p>
 */
public final class Standalone {
    
    private Standalone() {}
    
    /**
     * Main entry point.
     *
     * @param args optional: {@code [rows] [columns]}
     */
    public static void main(String[] args) {
        int rows = 24;
        int columns = 80;
        
        if (args.length >= 1) {
            try {
                rows = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid rows: " + args[0]);
            }
        }
        if (args.length >= 2) {
            try {
                columns = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid columns: " + args[1]);
            }
        }
        
        final int finalRows = rows;
        final int finalColumns = columns;
        SwingUtilities.invokeLater(() -> createAndShow(finalRows, finalColumns));
    }
    
    private static void createAndShow(int rows, int columns) {
        try {
            // Set system LAF
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Fall back to default
        }
        
        JFrame frame = new JFrame("LG3D Terminal");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        
        TerminalSession session;
        try {
            session = new TerminalSession(rows, columns);
            session.start();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame,
                    "Failed to start terminal: " + e.getMessage(),
                    "Terminal Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        frame.add(session.getDisplay());
        frame.pack();
        frame.setLocationRelativeTo(null);
        
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                try {
                    session.close();
                } catch (IOException ex) {
                    // ignore
                }
            }
        });
        
        frame.setVisible(true);
        session.getDisplay().requestFocusInWindow();
    }
}
