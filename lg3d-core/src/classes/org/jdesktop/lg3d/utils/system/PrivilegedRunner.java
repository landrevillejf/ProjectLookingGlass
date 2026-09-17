/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.utils.system;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Runs commands with elevated privileges through {@code pkexec} (polkit),
 * reporting a structured result so callers can tell "succeeded" apart from
 * "the user dismissed the authentication dialog" and "polkit is unavailable".
 *
 * <p>Used by the control center's user-management panel (useradd/usermod/
 * userdel/gpasswd/passwd/chage) and by the task manager to signal processes
 * owned by other users. Everything degrades gracefully: if {@code pkexec} is
 * not installed, {@link #run(List)} returns {@link Status#UNAVAILABLE} rather
 * than throwing, and the UI can switch to read-only.</p>
 *
 * <p>No JNI/JNA; this only shells out to {@code pkexec} via
 * {@link ProcessRunner}.</p>
 */
public final class PrivilegedRunner {
    private static final Logger logger = Logger.getLogger("lg.system");

    /** How long to wait for a privileged command (the user may take a while to
     *  type a password into the polkit agent). */
    private static final long TIMEOUT_SECONDS = 120;

    private PrivilegedRunner() {
        // no instances
    }

    /** The outcome of a privileged operation. */
    public enum Status {
        /** Command ran and exited 0. */
        SUCCESS,
        /** The user dismissed / was denied the polkit authorization prompt. */
        CANCELLED,
        /** pkexec/polkit is not installed, so elevation is impossible. */
        UNAVAILABLE,
        /** The command ran but failed (non-zero exit for a reason other than
         *  authorization being dismissed). */
        ERROR
    }

    /** Result of a {@link #run(List)} call. */
    public static final class PrivilegedResult {
        private final Status status;
        private final String message;
        private final int exitCode;
        private final String output;

        PrivilegedResult(Status status, String message, int exitCode, String output) {
            this.status = status;
            this.message = (message == null) ? "" : message;
            this.exitCode = exitCode;
            this.output = (output == null) ? "" : output;
        }

        public Status getStatus() {
            return status;
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        /** A human-readable explanation suitable for a dialog. */
        public String getMessage() {
            return message;
        }

        public int getExitCode() {
            return exitCode;
        }

        /** Combined stdout of the privileged command. */
        public String getOutput() {
            return output;
        }

        @Override
        public String toString() {
            return "PrivilegedResult[" + status + ", exit=" + exitCode + ", " + message + "]";
        }
    }

    /** True if {@code pkexec} is on the PATH. */
    public static boolean isAvailable() {
        return ProcessRunner.isAvailable("pkexec");
    }

    /**
     * Runs the given command with elevated privileges via {@code pkexec}.
     *
     * @param command the command and arguments (without a leading pkexec)
     * @return a structured {@link PrivilegedResult}; never null
     */
    public static PrivilegedResult run(List<String> command) {
        return run(command, null);
    }

    /**
     * Runs the given command with elevated privileges via {@code pkexec},
     * writing {@code stdin} to its standard input (used for {@code chpasswd}).
     *
     * @param command the command and arguments (without a leading pkexec)
     * @param stdin   text to feed to the command's stdin, or null for none
     * @return a structured {@link PrivilegedResult}; never null
     */
    public static PrivilegedResult run(List<String> command, String stdin) {
        if (command == null || command.isEmpty()) {
            return new PrivilegedResult(Status.ERROR, "empty command", -1, "");
        }
        if (!isAvailable()) {
            logger.fine("pkexec not available; cannot elevate: " + command.get(0));
            return new PrivilegedResult(Status.UNAVAILABLE,
                    "Administrative privileges are required but pkexec (polkit) is not installed.",
                    -1, "");
        }
        List<String> full = new ArrayList<>(command.size() + 1);
        full.add("pkexec");
        full.addAll(command);

        ProcessRunner.Result r = ProcessRunner.run(full, stdin, TIMEOUT_SECONDS, TimeUnit.SECONDS, null);
        if (!r.isStarted()) {
            return new PrivilegedResult(Status.ERROR, r.getMessage(), -1, r.getStdout());
        }
        int code = r.getExitCode();
        if (code == 0) {
            return new PrivilegedResult(Status.SUCCESS, "", 0, r.getStdout());
        }
        // pkexec exit codes: 126 = the authorization dialog was dismissed,
        // 127 = not authorized / no authentication agent available.
        String err = r.getMessage();
        if (code == 126 || code == 127
                || err.toLowerCase().contains("dismissed")
                || err.toLowerCase().contains("not authorized")
                || err.toLowerCase().contains("authentication agent")) {
            return new PrivilegedResult(Status.CANCELLED,
                    err.isEmpty() ? "Authorization cancelled." : err, code, r.getStdout());
        }
        return new PrivilegedResult(Status.ERROR, err.isEmpty() ? ("exit code " + code) : err,
                code, r.getStdout());
    }

    /** Convenience varargs overload of {@link #run(List)}. */
    public static PrivilegedResult run(String... command) {
        return run(Arrays.asList(command));
    }
}
