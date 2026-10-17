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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.jdesktop.lg3d.webserver.deploy.DeploymentRegistry;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A minimal reverse proxy that publishes each running application under its own
 * {@code /<contextPath>/**} prefix on the management server's port, so a deployed
 * app is reachable at {@code http://<host>:8080/shop/...} without exposing its
 * internal port.
 *
 * <p>The routing table is read live from the {@link DeploymentRegistry} and the
 * longest matching {@code contextPath} wins. Forwarding is a plain request/response
 * relay over the JDK {@link HttpClient}: method, headers and body go upstream, the
 * status, headers and body come back. It is deliberately not a full proxy -- there
 * is no WebSocket upgrade, no chunked streaming and no connection pooling -- which
 * is enough to browse and exercise a containerized Spring Boot app. Anything that
 * is not a proxied path (the {@code /api} management endpoints, or an unknown
 * prefix) falls through to the normal Spring handling.</p>
 *
 * <p>An unreachable application is a {@code 502} with a readable body, never a
 * stack trace, matching how the rest of the server degrades.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ReverseProxyFilter extends OncePerRequestFilter {

    /** Hop-by-hop / client-specific headers that must not be relayed either way. */
    private static final Set<String> SKIPPED_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "upgrade",
            "transfer-encoding", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final DeploymentRegistry registry;
    private final WebServerProperties properties;
    private final HttpClient client;

    public ReverseProxyFilter(DeploymentRegistry registry, WebServerProperties properties) {
        this.registry = registry;
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!properties.isProxyEnabled() || path == null || path.startsWith("/api")) {
            chain.doFilter(request, response);
            return;
        }
        RouteMatch match = match(registry.proxyRoutes(), path);
        if (match == null) {
            chain.doFilter(request, response);
            return;
        }
        forward(request, response, match);
    }

    private void forward(HttpServletRequest request, HttpServletResponse response, RouteMatch match)
            throws IOException {
        String target = "http://localhost:" + match.port() + match.remainingPath();
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            target = target + "?" + query;
        }
        String method = request.getMethod();
        HttpRequest.BodyPublisher body = hasBody(method)
                ? HttpRequest.BodyPublishers.ofByteArray(request.getInputStream().readAllBytes())
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                .timeout(REQUEST_TIMEOUT)
                .method(method, body);
        copyRequestHeaders(request, builder);

        try {
            HttpResponse<byte[]> upstream = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            writeResponse(response, upstream);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            badGateway(response, "interrupted while contacting the application");
        } catch (IOException e) {
            badGateway(response, "could not reach the application at " + match.contextPath()
                    + ": " + e.getMessage());
        }
    }

    private static void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name == null || SKIPPED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                builder.header(name, values.nextElement());
            }
        }
    }

    private static void writeResponse(HttpServletResponse response, HttpResponse<byte[]> upstream)
            throws IOException {
        response.setStatus(upstream.statusCode());
        for (Map.Entry<String, java.util.List<String>> header :
                upstream.headers().map().entrySet()) {
            String name = header.getKey();
            if (name == null || SKIPPED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String value : header.getValue()) {
                response.addHeader(name, value);
            }
        }
        byte[] payload = upstream.body();
        if (payload != null && payload.length > 0) {
            response.setContentLength(payload.length);
            response.getOutputStream().write(payload);
        }
    }

    private static void badGateway(HttpServletResponse response, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(HttpStatus.BAD_GATEWAY.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + escape(message) + "\"}");
    }

    private static boolean hasBody(String method) {
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method);
    }

    private static String escape(String message) {
        return message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Picks the longest {@code contextPath} that is a path-prefix of {@code path}.
     *
     * @param routes the running applications' {@code contextPath -> port}
     * @param path   the raw request URI
     * @return the match, or null when no deployed app owns this path
     */
    static RouteMatch match(Map<String, Integer> routes, String path) {
        if (routes == null || path == null) {
            return null;
        }
        String best = null;
        for (String contextPath : routes.keySet()) {
            if (contextPath == null || contextPath.isEmpty()) {
                continue;
            }
            if ((path.equals(contextPath) || path.startsWith(contextPath + "/"))
                    && (best == null || contextPath.length() > best.length())) {
                best = contextPath;
            }
        }
        if (best == null) {
            return null;
        }
        String remaining = path.substring(best.length());
        return new RouteMatch(best, routes.get(best), remaining.isEmpty() ? "/" : remaining);
    }

    /** A resolved proxy destination. */
    record RouteMatch(String contextPath, int port, String remainingPath) {
    }
}
