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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A process attached to a pseudo-terminal: the shell behind a terminal window.
 *
 * <p>This owns the pair - the {@link Pty} master on one side and the child
 * running on the slave on the other - and ends both. The child is launched with
 * {@code posix_spawn} (see {@link PtySpawn}), which makes it a child of this JVM
 * like any {@code ProcessBuilder} child would be, so it has to be waited for: an
 * unreaped child stays a zombie, {@link ProcessHandle} keeps reporting it alive,
 * and a session whose shell has exited never notices. That is what the reaper
 * thread below is for, and it is not optional.</p>
 *
 * <p>Because the child was not started by {@code ProcessBuilder}, it is not a
 * {@code java.lang.Process} and never was one; the process-control methods here
 * answer from libc and {@link ProcessHandle} instead. Their contracts are the
 * ones {@code Process} established, so callers read the same way they would for
 * any child process.</p>
 */
public class PtyProcess implements Closeable {

    /** What {@code Process} reports for a child whose status cannot be known. */
    private static final int UNKNOWN_STATUS = -1;

    private final Pty pty;
    private final int pid;

    /** Set once the reaper is done, whether or not it learned an exit code. */
    private volatile boolean exited;
    private volatile int status = UNKNOWN_STATUS;

    /**
     * Wraps a PTY and the child running on its slave, and starts reaping.
     *
     * <p>Package-private by necessity: an instance is the only thing that owns the
     * reaper, and a public constructor would let a caller adopt a pid and leave it
     * unreaped - which is a zombie and a session that never ends. Use
     * {@link #spawn}.</p>
     */
    PtyProcess(Pty pty, int pid) {
        this.pty = pty;
        this.pid = pid;
        startReaper();
    }

    /**
     * Blocks in {@code waitpid} for this session's child and records how it ended.
     *
     * <p>A daemon thread, because a session whose shell outlives the desktop must
     * not be the reason the desktop can exit; a forked child, though, would keep
     * the JVM up, so the wait itself cannot hold the process open. It ends as soon
     * as the child does, so nothing is held reachable for longer than that.</p>
     */
    private void startReaper() {
        Thread thread = new Thread(() -> {
            Integer code = null;
            try {
                code = LibC.waitPid(pid);
            } catch (RuntimeException | Error e) {
                // A reaper that dies leaves every later session a zombie, so it
                // reports and carries on as if the child had simply gone missing.
                System.err.println("PTY reaper for pid " + pid + " failed: " + e);
            }
            markExited(code);
        }, "pty-reaper-" + pid);
        thread.setDaemon(true);
        thread.start();
    }

