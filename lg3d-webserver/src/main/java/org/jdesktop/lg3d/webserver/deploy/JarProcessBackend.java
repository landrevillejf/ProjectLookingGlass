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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The daemon-free {@link DeploymentBackend}: forks a Spring Boot {@code bootJar}
 * as a local {@code java -jar} child and supervises it.
 *
 * <p>This is the sibling of {@link DockerDeploymentBackend} for a host with no
 * Docker, and the backend the headless test suite can exercise with a fake
 * {@link ChildProcessStarter} (no real JVM is ever forked in CI). The child is
 * started with {@code --server.port=<port>} so it binds the same port the reverse
 * proxy forwards to.</p>
 *
 * <p>Live children are tracked in memory keyed by deployment id, so liveness and
 * logs survive only as long as this server process: after a server restart a
 * previously-running jar is reported stopped (the registry reconciles it) rather
 * than orphaned. Request {@code env} is a Docker-only affordance and is not applied
 * to a forked jar.</p>
 */
@Component
public class JarProcessBackend implements DeploymentBackend {

    private final ChildProcessStarter starter;
    private final Map<String, ChildProcess> children = new ConcurrentHashMap<>();

    /** Production constructor: forks real {@code java -jar} children. */
    public JarProcessBackend() {
        this(new RealChildProcessStarter());
    }

    /** Test constructor: injects a fake starter. */
    JarProcessBackend(ChildProcessStarter starter) {
        this.starter = starter;
    }

    @Override
    public DeploymentType type() {
        return DeploymentType.JAR;
    }

    @Override
    public boolean isAvailable() {
        // A JVM is always available to fork another JVM.
        return true;
    }

    @Override
    public String deploy(Deployment d) {
        return launch(d);
    }

    @Override
    public String start(Deployment d) {
        return launch(d);
    }

    private String launch(Deployment d) {
        String jar = d.jarPath();
        if (jar == null || jar.isBlank()) {
            throw new DeploymentException("a JAR deployment needs a jarPath");
        }
        if (!Files.exists(Path.of(jar))) {
            throw new DeploymentException("jar not found: " + jar);
        }
        // A restart replaces any stale handle for this id before forking anew.
        ChildProcess stale = children.remove(d.id());
        if (stale != null) {
            stale.stop();
        }
        List<String> command =
                List.of(javaExecutable(), "-jar", jar, "--server.port=" + d.port());
        try {
            ChildProcess child = starter.start(command);
            children.put(d.id(), child);
            return String.valueOf(child.pid());
        } catch (IOException e) {
            throw new DeploymentException(
                    "could not start jar '" + jar + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void stop(Deployment d) {
        ChildProcess child = children.remove(d.id());
        if (child != null) {
            child.stop();
        }
    }

    @Override
    public String logs(Deployment d, int tail) {
        ChildProcess child = children.get(d.id());
        return child == null ? "" : tailLines(child.output(), tail);
    }

    @Override
    public void remove(Deployment d) {
        stop(d);
    }

    @Override
    public boolean isRunning(Deployment d) {
        ChildProcess child = children.get(d.id());
        return child != null && child.isAlive();
    }

    /** @return the running JVM's own {@code java} executable, or bare {@code java}. */
    static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin", "java");
        if (Files.exists(bin)) {
            return bin.toString();
        }
        Path exe = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        return Files.exists(exe) ? exe.toString() : "java";
    }

    /** @return the last {@code tail} lines of {@code output} (all of it when tail <= 0). */
    static String tailLines(String output, int tail) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        if (tail <= 0) {
            return output;
        }
        String[] all = output.split("\n", -1);
        if (all.length <= tail) {
            return output;
        }
        return String.join("\n", Arrays.copyOfRange(all, all.length - tail, all.length));
    }
}
