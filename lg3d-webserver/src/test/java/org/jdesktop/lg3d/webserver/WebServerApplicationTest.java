/*
 * The one full-context test: boots WebServerApplication on a random port with a
 * temp-dir state store, Docker disabled and the proxy off, so it needs neither a
 * daemon, a display nor a real fork. Proves the beans wire together and the live
 * HTTP surface behaves end to end -- health reports UP with dockerAvailable=false,
 * info reads the actual random port, a missing deployment type is a 400, and a
 * Docker deploy with the backend disabled degrades to a readable 503 rather than a
 * 500 stack trace.
 */
package org.jdesktop.lg3d.webserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webserver.docker-enabled=false",
                "webserver.proxy-enabled=false",
                "webserver.reconcile-interval-ms=3600000"
        })
class WebServerApplicationTest {

    @TempDir
    static Path stateDir;

    @DynamicPropertySource
    static void isolateStateDir(DynamicPropertyRegistry registry) {
        registry.add("webserver.deploy-dir", () -> stateDir.toString());
    }

    @Autowired
    private TestRestTemplate rest;

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void healthReportsUpWithoutADaemon() {
        ResponseEntity<String> health = rest.getForEntity("/api/server/health", String.class);
        assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(health.getBody())
                .contains("\"status\":\"UP\"")
                .contains("\"dockerAvailable\":false")
                .contains("\"jarAvailable\":true");
    }

    @Test
    void infoReportsTheLiveRandomPort() {
        ResponseEntity<String> info = rest.getForEntity("/api/server/info", String.class);
        assertThat(info.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(info.getBody()).contains("\"version\":\"dev\"");
        assertThat(info.getBody()).doesNotContain("\"port\":0");
    }

    @Test
    void deployWithoutTypeIsBadRequest() {
        ResponseEntity<String> response =
                rest.postForEntity("/api/apps", json("{\"image\":\"shop:1.0\"}"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("error");
    }

    @Test
    void dockerDeployWithBackendDisabledIsServiceUnavailable() {
        ResponseEntity<String> response = rest.postForEntity("/api/apps",
                json("{\"type\":\"DOCKER\",\"image\":\"shop:1.0\"}"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).contains("not available");
    }
}
