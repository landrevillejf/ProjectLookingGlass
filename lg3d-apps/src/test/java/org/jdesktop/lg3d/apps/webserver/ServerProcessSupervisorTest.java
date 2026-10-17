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
package org.jdesktop.lg3d.apps.webserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@link ServerProcessSupervisor}'s pure decisions and lifecycle without
 * ever forking a real process.
 *
 * <p>The jar-resolution order (system property &rarr; bundled {@code libs/}
 * &rarr; a source tree's {@code build-gradle/libs/}) and the exact {@code java
 * -jar ... --server.port=N} argv are asserted against temporary directories, and
 * start/stop/isRunning run through a fake {@link ServerProcessSupervisor.Spawner}
 * so CI never spawns a JVM. The supervisor's contract is that it never throws: a
 * missing jar or a refused spawn degrades to a not-started {@code Result} carrying
 * the reason.</p>
 */
class ServerProcessSupervisorTest {

    private String savedJarProperty;
    private String savedCodebaseProperty;

    @BeforeEach
    void clearProperties() {
        savedJarProperty = System.getProperty(ServerProcessSupervisor.JAR_PROPERTY);
        savedCodebaseProperty = System.getProperty(ServerProcessSupervisor.APPCODEBASE_PROPERTY);
        System.clearProperty(ServerProcessSupervisor.JAR_PROPERTY);
        System.clearProperty(ServerProcessSupervisor.APPCODEBASE_PROPERTY);
    }

