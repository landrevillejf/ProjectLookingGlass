/*
 * Pins ReverseProxyFilter two ways. First the pure routing decision (match()):
 * longest-context-prefix wins, an exact context yields "/", a partial segment like
 * "/shopping" does not match "/shop", and null/empty inputs are safe. Then real
 * forwarding against an in-JVM com.sun.net.httpserver backend: a GET/POST is
 * relayed with its method, path and query, an unreachable app degrades to a
 * readable 502 (never a stack trace), and a disabled proxy, an /api path or an
 * unknown prefix all fall through to the chain.
 */
package org.jdesktop.lg3d.webserver.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.jdesktop.lg3d.webserver.deploy.DeploymentRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReverseProxyFilterTest {

    private final WebServerProperties properties = new WebServerProperties();
    private HttpServer backend;

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    // ---- routing decision -------------------------------------------------

    @Test
    void matchPicksLongestContextPrefix() {
        Map<String, Integer> routes = Map.of("/shop", 9001, "/shop/admin", 9002);
        ReverseProxyFilter.RouteMatch match = ReverseProxyFilter.match(routes, "/shop/admin/users");
        assertThat(match.contextPath()).isEqualTo("/shop/admin");
        assertThat(match.port()).isEqualTo(9002);
        assertThat(match.remainingPath()).isEqualTo("/users");
    }

    @Test
    void matchExactContextYieldsRoot() {
        ReverseProxyFilter.RouteMatch match = ReverseProxyFilter.match(Map.of("/shop", 9001), "/shop");
        assertThat(match.contextPath()).isEqualTo("/shop");
        assertThat(match.remainingPath()).isEqualTo("/");
    }

    @Test
    void matchReturnsNullWhenNoRouteOwnsPath() {
        assertThat(ReverseProxyFilter.match(Map.of("/shop", 9001), "/other")).isNull();
        assertThat(ReverseProxyFilter.match(null, "/shop")).isNull();
        assertThat(ReverseProxyFilter.match(Map.of("/shop", 9001), null)).isNull();
    }

    @Test
    void matchDoesNotSplitASegment() {
        assertThat(ReverseProxyFilter.match(Map.of("/shop", 9001), "/shopping")).isNull();
    }

    @Test
    void matchSkipsEmptyContextPath() {
        Map<String, Integer> routes = new HashMap<>();
        routes.put("", 9001);
        assertThat(ReverseProxyFilter.match(routes, "/anything")).isNull();
    }

    // ---- forwarding -------------------------------------------------------

    @Test
    void forwardsGetToRunningApp() throws Exception {
        int port = startEchoBackend();
        ReverseProxyFilter filter = filterOver(Map.of("/app", port));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/app/hello"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("path=/hello").contains("method=GET");
    }

    @Test
    void forwardsQueryString() throws Exception {
        int port = startEchoBackend();
        ReverseProxyFilter filter = filterOver(Map.of("/app", port));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/search");
        request.setQueryString("q=lg3d&page=2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getContentAsString()).contains("query=q=lg3d&page=2");
    }

    @Test
    void forwardsPostWithBodyAndMethod() throws Exception {
        int port = startEchoBackend();
        ReverseProxyFilter filter = filterOver(Map.of("/app", port));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/submit");
        request.setContent("payload".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("method=POST").contains("path=/submit");
    }

    @Test
    void unreachableAppIsBadGateway() throws Exception {
        ReverseProxyFilter filter = filterOver(Map.of("/dead", freePort()));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/dead/x"), response, chain);

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(response.getContentAsString()).contains("could not reach");
    }

    @Test
    void disabledProxyFallsThrough() throws Exception {
        properties.setProxyEnabled(false);
        ReverseProxyFilter filter = filterOver(Map.of("/app", 9001));

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/app/x"),
                new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void apiPathFallsThrough() throws Exception {
        ReverseProxyFilter filter = filterOver(Map.of("/app", 9001));
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/apps"),
                new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void unknownPrefixFallsThrough() throws Exception {
        ReverseProxyFilter filter = filterOver(Map.of("/app", 9001));
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/unknown/x"),
                new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    private ReverseProxyFilter filterOver(Map<String, Integer> routes) {
        DeploymentRegistry registry = mock(DeploymentRegistry.class);
        when(registry.proxyRoutes()).thenReturn(routes);
        return new ReverseProxyFilter(registry, properties);
    }

    private int startEchoBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        backend.createContext("/", exchange -> {
            String body = "path=" + exchange.getRequestURI().getPath()
                    + " method=" + exchange.getRequestMethod()
                    + " query=" + exchange.getRequestURI().getQuery();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        backend.start();
        return backend.getAddress().getPort();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
