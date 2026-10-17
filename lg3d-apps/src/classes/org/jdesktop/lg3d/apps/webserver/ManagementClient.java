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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.jdesktop.lg3d.apps.webserver.dto.AppView;
import org.jdesktop.lg3d.apps.webserver.dto.DeployRequest;
import org.jdesktop.lg3d.apps.webserver.dto.HealthView;
import org.jdesktop.lg3d.apps.webserver.dto.LogsView;
import org.jdesktop.lg3d.apps.webserver.dto.MetricsView;
import org.jdesktop.lg3d.apps.webserver.dto.ServerInfoView;

/**
 * The console's read/write client for the {@code lg3d-webserver} management REST
 * API: server health/info/metrics plus full CRUD and lifecycle for deployed
 * applications, reached with only the JDK's {@link HttpClient} and Jackson.
 *
 * <p>This is the seam the Swing {@link WebServerConsolePanel} renders, so all
 * the fetching, parsing and error mapping is exercised headlessly with no
 * network and no X display: the transport is injected through {@link Transport}
 * (the tests drive the real one against an in-JVM {@code com.sun.net.httpserver}
 * fake, and the parse methods against literal JSON), and the default
 * {@link #HTTP_TRANSPORT} is only ever built when a real call is made.</p>
 *
 * <p>Why the console talks HTTP and never imports a server class: the whole point
 * of the child-process design is that Spring never enters the desktop JVM. The
 * {@code dto} records restate the wire shape client-side, so a server restart,
 * relocation or version bump is a transport concern, not a classpath one. This
 * mirrors how the Wonderland World browser reads {@code wonderland-server} over
 * its own {@code WorldCatalog}.</p>
 *
 * <p>Every non-2xx answer becomes a {@link ManagementException} carrying the
 * server's own {@code {"error": "<sentence>"}} body, so the panel shows a
 * readable reason (a 503 "Docker is not available", a 404 "no deployment with
 * id") rather than a bare status code &mdash; the same honesty the server's
 * {@code ApiExceptionHandler} guarantees on its side.</p>
 */
public final class ManagementClient {

    /**
     * System property naming the server to drive, so a launcher script or
     * {@code -Dwebserver.url=host:port} points the console at a server without
     * editing it. Unset or blank, {@link #DEFAULT_SERVER} is used.
     */
    public static final String SERVER_PROPERTY = "webserver.url";

    /** The server assumed when {@link #SERVER_PROPERTY} is unset or blank. */
    public static final String DEFAULT_SERVER = "http://localhost:8080";

    static final String HEALTH_PATH = "/api/server/health";
    static final String INFO_PATH = "/api/server/info";
    static final String METRICS_PATH = "/api/server/metrics";
    static final String APPS_PATH = "/api/apps";

    /** Request timeout: a local Spring Boot answers in milliseconds. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Shared client; no connection is made at construction. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // Transport seam
    // ------------------------------------------------------------------

    /**
     * One HTTP exchange; the seam that keeps the tests off the network. It is
     * deliberately method+url+body so a single fake can serve every verb the API
     * uses (GET/POST/DELETE) and assert on the exact request it received.
     */
    public interface Transport {
        /**
         * @param method {@code GET}, {@code POST} or {@code DELETE}
         * @param url    an absolute http(s) URL
         * @param body   the request body for POST, else {@code null}
         * @return the status and body the server answered with
         * @throws IOException on a transport failure (the server is unreachable)
         */
        Response exchange(String method, String url, String body) throws IOException;
    }

    /** One HTTP answer: a status code and its body (possibly empty). */
    public record Response(int status, String body) {

        /** Whether the status is a 2xx success. */
        public boolean isSuccess() {
            return status / 100 == 2;
        }

        /** The body, never {@code null}. */
        public String bodyOrEmpty() {
            return body == null ? "" : body;
        }
    }

