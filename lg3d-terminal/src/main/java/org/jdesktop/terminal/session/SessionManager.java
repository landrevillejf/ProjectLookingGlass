/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 */
package org.jdesktop.terminal.session;

import org.jdesktop.terminal.TerminalSession;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages multiple terminal sessions in a tabbed interface.
 */
public final class SessionManager {
    
    private final JTabbedPane tabbedPane;
    private final List<TerminalSession> sessions = new ArrayList<>();
    
    public SessionManager() {
        this.tabbedPane = new JTabbedPane();
    }
    
    public TerminalSession createSession(int rows, int columns) throws IOException {
        TerminalSession session = new TerminalSession(rows, columns);
        session.start();
        sessions.add(session);
        tabbedPane.addTab("Terminal " + sessions.size(), session.getDisplay());
        return session;
    }
    
    public void closeSession(int index) {
        if (index < 0 || index >= sessions.size()) return;
        TerminalSession session = sessions.remove(index);
        tabbedPane.removeTabAt(index);
        try {
            session.close();
        } catch (IOException e) {
            // ignore
        }
    }
    
    public JTabbedPane getTabbedPane() {
        return tabbedPane;
    }
    
    public void dispose() {
        for (TerminalSession session : sessions) {
            try {
                session.close();
            } catch (IOException e) {
                // ignore
            }
        }
        sessions.clear();
    }
}
