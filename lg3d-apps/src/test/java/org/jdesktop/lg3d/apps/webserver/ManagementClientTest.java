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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jdesktop.lg3d.apps.webserver.dto.AppView;
import org.jdesktop.lg3d.apps.webserver.dto.DeployRequest;
import org.jdesktop.lg3d.apps.webserver.dto.HealthView;
import org.jdesktop.lg3d.apps.webserver.dto.LogsView;
import org.jdesktop.lg3d.apps.webserver.dto.MetricsView;
import org.jdesktop.lg3d.apps.webserver.dto.ServerInfoView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link ManagementClient} end to end.
 *
 * <p>Two layers, both headless and network-local: the real {@code HTTP_TRANSPORT}
 * is exercised against an in-JVM {@link HttpServer} on a loopback ephemeral port
 * (so GET/POST/DELETE, the deploy body, the 404 and the 503 no-daemon path are all
 * proven over a real socket), and the pure address/parse/error-mapping helpers are
 * asserted against literal JSON. Nothing here needs the Spring server, a display
 * or a docker daemon.</p>
 */
class ManagementClientTest {

    private static final String HEALTH = "{\"status\":\"UP\",\"dockerAvailable\":false,"
            + "\"jarAvailable\":true,\"deployments\":1,\"running\":1}";
    private static final String INFO = "{\"version\":\"1.69.0-dev\",\"port\":8080,"
            + "\"javaVersion\":\"21\",\"startTime\":1000,\"metrics\":{\"heapUsedBytes\":1,"
            + "\"heapMaxBytes\":2,\"threadCount\":3,\"peakThreadCount\":4,"
            + "\"systemLoadAverage\":0.0,\"availableProcessors\":5,\"uptimeMillis\":6}}";
    private static final String METRICS = "{\"heapUsedBytes\":10,\"heapMaxBytes\":20,"
            + "\"threadCount\":3,\"peakThreadCount\":4,\"systemLoadAverage\":0.5,"
            + "\"availableProcessors\":8,\"uptimeMillis\":60000}";
    private static final String APP = "{\"id\":\"shop\",\"name\":\"Shop\",\"type\":\"DOCKER\","
            + "\"image\":\"nginx\",\"jarPath\":null,\"contextPath\":\"/shop\",\"port\":9001,"
            + "\"state\":\"RUNNING\",\"runtimeId\":\"abc\",\"deployedAt\":1000,\"lastError\":null,"
            + "\"url\":\"http://localhost:9001/shop\"}";
    private static final String APPS = "[" + APP + "]";
    private static final String NO_DAEMON = "{\"error\":\"Docker is not available on this host "
            + "(no daemon is reachable)\"}";