    /** The real transport: a blocking exchange on the JDK HTTP client. */
    static final Transport HTTP_TRANSPORT = (method, url, body) -> {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "lg3d-webserver-console/1.0")
                .header("Accept", "application/json");
        HttpRequest request;
        if ("POST".equalsIgnoreCase(method)) {
            request = builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                    .build();
        } else if ("DELETE".equalsIgnoreCase(method)) {
            request = builder.DELETE().build();
        } else {
            request = builder.GET().build();
        }
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while contacting " + url, ie);
        }
        return new Response(resp.statusCode(), resp.body());
    };

    /**
     * A non-2xx answer from the management API, carrying the HTTP status and the
     * server's readable {@code {"error": ...}} sentence. An {@link IOException}
     * so a caller that only catches transport failures still handles it.
     */
    public static final class ManagementException extends IOException {

        private static final long serialVersionUID = 1L;

        private final int status;

        ManagementException(int status, String message) {
            super(message);
            this.status = status;
        }

        /** The HTTP status the server answered with. */
        public int status() {
            return status;
        }
    }

    // ------------------------------------------------------------------
    // Instance
    // ------------------------------------------------------------------

    private final String server;
    private final Transport transport;

    /** The production client: real JDK HTTP transport at {@code server}. */
    public ManagementClient(String server) {
        this(server, HTTP_TRANSPORT);
    }

    /** A client over an injected transport; the test seam. */
    ManagementClient(String server, Transport transport) {
        this.server = normalizeServer(
                (server == null || server.isBlank()) ? DEFAULT_SERVER : server.trim());
        this.transport = (transport == null) ? HTTP_TRANSPORT : transport;
    }

    /** The normalized base URL this client drives. */
    public String server() {
        return server;
    }

    // ------------------------------------------------------------------
    // URLs
    // ------------------------------------------------------------------

    /** The health URL. */
    public String healthUrl() {
        return server + HEALTH_PATH;
    }

    /** The server-info URL. */
    public String infoUrl() {
        return server + INFO_PATH;
    }

    /** The metrics URL. */
    public String metricsUrl() {
        return server + METRICS_PATH;
    }

    /** The applications-collection URL. */
    public String appsUrl() {
        return server + APPS_PATH;
    }

    /** One application's URL. */
    public String appUrl(String id) {
        return appsUrl() + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
    }

    /** One application's logs URL, tailing {@code tail} lines. */
    public String logsUrl(String id, int tail) {
        return appUrl(id) + "/logs?tail=" + tail;
    }

    /** One application's start URL. */
    public String startUrl(String id) {
        return appUrl(id) + "/start";
    }

    /** One application's stop URL. */
    public String stopUrl(String id) {
        return appUrl(id) + "/stop";
    }

    // ------------------------------------------------------------------
    // API calls (blocking; call off the EDT)
    // ------------------------------------------------------------------

    /** Reads server liveness and backend availability. */
    public HealthView health() throws IOException {
        return parseHealth(exchange("GET", healthUrl(), null).bodyOrEmpty());
    }

    /** Reads build/runtime identity plus a metrics snapshot. */
    public ServerInfoView info() throws IOException {
        return parseInfo(exchange("GET", infoUrl(), null).bodyOrEmpty());
    }

    /** Reads a point-in-time JVM snapshot. */
    public MetricsView metrics() throws IOException {
        return parseMetrics(exchange("GET", metricsUrl(), null).bodyOrEmpty());
    }

    /** Lists every registered deployment. */
    public List<AppView> listApps() throws IOException {
        return parseApps(exchange("GET", appsUrl(), null).bodyOrEmpty());
    }

    /** Deploys an application; the server answers {@code 201} with the new app. */
    public AppView deploy(DeployRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        String body = MAPPER.writeValueAsString(request);
        return parseApp(exchange("POST", appsUrl(), body).bodyOrEmpty());
    }

    /** Reads one deployment by id. */
    public AppView app(String id) throws IOException {
        return parseApp(exchange("GET", appUrl(id), null).bodyOrEmpty());
    }

    /** Reads the tailed output of one deployment. */
    public LogsView logs(String id, int tail) throws IOException {
        return parseLogs(exchange("GET", logsUrl(id, tail), null).bodyOrEmpty());
    }

    /** Starts one deployment. */
    public AppView start(String id) throws IOException {
        return parseApp(exchange("POST", startUrl(id), null).bodyOrEmpty());
    }

    /** Stops one deployment. */
    public AppView stop(String id) throws IOException {
        return parseApp(exchange("POST", stopUrl(id), null).bodyOrEmpty());
    }

    /** Undeploys one deployment; the server answers {@code 204 No Content}. */
    public void undeploy(String id) throws IOException {
        exchange("DELETE", appUrl(id), null);
    }

    /**
     * Runs one exchange and maps a non-2xx answer to a
     * {@link ManagementException} carrying the server's readable error.
     */
    private Response exchange(String method, String url, String body) throws IOException {
        Response response = transport.exchange(method, url, body);
        if (!response.isSuccess()) {
            throw new ManagementException(response.status(),
                    errorMessage(response.status(), response.bodyOrEmpty()));
        }
        return response;
    }

    // ------------------------------------------------------------------
    // Parsing (pure; headless-tested against literal JSON)
    // ------------------------------------------------------------------

    /** Parses a health payload. */
    static HealthView parseHealth(String body) throws IOException {
        return MAPPER.readValue(body, HealthView.class);
    }

    /** Parses a server-info payload. */
    static ServerInfoView parseInfo(String body) throws IOException {
        return MAPPER.readValue(body, ServerInfoView.class);
    }

    /** Parses a metrics payload. */
    static MetricsView parseMetrics(String body) throws IOException {
        return MAPPER.readValue(body, MetricsView.class);
    }

    /** Parses an applications-collection payload. */
    static List<AppView> parseApps(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        return MAPPER.readValue(body, new TypeReference<List<AppView>>() { });
    }

    /** Parses a single-application payload. */
    static AppView parseApp(String body) throws IOException {
        return MAPPER.readValue(body, AppView.class);
    }

    /** Parses a logs payload. */
    static LogsView parseLogs(String body) throws IOException {
        return MAPPER.readValue(body, LogsView.class);
    }

    /** Serializes a deploy request to its JSON body. */
    static String writeDeploy(DeployRequest request) throws IOException {
        return MAPPER.writeValueAsString(request);
    }

    // ------------------------------------------------------------------
    // Address + error handling
    // ------------------------------------------------------------------

    /**
     * The server the console should drive: {@link #SERVER_PROPERTY} when set to
     * something non-blank, else {@link #DEFAULT_SERVER}.
     */
    public static String defaultServer() {
        String configured = System.getProperty(SERVER_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_SERVER;
        }
        return normalizeServer(configured.trim());
    }

    /**
     * Turns what a user typed into a base URL: {@code localhost:8080} and
     * {@code http://host:8080/} both become {@code http://host:8080}, because the
     * field holds a server, not a page, and {@link URI#create(String)} rejects the
     * bare form. Mirrors {@code WorldCatalog.normalizeServer}.
     *
     * @throws IllegalArgumentException when nothing was given
     */
    public static String normalizeServer(String server) {
        if (server == null || server.trim().isEmpty()) {
            throw new IllegalArgumentException("server must not be empty");
        }
        String base = server.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.contains("://")) {
            base = "http://" + base;
        }
        return base;
    }

    /**
     * The readable reason for a failed call: the server's {@code {"error": ...}}
     * sentence when it sent one, else a bare {@code HTTP <status>}. Never throws
     * &mdash; a malformed error body degrades to the status line.
     */
    static String errorMessage(int status, String body) {
        if (body != null && !body.isBlank()) {
            try {
                JsonNode node = MAPPER.readTree(body);
                JsonNode error = node.get("error");
                if (error != null && !error.isNull() && !error.asText().isBlank()) {
                    return error.asText();
                }
            } catch (IOException ignored) {
                // Not JSON, or no "error" field: fall through to the status line.
            }
        }
        return "HTTP " + status;
    }
}
