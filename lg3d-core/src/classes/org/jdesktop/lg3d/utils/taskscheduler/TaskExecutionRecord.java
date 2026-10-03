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
package org.jdesktop.lg3d.utils.taskscheduler;

/**
 * An immutable record of one execution of a {@link ScheduledTask}, kept as the
 * task's run history so the scheduler is auditable: what ran, when, how long it
 * took, whether it succeeded, and a short, secret-free tail of its output.
 *
 * <p>The output tail is capped at {@value #MAX_OUTPUT_CHARS} characters both to
 * keep the persisted history small and to avoid buffering an unbounded amount of
 * a chatty program's stdout in memory.</p>
 */
public final class TaskExecutionRecord {

    /** The most output characters retained per record. */
    public static final int MAX_OUTPUT_CHARS = 2000;

    /** How a single execution ended. */
    public enum Status {
        /** The program exited with code 0. */
        SUCCESS,
        /** The program exited with a non-zero code. */
        FAILURE,
        /** The run was killed after exceeding the task timeout. */
        TIMED_OUT,
        /** The program could not be started at all (missing executable, I/O). */
        ERROR,
        /** The run was skipped because a previous instance was still running. */
        SKIPPED
    }

    private final long startedAtMillis;
    private final long durationMillis;
    private final Status status;
    private final int exitCode;
    private final String output;

    public TaskExecutionRecord(long startedAtMillis, long durationMillis,
            Status status, int exitCode, String output) {
        this.startedAtMillis = startedAtMillis;
        this.durationMillis = durationMillis;
        this.status = status;
        this.exitCode = exitCode;
        this.output = cap(output);
    }

    private static String cap(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.length() > MAX_OUTPUT_CHARS
                ? s.substring(s.length() - MAX_OUTPUT_CHARS) : s;
        // Strip control characters that would corrupt the encoded prefs value.
        return trimmed.replace('\n', ' ').replace('\r', ' ')
                .replace('|', '/').replace(';', ',');
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public Status getStatus() {
        return status;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /**
     * Serialises to a single {@code |}-delimited field, safe to store in one
     * preferences value (all delimiters are stripped from the output).
     */
    public String encode() {
        return startedAtMillis + "|" + durationMillis + "|" + status.name()
                + "|" + exitCode + "|" + output;
    }

    /** Reverses {@link #encode()}; returns null on a malformed record. */
    public static TaskExecutionRecord decode(String s) {
        if (s == null) {
            return null;
        }
        String[] f = s.split("\\|", 5);
        if (f.length < 4) {
            return null;
        }
        try {
            long start = Long.parseLong(f[0]);
            long dur = Long.parseLong(f[1]);
            Status st = Status.valueOf(f[2]);
            int code = Integer.parseInt(f[3]);
            String out = f.length > 4 ? f[4] : "";
            return new TaskExecutionRecord(start, dur, st, code, out);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return status + " exit=" + exitCode + " @" + startedAtMillis
                + " (" + durationMillis + "ms)";
    }
}
