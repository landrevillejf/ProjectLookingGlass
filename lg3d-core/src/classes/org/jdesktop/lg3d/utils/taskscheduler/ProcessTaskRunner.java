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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DAppRegistry;

/**
 * The production {@link TaskRunner}: it executes a {@link ScheduledTask} and
 * captures the outcome as a {@link TaskExecutionRecord}.
 *
 * <p>Security is the whole point of this class. A {@link ScheduledTask.Action#COMMAND}
 * task is run through {@link ProcessBuilder} with its <em>argv vector passed
 * verbatim</em> - never concatenated into a string and handed to {@code /bin/sh}.
 * Because no shell is involved, a value such as {@code "; rm -rf ~"} is delivered
 * to the program as a single literal argument and can never be re-interpreted as
 * a shell metacharacter. The child's environment is the desktop's own environment
 * plus the task's overlay (with {@code DISPLAY} pointed at the lg3d server so a
 * GUI program opens on the right screen), its working directory is honoured, and
 * the run is bounded by the task timeout: when the wall-clock limit is exceeded
 * the process is {@link Process#destroyForcibly() force-killed} and reported as
 * {@link TaskExecutionRecord.Status#TIMED_OUT}. Captured output is bounded so a
 * chatty program cannot exhaust memory.</p>
 *
 * <p>A {@link ScheduledTask.Action#APP} task instead launches a start-menu
 * application in the desktop JVM via
 * {@link Desktop2DAppRegistry#launchSwingFrame(String)}, exactly as clicking the
 * menu entry would; that launch is asynchronous by nature, so it is reported as a
 * successful dispatch.</p>
 */
public class ProcessTaskRunner implements TaskRunner {

    private static final Logger logger =
            Logger.getLogger("lg.utils.taskscheduler");

    /** Hard ceiling on characters read from a child process, independent of the record cap. */
    private static final int MAX_CAPTURE_CHARS = TaskExecutionRecord.MAX_OUTPUT_CHARS;

    @Override
    public TaskExecutionRecord run(ScheduledTask task) {
        if (task == null) {
            return errorRecord("null task");
        }
        long startedAt = System.currentTimeMillis();
        try {
            if (task.getAction() == ScheduledTask.Action.APP) {
                return runApp(task, startedAt);
            }
            return runCommand(task, startedAt);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Unexpected error running task " + task.getId(), e);
            return record(startedAt, TaskExecutionRecord.Status.ERROR, -1,
                    "runner error: " + e);
        }
    }

    // ------------------------------------------------------------------
    // COMMAND: external process, argv-verbatim (no shell)
    // ------------------------------------------------------------------

    private TaskExecutionRecord runCommand(ScheduledTask task, long startedAt) {
        List<String> argv = task.getArgv();
        if (argv.isEmpty()) {
            return record(startedAt, TaskExecutionRecord.Status.ERROR, -1,
                    "task has no command to run");
        }
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);

        File dir = resolveWorkingDir(task.getWorkingDir());
        if (dir != null) {
            pb.directory(dir);
        }

        Map<String, String> childEnv = pb.environment();
        String display = resolveDisplay();
        if (display != null) {
            childEnv.put("DISPLAY", display);
        }
        for (Map.Entry<String, String> e : task.getEnv().entrySet()) {
            if (e.getKey() != null && !e.getKey().isEmpty()) {
                childEnv.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }

        Process process = null;
        try {
            process = pb.start();
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not start: " + argv, e);
            return record(startedAt, TaskExecutionRecord.Status.ERROR, -1,
                    "could not start: " + e.getMessage());
        }

        // Drain output on a separate thread so a full pipe never deadlocks the
        // process while we are blocked in waitFor().
        StringBuilder out = new StringBuilder();
        Thread drainer = drainAsync(process, out);

        boolean finished;
        try {
            long timeout = task.getTimeoutSeconds();
            if (timeout <= 0) {
                process.waitFor();
                finished = true;
            } else {
                finished = process.waitFor(timeout, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return record(startedAt, TaskExecutionRecord.Status.ERROR, -1,
                    "interrupted while waiting");
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(drainer);
            return record(startedAt, TaskExecutionRecord.Status.TIMED_OUT, -1,
                    tail(out) + " [timed out after " + task.getTimeoutSeconds() + "s]");
        }

        joinQuietly(drainer);
        int exit = process.exitValue();
        TaskExecutionRecord.Status status =
                exit == 0 ? TaskExecutionRecord.Status.SUCCESS
                          : TaskExecutionRecord.Status.FAILURE;
        return record(startedAt, status, exit, tail(out));
    }

    /** Reads the child's merged output into {@code sink}, bounded, off-thread. */
    private static Thread drainAsync(final Process process, final StringBuilder sink) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buf = new char[1024];
                    int n;
                    while ((n = r.read(buf)) > 0) {
                        synchronized (sink) {
                            if (sink.length() < MAX_CAPTURE_CHARS) {
                                sink.append(buf, 0,
                                        Math.min(n, MAX_CAPTURE_CHARS - sink.length()));
                            }
                        }
                    }
                } catch (IOException e) {
                    // Process ended or stream closed; nothing useful to do.
                }
            }
        }, "task-output-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ------------------------------------------------------------------
    // APP: in-JVM start-menu launch
    // ------------------------------------------------------------------

    private TaskExecutionRecord runApp(ScheduledTask task, long startedAt) {
        String command = joinArgv(task.getArgv());
        if (command.isEmpty()) {
            return record(startedAt, TaskExecutionRecord.Status.ERROR, -1,
                    "app task has no command");
        }
        try {
            launchApp(command);
            return record(startedAt, TaskExecutionRecord.Status.SUCCESS, 0,
                    "launched app: " + command);
        } catch (RuntimeException e) {
            return record(startedAt, TaskExecutionRecord.Status.ERROR, -1,
                    "could not launch app: " + e.getMessage());
        }
    }

    /**
     * Dispatches an in-JVM start-menu app. Extracted so a test can override it
     * rather than actually launching desktop applications.
     */
    void launchApp(String command) {
        Desktop2DAppRegistry.launchSwingFrame(command);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String joinArgv(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (String a : argv) {
            if (a == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(a);
        }
        return sb.toString();
    }

    private static File resolveWorkingDir(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File f = new File(path.trim());
        return f.isDirectory() ? f : null;
    }

    private static String resolveDisplay() {
        String d = System.getProperty("lg.lgserverdisplay");
        if (d == null || d.trim().isEmpty()) {
            d = System.getenv("DISPLAY");
        }
        return (d == null || d.trim().isEmpty()) ? null : d;
    }

    private static String tail(StringBuilder sb) {
        synchronized (sb) {
            return sb.toString();
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static TaskExecutionRecord record(long startedAt,
            TaskExecutionRecord.Status status, int exitCode, String output) {
        long dur = System.currentTimeMillis() - startedAt;
        return new TaskExecutionRecord(startedAt, dur, status, exitCode, output);
    }

    private static TaskExecutionRecord errorRecord(String msg) {
        return record(System.currentTimeMillis(), TaskExecutionRecord.Status.ERROR, -1, msg);
    }
}
