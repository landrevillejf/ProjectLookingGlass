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

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The production {@link DockerCli}: prepends {@code docker} and delegates to a
 * {@link ProcessLauncher}.
 *
 * <p>The daemon probe is {@code docker version --format {{.Server.Version}}},
 * which exits non-zero both when the CLI is absent and when it is present but the
 * daemon is down -- so {@link #isAvailable()} is false in both cases and the
 * server degrades the same way. A generous timeout keeps a hung daemon from
 * stalling a management request forever.</p>
 */
@Component
public class ProcessDockerCli implements DockerCli {

    /** How long a single docker command may run before it is destroyed. */
    static final long TIMEOUT_MS = 20_000L;

    private static final List<String> VERSION_PROBE =
            List.of("version", "--format", "{{.Server.Version}}");

    private final ProcessLauncher launcher;

    /** Production constructor: runs real {@code docker} commands. */
    public ProcessDockerCli() {
        this(new RealProcessLauncher());
    }

    /** Test constructor: injects a fake launcher. */
    ProcessDockerCli(ProcessLauncher launcher) {
        this.launcher = launcher;
    }

    @Override
    public CommandResult execute(List<String> args) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add("docker");
        command.addAll(args);
        return launcher.launch(command, TIMEOUT_MS);
    }

    @Override
    public boolean isAvailable() {
        return execute(VERSION_PROBE).ok();
    }

    @Override
    public String version() {
        CommandResult result = execute(VERSION_PROBE);
        return result.ok() ? result.stdoutTrimmed() : null;
    }
}
