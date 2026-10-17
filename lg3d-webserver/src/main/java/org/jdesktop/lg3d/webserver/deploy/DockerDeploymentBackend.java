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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.springframework.stereotype.Component;

/**
 * The primary {@link DeploymentBackend}: runs a containerized Spring Boot
 * application by shelling out to the system {@code docker} CLI -- the same
 * "no docker-java" approach the desktop's Docker Manager uses.
 *
 * <p>The container name is the deployment {@code id}, which is the stable handle
 * for every later operation ({@code start}/{@code stop}/{@code logs}/{@code rm}/
 * {@code inspect}). A fresh deploy is {@code docker run -d --name <id>
 * -p <port>:<port> -e SERVER_PORT=<port> ...}, so the host port, the published
 * container port and the app's own {@code server.port} all coincide and the
 * reverse proxy can reach the app on {@code localhost:<port>}. A restart reuses
 * the stopped container with {@code docker start}, falling back to a fresh
 * {@code run} if the container has since been pruned.</p>
 *
 * <p>Nothing here throws a raw {@code IOException} or leaks a stack trace: a
 * missing daemon, a failed pull or a non-zero exit becomes a
 * {@link DeploymentException} with a sentence fit to show a human, which the
 * management API renders as a clean {@code 503}.</p>
 */
@Component
public class DockerDeploymentBackend implements DeploymentBackend {

    private final DockerCli docker;
    private final WebServerProperties properties;

    /** Production constructor: Spring injects the {@link DockerCli} bean. */
    public DockerDeploymentBackend(DockerCli docker, WebServerProperties properties) {
        this.docker = docker;
        this.properties = properties;
    }

    @Override
    public DeploymentType type() {
        return DeploymentType.DOCKER;
    }

    @Override
    public boolean isAvailable() {
        return properties.isDockerEnabled() && docker.isAvailable();
    }

    @Override
    public String deploy(Deployment d) {
        requireAvailable();
        if (d.image() == null || d.image().isBlank()) {
            throw new DeploymentException("a Docker deployment needs an image");
        }
        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("-d");
        args.add("--name");
        args.add(d.id());
        args.add("-p");
        args.add(d.port() + ":" + d.port());
        args.add("-e");
        args.add("SERVER_PORT=" + d.port());
        for (Map.Entry<String, String> env : d.envOrEmpty().entrySet()) {
            args.add("-e");
            args.add(env.getKey() + "=" + env.getValue());
        }
        args.add(d.image());

        CommandResult result = docker.execute(args);
        if (!result.ok()) {
            throw fail("run '" + d.image() + "'", result);
        }
        String containerId = result.stdoutTrimmed();
        return containerId.isEmpty() ? d.id() : containerId;
    }

    @Override
    public String start(Deployment d) {
        requireAvailable();
        CommandResult result = docker.execute(List.of("start", d.id()));
        if (result.ok()) {
            return d.id();
        }
        // The container is gone (pruned/removed) -- recreate it from the image.
        if (d.image() != null && !d.image().isBlank()) {
            return deploy(d);
        }
        throw fail("start '" + d.id() + "'", result);
    }

    @Override
    public void stop(Deployment d) {
        requireAvailable();
        CommandResult result = docker.execute(List.of("stop", d.id()));
        if (!result.ok()) {
            throw fail("stop '" + d.id() + "'", result);
        }
    }

    @Override
    public String logs(Deployment d, int tail) {
        List<String> args = new ArrayList<>();
        args.add("logs");
        if (tail > 0) {
            args.add("--tail");
            args.add(Integer.toString(tail));
        }
        args.add(d.id());
        CommandResult result = docker.execute(args);
        // A stopped/missing container is not an error for a log read -- return
        // whatever came back rather than failing the request.
        return result.ok() ? result.stdout() : firstNonBlank(result.stdout(), result.stderr());
    }

    @Override
    public void remove(Deployment d) {
        // Best effort: force-remove whether running or stopped, and never fail an
        // undeploy because the container was already gone.
        docker.execute(List.of("rm", "-f", d.id()));
    }

    @Override
    public boolean isRunning(Deployment d) {
        CommandResult result = docker.execute(
                List.of("inspect", "-f", "{{.State.Running}}", d.id()));
        return result.ok() && "true".equalsIgnoreCase(result.stdoutTrimmed());
    }

    private void requireAvailable() {
        if (!properties.isDockerEnabled()) {
            throw new DeploymentException(
                    "Docker deployments are disabled (set webserver.docker-enabled=true)");
        }
        if (!docker.isAvailable()) {
            throw new DeploymentException(
                    "Docker is not available on this host (no daemon is reachable)");
        }
    }

    private static DeploymentException fail(String op, CommandResult result) {
        String detail;
        if (result.stderr() != null && !result.stderr().isBlank()) {
            detail = result.stderr().trim();
        } else if (result.exitCode() < 0) {
            detail = "the command could not be run";
        } else {
            detail = "exit code " + result.exitCode();
        }
        return new DeploymentException("docker " + op + " failed: " + detail);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return "no further detail";
    }
}
