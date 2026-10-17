/*
 * Pins DeploymentRegistry -- the server's brain -- over the faked docker/jar
 * backends and a temp-dir store: it owns id/name/context-path/port assignment so
 * two deploys can never collide, separates failure kinds (bad input ->
 * IllegalArgumentException, unknown id -> NoSuchElementException, runtime ->
 * DeploymentException), reconciles an app that died outside the API back to
 * STOPPED, persists across registry instances, and degrades cleanly (a deploy
 * with no daemon persists nothing; a start with no daemon records FAILED rather
 * than throwing). No Spring context, no docker daemon and no real fork.
 */
package org.jdesktop.lg3d.webserver.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentRegistryTest {

    @TempDir
    Path tempDir;

    private FakeDockerCli docker;
    private FakeChildProcessStarter starter;
    private WebServerProperties properties;
    private DeploymentRegistry registry;

    @BeforeEach
    void setUp() {
        docker = new FakeDockerCli();
        starter = new FakeChildProcessStarter();
        properties = DeployFixtures.properties();
        registry = DeployFixtures.registry(tempDir, properties, docker, starter);
    }

    private static DeploymentRequest docker(String name, String image) {
        return new DeploymentRequest(name, DeploymentType.DOCKER, image, null, null, null, null);
    }

    private static DeploymentRequest docker(String name, String image, String contextPath, Integer port) {
        return new DeploymentRequest(name, DeploymentType.DOCKER, image, null, contextPath, port, null);
    }

    private static DeploymentRequest jar(String name, String jarPath) {
        return new DeploymentRequest(name, DeploymentType.JAR, null, jarPath, null, null, null);
    }

    private Path realJar(String fileName) throws IOException {
        Path jar = tempDir.resolve(fileName);
        Files.writeString(jar, "not a real jar");
        return jar;
    }

    // ---- id / name / context / port assignment ---------------------------

    @Test
    void deployAssignsIdNameContextPortAndRunning() {
        Deployment d = registry.deploy(docker("Shop", "shop:1.0"));

        assertThat(d.id()).isEqualTo("shop");
        assertThat(d.name()).isEqualTo("Shop");
        assertThat(d.contextPath()).isEqualTo("/shop");
        assertThat(d.port()).isEqualTo(9001);
        assertThat(d.type()).isEqualTo(DeploymentType.DOCKER);
        assertThat(d.image()).isEqualTo("shop:1.0");
        assertThat(d.state()).isEqualTo(AppState.RUNNING);
        assertThat(d.runtimeId()).isEqualTo("abc123containerid");
    }

    @Test
    void deployCarriesEnv() {
        DeploymentRequest request = new DeploymentRequest(
                "Shop", DeploymentType.DOCKER, "shop:1.0", null, null, null, Map.of("K", "V"));
        assertThat(registry.deploy(request).envOrEmpty()).containsEntry("K", "V");
    }

    @Test
    void sequentialDeploysAutoAssignSequentialPorts() {
        assertThat(registry.deploy(docker("a", "a:1")).port()).isEqualTo(9001);
        assertThat(registry.deploy(docker("b", "b:1")).port()).isEqualTo(9002);
    }

    @Test
    void explicitPortIsHonoured() {
        assertThat(registry.deploy(docker("shop", "shop:1.0", null, 9100)).port()).isEqualTo(9100);
    }

    @Test
    void nameDerivedFromImageWhenBlank() {
        Deployment d = registry.deploy(docker(null, "registry.example.com/team/shop:1.0"));
        assertThat(d.name()).isEqualTo("shop");
        assertThat(d.id()).isEqualTo("shop");
        assertThat(d.contextPath()).isEqualTo("/shop");
    }

    @Test
    void nameDerivedFromJarWhenBlank() throws IOException {
        Path jar = realJar("myapp.jar");
        Deployment d = registry.deploy(jar(null, jar.toString()));
        assertThat(d.name()).isEqualTo("myapp");
        assertThat(d.state()).isEqualTo(AppState.RUNNING);
    }

    @Test
    void explicitContextPathIsNormalized() {
        assertThat(registry.deploy(docker("Store", "store:1.0", "store/", null)).contextPath())
                .isEqualTo("/store");
        assertThat(registry.deploy(docker("Cart", "cart:1.0", "cart", null)).contextPath())
                .isEqualTo("/cart");
    }

    @Test
    void idCollisionGetsNumericSuffix() {
        assertThat(registry.deploy(docker("Shop", "shop:1.0", "/a", null)).id()).isEqualTo("shop");
        assertThat(registry.deploy(docker("Shop", "shop:1.0", "/b", null)).id()).isEqualTo("shop-2");
    }

    // ---- validation (400-class) ------------------------------------------

    @Test
    void dockerWithoutImageIsRejected() {
        DeploymentRequest request =
                new DeploymentRequest("shop", DeploymentType.DOCKER, null, null, null, null, null);
        assertThatThrownBy(() -> registry.deploy(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image");
    }

    @Test
    void jarWithoutPathIsRejected() {
        DeploymentRequest request =
                new DeploymentRequest("app", DeploymentType.JAR, null, null, null, null, null);
        assertThatThrownBy(() -> registry.deploy(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jarPath");
    }

    @Test
    void nullTypeIsRejected() {
        DeploymentRequest request =
                new DeploymentRequest("x", null, "x:1", null, null, null, null);
        assertThatThrownBy(() -> registry.deploy(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void outOfRangePortIsRejected() {
        assertThatThrownBy(() -> registry.deploy(docker("lo", "lo:1", null, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> registry.deploy(docker("hi", "hi:1", null, 70000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void duplicateExplicitPortIsRejected() {
        registry.deploy(docker("shop", "shop:1.0", "/shop", 9100));
        assertThatThrownBy(() -> registry.deploy(docker("cart", "cart:1.0", "/cart", 9100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already used");
    }

    @Test
    void duplicateContextPathIsRejected() {
        registry.deploy(docker("Shop", "shop:1.0"));
        assertThatThrownBy(() -> registry.deploy(docker("Shop", "shop:2.0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context path");
    }

    @Test
    void exhaustedPortRangeIsAReadableFailure() {
        properties.getPortRange().setStart(9001);
        properties.getPortRange().setEnd(9001);
        registry.deploy(docker("a", "a:1"));
        assertThatThrownBy(() -> registry.deploy(docker("b", "b:1")))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("no free port");
    }

    // ---- degradation (503-class) -----------------------------------------

    @Test
    void deployWithNoDaemonPersistsNothing() {
        docker.available(false);
        assertThatThrownBy(() -> registry.deploy(docker("shop", "shop:1.0")))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("not available");
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void startWithNoDaemonRecordsFailedWithoutThrowing() {
        registry.deploy(docker("shop", "shop:1.0"));
        registry.stop("shop");
        docker.available(false);

        Deployment result = registry.start("shop");
        assertThat(result.state()).isEqualTo(AppState.FAILED);
        assertThat(result.lastError()).contains("not available");
    }

    @Test
    void startBackendFailureRecordsFailedAndThrows() {
        registry.deploy(docker("shop", "shop:1.0"));
        registry.stop("shop");
        docker.on("start", new CommandResult(1, "", "boom"));
        docker.on("run", new CommandResult(1, "", "boom"));

        assertThatThrownBy(() -> registry.start("shop"))
                .isInstanceOf(DeploymentException.class);
        assertThat(registry.get("shop").state()).isEqualTo(AppState.FAILED);
        assertThat(registry.get("shop").lastError()).contains("boom");
    }

    // ---- lifecycle --------------------------------------------------------

    @Test
    void jarDeployForksAndTracksRunning() throws IOException {
        Path jar = realJar("app.jar");
        starter.toReturn(new FakeChildProcess(4242, true, ""));

        Deployment d = registry.deploy(jar("app", jar.toString()));
        assertThat(d.state()).isEqualTo(AppState.RUNNING);
        assertThat(d.runtimeId()).isEqualTo("4242");
        assertThat(starter.lastCommand()).containsExactly(
                org.jdesktop.lg3d.webserver.deploy.JarProcessBackend.javaExecutable(),
                "-jar", jar.toString(), "--server.port=9001");
    }

    @Test
    void stopMarksStoppedAndStartReusesContainer() {
        registry.deploy(docker("shop", "shop:1.0"));

        registry.stop("shop");
        assertThat(registry.get("shop").state()).isEqualTo(AppState.STOPPED);
        assertThat(docker.lastExecuted()).containsExactly("stop", "shop");

        Deployment restarted = registry.start("shop");
        assertThat(restarted.state()).isEqualTo(AppState.RUNNING);
        assertThat(docker.lastExecuted()).containsExactly("start", "shop");
    }

    @Test
    void undeployRemovesAndForgets() {
        registry.deploy(docker("shop", "shop:1.0"));
        registry.undeploy("shop");

        assertThat(docker.lastExecuted()).containsExactly("rm", "-f", "shop");
        assertThat(registry.list()).isEmpty();
        assertThatThrownBy(() -> registry.get("shop")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void undeployToleratesRemovalFailure() {
        registry.deploy(docker("shop", "shop:1.0"));
        docker.on("rm", new CommandResult(1, "", "already gone"));

        registry.undeploy("shop");
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void logsDelegateToBackend() {
        registry.deploy(docker("shop", "shop:1.0"));
        docker.on("logs", new CommandResult(0, "hello", ""));

        assertThat(registry.logs("shop", 50)).isEqualTo("hello");
        assertThat(docker.lastExecuted()).containsExactly("logs", "--tail", "50", "shop");
    }

    @Test
    void unknownIdIsANotFound() {
        assertThatThrownBy(() -> registry.get("ghost")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> registry.start("ghost")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> registry.stop("ghost")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> registry.logs("ghost", 10)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> registry.undeploy("ghost")).isInstanceOf(NoSuchElementException.class);
    }

    // ---- reconciliation ---------------------------------------------------

    @Test
    void reconcileMarksDeadContainerStopped() {
        registry.deploy(docker("shop", "shop:1.0"));
        docker.on("inspect", new CommandResult(0, "false", ""));

        registry.reconcile();
        assertThat(registry.get("shop").state()).isEqualTo(AppState.STOPPED);
    }

    @Test
    void reconcileMarksDeadChildStopped() throws IOException {
        Path jar = realJar("app.jar");
        FakeChildProcess child = new FakeChildProcess(4242, true, "");
        starter.toReturn(child);
        registry.deploy(jar("app", jar.toString()));

        child.setAlive(false);
        registry.reconcile();
        assertThat(registry.get("app").state()).isEqualTo(AppState.STOPPED);
    }

    @Test
    void lazyListReconcilesWithoutExplicitCall() {
        registry.deploy(docker("shop", "shop:1.0"));
        docker.on("inspect", new CommandResult(0, "false", ""));

        assertThat(registry.list()).allSatisfy(d -> assertThat(d.state()).isEqualTo(AppState.STOPPED));
    }

    @Test
    void proxyRoutesOnlyIncludeRunningApps() {
        registry.deploy(docker("shop", "shop:1.0"));
        registry.deploy(docker("cart", "cart:1.0"));
        registry.stop("cart");

        assertThat(registry.proxyRoutes()).containsExactly(Map.entry("/shop", 9001));
    }

    // ---- persistence ------------------------------------------------------

    @Test
    void deploymentsSurviveANewRegistryInstance() {
        registry.deploy(docker("shop", "shop:1.0"));

        DeploymentRegistry reloaded = DeployFixtures.registry(
                tempDir, DeployFixtures.properties(), new FakeDockerCli(), new FakeChildProcessStarter());
        List<Deployment> restored = reloaded.list();
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).id()).isEqualTo("shop");
    }

    // ---- availability -----------------------------------------------------

    @Test
    void backendAvailabilityReflectsDaemonAndFlag() {
        assertThat(registry.isBackendAvailable(DeploymentType.DOCKER)).isTrue();
        assertThat(registry.isBackendAvailable(DeploymentType.JAR)).isTrue();

        docker.available(false);
        assertThat(registry.isBackendAvailable(DeploymentType.DOCKER)).isFalse();

        docker.available(true);
        properties.setDockerEnabled(false);
        assertThat(registry.isBackendAvailable(DeploymentType.DOCKER)).isFalse();
    }

    // ---- slug helper ------------------------------------------------------

    @Test
    void slugIsDockerNameSafe() {
        assertThat(DeploymentRegistry.slug("My App!")).isEqualTo("my-app");
        assertThat(DeploymentRegistry.slug("Hello World")).isEqualTo("hello-world");
        assertThat(DeploymentRegistry.slug("9lives")).isEqualTo("app-9lives");
        assertThat(DeploymentRegistry.slug("")).isEqualTo("app-");
        assertThat(DeploymentRegistry.slug(null)).isEmpty();
    }
}
