/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A minimal {@link ProcessBuilder} helper for running external commands and
 * capturing their output.
 *
 * <p>The Linux system backends in this package ({@link DisplayService},
 * {@link Opener}, {@link PrivilegedRunner}, {@link ThermalService}, ...) shell
 * out to standard desktop tools (xrandr, xdg-open, gio, pkexec, sensors,
 * getconf). This class centralises that plumbing so callers never touch raw
 * streams.</p>
 *
 * <p>Everything is best-effort: a missing executable, a non-zero exit or a
 * timeout is reported through the returned {@link Result} instead of being
 * thrown, so the desktop degrades gracefully when a tool is not installed
 * (for example, xrandr is useless under a Wayland compositor).</p>
 *
 * <p>No JNI/JNA and no external libraries are used; this is pure JDK.</p>
 */
public final class ProcessRunner {
    private static final Logger logger = Logger.getLogger("lg.system");

    /** Cached results of {@link #isAvailable(String)} lookups. */
    private static final Map<String, Boolean> AVAILABILITY = new ConcurrentHashMap<>();

    private ProcessRunner() {
        // no instances
    }

    /**
     * The outcome of running a command. Distinguishes "could not even start"
     * (executable missing) from "started and returned a non-zero exit code".
     */
    public static final class Result {
        private final boolean started;
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        Result(boolean started, int exitCode, String stdout, String stderr) {
            this.started = started;
            this.exitCode = exitCode;
            this.stdout = (stdout == null) ? "" : stdout;
            this.stderr = (stderr == null) ? "" : stderr;
        }

        /** True if the process was actually started (the executable was found). */
        public boolean isStarted() {
            return started;
        }

        /** True if the process started and exited with code 0. */
        public boolean isSuccess() {
            return started && exitCode == 0;
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
        public List<String> getStdoutLines() {
            return splitLines(stdout);
        }

        /** stderr split into trimmed, non-empty lines. */
        public List<String> getStderrLines() {
            return splitLines(stderr);
        }

        /** The first of stderr/stdout that is non-blank, else "". */
        public String getMessage() {
            if (!stderr.isBlank()) {
                return stderr.trim();
            }
            if (!stdout.isBlank()) {
                return stdout.trim();
            }
            return "";
        }

        @Override
        public String toString() {
            return "Result[started=" + started + ", exit=" + exitCode + "]";
        }
    }

    /** Splits text into trimmed, non-empty lines. */
    public static List<String> splitLines(String text) {
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

    /**
     * Returns true if the given executable can be found on {@code PATH} and is
     * runnable. Results are cached.
     */
    public static boolean isAvailable(String executable) {
        if (executable == null || executable.isEmpty()) {
            return false;
        }
        Boolean cached = AVAILABILITY.get(executable);
        if (cached != null) {
            return cached;
        }
        boolean found = false;
        if (executable.indexOf(File.separatorChar) >= 0) {
            File f = new File(executable);
            found = f.isFile() && f.canExecute();
        } else {
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(File.pathSeparator)) {
                    if (dir.isEmpty()) {
                        continue;
                    }
                    File f = new File(dir, executable);
                    if (f.isFile() && f.canExecute()) {
                        found = true;
                        break;
                    }
                }
            }
        }
        AVAILABILITY.put(executable, found);
        return found;
    }

    /** Runs a command with a default 10 second timeout. */
    public static Result run(String... command) {
        return run(Arrays.asList(command), 10, TimeUnit.SECONDS, null);
    }

    /** Runs a command with a default 10 second timeout. */
    public static Result run(List<String> command) {
        return run(command, 10, TimeUnit.SECONDS, null);
    }

    /**
     * Runs a command, capturing stdout and stderr on separate gobbler threads
     * (so a full pipe can never deadlock the child) and enforcing a timeout.
     *
     * @param command    the command and arguments
     * @param timeout    how long to wait for the process to exit
     * @param unit       the timeout unit
     * @param workingDir optional working directory (null = inherit)
     * @return the captured {@link Result}; never null
     */
    public static Result run(List<String> command, long timeout, TimeUnit unit, File workingDir) {
        return run(command, null, timeout, unit, workingDir);
    }

    /**
     * Runs a command, optionally writing {@code stdin} to the child's standard
     * input, capturing stdout and stderr and enforcing a timeout. Used by
     * {@link UserService} to feed {@code chpasswd}.
     *
     * @param command    the command and arguments
     * @param stdin      text to write to the child's stdin, or null for none
     * @param timeout    how long to wait for the process to exit
     * @param unit       the timeout unit
     * @param workingDir optional working directory (null = inherit)
     * @return the captured {@link Result}; never null
     */
    public static Result run(List<String> command, String stdin, long timeout, TimeUnit unit, File workingDir) {
        if (command == null || command.isEmpty()) {
            return new Result(false, -1, "", "empty command");
        }
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workingDir != null) {
                pb.directory(workingDir);
            }
            // Make sure GUI-triggering tools (xdg-open, pkexec's agent) reach the
            // same X display the desktop is running on.
            Map<String, String> env = pb.environment();
            String display = System.getProperty("lg.lgserverdisplay");
            if (display == null || display.isEmpty()) {
                display = System.getenv("DISPLAY");
            }
            if (display != null && !display.isEmpty()) {
                env.put("DISPLAY", display);
            }
            process = pb.start();
        } catch (IOException | SecurityException e) {
            // Executable not found, not executable, or blocked by a security
            // manager. Treat as "not started" rather than an exception.
            logger.log(Level.FINE, "Could not start command: " + command.get(0), e);
            return new Result(false, -1, "", String.valueOf(e.getMessage()));
        }

        // Feed stdin (if any) and close it so the child never blocks reading.
        try {
            if (stdin != null) {
                process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush();
            }
            process.getOutputStream().close();
        } catch (IOException e) {
            logger.log(Level.FINE, "Could not write stdin for " + command.get(0), e);
        }

        StreamGobbler out = new StreamGobbler(process.getInputStream());
        StreamGobbler err = new StreamGobbler(process.getErrorStream());
        out.start();
        err.start();

        try {
            boolean finished = process.waitFor(timeout, unit);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                out.join(500);
                err.join(500);
                logger.log(Level.FINE, "Command timed out: {0}", command.get(0));
                return new Result(true, -1, out.getContents(), "timed out after " + timeout + " " + unit);
            }
            out.join(2000);
            err.join(2000);
            return new Result(true, process.exitValue(), out.getContents(), err.getContents());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(true, -1, out.getContents(), "interrupted");
        }
    }

    /** Runs a command via {@code /bin/sh -c} (for shell features like pipes). */
    public static Result runShell(String script, long timeout, TimeUnit unit) {
        return run(Arrays.asList("/bin/sh", "-c", script), timeout, unit, null);
    }

    /** Reads all bytes from a stream on its own thread. */
    private static final class StreamGobbler extends Thread {
        private final InputStream in;
        private final StringBuilder sb = new StringBuilder();

        StreamGobbler(InputStream in) {
            this.in = in;
            setDaemon(true);
            setName("ProcessRunner-gobbler");
        }

        @Override
        public void run() {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } catch (IOException e) {
                // Stream closed when the process exits; nothing to do.
            }
        }

        synchronized String getContents() {
            return sb.toString();
        }
    }
}
