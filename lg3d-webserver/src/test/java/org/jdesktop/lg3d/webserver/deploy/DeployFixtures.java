/*
 * Wires a fully-faked deployment stack (registry + docker backend + jar backend +
 * temp-dir store) behind one public factory, so tests in ANY package -- including
 * the web-layer controller test -- can build a deterministic registry without a
 * Spring context, a docker daemon or a real fork. It can reach the package-private
 * test constructors because it lives in the deploy package, and it calls the
 * registry's @PostConstruct load() explicitly (Spring would otherwise do it).
 */
package org.jdesktop.lg3d.webserver.deploy;

import java.nio.file.Path;
import java.util.List;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;

public final class DeployFixtures {

    private DeployFixtures() {
    }

    /** @return properties with the defaults a test wants (localhost, docker on). */
    public static WebServerProperties properties() {
        WebServerProperties properties = new WebServerProperties();
        properties.setPublicHost("localhost");
        return properties;
    }

    /** @return a Docker backend over the given fake CLI. */
    public static DockerDeploymentBackend dockerBackend(FakeDockerCli docker,
                                                        WebServerProperties properties) {
        return new DockerDeploymentBackend(docker, properties);
    }

    /** @return a JAR backend over the given fake starter. */
    public static JarProcessBackend jarBackend(FakeChildProcessStarter starter) {
        return new JarProcessBackend(starter);
    }

    /** @return a store pinned to an explicit directory. */
    public static DeploymentStore store(Path stateDir) {
        return new DeploymentStore(stateDir);
    }

    /**
     * Builds a registry over both fakes and a temp-dir store, and runs its
     * {@code @PostConstruct} load so persisted state is restored just as Spring
     * would.
     */
    public static DeploymentRegistry registry(Path stateDir,
                                              WebServerProperties properties,
                                              FakeDockerCli docker,
                                              FakeChildProcessStarter starter) {
        DeploymentRegistry registry = new DeploymentRegistry(
                List.of(dockerBackend(docker, properties), jarBackend(starter)),
                store(stateDir),
                properties);
        registry.load();
        return registry;
    }
}
