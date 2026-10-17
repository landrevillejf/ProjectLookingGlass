/*
 * Pins the Deployment record's immutability contract: each lifecycle helper returns
 * a NEW copy, preserving the identity/spec (id, name, type, image, port, context
 * path, env) while changing only state/runtimeId/lastError -- running() clears the
 * error, stopped() clears the runtime id and error, failed() keeps the runtime id
 * and records the reason.
 */
package org.jdesktop.lg3d.webserver.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DeploymentTest {

    private static Deployment sample() {
        return new Deployment("shop", "Shop", DeploymentType.DOCKER, "shop:1.0", null,
                "/shop", 9001, Map.of("A", "B"), AppState.STOPPED, null, 1000L, null);
    }

    @Test
    void runningCarriesRuntimeIdAndClearsError() {
        Deployment running = sample().failed("boom").running("cid-1");
        assertThat(running.state()).isEqualTo(AppState.RUNNING);
        assertThat(running.runtimeId()).isEqualTo("cid-1");
        assertThat(running.lastError()).isNull();
        assertThat(running.isRunning()).isTrue();
    }

    @Test
    void stoppedClearsRuntimeIdAndError() {
        Deployment stopped = sample().running("cid-1").stopped();
        assertThat(stopped.state()).isEqualTo(AppState.STOPPED);
        assertThat(stopped.runtimeId()).isNull();
        assertThat(stopped.lastError()).isNull();
        assertThat(stopped.isRunning()).isFalse();
    }

    @Test
    void failedKeepsRuntimeIdAndRecordsReason() {
        Deployment failed = sample().running("cid-1").failed("port already bound");
        assertThat(failed.state()).isEqualTo(AppState.FAILED);
        assertThat(failed.runtimeId()).isEqualTo("cid-1");
        assertThat(failed.lastError()).isEqualTo("port already bound");
    }

    @Test
    void transitionsPreserveIdentityAndSpec() {
        Deployment running = sample().running("cid-1");
        assertThat(running.id()).isEqualTo("shop");
        assertThat(running.name()).isEqualTo("Shop");
        assertThat(running.type()).isEqualTo(DeploymentType.DOCKER);
        assertThat(running.image()).isEqualTo("shop:1.0");
        assertThat(running.contextPath()).isEqualTo("/shop");
        assertThat(running.port()).isEqualTo(9001);
        assertThat(running.deployedAt()).isEqualTo(1000L);
        assertThat(running.envOrEmpty()).containsEntry("A", "B");
    }

    @Test
    void envOrEmptyIsNeverNull() {
        Deployment noEnv = new Deployment("a", "a", DeploymentType.JAR, null, "/x.jar",
                "/a", 9002, null, AppState.STOPPED, null, 1L, null);
        assertThat(noEnv.envOrEmpty()).isEmpty();
    }
}
