/*
 * Pins ProcessDockerCli over a fake ProcessLauncher: it prepends "docker" to the
 * argv, applies the bounded timeout, and interprets the version probe so a missing
 * daemon (a failed probe) reads as unavailable with a null version rather than
 * throwing -- the graceful-degradation seam the whole server rests on.
 */
package org.jdesktop.lg3d.webserver.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessDockerCliTest {

    @Test
    void executePrependsDockerAndAppliesTimeout() {
        FakeProcessLauncher launcher =
                new FakeProcessLauncher().result(new CommandResult(0, "ok", ""));
        ProcessDockerCli cli = new ProcessDockerCli(launcher);

        CommandResult result = cli.execute(List.of("ps", "-q"));

        assertThat(result.ok()).isTrue();
        assertThat(launcher.lastCommand()).containsExactly("docker", "ps", "-q");
        assertThat(launcher.timeouts).containsExactly(ProcessDockerCli.TIMEOUT_MS);
    }

    @Test
    void availableAndVersionReadTheServerProbe() {
        FakeProcessLauncher launcher =
                new FakeProcessLauncher().result(new CommandResult(0, "24.0.5\n", ""));
        ProcessDockerCli cli = new ProcessDockerCli(launcher);

        assertThat(cli.isAvailable()).isTrue();
        assertThat(cli.version()).isEqualTo("24.0.5");
        assertThat(launcher.lastCommand())
                .containsExactly("docker", "version", "--format", "{{.Server.Version}}");
    }

    @Test
    void unavailableWhenProbeFails() {
        FakeProcessLauncher launcher =
                new FakeProcessLauncher().result(CommandResult.failure("could not run docker"));
        ProcessDockerCli cli = new ProcessDockerCli(launcher);

        assertThat(cli.isAvailable()).isFalse();
        assertThat(cli.version()).isNull();
    }

    @Test
    void productionConstructorDoesNotTouchDocker() {
        assertThat(new ProcessDockerCli()).isNotNull();
    }
}
