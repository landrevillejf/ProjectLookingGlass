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

import org.jdesktop.terminal.display.TerminalDisplay;
import org.jdesktop.terminal.process.ShellLauncher;
import org.jdesktop.terminal.emulation.TerminalEmulator;
import org.jdesktop.terminal.emulation.TerminalState;
import org.jdesktop.terminal.pty.PtyProcess;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A complete terminal session: a PTY-attached process, an emulator that
 * interprets the process's output, and a display that renders the result.
 * This is the main entry point for embedding a terminal in an application.
 *
 * <p>Lifecycle: create → {@link #start()} → use the {@link #getDisplay()}
 * component → {@link #close()}. The PTY reader runs on a background thread;
 * all display updates are marshalled to the EDT via {@code SwingUtilities}.</p>
 */
public final class TerminalSession implements Closeable {
    
    private static final int DEFAULT_ROWS = 24;
    private static final int DEFAULT_COLUMNS = 80;
    private static final int READ_BUFFER_SIZE = 4096;
    
    private final int rows;
    private final int columns;
    private final TerminalDisplay display;
    
    private PtyProcess process;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    /**
     * Creates a new terminal session with default dimensions (24×80).
     */
    public TerminalSession() {
        this(DEFAULT_ROWS, DEFAULT_COLUMNS);
    }
    
    /**
     * Creates a new terminal session with the specified dimensions.
     *
     * @param rows    the number of rows
     * @param columns the number of columns
     */
    public TerminalSession(int rows, int columns) {
        this.rows = rows;
        this.columns = columns;
        this.display = new TerminalDisplay(rows, columns);
    }
    
    /**
     * Starts the session by launching the user's default shell. After this
     * returns, the display is live and receiving output from the shell.
     *
     * @throws IOException if the shell cannot be launched
     * @throws IllegalStateException if the session is already started
     */
    public void start() throws IOException {
        start(null, null, null);
    }
    
    /**
     * Starts the session with custom configuration.
     *
     * @param shellOverride explicit shell path (null to auto-detect)
     * @param env           additional environment variables (null to inherit)
     * @param workDir       working directory (null to inherit)
     * @throws IOException if the shell cannot be launched
     * @throws IllegalStateException if the session is already started
     */
    public void start(String shellOverride, Map<String, String> env, String workDir)
            throws IOException {
        if (running.getAndSet(true)) {
            throw new IllegalStateException("Session already started");
        }
        
        this.process = ShellLauncher.launchShell(shellOverride, env, workDir, rows, columns);
        this.display.setPtyOutput(process.getOutputStream());
        
        // Start the PTY reader thread
        this.readerThread = new Thread(this::readLoop, "terminal-reader-" + process.getPid());
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }
    
    /**
     * Starts the session by running a specific command instead of a shell.
     *
     * @param command the command to execute
     * @param args    command arguments
     * @param env     additional environment variables (null to inherit)
     * @param workDir working directory (null to inherit)
     * @throws IOException if the command cannot be launched
     */
    public void startCommand(String command, String[] args, Map<String, String> env,
                              String workDir) throws IOException {
        if (running.getAndSet(true)) {
            throw new IllegalStateException("Session already started");
        }
        
        this.process = ShellLauncher.launchCommand(command, args, env, workDir, rows, columns);
        this.display.setPtyOutput(process.getOutputStream());
        
        this.readerThread = new Thread(this::readLoop, "terminal-reader-" + process.getPid());
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }
    
    /** Returns the Swing display component. */
    public TerminalDisplay getDisplay() {
        return display;
    }
    
    /** Returns the terminal state (for programmatic inspection). */
    public TerminalState getState() {
        return display.getState();
    }
    
    /** Returns the terminal emulator. */
    public TerminalEmulator getEmulator() {
        return display.getEmulator();
    }
    
    /** Returns true if the session is running. */
    public boolean isRunning() {
        return running.get() && process != null && process.isAlive();
    }
    
    /** Returns the PID of the attached process, or -1 if not started. */
    public int getPid() {
        return process != null ? process.getPid() : -1;
    }
    
    /**
     * Resizes the terminal. This notifies the attached process via SIGWINCH
     * so it can reflow its output.
     *
     * @param newRows    the new number of rows
     * @param newColumns the new number of columns
     * @throws IOException if the resize notification fails
     */
    public void resize(int newRows, int newColumns) throws IOException {
        if (process != null) {
            process.resize(newRows, newColumns, 0, 0);
        }
    }
    
    /**
     * Sends raw bytes to the PTY input (as if the user typed them).
     *
     * @param data the bytes to send
     * @throws IOException if the write fails
     */
    public void sendInput(byte[] data) throws IOException {
        if (process != null) {
            process.getOutputStream().write(data);
            process.getOutputStream().flush();
        }
    }
    
    /** The background read loop: reads PTY output and feeds it to the emulator. */
    private void readLoop() {
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        TerminalEmulator emulator = display.getEmulator();
        
        try {
            InputStream in = process.getInputStream();
            while (running.get() && process.isAlive()) {
                int bytesRead = in.read(buffer);
                if (bytesRead < 0) {
                    break; // EOF
                }
                if (bytesRead > 0) {
                    emulator.process(buffer, 0, bytesRead);
                    javax.swing.SwingUtilities.invokeLater(display::repaint);
                }
            }
        } catch (IOException e) {
            // PTY closed or process exited
        } finally {
            running.set(false);
        }
    }
    
    @Override
    public void close() throws IOException {
        running.set(false);
        
        // Interrupt the reader thread
        if (readerThread != null) {
            readerThread.interrupt();
        }
        
        // Kill the process
        if (process != null) {
            process.destroyForcibly();
            process.close();
        }
        
        // Dispose the display
        display.dispose();
    }
}
