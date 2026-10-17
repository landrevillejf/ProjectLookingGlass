/*
 * Pins DockerDeploymentBackend over a FakeDockerCli: the exact argv of every
 * lifecycle command (so a wrong flag is caught), stdout parsing (container id,
 * inspect state), the start->run fallback for a pruned container, and the
 * never-throw/never-500 degradation -- a missing daemon, a disabled backend, a
 * failed pull or a missing container all become a readable DeploymentException or
 * a benign empty result, not a stack trace.
 */
package org.jdesktop.lg3d.webserver.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.junit.jupiter.api.Test;

class DockerDeploymentBackendTest {

    private final WebServerProperties properties = DeployFixtures.properties();

    private static Deployment docker(String id, int port, Map<String, String> env) {
        return new Deployment(id, id, DeploymentType.DOCKER, "shop:1.0", null,
                "/" + id, port, env, AppState.STOPPED, null, 1L, null);
    }

    private static Deployment noImage() {
        return new Deployment("x", "x", DeploymentType.DOCKER, null, null,
                "/x", 9001, Map.of(), AppState.STOPPED, null, 1L, null);
    }

    @Test
    void typeIsDocker() {
        assertThat(new DockerDeploymentBackend(new FakeDockerCli(), properties).type())
                .isEqualTo(DeploymentType.DOCKER);
    }

    @Test
    void deployBuildsExactRunArgv() {
        FakeDockerCli cli = new FakeDockerCli();
        String runtime = new DockerDeploymentBackend(cli, properties).deploy(docker("shop", 9001, Map.of()));

        assertThat(runtime).isEqualTo("abc123containerid");
        assertThat(cli.lastExecuted()).containsExactly(
                "run", "-d", "--name", "shop", "-p", "9001:9001",
                "-e", "SERVER_PORT=9001", "shop:1.0");
    }

    @Test
    void deployAppendsEnvAfterServerPortInOrder() {
        FakeDockerCli cli = new FakeDockerCli();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FOO", "bar");
        env.put("BAZ", "qux");

        new DockerDeploymentBackend(cli, properties).deploy(docker("shop", 9001, env));

        assertThat(cli.lastExecuted()).containsExactly(
                "run", "-d", "--name", "shop", "-p", "9001:9001", "-e", "SERVER_PORT=9001",
                "-e", "FOO=bar", "-e", "BAZ=qux", "shop:1.0");
    }

    @Test
    void deployReturnsContainerIdOrFallsBackToName() {
        FakeDockerCli withId = new FakeDockerCli().on("run", new CommandResult(0, "  cid999 \n", ""));
        assertThat(new DockerDeploymentBackend(withId, properties).deploy(docker("shop", 9001, Map.of())))
                .isEqualTo("cid999");

        FakeDockerCli blank = new FakeDockerCli().on("run", new CommandResult(0, "   ", ""));
        assertThat(new DockerDeploymentBackend(blank, properties).deploy(docker("shop", 9001, Map.of())))
                .isEqualTo("shop");
    }

    @Test
    void deployWithoutImageThrowsReadable() {
        DockerDeploymentBackend backend = new DockerDeploymentBackend(new FakeDockerCli(), properties);
        assertThatThrownBy(() -> backend.deploy(noImage()))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("image");
    }

