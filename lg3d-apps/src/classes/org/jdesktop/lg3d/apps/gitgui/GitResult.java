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
package org.jdesktop.lg3d.apps.gitgui;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.utils.system.ProcessRunner;

/**
 * The immutable outcome of running one {@code git} invocation: whether the
 * process actually started, its exit code, and its captured stdout / stderr.
 *
 * <p>This is the AWT-free value type the whole Git GUI backend speaks in. It
 * deliberately distinguishes "could not even start" (git missing from
 * {@code PATH}) from "started and returned a non-zero exit code" (a real git
 * error such as a merge conflict or a rejected push), so the panel can surface
 * honest guidance instead of a fake success.</p>
 */
public final class GitResult {

    /** A result standing in for "git is not installed / not started". */
    public static final GitResult NOT_STARTED =
            new GitResult(false, -1, "", "git is not available");

    private final boolean started;
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    /**
     * @param started  true when the git process was actually launched
     * @param exitCode the process exit code ({@code -1} when it never ran)
     * @param stdout   captured standard output (never null)
     * @param stderr   captured standard error (never null)
     */
    public GitResult(boolean started, int exitCode, String stdout, String stderr) {
        this.started = started;
        this.exitCode = exitCode;
        this.stdout = (stdout == null) ? "" : stdout;
        this.stderr = (stderr == null) ? "" : stderr;
    }

    /** Adapts a {@link ProcessRunner.Result} into a {@link GitResult}. */
    public static GitResult of(ProcessRunner.Result result) {
        if (result == null) {
            return NOT_STARTED;
        }
        return new GitResult(result.isStarted(), result.getExitCode(),
                result.getStdout(), result.getStderr());
    }

    /** True when the git process was launched and exited with code 0. */
    public boolean isSuccess() {
        return started && exitCode == 0;
    }

    /** True when the git executable was found and the process started. */
    public boolean isStarted() {
        return started;
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

    /** stdout split into trimmed, non-empty lines. */
    public List<String> stdoutLines() {
        return splitLines(stdout);
    }

    /**
     * The most useful single-line description of the outcome: git's own stderr
     * when it complained, else stdout, else a synthesised exit-code note.
     *
     * @return a human-readable message, never null / blank
     */
    public String getMessage() {
        if (!started) {
            return stderr.isBlank() ? "git is not available" : stderr.trim();
        }
        if (!stderr.isBlank()) {
            return firstLine(stderr.trim());
        }
        if (!stdout.isBlank()) {
            return firstLine(stdout.trim());
        }
        return (exitCode == 0) ? "OK" : ("git exited with code " + exitCode);
    }

    /** Splits text into trimmed, non-empty lines. */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }
        return lines;
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return (nl >= 0) ? text.substring(0, nl).trim() : text;
    }

    @Override
    public String toString() {
        return "GitResult[started=" + started + ", exit=" + exitCode + "]";
    }
}
