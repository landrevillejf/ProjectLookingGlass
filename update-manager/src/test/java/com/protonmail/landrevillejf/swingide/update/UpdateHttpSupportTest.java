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
package com.protonmail.landrevillejf.swingide.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redirect and bearer-token behaviour of {@link UpdateHttpSupport}, exercised
 * against a local {@link HttpServer}. The private GitHub release flow is a chain
 * of redirects that ends on a different host serving a pre-signed URL, so the
 * token must be kept on same-host hops and dropped once the host changes.
 */
class UpdateHttpSupportTest {

    private static final String TOKEN = "secret-token";
    private static final String BODY = "{\"latestVersion\":\"9.9.9\"}";

    private HttpServer server;
    private HttpClient client;
    private int port;

    /** Records whether the last request to {@code /final} carried the token. */
    private final AtomicReference<String> finalAuthHeader = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        // Same-host redirect: the token must survive to /final.
        server.createContext("/redirect-same", exchange ->
            redirect(exchange, "http://localhost:" + port + "/final"));

        // Cross-host redirect (localhost -> 127.0.0.1): the token must be dropped.
        server.createContext("/redirect-cross", exchange ->
            redirect(exchange, "http://127.0.0.1:" + port + "/final"));

        server.createContext("/final", exchange -> {
            finalAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, BODY);
        });

        // Always redirects to itself, to exercise the loop guard.
        server.createContext("/loop", exchange ->
            redirect(exchange, "http://localhost:" + port + "/loop"));

        server.start();

        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testTokenIsKeptOnASameHostRedirect() throws Exception {
        HttpResponse<InputStream> response = UpdateHttpSupport.get(
            client, URI.create("http://localhost:" + port + "/redirect-same"), TOKEN, Duration.ofSeconds(10)
        );

        try (InputStream in = response.body()) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(BODY);
        }
        assertThat(finalAuthHeader.get()).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void testTokenIsDroppedOnACrossHostRedirect() throws Exception {
        // Start on localhost, get redirected to 127.0.0.1: a different host, so
        // the credential must not follow.
        HttpResponse<InputStream> response = UpdateHttpSupport.get(
            client, URI.create("http://localhost:" + port + "/redirect-cross"), TOKEN, Duration.ofSeconds(10)
        );

        try (InputStream in = response.body()) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(BODY);
        }
        assertThat(finalAuthHeader.get()).isNull();
    }

    @Test
    void testAnonymousRequestCarriesNoAuthorizationHeader() throws Exception {
        UpdateHttpSupport.get(
            client, URI.create("http://localhost:" + port + "/final"), null, Duration.ofSeconds(10)
        ).body().close();

        assertThat(finalAuthHeader.get()).isNull();
    }

    @Test
    void testRedirectLoopIsBounded() {
        assertThatThrownBy(() -> UpdateHttpSupport.get(
            client, URI.create("http://localhost:" + port + "/loop"), TOKEN, Duration.ofSeconds(10)
        ))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Too many redirects");
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
