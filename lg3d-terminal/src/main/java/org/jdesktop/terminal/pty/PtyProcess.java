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
package org.jdesktop.terminal.pty;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * A process attached to a pseudo-terminal. This class manages the lifecycle
 * of a child process (typically a shell) connected to a PTY, providing
 * bidirectional I/O and process control.
 *
 * <p>The process is spawned with the PTY as its controlling terminal, so
 * it behaves as if it were running in a real terminal window.</p>
 */
public class PtyProcess implements Closeable {
    
    private final Pty pty;
    private final Process process;
    private final int pid;
    private volatile boolean alive = true;
    
    /**
     * Creates a new PtyProcess wrapping the given PTY and process.
     *
     * @param pty     the PTY the process is attached to
     * @param process the child process
     * @param pid     the process ID
     */
    public PtyProcess(Pty pty, Process process, int pid) {
        this.pty = pty;
        this.process = process;
        this.pid = pid;
    }
    
    /**
     * Returns the PTY this process is attached to.
     */
    public Pty getPty() {
        return pty;
    }
    
    /**
     * Returns the input stream for reading output from the process.
     */
    public InputStream getInputStream() {
        return pty.getInputStream();
    }
    
    /**
     * Returns the output stream for writing input to the process.
     */
    public OutputStream getOutputStream() {
        return pty.getOutputStream();
    }
    
    /**
     * Returns the process ID.
     */
    public int getPid() {
        return pid;
    }
    
    /**
     * Returns true if the process is still running.
     */
    public boolean isAlive() {
        if (!alive) {
            return false;
        }
        try {
            process.exitValue();
            alive = false;
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }
    
    /**
     * Waits for the process to exit and returns its exit code.
     *
     * @return the exit code
     * @throws InterruptedException if the wait is interrupted
     */
    public int waitFor() throws InterruptedException {
        return process.waitFor();
    }
    
    /**
     * Returns the exit code if the process has exited, or throws an exception
     * if it is still running.
     *
     * @return the exit code
     * @throws IllegalThreadStateException if the process has not yet exited
     */
    public int exitValue() {
        return process.exitValue();
    }
    
    /**
     * Destroys the process, sending SIGTERM.
     */
    public void destroy() {
        process.destroy();
        alive = false;
    }
    
    /**
     * Destroys the process forcibly, sending SIGKILL.
     */
    public void destroyForcibly() {
        process.destroyForcibly();
        alive = false;
    }
    
    /**
     * Resizes the terminal window, notifying the process via SIGWINCH.
     *
     * @param rows    the new number of rows
     * @param columns the new number of columns
     * @param width   the new width in pixels
     * @param height  the new height in pixels
     * @throws IOException if the resize fails
     */
    public void resize(int rows, int columns, int width, int height) throws IOException {
        pty.resize(rows, columns, width, height);
    }
    
    @Override
    public void close() throws IOException {
        try {
            if (isAlive()) {
                destroy();
            }
        } finally {
            pty.close();
        }
    }
    
    /**
     * Spawns a new process attached to a PTY.
     *
     * @param command the command to execute (e.g., "/bin/bash")
     * @param args    command arguments
     * @param env     environment variables (null to inherit)
     * @param dir     working directory (null to inherit)
     * @param rows    initial terminal rows
     * @param columns initial terminal columns
     * @return the new PtyProcess
     * @throws IOException if spawning fails
     */
    public static PtyProcess spawn(String command, String[] args, Map<String, String> env,
                                    String dir, int rows, int columns) throws IOException {
        JnaPty pty = new JnaPty(rows, columns);
        
        // Build the full command
        String[] fullCommand = new String[1 + (args != null ? args.length : 0)];
        fullCommand[0] = command;
        if (args != null) {
            System.arraycopy(args, 0, fullCommand, 1, args.length);
        }
        
        // Spawn the process
        ProcessBuilder pb = new ProcessBuilder(fullCommand);
        if (env != null) {
            pb.environment().putAll(env);
        }
        if (dir != null) {
            pb.directory(new java.io.File(dir));
        }
        
        // Set TERM environment variable
        pb.environment().put("TERM", "xterm-256color");
        
        Process process = pb.start();
        
        // Get the PID (Java 9+)
        long pidLong = process.pid();
        int pid = (int) pidLong;
        
        return new PtyProcess(pty, process, pid);
    }
    
    /**
     * Spawns a shell process attached to a PTY, using the user's default shell.
     *
     * @param rows    initial terminal rows
     * @param columns initial terminal columns
     * @return the new PtyProcess
     * @throws IOException if spawning fails
     */
    public static PtyProcess spawnShell(int rows, int columns) throws IOException {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isEmpty()) {
            shell = "/bin/bash";
        }
        return spawn(shell, null, null, null, rows, columns);
    }
}
