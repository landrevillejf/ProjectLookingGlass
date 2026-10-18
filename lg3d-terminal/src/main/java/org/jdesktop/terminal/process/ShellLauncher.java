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

import java.io.IOException;
import java.util.Map;

/**
 * Detects and launches the user's preferred shell. The shell is determined by
 * (in priority order): an explicit override, the {@code SHELL} environment
 * variable, or {@code /bin/bash} as the fallback.
 */
public final class ShellLauncher {
    
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
     * @throws IOException if launching fails
     */
    public static PtyProcess launchShell(String shellOverride, Map<String, String> env,
                                          String workDir, int rows, int columns) throws IOException {
        String shell = shellOverride;
        if (shell == null || shell.isEmpty()) {
            shell = System.getenv("SHELL");
        }
        if (shell == null || shell.isEmpty()) {
            shell = "/bin/bash";
        }
        return PtyProcess.spawn(shell, null, env, workDir, rows, columns);
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
