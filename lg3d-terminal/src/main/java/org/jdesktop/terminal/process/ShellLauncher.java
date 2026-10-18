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
package org.jdesktop.terminal.process;

import org.jdesktop.terminal.pty.PtyProcess;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Detects and launches the user's preferred shell. The shell is determined by
 * (in priority order): an explicit override, the {@code SHELL} environment
 * variable, {@code /bin/bash}, then {@code /bin/sh} - and a candidate counts only
 * if it is actually executable on this host.
 *
 * <p>Checking rather than trusting matters here: {@code SHELL} is unset in a lot of
 * launch contexts (a desktop started by a display manager, a scheduled job, a JVM
 * forked from a service), and a value left over from another machine produces a
 * spawn error that names neither the shell nor the reason. Every candidate that was
 * rejected is listed in the failure.</p>
 */
public final class ShellLauncher {
    
    /** Shells to try, in order, when nothing above them is usable. */
    private static final String[] FALLBACK_SHELLS = {"/bin/bash", "/bin/sh"};

    private ShellLauncher() {}
    
    /**
     * Launches the user's default shell attached to a new PTY.
     *
     * @param rows    initial terminal rows
     * @param columns initial terminal columns
     * @return the new PtyProcess
     * @throws IOException if launching fails
     */
    public static PtyProcess launchShell(int rows, int columns) throws IOException {
        return launchShell(null, null, null, rows, columns);
    }
    
    /**
     * Launches a shell with custom configuration.
     *
     * @param shellOverride explicit shell path (null to auto-detect)
     * @param env         additional environment variables (null to inherit)
     * @param workDir     working directory (null to inherit)
     * @param rows        initial terminal rows
     * @param columns     initial terminal columns
     * @return the new PtyProcess
     * @throws IOException if no usable shell was found, or the launch failed
     */
    public static PtyProcess launchShell(String shellOverride, Map<String, String> env,
                                          String workDir, int rows, int columns) throws IOException {
        return PtyProcess.spawn(resolveShell(shellOverride), null, env, workDir, rows, columns);
    }

    /**
     * Chooses the shell to run: the override if one was given, then {@code SHELL},
     * then the fallbacks.
     *
     * <p>A candidate is accepted when it is a path that exists and is executable,
     * or a bare program name - {@code posix_spawnp} resolves those through
     * {@code PATH}, and asking this method to stat {@code "bash"} would reject a
     * perfectly good shell because the desktop's working directory is not
     * {@code /bin}.</p>
     *
     * @throws IOException naming every candidate that was rejected, and how
     */
    static String resolveShell(String shellOverride) throws IOException {
        StringBuilder rejected = new StringBuilder();
        String candidate = accept(shellOverride, rejected, "requested shell")
                ? shellOverride : null;
        if (candidate == null) {
            String fromEnv = System.getenv("SHELL");
            if (fromEnv != null && accept(fromEnv, rejected, "$SHELL")) {
                candidate = fromEnv;
            }
        }
        if (candidate == null) {
            for (String fallback : FALLBACK_SHELLS) {
                if (accept(fallback, rejected, "default shell")) {
                    candidate = fallback;
                    break;
                }
            }
        }
        if (candidate == null) {
            throw new IOException("no usable shell to run: " + rejected);
        }
        return candidate;
    }

    /**
     * True when {@code shell} can be executed here, appending the reason to
     * {@code rejected} when it cannot.
     */
    private static boolean accept(String shell, StringBuilder rejected, String role) {
        if (shell == null || shell.isEmpty()) {
            // Nothing offered at this level of the priority order: not a rejection,
            // just a step with no candidate, so the next one gets its turn.
            return false;
        }
        if (!shell.contains("/")) {
            // A program name, resolved by PATH when the child is spawned.
            return true;
        }
        File file = new File(shell);
        if (file.canExecute() && !file.isDirectory()) {
            return true;
        }
        if (rejected.length() > 0) {
            rejected.append("; ");
        }
        rejected.append(role).append(' ').append(shell).append(" is ")
                .append(file.exists() ? "not executable" : "missing");
        return false;
    }
    
    /**
     * Launches a specific command attached to a new PTY.
     *
     * @param command the command to execute
     * @param args    command arguments
     * @param env     additional environment variables (null to inherit)
     * @param workDir working directory (null to inherit)
     * @param rows    initial terminal rows
     * @param columns initial terminal columns
     * @return the new PtyProcess
     * @throws IOException if launching fails
     */
    public static PtyProcess launchCommand(String command, String[] args,
                                            Map<String, String> env, String workDir,
                                            int rows, int columns) throws IOException {
        return PtyProcess.spawn(command, args, env, workDir, rows, columns);
    }
    
    /**
     * Returns the path of the user's default shell.
     */
    public static String detectShell() {
        String shell = System.getenv("SHELL");
        return (shell != null && !shell.isEmpty()) ? shell : "/bin/bash";
    }
}
