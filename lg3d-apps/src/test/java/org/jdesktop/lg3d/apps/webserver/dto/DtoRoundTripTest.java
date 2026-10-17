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
package org.jdesktop.lg3d.apps.webserver.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Jackson round-trip tests for the console's client-side DTO records.
 *
 * <p>These records restate the {@code lg3d-webserver} wire shape so the console
 * never imports a server class; the round-trips pin that shape (every component
 * survives serialize &rarr; deserialize), the derived helpers, and the two
 * forward-compatibility guarantees the design leans on: unknown fields from a
 * newer server are ignored, and a {@link DeployRequest} omits its nulls so the
 * server's own derivation rules (name/context/port) still apply.</p>
 */
class DtoRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("HealthView round-trips and reports its liveness")
    void healthRoundTrips() throws Exception {
        HealthView health = new HealthView("UP", true, false, 3, 2);
        HealthView back = MAPPER.readValue(MAPPER.writeValueAsString(health), HealthView.class);
        assertThat(back).isEqualTo(health);
        assertThat(back.isUp()).isTrue();
        assertThat(new HealthView("DOWN", false, false, 0, 0).isUp()).isFalse();
    }

    @Test
    @DisplayName("HealthView ignores an unknown field from a newer server")
    void healthIgnoresUnknownField() throws Exception {
        HealthView health = MAPPER.readValue(
                "{\"status\":\"UP\",\"dockerAvailable\":true,\"jarAvailable\":true,"
                + "\"deployments\":1,\"running\":0,\"futureField\":\"x\"}",
                HealthView.class);
        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.dockerAvailable()).isTrue();
        assertThat(health.deployments()).isEqualTo(1);
    }

    @Test
    @DisplayName("MetricsView round-trips and computes the heap fraction")
    void metricsRoundTrips() throws Exception {
        MetricsView metrics = new MetricsView(50L, 100L, 12, 20, 1.5d, 8, 60000L);
        MetricsView back = MAPPER.readValue(MAPPER.writeValueAsString(metrics), MetricsView.class);
        assertThat(back).isEqualTo(metrics);
        assertThat(back.heapFraction()).isEqualTo(0.5d);
        assertThat(new MetricsView(0L, 0L, 0, 0, 0.0d, 0, 0L).heapFraction()).isZero();
    }

    @Test
    @DisplayName("ServerInfoView round-trips with its nested metrics")
    void infoRoundTrips() throws Exception {
        ServerInfoView info = new ServerInfoView("1.69.0-dev", 8080, "21.0.1", 1000L,
                new MetricsView(1L, 2L, 3, 4, 0.0d, 5, 6L));
        ServerInfoView back = MAPPER.readValue(MAPPER.writeValueAsString(info),
                ServerInfoView.class);
        assertThat(back).isEqualTo(info);
        assertThat(back.metrics().threadCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("AppView round-trips and derives running/source")
    void appRoundTrips() throws Exception {
        AppView docker = new AppView("shop", "Shop", "DOCKER", "nginx:latest", null,
                "/shop", 9001, "RUNNING", "abc123", 1000L, null,
                "http://localhost:9001/shop");
        AppView back = MAPPER.readValue(MAPPER.writeValueAsString(docker), AppView.class);
        assertThat(back).isEqualTo(docker);
        assertThat(back.isRunning()).isTrue();
        assertThat(back.source()).isEqualTo("nginx:latest");

        AppView jar = new AppView("j", "J", "JAR", null, "/tmp/x.jar", "/j", 9002,
                "STOPPED", null, 0L, "boom", "http://localhost:9002/j");
        assertThat(jar.isRunning()).isFalse();
        assertThat(jar.source()).isEqualTo("/tmp/x.jar");
    }

    @Test
    @DisplayName("LogsView round-trips and never returns null logs")
    void logsRoundTrips() throws Exception {
        LogsView logs = new LogsView("shop", "line1\nline2");
        LogsView back = MAPPER.readValue(MAPPER.writeValueAsString(logs), LogsView.class);
        assertThat(back).isEqualTo(logs);
        assertThat(new LogsView("x", null).logsOrEmpty()).isEmpty();
    }

    @Test
    @DisplayName("DeployRequest omits nulls and carries the type wire value")
    void deployRequestSerializes() throws Exception {
        String docker = MAPPER.writeValueAsString(DeployRequest.docker("nginx"));
        assertThat(docker).contains("\"type\":\"DOCKER\"").contains("\"image\":\"nginx\"");
        assertThat(docker).doesNotContain("jarPath");

        DeployRequest full = DeployRequest.jar("My App", "/tmp/x.jar", "/app", 9010);
        DeployRequest back = MAPPER.readValue(MAPPER.writeValueAsString(full),
                DeployRequest.class);
        assertThat(back).isEqualTo(full);
        assertThat(back.type()).isEqualTo(DeployRequest.TYPE_JAR);
        assertThat(back.port()).isEqualTo(9010);
    }

    @Test
    @DisplayName("DeployRequest carries environment variables")
    void deployRequestCarriesEnv() throws Exception {
        DeployRequest request = new DeployRequest("n", "DOCKER", "img", null, "/n", null,
                Map.of("KEY", "VALUE"));
        DeployRequest back = MAPPER.readValue(MAPPER.writeValueAsString(request),
                DeployRequest.class);
        assertThat(back.env()).containsEntry("KEY", "VALUE");
    }
}
