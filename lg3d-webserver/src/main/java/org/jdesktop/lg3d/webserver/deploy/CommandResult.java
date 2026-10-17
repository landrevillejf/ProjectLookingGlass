/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.webserver.deploy;

/**
 * The outcome of one external command (a {@code docker} invocation).
 *
 * <p>A value object rather than a thrown exception: the Docker seam reports a
 * missing daemon or a non-zero exit as an ordinary result the caller turns into a
 * readable message, so the management API degrades instead of 500-ing. A negative
 * {@code exitCode} is reserved for "the command could not be run at all" (no
 * {@code docker} on the PATH, or a timeout), whose reason is carried in
 * {@code stderr}.</p>
 *
 * @param exitCode the process exit code, or a negative value if it never ran
 * @param stdout   the captured standard output (never null; may be empty)
 * @param stderr   the captured standard error / failure reason (never null)
 */
public record CommandResult(int exitCode, String stdout, String stderr) {

    /** A result for a command that could not be run, carrying the reason. */
    public static CommandResult failure(String reason) {
        return new CommandResult(-1, "", reason == null ? "" : reason);
    }

    /** @return true when the command ran and exited zero. */
    public boolean ok() {
        return exitCode == 0;
    }

    /** @return {@code stdout} trimmed, or an empty string when it is null. */
    public String stdoutTrimmed() {
        return stdout == null ? "" : stdout.trim();
    }
}
