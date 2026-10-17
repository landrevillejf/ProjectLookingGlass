/*
 * Pins JarProcessBackend over a FakeChildProcessStarter: it forks `java -jar <jar>
 * --server.port=<port>`, tracks the child by deployment id, reports liveness, tails
 * the buffered output, replaces a stale child on redeploy, and turns a missing jar
 * or a failed fork into a readable DeploymentException -- all without starting a
 * real JVM.
 */
package org.jdesktop.lg3d.webserver.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarProcessBackendTest {

    @TempDir
    Path tempDir;

    private Path aJar() throws IOException {
        Path jar = tempDir.resolve("app.jar");
        Files.writeString(jar, "not a real jar");
        return jar;
    }

    private static Deployment jar(String id, String jarPath, int port) {
        return new Deployment(id, id, DeploymentType.JAR, null, jarPath,
                "/" + id, port, Map.of(), AppState.STOPPED, null, 1L, null);
    }

    @Test
    void typeIsJarAndAlwaysAvailable() {
        JarProcessBackend backend = new JarProcessBackend(new FakeChildProcessStarter());
        assertThat(backend.type()).isEqualTo(DeploymentType.JAR);
        assertThat(backend.isAvailable()).isTrue();
    }

    @Test
    void deployForksJavaJarWithServerPort() throws IOException {
        Deployment d = jar("app", aJar().toString(), 9010);
        FakeChildProcessStarter starter =
                new FakeChildProcessStarter().toReturn(new FakeChildProcess(777, true, ""));
        JarProcessBackend backend = new JarProcessBackend(starter);

        assertThat(backend.deploy(d)).isEqualTo("777");
        List<String> command = starter.lastCommand();
        assertThat(command.get(0)).contains("java");
        assertThat(command.get(1)).isEqualTo("-jar");
        assertThat(command.get(2)).isEqualTo(d.jarPath());
        assertThat(command.get(3)).isEqualTo("--server.port=9010");
        assertThat(backend.isRunning(d)).isTrue();
    }

    @Test
    void deployMissingJarThrowsReadable() {
        JarProcessBackend backend = new JarProcessBackend(new FakeChildProcessStarter());
        Deployment d = jar("app", tempDir.resolve("nope.jar").toString(), 9010);
        assertThatThrownBy(() -> backend.deploy(d))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("jar not found");
    }

    @Test
    void deployBlankJarPathThrowsReadable() {
        JarProcessBackend backend = new JarProcessBackend(new FakeChildProcessStarter());
        Deployment d = new Deployment("app", "app", DeploymentType.JAR, null, "  ",
                "/app", 9010, Map.of(), AppState.STOPPED, null, 1L, null);
        assertThatThrownBy(() -> backend.deploy(d))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("jarPath");
    }

    @Test
    void failedForkBecomesDeploymentException() throws IOException {
        FakeChildProcessStarter starter =
                new FakeChildProcessStarter().failWith(new IOException("no such file"));
        JarProcessBackend backend = new JarProcessBackend(starter);
        Deployment d = jar("app", aJar().toString(), 9010);
        assertThatThrownBy(() -> backend.start(d))
                .isInstanceOf(DeploymentException.class)
                .hasMessageContaining("could not start jar");
    }

    @Test
    void stopKillsTrackedChild() throws IOException {
        FakeChildProcess child = new FakeChildProcess(777, true, "");
        JarProcessBackend backend =
                new JarProcessBackend(new FakeChildProcessStarter().toReturn(child));
        Deployment d = jar("app", aJar().toString(), 9010);
        backend.deploy(d);

        backend.stop(d);
        assertThat(child.stopCalled).isTrue();
        assertThat(backend.isRunning(d)).isFalse();
    }

    @Test
    void stopUnknownIsNoOp() {
        JarProcessBackend backend = new JarProcessBackend(new FakeChildProcessStarter());
        backend.stop(jar("ghost", "/x.jar", 9010));
    }

    @Test
    void redeployStopsStaleChild() throws IOException {
        FakeChildProcess first = new FakeChildProcess(1, true, "");
        FakeChildProcess second = new FakeChildProcess(2, true, "");
        FakeChildProcessStarter starter = new FakeChildProcessStarter().toReturn(first);
        JarProcessBackend backend = new JarProcessBackend(starter);
        Deployment d = jar("app", aJar().toString(), 9010);

        backend.deploy(d);
        starter.toReturn(second);
        backend.deploy(d);

        assertThat(first.stopCalled).isTrue();
        assertThat(backend.isRunning(d)).isTrue();
    }

    @Test
    void logsTailsBufferedOutput() throws IOException {
        FakeChildProcess child = new FakeChildProcess(777, true, "l1\nl2\nl3\nl4\nl5");
        JarProcessBackend backend =
                new JarProcessBackend(new FakeChildProcessStarter().toReturn(child));
        Deployment d = jar("app", aJar().toString(), 9010);
        backend.deploy(d);

        assertThat(backend.logs(d, 2)).isEqualTo("l4\nl5");
        assertThat(backend.logs(d, 0)).isEqualTo("l1\nl2\nl3\nl4\nl5");
    }

    @Test
    void logsUnknownIsEmpty() {
        JarProcessBackend backend = new JarProcessBackend(new FakeChildProcessStarter());
        assertThat(backend.logs(jar("ghost", "/x.jar", 9010), 10)).isEmpty();
    }

    @Test
    void removeStopsChild() throws IOException {
        FakeChildProcess child = new FakeChildProcess(777, true, "");
        JarProcessBackend backend =
                new JarProcessBackend(new FakeChildProcessStarter().toReturn(child));
        Deployment d = jar("app", aJar().toString(), 9010);
        backend.deploy(d);

        backend.remove(d);
        assertThat(child.stopCalled).isTrue();
    }

    @Test
    void tailLinesHandlesEdges() {
        assertThat(JarProcessBackend.tailLines(null, 5)).isEmpty();
        assertThat(JarProcessBackend.tailLines("", 5)).isEmpty();
        assertThat(JarProcessBackend.tailLines("a\nb\nc", 10)).isEqualTo("a\nb\nc");
        assertThat(JarProcessBackend.tailLines("a\nb\nc", 2)).isEqualTo("b\nc");
        assertThat(JarProcessBackend.tailLines("a\nb\nc", -1)).isEqualTo("a\nb\nc");
    }

    @Test
    void javaExecutablePointsAtAJavaBinary() {
        assertThat(JarProcessBackend.javaExecutable()).contains("java");
    }
}
