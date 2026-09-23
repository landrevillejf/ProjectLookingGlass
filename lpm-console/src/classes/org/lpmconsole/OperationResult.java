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
