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

    /** @return a registry over the given backends and a temp-dir store, loaded. */
    private DeploymentRegistry registryOver(DeploymentBackend... backends) {
        DeploymentRegistry r = new DeploymentRegistry(
                List.of(backends), DeployFixtures.store(tempDir), properties);
        r.load();
        return r;
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
        assertThat(DeploymentRegistry.slug("--shop--")).isEqualTo("shop");
        assertThat(DeploymentRegistry.slug("a{b")).isEqualTo("a-b");
    }

    // ---- defensive arms, driven by a fault-injecting backend --------------

    @Test
    void deployWithNoBackendForTheTypeIsA503() {
        DeploymentRegistry r = registryOver();
        assertThatThrownBy(() -> r.deploy(docker("shop", "shop:1.0")))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("no backend for type");
    }

    @Test
    void deployRethrowsABackendDeploymentException() {
        StubBackend backend = new StubBackend(DeploymentType.DOCKER);
        backend.onDeploy = new DeploymentException("image pull failed");
        DeploymentRegistry r = registryOver(backend);
        assertThatThrownBy(() -> r.deploy(docker("shop", "shop:1.0")))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("image pull failed");
        assertThat(r.list()).isEmpty();
    }

    @Test
    void deployWrapsABackendRuntimeException() {
        StubBackend backend = new StubBackend(DeploymentType.DOCKER);
        backend.onDeploy = new IllegalStateException("kaboom");
        DeploymentRegistry r = registryOver(backend);
        assertThatThrownBy(() -> r.deploy(docker("shop", "shop:1.0")))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("deploy failed: kaboom");
    }

    @Test
    void startWrapsABackendRuntimeExceptionAndRecordsFailed() {
        StubBackend backend = new StubBackend(DeploymentType.DOCKER);
        DeploymentRegistry r = registryOver(backend);
        r.deploy(docker("shop", "shop:1.0"));
        backend.onStart = new IllegalStateException("nope");
        assertThatThrownBy(() -> r.start("shop"))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("start failed: nope");
        assertThat(r.get("shop").state()).isEqualTo(AppState.FAILED);
    }

    @Test
    void stopRethrowsABackendDeploymentException() {
        StubBackend backend = new StubBackend(DeploymentType.DOCKER);
        DeploymentRegistry r = registryOver(backend);
        r.deploy(docker("shop", "shop:1.0"));
        backend.onStop = new DeploymentException("stop boom");
        assertThatThrownBy(() -> r.stop("shop"))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("stop boom");
        assertThat(r.get("shop").state()).isEqualTo(AppState.FAILED);
    }

    @Test
    void stopWrapsABackendRuntimeException() {
        StubBackend backend = new StubBackend(DeploymentType.DOCKER);
        DeploymentRegistry r = registryOver(backend);
        r.deploy(docker("shop", "shop:1.0"));
        backend.onStop = new IllegalStateException("stop nope");
        assertThatThrownBy(() -> r.stop("shop"))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("stop failed: stop nope");
    }

    @Test
    void undeployToleratesABackendRemoveThatThrows() {
        StubBackend backend = new StubBackend(DeploymentType.DOCKER);
        DeploymentRegistry r = registryOver(backend);
        r.deploy(docker("shop", "shop:1.0"));
        backend.onRemove = new IllegalStateException("rm nope");
        r.undeploy("shop");
        assertThat(r.list()).isEmpty();
    }

    @Test
    void reconcileTreatsAnIsRunningThatThrowsAsNotRunning() {
        StubBackend backend = new StubBackend(DeploymentType.DOCKER);
        DeploymentRegistry r = registryOver(backend);
        r.deploy(docker("shop", "shop:1.0"));
        backend.onIsRunning = new IllegalStateException("inspect boom");
        r.reconcile();
        assertThat(r.get("shop").state()).isEqualTo(AppState.STOPPED);
    }

    @Test
    void getLazilyReconcilesADeploymentThatDiedOutsideTheApi() {
        StubBackend backend = new StubBackend(DeploymentType.DOCKER);
        DeploymentRegistry r = registryOver(backend);
        r.deploy(docker("shop", "shop:1.0"));
        backend.running = false;
        assertThat(r.get("shop").state()).isEqualTo(AppState.STOPPED);
    }

    @Test
    void startWithAnUnavailableJarBackendUsesTheJarMessage() {
        StubBackend backend = new StubBackend(DeploymentType.JAR);
        DeploymentRegistry r = registryOver(backend);
        r.deploy(jar("app", "/tmp/app.jar"));
        backend.available = false;
        Deployment result = r.start("app");
        assertThat(result.state()).isEqualTo(AppState.FAILED);
        assertThat(result.lastError()).contains("no runtime is available to start a JAR");
    }

    @Test
    void startOfADeploymentWhoseBackendIsGoneIsA503() {
        StubBackend backend = new StubBackend(DeploymentType.DOCKER);
        registryOver(backend).deploy(docker("shop", "shop:1.0"));
        // A fresh registry over the same store but with no backends at all: the
        // persisted deployment survives, its runtime does not.
        DeploymentRegistry orphan = new DeploymentRegistry(
                List.of(), DeployFixtures.store(tempDir), properties);
        orphan.load();
        assertThatThrownBy(() -> orphan.start("shop"))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("no backend for type");
    }

    @Test
    void snapshotReturnsTheCurrentTable() {
        DeploymentRegistry r = registryOver(new StubBackend(DeploymentType.DOCKER));
        r.deploy(docker("shop", "shop:1.0"));
        assertThat(r.snapshot()).hasSize(1);
        assertThat(r.snapshot().get(0).id()).isEqualTo("shop");
    }

    // ---- input-derivation and lookup edge arms ----------------------------

    @Test
    void isBackendAvailableIsFalseForAnUnregisteredType() {
        DeploymentRegistry r = registryOver(new StubBackend(DeploymentType.DOCKER));
        assertThat(r.isBackendAvailable(DeploymentType.DOCKER)).isTrue();
        assertThat(r.isBackendAvailable(DeploymentType.JAR)).isFalse();
    }

    @Test
    void getOfADeploymentWhoseBackendIsGoneReconcilesItToStopped() {
        registryOver(new StubBackend(DeploymentType.DOCKER)).deploy(docker("shop", "shop:1.0"));
        DeploymentRegistry orphan = new DeploymentRegistry(
                List.of(), DeployFixtures.store(tempDir), properties);
        orphan.load();
        // No backend for DOCKER, so a recorded RUNNING app cannot be confirmed up.
        assertThat(orphan.get("shop").state()).isEqualTo(AppState.STOPPED);
    }

    @Test
    void nullIdIsANotFound() {
        assertThatThrownBy(() -> registry.get(null)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void nullRequestIsRejected() {
        assertThatThrownBy(() -> registry.deploy(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
    }

    @Test
    void dockerWithABlankImageIsRejected() {
        DeploymentRequest request =
                new DeploymentRequest("shop", DeploymentType.DOCKER, "   ", null, null, null, null);
        assertThatThrownBy(() -> registry.deploy(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image");
    }

    @Test
    void jarWithABlankPathIsRejected() {
        DeploymentRequest request =
                new DeploymentRequest("app", DeploymentType.JAR, null, "   ", null, null, null);
        assertThatThrownBy(() -> registry.deploy(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jarPath");
    }

    @Test
    void blankNameIsDerivedFromTheImage() {
        assertThat(registry.deploy(docker("   ", "shop:1.0")).name()).isEqualTo("shop");
    }

    @Test
    void blankContextPathIsDerivedFromTheName() {
        assertThat(registry.deploy(docker("Shop", "shop:1.0", "   ", null)).contextPath())
                .isEqualTo("/shop");
    }

    @Test
    void rootContextPathIsPreserved() {
        assertThat(registry.deploy(docker("Shop", "shop:1.0", "/", null)).contextPath())
                .isEqualTo("/");
    }

    @Test
    void nameOfAnImageThatReducesToNothingFallsBackToApp() {
        // ".jar" strips its extension down to an empty stem, so the derived name is "app".
        assertThat(registry.deploy(docker(null, ".jar")).name()).isEqualTo("app");
    }

    @Test
    void proxyRoutesSkipsARunningDeploymentWithNoContextPath() {
        // A hand-persisted RUNNING record with no context path: the routing guard
        // must skip it rather than publish a null prefix.
        DeploymentStore store = DeployFixtures.store(tempDir);
        store.save(List.of(new Deployment("ghost", "ghost", DeploymentType.DOCKER, "img:1", null,
                null, 9001, Map.of(), AppState.RUNNING, "cid", 1L, null)));
        DeploymentRegistry r = new DeploymentRegistry(
                List.of(new StubBackend(DeploymentType.DOCKER)), store, properties);
        r.load();
        assertThat(r.proxyRoutes()).isEmpty();
    }

    /**
     * A scriptable {@link DeploymentBackend} that succeeds by default and throws
     * the configured {@link RuntimeException} from any single lifecycle method, so
     * the registry's defensive catch arms can be reached without a docker daemon
     * or a real fork.
     */
    private static final class StubBackend implements DeploymentBackend {
        private final DeploymentType type;
        private boolean available = true;
        private boolean running = true;
        private RuntimeException onDeploy;
        private RuntimeException onStart;
        private RuntimeException onStop;
        private RuntimeException onRemove;
        private RuntimeException onIsRunning;

        StubBackend(DeploymentType type) {
            this.type = type;
        }

        @Override
        public DeploymentType type() {
            return type;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String deploy(Deployment deployment) {
            if (onDeploy != null) {
                throw onDeploy;
            }
            return "stub-runtime";
        }

        @Override
        public String start(Deployment deployment) {
            if (onStart != null) {
                throw onStart;
            }
            return "stub-runtime";
        }

        @Override
        public void stop(Deployment deployment) {
            if (onStop != null) {
                throw onStop;
            }
        }

        @Override
        public String logs(Deployment deployment, int tail) {
            return "stub-logs";
        }

        @Override
        public void remove(Deployment deployment) {
            if (onRemove != null) {
                throw onRemove;
            }
        }

        @Override
        public boolean isRunning(Deployment deployment) {
            if (onIsRunning != null) {
                throw onIsRunning;
            }
            return running;
        }
    }
}
