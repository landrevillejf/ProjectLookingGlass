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

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an LPM command execution.
 */
public class OperationResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final boolean success;
    private final List<String> outputLines;

    public OperationResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.success = (exitCode == 0);
        this.outputLines = parseOutput(stdout);
    }

    private List<String> parseOutput(String output) {
        List<String> lines = new ArrayList<>();
        if (output != null && !output.isEmpty()) {
            for (String line : output.split("\n")) {
                lines.add(line);
            }
        }
        return lines;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public boolean isSuccess() {
        return success;
    }

    public List<String> getOutputLines() {
        return outputLines;
    }

    public boolean isIntegrityCheck() {
        // verify returns non-zero when files are modified/missing
        // This is a result state, not a failure
        return stderr != null && stderr.contains("integrity") || 
               stdout != null && stdout.contains("modified");
    }
}