    @Test
    void deployRefusedWhenDaemonUnavailable() {
        FakeDockerCli cli = new FakeDockerCli().available(false);
        DockerDeploymentBackend backend = new DockerDeploymentBackend(cli, properties);

        assertThat(backend.isAvailable()).isFalse();
        assertThatThrownBy(() -> backend.deploy(docker("shop", 9001, Map.of())))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void deployRefusedWhenDockerDisabledWithoutProbingDaemon() {
        properties.setDockerEnabled(false);
        FakeDockerCli cli = new FakeDockerCli();
        DockerDeploymentBackend backend = new DockerDeploymentBackend(cli, properties);

        assertThat(backend.isAvailable()).isFalse();
        assertThat(cli.availabilityProbes).isZero();
        assertThatThrownBy(() -> backend.deploy(docker("shop", 9001, Map.of())))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void deployFailureCarriesStderr() {
        FakeDockerCli cli = new FakeDockerCli()
                .on("run", new CommandResult(1, "", "Error: image 'shop:1.0' not found"));
        DockerDeploymentBackend backend = new DockerDeploymentBackend(cli, properties);

        assertThatThrownBy(() -> backend.deploy(docker("shop", 9001, Map.of())))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void startReusesContainer() {
        FakeDockerCli cli = new FakeDockerCli().on("start", new CommandResult(0, "shop", ""));
        assertThat(new DockerDeploymentBackend(cli, properties).start(docker("shop", 9001, Map.of())))
                .isEqualTo("shop");
        assertThat(cli.lastExecuted()).containsExactly("start", "shop");
    }

    @Test
    void startFallsBackToRunWhenContainerPruned() {
        FakeDockerCli cli = new FakeDockerCli().on("start", new CommandResult(1, "", "No such container"));
        assertThat(new DockerDeploymentBackend(cli, properties).start(docker("shop", 9001, Map.of())))
                .isEqualTo("abc123containerid");
        assertThat(cli.lastExecuted()).containsExactly(
                "run", "-d", "--name", "shop", "-p", "9001:9001",
                "-e", "SERVER_PORT=9001", "shop:1.0");
    }

    @Test
    void startWithoutImagePropagatesFailure() {
        FakeDockerCli cli = new FakeDockerCli().on("start", new CommandResult(1, "", "No such container"));
        assertThatThrownBy(() -> new DockerDeploymentBackend(cli, properties).start(noImage()))
                .isInstanceOf(DeploymentException.class);
    }

    @Test
    void stopBuildsArgvAndPropagatesFailure() {
        FakeDockerCli ok = new FakeDockerCli().on("stop", new CommandResult(0, "shop", ""));
        new DockerDeploymentBackend(ok, properties).stop(docker("shop", 9001, Map.of()));
        assertThat(ok.lastExecuted()).containsExactly("stop", "shop");

        FakeDockerCli bad = new FakeDockerCli().on("stop", new CommandResult(1, "", "timed out"));
        assertThatThrownBy(() -> new DockerDeploymentBackend(bad, properties).stop(docker("shop", 9001, Map.of())))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void logsTailsAndNeverThrows() {
        FakeDockerCli cli = new FakeDockerCli().on("logs", new CommandResult(0, "line1\nline2\n", ""));
        DockerDeploymentBackend backend = new DockerDeploymentBackend(cli, properties);

        assertThat(backend.logs(docker("shop", 9001, Map.of()), 50)).isEqualTo("line1\nline2\n");
        assertThat(cli.lastExecuted()).containsExactly("logs", "--tail", "50", "shop");

        backend.logs(docker("shop", 9001, Map.of()), 0);
        assertThat(cli.lastExecuted()).containsExactly("logs", "shop");

        FakeDockerCli bad = new FakeDockerCli().on("logs", new CommandResult(1, "", "No such container"));
        assertThat(new DockerDeploymentBackend(bad, properties).logs(docker("shop", 9001, Map.of()), 10))
                .isEqualTo("No such container");
    }

    @Test
    void removeForceRemovesBestEffort() {
        FakeDockerCli cli = new FakeDockerCli().on("rm", new CommandResult(1, "", "No such container"));
        new DockerDeploymentBackend(cli, properties).remove(docker("shop", 9001, Map.of()));
        assertThat(cli.lastExecuted()).containsExactly("rm", "-f", "shop");
    }

    @Test
    void isRunningParsesInspectState() {
        FakeDockerCli up = new FakeDockerCli().on("inspect", new CommandResult(0, "true\n", ""));
        assertThat(new DockerDeploymentBackend(up, properties).isRunning(docker("shop", 9001, Map.of()))).isTrue();
        assertThat(up.lastExecuted()).containsExactly("inspect", "-f", "{{.State.Running}}", "shop");

        FakeDockerCli down = new FakeDockerCli().on("inspect", new CommandResult(0, "false", ""));
        assertThat(new DockerDeploymentBackend(down, properties).isRunning(docker("shop", 9001, Map.of()))).isFalse();

        FakeDockerCli gone = new FakeDockerCli().on("inspect", new CommandResult(1, "", "No such container"));
        assertThat(new DockerDeploymentBackend(gone, properties).isRunning(docker("shop", 9001, Map.of()))).isFalse();
    }
}
