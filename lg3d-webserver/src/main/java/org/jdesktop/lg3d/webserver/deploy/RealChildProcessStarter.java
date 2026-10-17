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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The production {@link ChildProcessStarter}: a {@link ProcessBuilder} whose child
 * is drained and supervised by a {@link RealChildProcess}.
 *
 * <p>stdout and stderr are merged ({@code redirectErrorStream}) and read on a
 * daemon thread into a bounded line window, so a chatty Spring Boot child can
 * neither fill the pipe and deadlock nor grow the heap without limit. Stopping
 * sends {@code SIGTERM} first and only escalates to a forcible kill after a short
 * grace period, giving the child a chance to shut down cleanly.</p>
 */
public final class RealChildProcessStarter implements ChildProcessStarter {

    @Override
    public ChildProcess start(List<String> command) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IOException("no command given");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        return new RealChildProcess(builder.start());
    }

    /** A {@link ChildProcess} backed by a real {@link Process}. */
    private static final class RealChildProcess implements ChildProcess {

        /** How many trailing output lines are retained for {@link #output()}. */
        private static final int MAX_LINES = 2000;

        /** How long a {@code SIGTERM} is given before a forcible kill. */
        private static final long GRACE_MILLIS = 3000L;

        private final Process process;
        private final ArrayDeque<String> lines = new ArrayDeque<>();

        RealChildProcess(Process process) {
            this.process = process;
            Thread gobbler = new Thread(this::drain, "webserver-child-" + process.pid());
            gobbler.setDaemon(true);
            gobbler.start();
        }

        private void drain() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (lines) {
                        if (lines.size() >= MAX_LINES) {
                            lines.pollFirst();
                        }
                        lines.addLast(line);
                    }
                }
            } catch (IOException ignored) {
                // the child exited and closed the stream; whatever was read stands
            }
        }

        @Override
        public long pid() {
            return process.pid();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void stop() {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        @Override
        public String output() {
            synchronized (lines) {
                return String.join("\n", lines);
            }
        }
    }
}
