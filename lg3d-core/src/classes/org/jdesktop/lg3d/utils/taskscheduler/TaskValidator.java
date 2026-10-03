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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Validates a {@link ScheduledTask} before it is persisted or run, so a bad or
 * hostile definition is rejected at the boundary (the scheduler UI) rather than
 * discovered - or worse, executed - later.
 *
 * <p>Two things are enforced:</p>
 * <ul>
 *   <li><b>Correctness.</b> A task must have a name, a well-formed trigger, and
 *       for a {@link ScheduledTask.Action#COMMAND} a non-empty argv whose program
 *       token is present. A CRON trigger is re-parsed here so a malformed
 *       expression is caught with a clear message.</li>
 *   <li><b>Safety limits.</b> argv length, individual argument length, environment
 *       size, name length, timeout and retry counts are all bounded. These are
 *       resource guards (a task cannot be defined with a million arguments or an
 *       infinite timeout), not shell-injection defences: injection is already
 *       structurally impossible because {@link ProcessTaskRunner} passes argv
 *       verbatim to {@link ProcessBuilder} with no shell in the loop. As a
 *       belt-and-braces measure a NUL byte - the one character that could corrupt
 *       an argv entry - is rejected outright.</li>
 * </ul>
 *
 * <p>{@link #validate} returns the full list of problems (empty means valid) so
 * the UI can show every issue at once; {@link #assertValid} throws on the first
 * problem for callers that just need a gate.</p>
 */
public final class TaskValidator {

    /** Largest number of argv entries accepted for a command task. */
    public static final int MAX_ARGV = 64;
    /** Longest single argv entry (or env key/value) accepted. */
    public static final int MAX_ARG_LENGTH = 4096;
    /** Largest number of environment overlay entries accepted. */
    public static final int MAX_ENV_ENTRIES = 64;
    /** Longest task name accepted. */
    public static final int MAX_NAME_LENGTH = 120;
    /** Longest description accepted. */
    public static final int MAX_DESCRIPTION_LENGTH = 1000;
    /** Largest per-run timeout accepted (one day); 0 means "no timeout". */
    public static final long MAX_TIMEOUT_SECONDS = 86_400L;
    /** Largest retry count accepted. */
    public static final int MAX_RETRY_COUNT = 10;

    private TaskValidator() {
        // utility
    }

    /**
     * Returns every problem found with {@code task}; an empty list means the task
     * is valid and safe to save and run. Never returns null.
     */
    public static List<String> validate(ScheduledTask task) {
        List<String> problems = new ArrayList<String>();
        if (task == null) {
            problems.add("task is null");
            return problems;
        }

        String name = task.getName();
        if (name == null || name.trim().isEmpty()) {
            problems.add("a task name is required");
        } else if (name.length() > MAX_NAME_LENGTH) {
            problems.add("name is longer than " + MAX_NAME_LENGTH + " characters");
        } else if (hasNul(name)) {
            problems.add("name contains a NUL byte");
        }

        String desc = task.getDescription();
        if (desc != null && desc.length() > MAX_DESCRIPTION_LENGTH) {
            problems.add("description is longer than " + MAX_DESCRIPTION_LENGTH + " characters");
        }

        validateTrigger(task.getTrigger(), problems);
        validateAction(task, problems);
        validateEnv(task.getEnv(), problems);
        validateNumbers(task, problems);

        return problems;
    }

    /**
     * Throws {@link IllegalArgumentException} describing the first problem with
     * {@code task}, or returns quietly when it is valid.
     */
    public static void assertValid(ScheduledTask task) {
        List<String> problems = validate(task);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
    }

    /** True when {@link #validate} reports no problems. */
    public static boolean isValid(ScheduledTask task) {
        return validate(task).isEmpty();
    }

    private static void validateTrigger(Trigger trigger, List<String> problems) {
        if (trigger == null) {
            problems.add("a schedule (trigger) is required");
            return;
        }
        switch (trigger.getKind()) {
            case CRON: {
                String expr = trigger.getCron();
                if (expr == null || expr.trim().isEmpty()) {
                    problems.add("cron trigger has no expression");
                    return;
                }
                try {
                    CronExpression.parse(expr);
                } catch (IllegalArgumentException e) {
                    problems.add("invalid cron expression: " + e.getMessage());
                }
                break;
            }
            case INTERVAL:
                if (trigger.getPeriodMillis() <= 0) {
                    problems.add("interval period must be positive");
                }
                break;
            case ONCE:
                if (trigger.getAtEpochMillis() <= 0) {
                    problems.add("a one-time task needs a scheduled instant");
                }
                break;
            default:
                problems.add("unknown trigger kind");
        }
    }

    private static void validateAction(ScheduledTask task, List<String> problems) {
        if (task.getAction() == ScheduledTask.Action.APP) {
            // An APP task carries the start-menu command line in argv[0..].
            if (task.getArgv().isEmpty() || isBlank(task.getArgv().get(0))) {
                problems.add("an app task needs the application command");
            }
            return;
        }
        List<String> argv = task.getArgv();
        if (argv.isEmpty()) {
            problems.add("a command task needs at least the program to run");
            return;
        }
        if (isBlank(argv.get(0))) {
            problems.add("the program (first argument) must not be blank");
        }
        if (argv.size() > MAX_ARGV) {
            problems.add("too many arguments (" + argv.size() + " > " + MAX_ARGV + ")");
        }
        for (String a : argv) {
            if (a == null) {
                problems.add("an argument is null");
                continue;
            }
            if (a.length() > MAX_ARG_LENGTH) {
                problems.add("an argument is longer than " + MAX_ARG_LENGTH + " characters");
            }
            if (hasNul(a)) {
                problems.add("an argument contains a NUL byte");
            }
        }
        String wd = task.getWorkingDir();
        if (wd != null && wd.length() > MAX_ARG_LENGTH) {
            problems.add("working directory path is too long");
        } else if (hasNul(wd)) {
            problems.add("working directory contains a NUL byte");
        }
    }

    private static void validateEnv(Map<String, String> env, List<String> problems) {
        if (env == null || env.isEmpty()) {
            return;
        }
        if (env.size() > MAX_ENV_ENTRIES) {
            problems.add("too many environment entries (" + env.size() + " > " + MAX_ENV_ENTRIES + ")");
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || k.trim().isEmpty()) {
                problems.add("an environment variable name is blank");
                continue;
            }
            if (k.length() > MAX_ARG_LENGTH || (v != null && v.length() > MAX_ARG_LENGTH)) {
                problems.add("environment entry \"" + k + "\" is too long");
            }
            if (hasNul(k) || hasNul(v)) {
                problems.add("environment entry \"" + k + "\" contains a NUL byte");
            }
            if (k.indexOf('=') >= 0) {
                problems.add("environment variable name \"" + k + "\" must not contain '='");
            }
        }
    }

    private static void validateNumbers(ScheduledTask task, List<String> problems) {
        long timeout = task.getTimeoutSeconds();
        if (timeout < 0 || timeout > MAX_TIMEOUT_SECONDS) {
            problems.add("timeout must be between 0 and " + MAX_TIMEOUT_SECONDS + " seconds");
        }
        if (task.getRetryCount() < 0 || task.getRetryCount() > MAX_RETRY_COUNT) {
            problems.add("retry count must be between 0 and " + MAX_RETRY_COUNT);
        }
        if (task.getRetryDelayMillis() < 0) {
            problems.add("retry delay must not be negative");
        }
    }

    private static boolean hasNul(String s) {
        return s != null && s.indexOf('\0') >= 0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** An unmodifiable view of a task's problems, handy for logging. */
    public static List<String> problemsOf(ScheduledTask task) {
        return Collections.unmodifiableList(validate(task));
    }
}
