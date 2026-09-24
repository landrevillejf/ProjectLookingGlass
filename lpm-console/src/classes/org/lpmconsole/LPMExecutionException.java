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
package org.lpmconsole;

/**
 * Exception thrown when LPM command execution fails.
 */
public class LPMExecutionException extends Exception {
    private final int exitCode;
    private final String stderr;

    public LPMExecutionException(String message) {
        super(message);
        this.exitCode = -1;
        this.stderr = null;
    }

    public LPMExecutionException(String message, Throwable cause) {
        super(message, cause);
        this.exitCode = -1;
        this.stderr = null;
    }

    public LPMExecutionException(String message, int exitCode, String stderr) {
        super(message);
        this.exitCode = exitCode;
        this.stderr = stderr;
    }

    public LPMExecutionException(String message, Throwable cause, int exitCode, String stderr) {
        super(message, cause);
        this.exitCode = exitCode;
        this.stderr = stderr;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStderr() {
        return stderr;
    }
}