    private HttpServer server;
    private ManagementClient client;
    private final AtomicReference<String> lastDeployBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/server/health", ex -> respond(ex, 200, HEALTH));
        server.createContext("/api/server/info", ex -> respond(ex, 200, INFO));
        server.createContext("/api/server/metrics", ex -> respond(ex, 200, METRICS));
        server.createContext("/api/apps", this::routeApps);
        server.start();
        client = new ManagementClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Routes every {@code /api/apps/**} call by path + method, like the server. */
    private void routeApps(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        switch (path) {
            case "/api/apps" -> {
                if ("POST".equals(method)) {
                    String body = new String(ex.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8);
                    lastDeployBody.set(body);
                    if (body.contains("needs-docker")) {
                        respond(ex, 503, NO_DAEMON);
                    } else {
                        respond(ex, 201, APP);
                    }
                } else {
                    respond(ex, 200, APPS);
                }
            }
            case "/api/apps/ghost" -> respond(ex, 404,
                    "{\"error\":\"no deployment with id 'ghost'\"}");
            case "/api/apps/shop/logs" -> respond(ex, 200,
                    "{\"id\":\"shop\",\"logs\":\"hello\\nworld\"}");
            case "/api/apps/shop/start" -> respond(ex, 200, APP);
            case "/api/apps/shop/stop" -> respond(ex, 200, APP.replace("RUNNING", "STOPPED"));
            case "/api/apps/shop" -> {
                if ("DELETE".equals(method)) {
                    respond(ex, 204, "");
                } else {
                    respond(ex, 200, APP);
                }
            }
            default -> respond(ex, 404, "{\"error\":\"not found\"}");
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } else {
            ex.close();
        }
    }

    // ------------------------------------------------------------------
    // Live transport (real HTTP over loopback)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("health reads over HTTP")
    void healthOverHttp() throws Exception {
        HealthView health = client.health();
        assertThat(health.isUp()).isTrue();
        assertThat(health.dockerAvailable()).isFalse();
        assertThat(health.jarAvailable()).isTrue();
        assertThat(health.deployments()).isEqualTo(1);
        assertThat(health.running()).isEqualTo(1);
    }

    @Test
    @DisplayName("info reads over HTTP with its nested metrics")
    void infoOverHttp() throws Exception {
        ServerInfoView info = client.info();
        assertThat(info.version()).isEqualTo("1.69.0-dev");
        assertThat(info.port()).isEqualTo(8080);
        assertThat(info.metrics().threadCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("metrics reads over HTTP")
    void metricsOverHttp() throws Exception {
        MetricsView metrics = client.metrics();
        assertThat(metrics.heapUsedBytes()).isEqualTo(10L);
        assertThat(metrics.availableProcessors()).isEqualTo(8);
    }

    @Test
    @DisplayName("the application list reads over HTTP")
    void listAppsOverHttp() throws Exception {
        List<AppView> apps = client.listApps();
        assertThat(apps).hasSize(1);
        assertThat(apps.get(0).id()).isEqualTo("shop");
    }

    @Test
    @DisplayName("deploy posts the request body and reads the created app")
    void deployOverHttp() throws Exception {
        AppView created = client.deploy(DeployRequest.docker("Shop", "nginx", "/shop", 9001));
        assertThat(created.id()).isEqualTo("shop");
        assertThat(lastDeployBody.get())
                .contains("\"type\":\"DOCKER\"")
                .contains("\"image\":\"nginx\"")
                .contains("\"port\":9001");
    }

    @Test
    @DisplayName("a refused deploy surfaces the server's no-daemon sentence as a 503")
    void deploySurfacesNoDaemon() {
        ManagementClient.ManagementException caught = null;
        try {
            client.deploy(DeployRequest.docker("needs-docker"));
        } catch (ManagementClient.ManagementException e) {
            caught = e;
        } catch (IOException unexpected) {
            throw new AssertionError("expected a ManagementException", unexpected);
        }
        assertThat(caught).isNotNull();
        assertThat(caught.status()).isEqualTo(503);
        assertThat(caught).hasMessageContaining("Docker is not available");
    }

    @Test
    @DisplayName("one app, its logs, start and stop all read over HTTP")
    void appLifecycleOverHttp() throws Exception {
        assertThat(client.app("shop").name()).isEqualTo("Shop");
        LogsView logs = client.logs("shop", 200);
        assertThat(logs.logsOrEmpty()).contains("hello");
        assertThat(client.start("shop").isRunning()).isTrue();
        assertThat(client.stop("shop").isRunning()).isFalse();
    }

    @Test
    @DisplayName("an unknown id is a 404 carrying the server's sentence")
    void unknownAppIs404() {
        assertThatThrownBy(() -> client.app("ghost"))
                .isInstanceOf(ManagementClient.ManagementException.class)
                .hasMessageContaining("no deployment with id 'ghost'");
    }

    @Test
    @DisplayName("undeploy deletes over HTTP without throwing")
    void undeployOverHttp() throws Exception {
        client.undeploy("shop");
    }

    @Test
    @DisplayName("an unreachable server is an IOException, not a hang or a 500")
    void unreachableServerIsIoFailure() {
        ManagementClient dead = new ManagementClient("http://127.0.0.1:1");
        assertThatThrownBy(dead::health).isInstanceOf(IOException.class);
    }

    // ------------------------------------------------------------------
    // Pure helpers (no socket)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("normalizeServer adds the scheme and strips the trailing slash")
    void normalizesServerAddress() {
        assertThat(ManagementClient.normalizeServer("localhost:8080"))
                .isEqualTo("http://localhost:8080");
        assertThat(ManagementClient.normalizeServer("http://host:8080/"))
                .isEqualTo("http://host:8080");
        assertThatThrownBy(() -> ManagementClient.normalizeServer("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the client normalizes the address it was given")
    void clientNormalizesItsServer() {
        assertThat(new ManagementClient("localhost:9999").server())
                .isEqualTo("http://localhost:9999");
        assertThat(new ManagementClient(null).server())
                .isEqualTo(ManagementClient.DEFAULT_SERVER);
    }

    @Test
    @DisplayName("errorMessage prefers the server sentence, else the status line")
    void mapsErrorBodies() {
        assertThat(ManagementClient.errorMessage(503, "{\"error\":\"boom\"}")).isEqualTo("boom");
        assertThat(ManagementClient.errorMessage(500, "plain text")).isEqualTo("HTTP 500");
        assertThat(ManagementClient.errorMessage(500, "")).isEqualTo("HTTP 500");
        assertThat(ManagementClient.errorMessage(500, "{}")).isEqualTo("HTTP 500");
        assertThat(ManagementClient.errorMessage(404, "{\"error\":\"\"}")).isEqualTo("HTTP 404");
    }

    @Test
    @DisplayName("the parse helpers read literal payloads")
    void parsesLiteralJson() throws Exception {
        assertThat(ManagementClient.parseHealth(HEALTH).status()).isEqualTo("UP");
        assertThat(ManagementClient.parseInfo(INFO).version()).isEqualTo("1.69.0-dev");
        assertThat(ManagementClient.parseMetrics(METRICS).heapUsedBytes()).isEqualTo(10L);
        assertThat(ManagementClient.parseApps(APPS)).hasSize(1);
        assertThat(ManagementClient.parseApps("")).isEmpty();
        assertThat(ManagementClient.parseApp(APP).id()).isEqualTo("shop");
        assertThat(ManagementClient.parseLogs("{\"id\":\"x\",\"logs\":\"y\"}").logs())
                .isEqualTo("y");
    }

    @Test
    @DisplayName("writeDeploy serializes a request to its JSON body")
    void writesDeployBody() throws Exception {
        assertThat(ManagementClient.writeDeploy(DeployRequest.jar("/tmp/x.jar")))
                .contains("\"type\":\"JAR\"")
                .contains("\"jarPath\":\"/tmp/x.jar\"");
    }

    @Test
    @DisplayName("the URL builders compose the management endpoints")
    void buildsUrls() {
        ManagementClient c = new ManagementClient("http://host:1");
        assertThat(c.healthUrl()).isEqualTo("http://host:1/api/server/health");
        assertThat(c.infoUrl()).isEqualTo("http://host:1/api/server/info");
        assertThat(c.metricsUrl()).isEqualTo("http://host:1/api/server/metrics");
        assertThat(c.appsUrl()).isEqualTo("http://host:1/api/apps");
        assertThat(c.appUrl("shop")).isEqualTo("http://host:1/api/apps/shop");
        assertThat(c.logsUrl("shop", 50)).isEqualTo("http://host:1/api/apps/shop/logs?tail=50");
        assertThat(c.startUrl("shop")).isEqualTo("http://host:1/api/apps/shop/start");
        assertThat(c.stopUrl("shop")).isEqualTo("http://host:1/api/apps/shop/stop");
    }
}
