/*
 * A scriptable ProcessLauncher stand-in: records each argv and the timeout it was
 * given and returns one canned CommandResult. Used to prove ProcessDockerCli
 * prepends "docker" and interprets the version probe, without running a process.
 */
package org.jdesktop.lg3d.webserver.deploy;

import java.util.ArrayList;
import java.util.List;

public final class FakeProcessLauncher implements ProcessLauncher {

    /** Every command passed to launch(), in order. */
    public final List<List<String>> launched = new ArrayList<>();

    /** The timeout each launch() was given, in order. */
    public final List<Long> timeouts = new ArrayList<>();

    private CommandResult result = new CommandResult(0, "ok", "");

    public FakeProcessLauncher result(CommandResult value) {
        this.result = value;
        return this;
    }

    public List<String> lastCommand() {
        return launched.isEmpty() ? null : launched.get(launched.size() - 1);
    }

    @Override
    public CommandResult launch(List<String> command, long timeoutMillis) {
        launched.add(List.copyOf(command));
        timeouts.add(timeoutMillis);
        return result;
    }
}
