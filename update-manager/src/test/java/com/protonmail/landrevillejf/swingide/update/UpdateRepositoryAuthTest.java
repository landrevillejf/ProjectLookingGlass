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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end behaviour of {@link UpdateRepository} against a private-style
 * endpoint that only answers when a bearer token is presented, mirroring a
 * GitHub release asset on a private repository.
 */
class UpdateRepositoryAuthTest {

    private static final String TOKEN = "secret-token";

    private static final String VERSION_JSON = """
        {
          "latestVersion": "9.9.9",
          "releaseDate": "2026-01-01T00:00:00Z",
          "platforms": {
            "universal": {
              "url": "https://example.invalid/swing-ide-9.9.9-all.jar",
              "size": 2048,
              "sha256": "deadbeef",
              "signatureUrl": ""
            }
          },
          "critical": false,
          "changelog": "https://example.invalid/changelog.md",
          "minJavaVersion": "21",
          "breakingChanges": false
        }
        """;

    private HttpServer server;
    private String protectedUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();

        // Serves the metadata only to an authenticated caller, like a private
        // GitHub release asset; everyone else gets 404.
        server.createContext("/version.json", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (("Bearer " + TOKEN).equals(auth)) {
                respond(exchange, 200, VERSION_JSON);
            } else {
                respond(exchange, 404, "");
            }
        });

        server.start();
        protectedUrl = "http://localhost:" + port + "/version.json";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testAuthenticatedFetchParsesTheMetadata() throws Exception {
        UpdateRepository repository = new UpdateRepository(protectedUrl, TOKEN);

        UpdateInfo info = repository.fetchLatestVersion();

        assertThat(info.getVersion()).isEqualTo("9.9.9");
        assertThat(info.getDownloadUrl()).isEqualTo("https://example.invalid/swing-ide-9.9.9-all.jar");
        assertThat(info.getSha256()).isEqualTo("deadbeef");
    }

    @Test
    void testAnonymousFetchIsReportedAsServerUnavailable() {
        UpdateRepository repository = new UpdateRepository(protectedUrl, null);

        assertThatThrownBy(repository::fetchLatestVersion)
            .isInstanceOf(UpdateServerUnavailableException.class)
            .satisfies(error -> assertThat(((UpdateServerUnavailableException) error).getStatusCode())
                .isEqualTo(404));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
