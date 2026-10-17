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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The production {@link ProcessLauncher}: a {@link ProcessBuilder} with a bounded
 * wait and concurrent output gobblers.
 *
 * <p>Both streams are drained on their own daemon threads while the process runs,
 * so a command that writes more than the OS pipe buffer (a long {@code docker
 * logs}) cannot deadlock against the wait. A process that outlives
 * {@code timeoutMillis} is destroyed forcibly and reported as a failure; a missing
 * executable surfaces as a failure carrying the {@code IOException} message rather
 * than throwing -- which is exactly how "no Docker installed" degrades to
 * {@code dockerAvailable=false}.</p>
 */
public final class RealProcessLauncher implements ProcessLauncher {

    @Override
    public CommandResult launch(List<String> command, long timeoutMillis) {
        if (command == null || command.isEmpty()) {
            return CommandResult.failure("no command given");
        }
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread outGobbler = gobble(process.getInputStream(), out);
            Thread errGobbler = gobble(process.getErrorStream(), err);

            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                joinQuietly(outGobbler);
                joinQuietly(errGobbler);
                return CommandResult.failure(
                        "timed out after " + timeoutMillis + "ms: " + command.get(0));
            }
            joinQuietly(outGobbler);
            joinQuietly(errGobbler);
            return new CommandResult(process.exitValue(), out.toString(), err.toString());
        } catch (IOException e) {
            return CommandResult.failure(
                    "could not run " + command.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return CommandResult.failure("interrupted running " + command.get(0));
        }
    }

    private static Thread gobble(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // the process ended and closed the stream; whatever was read stands
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