    /** Records the reaped status and wakes anybody in {@link #waitFor()}. */
    private synchronized void markExited(Integer code) {
        this.status = code == null ? UNKNOWN_STATUS : code;
        this.exited = true;
        notifyAll();
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
     *
     * <p>Both halves are needed. The reaper's answer is authoritative the moment
     * it has one, because a exited-but-not-yet-reaped child is still in
     * {@code /proc}; the kernel's answer covers every moment before that.</p>
     */
    public boolean isAlive() {
        if (exited) {
            return false;
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * Waits for the process to exit and returns its exit code.
     *
     * @return the exit code, or -1 if the child was killed by a signal or was
     *         reaped by somebody other than this session's reaper
     * @throws InterruptedException if the wait is interrupted
     */
    public int waitFor() throws InterruptedException {
        synchronized (this) {
            while (!exited) {
                wait();
            }
            return status;
        }
    }

    /**
     * Returns the exit code if the process has exited, or throws an exception
     * if it is still running.
     *
     * @return the exit code, or -1 when the status could not be determined
     * @throws IllegalThreadStateException if the process has not yet exited
     */
    public int exitValue() {
        if (!exited) {
            throw new IllegalThreadStateException(
                    "process " + pid + " has not exited yet");
        }
        return status;
    }

    /**
     * Asks the process to exit, sending SIGTERM.
     *
     * <p>A shell that has been SIGTERM'd exits, so the reaper notices and
     * {@link #isAlive()} goes false; nothing here waits, exactly as
     * {@code Process.destroy()} waits for nothing.</p>
     */
    public void destroy() {
        signal(LibC.SIGTERM);
    }

    /**
     * Destroys the process forcibly, sending SIGKILL.
     */
    public void destroyForcibly() {
        signal(LibC.SIGKILL);
    }

    /**
     * Sends a signal to the child, reporting a refusal the way {@code Process}
     * does not: quietly, because the only way this fails for a child of our own is
     * that the pid is already gone - which is what the caller wanted - or that it
     * has been recycled by the kernel and is no longer ours to signal. Either way
     * the reaper stays the authority on whether the session is alive.
     */
    private void signal(int signal) {
        try {
            LibC.kill(pid, signal);
        } catch (IOException e) {
            System.err.println("Could not signal pid " + pid + ": " + e.getMessage());
        }
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

    /**
     * Ends the session: stops the shell, then releases the PTY.
     *
     * <p>The master is closed even when the kill fails, because holding it open
     * keeps the shell's session alive with nobody left to look at it. The reaper is
     * left running: it is a daemon, it is the only thing that turns the child into
     * not-a-zombie, and closing the PTY does not make the process exit any sooner.</p>
     */
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
     * @param command the command to execute (e.g., "/bin/bash"); resolved through
     *                {@code PATH} when it is not a path
     * @param args    command arguments
     * @param env     environment variables to add or override, or null for none
     * @param dir     working directory (null to inherit)
     * @param rows    initial terminal rows
     * @param columns initial terminal columns
     * @return the new PtyProcess
     * @throws IOException if the PTY could not be created, or the command refused
     *                     to start - naming which of the two, and why
     */
    public static PtyProcess spawn(String command, String[] args, Map<String, String> env,
                                    String dir, int rows, int columns) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IOException("cannot start a terminal with no command to run");
        }
        Pty pty = new JnaPty(rows, columns);
        int pid;
        try {
            pid = PtySpawn.spawn(command, argv(command, args), environment(env, dir),
                    pty.getSlaveName(), pty.getMasterFd(), dir);
        } catch (IOException | RuntimeException | Error e) {
            // The PTY is ours and the child never started, so leaving the master
            // open would leak a pseudo-terminal and its /dev/pts entry.
            closeQuietly(pty);
            throw e;
        }
        return new PtyProcess(pty, pid);
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

    /** {@code argv[0]} is the program name, so the command leads the list. */
    private static String[] argv(String command, String[] args) {
        List<String> argv = new ArrayList<>();
        argv.add(command);
        if (args != null) {
            for (String arg : args) {
                if (arg != null) {
                    argv.add(arg);
                }
            }
        }
        return argv.toArray(new String[0]);
    }

    /**
     * The child's complete environment. A spawned process inherits nothing, so this
     * is where the desktop's own variables get handed down - and where the two the
     * terminal itself has to decide are decided:
     *
     * <ul>
     *   <li>{@code TERM}, because the emulator implements {@code xterm-256color}
     *   and every curses program in the window reads this to choose its escape
     *   sequences; anything else, and {@code vi} draws over itself.</li>
     *   <li>{@code PWD}, because {@code addchdir_np} moves the child into
     *   {@code dir} but cannot tell the shell that is where it happened, and bash
     *   prefers an inherited {@code PWD} over asking the kernel - leaving
     *   {@code cd} reporting the desktop's directory instead of the terminal's.</li>
     * </ul>
     *
     * <p>Explicit {@code env} entries win over the parent's, and {@code TERM} wins
     * over both, so a caller cannot ask the shell to talk to a terminal that is not
     * this one.</p>
     */
    private static String[] environment(Map<String, String> env, String dir) {
        Map<String, String> merged = new LinkedHashMap<>(System.getenv());
        if (env != null) {
            merged.putAll(env);
        }
        if (dir != null && !dir.isEmpty()) {
            merged.put("PWD", dir);
        }
        merged.put("TERM", "xterm-256color");
        List<String> envp = new ArrayList<>(merged.size());
        merged.forEach((name, value) -> envp.add(name + "=" + (value == null ? "" : value)));
        return envp.toArray(new String[0]);
    }

    /** Releases a PTY nobody will use, keeping the failure being reported intact. */
    private static void closeQuietly(Pty pty) {
        try {
            pty.close();
        } catch (IOException | RuntimeException | Error ignored) {
            // A master that would not close is already the least of this failure.
        }
    }

    /** True when this host can give the child a foreground process group. */
    public static boolean supportsJobControl() {
        return PtySpawn.supportsForegroundProcessGroup();
    }

    /** True when this host can build a PTY at all; see {@link JnaPty#isAvailable()}. */
    public static boolean isAvailable() {
        return JnaPty.isAvailable();
    }
}
