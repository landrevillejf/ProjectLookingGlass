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

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The heart of the server: owns the deployment table and drives each application
 * through the right {@link DeploymentBackend}.
 *
 * <p>The registry -- not the caller -- decides the id, the derived name and
 * context path, and the host port (auto-assigned from the configured range when
 * the request omits one), so two deploys can never collide on a container name or
 * a published port. It keeps the recorded state honest by reconciling any app it
 * believes is {@link AppState#RUNNING} against the backend's live answer, both
 * lazily (on {@link #list()}/{@link #get(String)}) and on a timer, so a container
 * that died outside the API is reported stopped rather than left lying.</p>
 *
 * <p>Failures are separated by kind: bad input raises {@link IllegalArgumentException}
 * (a {@code 400}), an unknown id raises {@link NoSuchElementException} (a {@code 404}),
 * and an operational failure raises {@link DeploymentException} (a {@code 503}) --
 * never a raw stack trace. A start/stop that fails also records the reason on the
 * deployment so the next read shows {@link AppState#FAILED}.</p>
 */
@Service
public class DeploymentRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(DeploymentRegistry.class);

    private final Map<DeploymentType, DeploymentBackend> backends =
            new EnumMap<>(DeploymentType.class);
    private final Map<String, Deployment> deployments = new LinkedHashMap<>();
    private final DeploymentStore store;
    private final WebServerProperties properties;

    public DeploymentRegistry(List<DeploymentBackend> backendList,
                              DeploymentStore store,
                              WebServerProperties properties) {
        for (DeploymentBackend backend : backendList) {
            backends.put(backend.type(), backend);
        }
        this.store = store;
        this.properties = properties;
    }

    /** Restores the persisted table without probing Docker, keeping boot fast. */
    @PostConstruct
    void load() {
        synchronized (this) {
            deployments.clear();
            for (Deployment d : store.load()) {
                deployments.put(d.id(), d);
            }
            if (!deployments.isEmpty()) {
                LOG.info("restored {} deployment(s) from {}", deployments.size(), store.file());
            }
        }
    }

    /** Periodically corrects recorded state so external changes surface. */
    @Scheduled(fixedDelayString = "${webserver.reconcile-interval-ms:15000}")
    public void reconcile() {
        synchronized (this) {
            if (refreshLocked()) {
                store.save(deployments.values());
            }
        }
    }

    /** @return every deployment, reconciled, in insertion order. */
    public List<Deployment> list() {
        synchronized (this) {
            if (refreshLocked()) {
                store.save(deployments.values());
            }
            return List.copyOf(deployments.values());
        }
    }

    /** @return one deployment, reconciled; throws {@link NoSuchElementException}. */
    public Deployment get(String id) {
        synchronized (this) {
            Deployment d = require(id);
            Deployment fresh = refreshState(d);
            if (fresh != d) {
                deployments.put(id, fresh);
                store.save(deployments.values());
            }
            return fresh;
        }
    }

    /**
     * Deploys and starts a new application.
     *
     * @throws IllegalArgumentException on bad input (a {@code 400})
     * @throws DeploymentException      when the runtime cannot start it (a {@code 503})
     */
    public Deployment deploy(DeploymentRequest request) {
        validate(request);
        DeploymentBackend backend = backends.get(request.type());
        if (backend == null) {
            throw new DeploymentException("no backend for type " + request.type());
        }
        if (!backend.isAvailable()) {
            throw new DeploymentException(unavailableMessage(request.type()));
        }
        synchronized (this) {
            String name = deriveName(request);
            String id = newId(name);
            String contextPath = deriveContextPath(request, name);
            requireContextPathFree(contextPath);
            int port = request.port() != null ? request.port() : allocatePort();
            requirePortFree(port);

            Deployment draft = new Deployment(id, name, request.type(), request.image(),
                    request.jarPath(), contextPath, port, request.envOrEmpty(),
                    AppState.STOPPED, null, System.currentTimeMillis(), null);

            String runtimeId;
            try {
                runtimeId = backend.deploy(draft);
            } catch (DeploymentException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new DeploymentException("deploy failed: " + e.getMessage(), e);
            }
            Deployment running = draft.running(runtimeId);
            deployments.put(id, running);
            store.save(deployments.values());
            return running;
        }
    }

    /** Starts a stopped deployment; records {@link AppState#FAILED} if it cannot. */
    public Deployment start(String id) {
        synchronized (this) {
            Deployment d = require(id);
            DeploymentBackend backend = backendFor(d);
            if (!backend.isAvailable()) {
                return failAndPersist(d, unavailableMessage(d.type()));
            }
            try {
                Deployment running = d.running(backend.start(d));
                deployments.put(id, running);
                store.save(deployments.values());
                return running;
            } catch (DeploymentException e) {
                failAndPersist(d, e.getMessage());
                throw e;
            } catch (RuntimeException e) {
                failAndPersist(d, e.getMessage());
                throw new DeploymentException("start failed: " + e.getMessage(), e);
            }
        }
    }

    /** Stops a running deployment; records {@link AppState#FAILED} if it cannot. */
    public Deployment stop(String id) {
        synchronized (this) {
            Deployment d = require(id);
            DeploymentBackend backend = backendFor(d);
            try {
                backend.stop(d);
                Deployment stopped = d.stopped();
                deployments.put(id, stopped);
                store.save(deployments.values());
                return stopped;
            } catch (DeploymentException e) {
                failAndPersist(d, e.getMessage());
                throw e;
            } catch (RuntimeException e) {
                failAndPersist(d, e.getMessage());
                throw new DeploymentException("stop failed: " + e.getMessage(), e);
            }
        }
    }

    /** Tears a deployment down completely and forgets it. */
    public void undeploy(String id) {
        synchronized (this) {
            Deployment d = require(id);
            try {
                backendFor(d).remove(d);
            } catch (RuntimeException e) {
                LOG.warn("could not fully remove '{}': {}", id, e.getMessage());
            }
            deployments.remove(id);
            store.save(deployments.values());
        }
    }

    /** @return the recent output of a deployment (never null). */
    public String logs(String id, int tail) {
        synchronized (this) {
            Deployment d = require(id);
            return backendFor(d).logs(d, tail);
        }
    }

    /** @return the {@code contextPath -> port} routes for running apps. */
    public Map<String, Integer> proxyRoutes() {
        synchronized (this) {
            Map<String, Integer> routes = new LinkedHashMap<>();
            for (Deployment d : deployments.values()) {
                if (d.state() == AppState.RUNNING && d.contextPath() != null) {
                    routes.put(d.contextPath(), d.port());
                }
            }
            return routes;
        }
    }

    /** @return true when the given backend's runtime can start an app right now. */
    public boolean isBackendAvailable(DeploymentType type) {
        DeploymentBackend backend = backends.get(type);
        return backend != null && backend.isAvailable();
    }

    // ---- internals -------------------------------------------------------

    private Deployment failAndPersist(Deployment d, String message) {
        Deployment failed = d.failed(message);
        deployments.put(d.id(), failed);
        store.save(deployments.values());
        return failed;
    }

    private boolean refreshLocked() {
        boolean changed = false;
        for (Map.Entry<String, Deployment> entry : deployments.entrySet()) {
            Deployment fresh = refreshState(entry.getValue());
            if (fresh != entry.getValue()) {
                entry.setValue(fresh);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * @return the deployment unchanged unless it is recorded RUNNING but the
     *         backend says it is not up, in which case a STOPPED copy
     */
    private Deployment refreshState(Deployment d) {
        if (d.state() != AppState.RUNNING) {
            return d;
        }
        DeploymentBackend backend = backends.get(d.type());
        if (backend != null && safeIsRunning(backend, d)) {
            return d;
        }
        return d.stopped();
    }

    private static boolean safeIsRunning(DeploymentBackend backend, Deployment d) {
        try {
            return backend.isRunning(d);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Deployment require(String id) {
        Deployment d = id == null ? null : deployments.get(id);
        if (d == null) {
            throw new NoSuchElementException("no deployment with id '" + id + "'");
        }
        return d;
    }

    private DeploymentBackend backendFor(Deployment d) {
        DeploymentBackend backend = backends.get(d.type());
        if (backend == null) {
            throw new DeploymentException("no backend for type " + d.type());
        }
        return backend;
    }

    private static String unavailableMessage(DeploymentType type) {
        return type == DeploymentType.DOCKER
                ? "Docker is not available on this host (no daemon is reachable)"
                : "no runtime is available to start a " + type + " application";
    }

    private static void validate(DeploymentRequest request) {
        if (request == null || request.type() == null) {
            throw new IllegalArgumentException("a deployment type is required");
        }
        if (request.type() == DeploymentType.DOCKER
                && (request.image() == null || request.image().isBlank())) {
            throw new IllegalArgumentException("a Docker deployment requires an 'image'");
        }
        if (request.type() == DeploymentType.JAR
                && (request.jarPath() == null || request.jarPath().isBlank())) {
            throw new IllegalArgumentException("a JAR deployment requires a 'jarPath'");
        }
        if (request.port() != null && (request.port() < 1 || request.port() > 65535)) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }

    private void requirePortFree(int port) {
        for (Deployment d : deployments.values()) {
            if (d.port() == port) {
                throw new IllegalArgumentException(
                        "port " + port + " is already used by '" + d.name() + "'");
            }
        }
    }

    private void requireContextPathFree(String contextPath) {
        for (Deployment d : deployments.values()) {
            if (contextPath.equals(d.contextPath())) {
                throw new IllegalArgumentException(
                        "context path '" + contextPath + "' is already used by '" + d.name() + "'");
            }
        }
    }

    private int allocatePort() {
        int start = properties.getPortRange().getStart();
        int end = properties.getPortRange().getEnd();
        Set<Integer> used = new HashSet<>();
        for (Deployment d : deployments.values()) {
            used.add(d.port());
        }
        for (int port = start; port <= end; port++) {
            if (!used.contains(port)) {
                return port;
            }
        }
        throw new DeploymentException("no free port in range " + start + "-" + end);
    }

    private String newId(String name) {
        // name is always a derived, non-blank name, and slug() of a non-blank
        // value is never empty (its own fallback is "app-"), so base is non-empty.
        String base = slug(name);
        String id = base;
        for (int n = 2; deployments.containsKey(id); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    private static String deriveName(DeploymentRequest request) {
        if (request.name() != null && !request.name().isBlank()) {
            return request.name().trim();
        }
        String source = request.type() == DeploymentType.DOCKER ? request.image() : request.jarPath();
        String last = source.substring(lastSeparator(source) + 1);
        int colon = last.lastIndexOf(':');
        if (colon > 0) {
            last = last.substring(0, colon);
        }
        if (last.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            last = last.substring(0, last.length() - 4);
        }
        return last.isBlank() ? "app" : last;
    }

    private static int lastSeparator(String path) {
        return Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    }

    private static String deriveContextPath(DeploymentRequest request, String name) {
        String requested = request.contextPath();
        if (requested == null || requested.isBlank()) {
            // name is non-blank here, and slug() of a non-blank value is never
            // empty, so the derived path always carries a segment.
            return "/" + slug(name);
        }
        String cp = requested.trim();
        if (!cp.startsWith("/")) {
            cp = "/" + cp;
        }
        while (cp.length() > 1 && cp.endsWith("/")) {
            cp = cp.substring(0, cp.length() - 1);
        }
        return cp;
    }

    /** Lowercases to a docker-name-safe slug: {@code [a-z0-9-]}, alnum first. */
    static String slug(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        String collapsed = sb.toString().replaceAll("-+", "-");
        int from = 0;
        int to = collapsed.length();
        while (from < to && collapsed.charAt(from) == '-') {
            from++;
        }
        while (to > from && collapsed.charAt(to - 1) == '-') {
            to--;
        }
        String trimmed = collapsed.substring(from, to);
        if (trimmed.isEmpty() || !Character.isLetter(trimmed.charAt(0))) {
            trimmed = "app-" + trimmed;
        }
        return trimmed;
    }

    /** Exposed for tests that build a registry without a full request. */
    List<Deployment> snapshot() {
        synchronized (this) {
            return new ArrayList<>(deployments.values());
        }
    }
}
