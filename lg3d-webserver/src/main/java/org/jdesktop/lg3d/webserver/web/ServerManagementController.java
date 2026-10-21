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
package org.jdesktop.lg3d.webserver.web;

import jakarta.validation.Valid;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.jdesktop.lg3d.webserver.deploy.Deployment;
import org.jdesktop.lg3d.webserver.deploy.DeploymentRegistry;
import org.jdesktop.lg3d.webserver.deploy.DeploymentRequest;
import org.jdesktop.lg3d.webserver.deploy.DeploymentType;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The management REST API the desktop console drives: server health/info/metrics
 * plus full CRUD and lifecycle for deployed applications, all under {@code /api}.
 *
 * <p>Deliberately thin -- it translates between JSON views and the
 * {@link DeploymentRegistry} and holds no logic of its own. Every response is a
 * purpose-built record so the wire shape stays stable and independent of the
 * internal {@link Deployment} record, and the reported port is read from the live
 * web server so it is correct even under a random test port.</p>
 */
@RestController
@RequestMapping("/api")
public class ServerManagementController {

    private final DeploymentRegistry registry;
    private final WebServerProperties properties;
    private final ApplicationContext context;

    public ServerManagementController(DeploymentRegistry registry,
                                      WebServerProperties properties,
                                      ApplicationContext context) {
        this.registry = registry;
        this.properties = properties;
        this.context = context;
    }

    @GetMapping("/server/health")
    public HealthView health() {
        List<Deployment> all = registry.list();
        int running = 0;
        for (Deployment d : all) {
            if (d.isRunning()) {
                running++;
            }
        }
        return new HealthView("UP",
                registry.isBackendAvailable(DeploymentType.DOCKER),
                registry.isBackendAvailable(DeploymentType.JAR),
                all.size(), running);
    }

    @GetMapping("/server/info")
    public ServerInfoView info() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        return new ServerInfoView(serverVersion(), port(),
                System.getProperty("java.version"), runtime.getStartTime(), metrics());
    }

    @GetMapping("/server/metrics")
    public MetricsView metrics() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        return new MetricsView(heap.getUsed(), heap.getMax(),
                threads.getThreadCount(), threads.getPeakThreadCount(),
                os.getSystemLoadAverage(), os.getAvailableProcessors(), runtime.getUptime());
    }

    @GetMapping("/apps")
    public List<AppView> apps() {
        List<AppView> views = new ArrayList<>();
        for (Deployment d : registry.list()) {
            views.add(AppView.from(d, properties.getPublicHost()));
        }
        return views;
    }

    @PostMapping("/apps")
    public ResponseEntity<AppView> deploy(@Valid @RequestBody DeploymentRequest request) {
        Deployment created = registry.deploy(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AppView.from(created, properties.getPublicHost()));
    }

    @GetMapping("/apps/{id}")
    public AppView app(@PathVariable String id) {
        return AppView.from(registry.get(id), properties.getPublicHost());
    }

    @GetMapping("/apps/{id}/logs")
    public LogsView logs(@PathVariable String id,
                         @RequestParam(name = "tail", defaultValue = "200") int tail) {
        return new LogsView(id, registry.logs(id, tail));
    }

    @PostMapping("/apps/{id}/start")
    public AppView start(@PathVariable String id) {
        return AppView.from(registry.start(id), properties.getPublicHost());
    }

    @PostMapping("/apps/{id}/stop")
    public AppView stop(@PathVariable String id) {
        return AppView.from(registry.stop(id), properties.getPublicHost());
    }

    @DeleteMapping("/apps/{id}")
    public ResponseEntity<Void> undeploy(@PathVariable String id) {
        registry.undeploy(id);
        return ResponseEntity.noContent().build();
    }

    private int port() {
        if (context instanceof WebServerApplicationContext webContext) {
            WebServer server = webContext.getWebServer();
            if (server != null) {
                return server.getPort();
            }
        }
        return 0;
    }

    private String serverVersion() {
        return versionOrDefault(getClass().getPackage().getImplementationVersion());
    }

    /**
     * @return {@code version}, or {@code "dev"} when the running classes carry no
     *         {@code Implementation-Version} -- the case on an exploded classpath
     *         (a test run) rather than a packaged jar. Split out from
     *         {@link #serverVersion()} so the manifest-present arm is testable
     *         without a jar whose manifest supplies one.
     */
    static String versionOrDefault(String version) {
        return version != null ? version : "dev";
    }

    /** Liveness plus what the server can actually run right now. */
    public record HealthView(String status, boolean dockerAvailable, boolean jarAvailable,
                             int deployments, int running) {
    }

    /** Build/runtime identity and a live metrics snapshot. */
    public record ServerInfoView(String version, int port, String javaVersion,
                                 long startTime, MetricsView metrics) {
    }

    /** A point-in-time JVM snapshot. */
    public record MetricsView(long heapUsedBytes, long heapMaxBytes, int threadCount,
                              int peakThreadCount, double systemLoadAverage,
                              int availableProcessors, long uptimeMillis) {
    }

    /** A deployment as rendered to clients, with its computed public URL. */
    public record AppView(String id, String name, String type, String image, String jarPath,
                          String contextPath, int port, String state, String runtimeId,
                          long deployedAt, String lastError, String url) {

        static AppView from(Deployment d, String publicHost) {
            String url = "http://" + publicHost + ":" + d.port() + d.contextPath();
            return new AppView(d.id(), d.name(), d.type().name(), d.image(), d.jarPath(),
                    d.contextPath(), d.port(), d.state().name(), d.runtimeId(),
                    d.deployedAt(), d.lastError(), url);
        }
    }

    /** The tailed output of a deployment. */
    public record LogsView(String id, String logs) {
    }
}
