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
import org.jdesktop.lg3d.apps.ssh.SshTerminalPanel;
import org.jdesktop.terminal.process.ShellLauncher;
import org.jdesktop.terminal.pty.PtyProcess;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A terminal window's content: the desktop's own shell, rendered by
 * {@link SshTerminalPanel}.
 *
 * <p>The rendering half is not new code. {@code SshTerminalPanel} is the ANSI/VT
 * emulator the SSH client ships with - scrollback, colours, styles, wide
 * characters, selection, keyboard translation - and it takes its bytes from
 * whoever feeds it ({@link SshTerminalPanel#feed}), so a local shell is one more
 * byte source: the master side of a {@link PtyProcess}. Splitting it this way
 * keeps the terminal's only genuinely host-specific part - allocating a pty
 * without forking the desktop, and attaching a child to it - in
 * {@code lg3d-terminal}, where it is testable headless.</p>
 *
 * <p><b>Nothing here throws at the desktop.</b> A terminal that cannot start is an
 * environment fact ({@code /dev/ptmx} not mounted, no shell on this system), not a
 * crash, and this panel is built on the event-dispatch thread by
 * {@link TerminalApp}. Failure is therefore reported in the window itself: the
 * message names what was tried and what the host answered.</p>
 *
 * <p>Callers own the lifecycle: {@link #dispose()} ends the shell and releases the
 * pty. Once disposed, the panel does nothing further.</p>
 */
public class TerminalPanel extends JPanel {

    private static final int DEFAULT_ROWS = 24;
    private static final int DEFAULT_COLUMNS = 80;
    private static final int READ_BUFFER_SIZE = 4096;

    /** The terminal is shown when a shell came up, the notice when it did not. */
    private static final String TERMINAL_CARD = "terminal";
    private static final String FAILED_CARD = "failed";

    private final SshTerminalPanel terminal;
    private final CardLayout cards;
    private final JLabel failureNotice;

    private PtyProcess process;
    private Thread reader;
    private volatile boolean closed;

    /**
     * Creates a terminal panel running the user's shell at 24 rows by 80 columns.
     */
    public TerminalPanel() {
        this(DEFAULT_ROWS, DEFAULT_COLUMNS);
    }

    /**
     * Creates a terminal panel running the user's shell at the given size.
     *
     * <p>The constructor never fails: if the shell cannot be launched, the panel
     * comes up showing why. Callers on the event-dispatch thread have no
     * {@code IOException} worth throwing.
     *
     * @param rows    initial number of rows
     * @param columns initial number of columns
     */
    public TerminalPanel(int rows, int columns) {
        super(new BorderLayout());

        terminal = new SshTerminalPanel();
        // resize(columns, rows) - the emulator's order, the opposite of Pty's.
        terminal.resize(columns, rows);

        cards = new CardLayout();
        JPanel stack = new JPanel(cards);
        stack.add(terminal.getScrollPane(), TERMINAL_CARD);
        JPanel failed = new JPanel(new BorderLayout());
        failureNotice = new JLabel(" ");
        failed.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        failed.add(failureNotice, BorderLayout.CENTER);
        stack.add(failed, FAILED_CARD);
        add(stack, BorderLayout.CENTER);

        setPreferredSize(terminal.getPreferredSize());
        startShell(rows, columns);
    }

    /**
     * Launches the shell and wires both directions.
     *
     * <p>The read thread starts only once a shell actually exists, and the size
     * listener only then too: a resize of a window that has no pty behind it has
     * nothing to tell anybody.</p>
     */
    private void startShell(int rows, int columns) {
        try {
            process = ShellLauncher.launchShell(null, null, null, rows, columns);
        } catch (IOException | RuntimeException e) {
            process = null;
            failureNotice.setText("Terminal could not start: " + e.getMessage());
            cards.show(this, FAILED_CARD);
            return;
        }

        terminal.setInputListener(this::sendToShell);
        terminal.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                syncSizeToPty();
            }
        });

        reader = new Thread(this::readLoop, "terminal-reader-" + process.getPid());
        reader.setDaemon(true);
        reader.start();
        terminal.requestFocusInWindow();
    }

    /** The terminal emulator component, for the frame to give the focus to. */
    public SshTerminalPanel getTerminal() {
        return terminal;
    }

    /** The pid of the shell, or -1 when none ever started. */
    public int getPid() {
        PtyProcess p = process;
        return p == null ? -1 : p.getPid();
    }

    /**
     * Returns true while the shell is running. False also when no shell ever
     * started, which is the case a caller must be able to tell apart from "ended".
     */
    public boolean isRunning() {
        PtyProcess p = process;
        return p != null && p.isAlive();
    }

    /**
     * Ends the shell and releases the pty. Idempotent, and safe to call from a
     * window listener on the event-dispatch thread: no native call here waits for
     * the child, the reaper inside {@link PtyProcess} does that on its own thread.
     */
    public void dispose() {
        if (closed) {
            return;
        }
        closed = true;

        if (reader != null) {
            reader.interrupt();
            reader = null;
        }
        PtyProcess p = process;
        process = null;
        if (p != null) {
            p.destroyForcibly();
            try {
                p.close();
            } catch (IOException e) {
                // A pty that would not close is already released for every
                // purpose that matters: the shell is SIGKILLed and reaped.
            }
        }
    }

    /**
     * Forwards keystrokes to the shell. Never throws: this runs on the
     * event-dispatch thread, called from the emulator's key handler, and a shell
     * that has exited is not an error the user can be handed a stack trace for.
     */
    private void sendToShell(byte[] data) {
        PtyProcess p = process;
        if (p == null || closed) {
            return;
        }
        try {
            p.getOutputStream().write(data);
            p.getOutputStream().flush();
        } catch (IOException | RuntimeException e) {
            notice("[input lost: " + e.getMessage() + "]");
        }
    }

    /**
     * Tells the pty the window's grid changed, so the shell and every program in it
     * get {@code SIGWINCH} and reflow.
     *
     * <p>The emulator is resized first: it owns the grid the user is looking at, and
     * what it ends up with is what the kernel should be told. A failed resize means
     * the shell exited between the two calls, which the reader reports on its own.</p>
     */
    private void syncSizeToPty() {
        PtyProcess p = process;
        if (p == null || closed) {
            return;
        }
        terminal.autoResize();
        try {
            p.resize(terminal.getRows(), terminal.getColumns(), 0, 0);
        } catch (IOException | RuntimeException e) {
            // Not a window the user can act on; the reader ends the session.
        }
    }

    /**
     * Reads the shell's output until it ends. A blocking read on the pty master is
     * exactly what this thread wants: it parks in the kernel, and returns -1 when
     * the last slave descriptor goes, which is how a shell exit looks from here.
     */
    private void readLoop() {
        PtyProcess p = process;
        if (p == null) {
            return;
        }
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        try {
            InputStream in = p.getInputStream();
            while (!closed && !Thread.currentThread().isInterrupted()) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                terminal.feed(buffer, 0, read);
            }
        } catch (IOException e) {
            if (!closed) {
                notice("[terminal read failed: " + e.getMessage() + "]");
            }
        }
        if (!closed) {
            notice("[shell exited]");
        }
    }

    /**
     * Writes a line into the terminal itself, the way the SSH client reports a
     * dropped session. Kept to plain text: the emulator owns the palette.
     */
    private void notice(String text) {
        byte[] bytes = ("\r\n" + text + "\r\n").getBytes(StandardCharsets.UTF_8);
        terminal.feed(bytes, 0, bytes.length);
    }

    /**
     * Convenience factory: creates a {@code Frame3D} hosting a new terminal panel,
     * suitable for the lg3d 3D desktop.
     *
     * @param title the window title
     * @return the new Frame3D
     */
    public static org.jdesktop.lg3d.wg.Frame3D createTitledWindow(String title) {
        TerminalPanel panel = new TerminalPanel();
        Dimension size = panel.getPreferredSize();
        return TitledSwingWindow.show(title, panel, size.width, size.height);
    }
}
