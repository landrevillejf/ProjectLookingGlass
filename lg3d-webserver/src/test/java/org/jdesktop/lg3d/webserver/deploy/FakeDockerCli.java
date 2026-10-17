/*
 * A scriptable DockerCli stand-in: records the exact argv of every execute() call
 * and returns canned CommandResults, so the Docker backend and the registry can be
 * proven without a daemon. Defaults make a fresh deploy succeed (run -> a container
 * id) and report running (inspect -> "true"); a test overrides a subcommand with
 * on(...) or flips availability to drive the degradation paths.
 */
package org.jdesktop.lg3d.webserver.deploy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FakeDockerCli implements DockerCli {

    /** Every argv passed to execute(), in order. */
    public final List<List<String>> executed = new ArrayList<>();

    /** How many times isAvailable()/version() were consulted. */
    public int availabilityProbes;

    private final Map<String, CommandResult> bySubcommand = new HashMap<>();
    private boolean available = true;
    private String version = "27.1.1";
    private CommandResult fallback = new CommandResult(0, "", "");

    public FakeDockerCli() {
        bySubcommand.put("run", new CommandResult(0, "abc123containerid", ""));
        bySubcommand.put("inspect", new CommandResult(0, "true", ""));
    }

    public FakeDockerCli available(boolean value) {
        this.available = value;
        return this;
    }

    public FakeDockerCli version(String value) {
        this.version = value;
        return this;
    }

    public FakeDockerCli on(String subcommand, CommandResult result) {
        bySubcommand.put(subcommand, result);
        return this;
    }

    public FakeDockerCli fallback(CommandResult result) {
        this.fallback = result;
        return this;
    }

    public List<String> lastExecuted() {
        return executed.isEmpty() ? null : executed.get(executed.size() - 1);
    }

    @Override
    public boolean isAvailable() {
        availabilityProbes++;
        return available;
    }

    @Override
    public String version() {
        availabilityProbes++;
        return available ? version : null;
    }

    @Override
    public CommandResult execute(List<String> args) {
        executed.add(List.copyOf(args));
        if (args.isEmpty()) {
            return fallback;
        }
        CommandResult canned = bySubcommand.get(args.get(0));
        return canned != null ? canned : fallback;
    }
}