    @AfterEach
    void restoreProperties() {
        restore(ServerProcessSupervisor.JAR_PROPERTY, savedJarProperty);
        restore(ServerProcessSupervisor.APPCODEBASE_PROPERTY, savedCodebaseProperty);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /** A child the fake spawner hands back; records whether it was stopped. */
    static final class FakeChild implements ServerProcessSupervisor.Child {
        boolean alive = true;
        boolean stopCalled;

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public void stop() {
            alive = false;
            stopCalled = true;
        }
    }

    /** Records the argv it was asked to spawn; can be told to fail. */
    static final class FakeSpawner implements ServerProcessSupervisor.Spawner {
        List<String> argv;
        final FakeChild child = new FakeChild();
        boolean fail;

        @Override
        public ServerProcessSupervisor.Child spawn(List<String> argv) throws IOException {
            if (fail) {
                throw new IOException("spawn refused");
            }
            this.argv = argv;
            return child;
        }
    }

    /** Writes a stub {@code lg3d-webserver-*-app.jar} into a source-tree layout. */
    private static Path writeBootJar(Path root) throws IOException {
        Path libs = root.resolve("lg3d-webserver").resolve("build-gradle").resolve("libs");
        Files.createDirectories(libs);
        Path jar = libs.resolve("lg3d-webserver-1.69.0-dev-app.jar");
        Files.writeString(jar, "stub");
        return jar;
    }

    // ------------------------------------------------------------------
    // Lifecycle through the fake spawner
    // ------------------------------------------------------------------

    @Test
    @DisplayName("start forks the resolved jar with the port argument")
    void startForksTheResolvedJar(@TempDir Path root) throws Exception {
        Path jar = writeBootJar(root);
        FakeSpawner spawner = new FakeSpawner();
        ServerProcessSupervisor supervisor =
                new ServerProcessSupervisor(List.of(root), spawner);

        ServerProcessSupervisor.Result result = supervisor.start(9099);

        assertThat(result.started()).isTrue();
        assertThat(result.port()).isEqualTo(9099);
        assertThat(result.url()).isEqualTo("http://localhost:9099");
        assertThat(spawner.argv).containsExactly(
                ServerProcessSupervisor.javaExecutable(),
                "-jar",
                jar.toAbsolutePath().toString(),
                "--server.port=9099");
        assertThat(supervisor.isRunning()).isTrue();
        assertThat(supervisor.port()).isEqualTo(9099);
        assertThat(supervisor.url()).isEqualTo("http://localhost:9099");
    }

    @Test
    @DisplayName("start defaults the port when it is not positive")
    void startDefaultsThePort(@TempDir Path root) throws Exception {
        writeBootJar(root);
        FakeSpawner spawner = new FakeSpawner();
        ServerProcessSupervisor supervisor =
                new ServerProcessSupervisor(List.of(root), spawner);

        ServerProcessSupervisor.Result result = supervisor.start(0);

        assertThat(result.port()).isEqualTo(ServerProcessSupervisor.DEFAULT_PORT);
        assertThat(spawner.argv.get(3))
                .isEqualTo("--server.port=" + ServerProcessSupervisor.DEFAULT_PORT);
    }

    @Test
    @DisplayName("start without a jar degrades to a note, never a throw")
    void startWithoutAJarDegrades(@TempDir Path empty) {
        FakeSpawner spawner = new FakeSpawner();
        ServerProcessSupervisor supervisor =
                new ServerProcessSupervisor(List.of(empty), spawner);

        ServerProcessSupervisor.Result result = supervisor.start(8080);

        assertThat(result.started()).isFalse();
        assertThat(result.note()).contains("bootJar was not found");
        assertThat(spawner.argv).isNull();
        assertThat(supervisor.isRunning()).isFalse();
    }

    @Test
    @DisplayName("a refused spawn degrades to a note, never a throw")
    void startReportsASpawnFailure(@TempDir Path root) throws Exception {
        writeBootJar(root);
        FakeSpawner spawner = new FakeSpawner();
        spawner.fail = true;
        ServerProcessSupervisor supervisor =
                new ServerProcessSupervisor(List.of(root), spawner);

        ServerProcessSupervisor.Result result = supervisor.start(8080);

        assertThat(result.started()).isFalse();
        assertThat(result.note()).contains("could not start").contains("spawn refused");
        assertThat(supervisor.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop reaps the child and is idempotent")
    void stopReapsTheChild(@TempDir Path root) throws Exception {
        writeBootJar(root);
        FakeSpawner spawner = new FakeSpawner();
        ServerProcessSupervisor supervisor =
                new ServerProcessSupervisor(List.of(root), spawner);
        supervisor.start(8080);
        assertThat(supervisor.isRunning()).isTrue();

        supervisor.stop();

        assertThat(spawner.child.stopCalled).isTrue();
        assertThat(supervisor.isRunning()).isFalse();
        assertThat(supervisor.port()).isEqualTo(-1);
        assertThat(supervisor.url()).isNull();
        supervisor.stop();  // idempotent: no throw, no second reap
    }

    @Test
    @DisplayName("start is a no-op when a child is already running")
    void startIsANoOpWhenAlreadyRunning(@TempDir Path root) throws Exception {
        writeBootJar(root);
        FakeSpawner spawner = new FakeSpawner();
        ServerProcessSupervisor supervisor =
                new ServerProcessSupervisor(List.of(root), spawner);
        supervisor.start(8080);

        ServerProcessSupervisor.Result second = supervisor.start(9090);

        assertThat(second.started()).isTrue();
        assertThat(second.note()).contains("already running");
        assertThat(supervisor.port()).isEqualTo(8080);
    }

    // ------------------------------------------------------------------
    // Jar resolution order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveJar prefers the -Dwebserver.jar system property")
    void resolveJarPrefersTheSystemProperty(@TempDir Path root) throws Exception {
        Path jar = writeBootJar(root);
        System.setProperty(ServerProcessSupervisor.JAR_PROPERTY,
                jar.toAbsolutePath().toString());
        ServerProcessSupervisor supervisor =
                new ServerProcessSupervisor(List.of(), new FakeSpawner());

        assertThat(supervisor.jar()).isEqualTo(jar);
    }

    @Test
    @DisplayName("a named-but-absent jar is not silently replaced by the search")
    void resolveJarHonoursAMissingPropertyPath(@TempDir Path root) throws Exception {
        writeBootJar(root);
        System.setProperty(ServerProcessSupervisor.JAR_PROPERTY,
                root.resolve("does-not-exist.jar").toString());
        ServerProcessSupervisor supervisor =
                new ServerProcessSupervisor(List.of(root), new FakeSpawner());

        assertThat(supervisor.jar()).isNull();
    }

    @Test
    @DisplayName("resolveJar finds the bundled jar under <lg.appcodebase>/libs")
    void resolveJarFindsTheBundledJar(@TempDir Path codebase) throws Exception {
        Path libs = codebase.resolve("libs");
        Files.createDirectories(libs);
        Path jar = libs.resolve("lg3d-webserver-9.9.9-app.jar");
        Files.writeString(jar, "stub");
        System.setProperty(ServerProcessSupervisor.APPCODEBASE_PROPERTY, codebase.toString());
        ServerProcessSupervisor supervisor =
                new ServerProcessSupervisor(List.of(), new FakeSpawner());

        assertThat(supervisor.jar()).isEqualTo(jar);
    }

    @Test
    @DisplayName("resolveJar finds the source-tree jar under build-gradle/libs")
    void resolveJarFindsTheSourceTreeJar(@TempDir Path root) throws Exception {
        Path jar = writeBootJar(root);
        ServerProcessSupervisor supervisor =
                new ServerProcessSupervisor(List.of(root), new FakeSpawner());

        assertThat(supervisor.jar()).isEqualTo(jar);
    }

    // ------------------------------------------------------------------
    // Pure helpers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("command builds the java -jar invocation with the port")
    void commandBuildsTheInvocation(@TempDir Path root) throws Exception {
        Path jar = writeBootJar(root);
        assertThat(ServerProcessSupervisor.command(jar, 1234)).containsExactly(
                ServerProcessSupervisor.javaExecutable(),
                "-jar",
                jar.toAbsolutePath().toString(),
                "--server.port=1234");
    }

    @Test
    @DisplayName("isServerAppJar matches only the executable -app classifier")
    void isServerAppJarMatchesTheExecutable() {
        assertThat(ServerProcessSupervisor.isServerAppJar(
                Path.of("lg3d-webserver-1.0-app.jar"))).isTrue();
        assertThat(ServerProcessSupervisor.isServerAppJar(
                Path.of("lg3d-webserver-1.0.jar"))).isFalse();
        assertThat(ServerProcessSupervisor.isServerAppJar(
                Path.of("other-1.0-app.jar"))).isFalse();
    }

    @Test
    @DisplayName("ancestorsOf walks up from a start and caps the search")
    void ancestorsOfWalksUpAndCaps(@TempDir Path root) {
        Path deep = root.resolve("a").resolve("b").resolve("c")
                .resolve("d").resolve("e").resolve("f").resolve("g");
        List<Path> ancestors = ServerProcessSupervisor.ancestorsOf(deep);
        assertThat(ancestors).isNotEmpty();
        assertThat(ancestors.get(0)).isEqualTo(deep.toAbsolutePath().normalize());
        assertThat(ancestors).hasSizeLessThanOrEqualTo(6);
    }

    @Test
    @DisplayName("ancestorsOf a null start is empty")
    void ancestorsOfNullIsEmpty() {
        assertThat(ServerProcessSupervisor.ancestorsOf(null)).isEmpty();
    }
}
