/*
 * Pins DeploymentStore's JSON persistence and its directory resolution order
 * (webserver.deploy-dir > lg3d.webserver.dir system property > ~/.lg3d/webserver),
 * plus the forgiving-read contract: a missing or corrupt file loads an empty table
 * rather than throwing, and a save creates its directories. Uses @TempDir and
 * restores the system property so it never touches the real ~/.lg3d.
 */
package org.jdesktop.lg3d.webserver.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentStoreTest {

    @TempDir
    Path tempDir;

    private static Deployment sample(String id) {
        return new Deployment(id, id, DeploymentType.DOCKER, "img:1", null, "/" + id,
                9001, Map.of("K", "V"), AppState.RUNNING, "cid", 123L, null);
    }

    @Test
    void roundTripsThroughJson() {
        new DeploymentStore(tempDir).save(List.of(sample("a"), sample("b")));
        assertThat(tempDir.resolve("deployments.json")).exists();

        List<Deployment> loaded = new DeploymentStore(tempDir).load();
        assertThat(loaded).hasSize(2);
        Deployment first = loaded.get(0);
        assertThat(first.id()).isEqualTo("a");
        assertThat(first.type()).isEqualTo(DeploymentType.DOCKER);
        assertThat(first.state()).isEqualTo(AppState.RUNNING);
        assertThat(first.port()).isEqualTo(9001);
        assertThat(first.runtimeId()).isEqualTo("cid");
        assertThat(first.envOrEmpty()).containsEntry("K", "V");
    }

    @Test
    void missingFileLoadsEmpty() {
        assertThat(new DeploymentStore(tempDir).load()).isEmpty();
    }

    @Test
    void corruptFileLoadsEmpty() throws IOException {
        Files.writeString(tempDir.resolve("deployments.json"), "{ this is not json");
        assertThat(new DeploymentStore(tempDir).load()).isEmpty();
    }

    @Test
    void saveCreatesDirectories() {
        Path nested = tempDir.resolve("deep/nested");
        new DeploymentStore(nested).save(List.of(sample("a")));
        assertThat(nested.resolve("deployments.json")).exists();
    }

    @Test
    void filePointsAtDeploymentsJson() {
        assertThat(new DeploymentStore(tempDir).file()).isEqualTo(tempDir.resolve("deployments.json"));
    }

    @Test
    void configuredDeployDirWins() {
        WebServerProperties properties = new WebServerProperties();
        properties.setDeployDir(tempDir.resolve("cfg").toString());
        assertThat(new DeploymentStore(properties).file().getParent())
                .isEqualTo(tempDir.resolve("cfg"));
    }

    @Test
    void systemPropertyOverridesWhenDeployDirBlank() {
        String previous = System.getProperty(DeploymentStore.DIR_PROPERTY);
        try {
            System.setProperty(DeploymentStore.DIR_PROPERTY, tempDir.resolve("sys").toString());
            assertThat(new DeploymentStore(new WebServerProperties()).file().getParent())
                    .isEqualTo(tempDir.resolve("sys"));
        } finally {
            restore(previous);
        }
    }

    @Test
    void defaultsToHomeLg3dWebserver() {
        String previous = System.getProperty(DeploymentStore.DIR_PROPERTY);
        try {
            System.clearProperty(DeploymentStore.DIR_PROPERTY);
            Path parent = new DeploymentStore(new WebServerProperties()).file().getParent();
            assertThat(parent.endsWith(Path.of(".lg3d", "webserver"))).isTrue();
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous != null) {
            System.setProperty(DeploymentStore.DIR_PROPERTY, previous);
        } else {
            System.clearProperty(DeploymentStore.DIR_PROPERTY);
        }
    }

    @Test
    void nullPropertiesFallsBackToTheHomeDefault() {
        String previous = System.getProperty(DeploymentStore.DIR_PROPERTY);
        try {
            System.clearProperty(DeploymentStore.DIR_PROPERTY);
            Path parent = new DeploymentStore((WebServerProperties) null).file().getParent();
            assertThat(parent.endsWith(Path.of(".lg3d", "webserver"))).isTrue();
        } finally {
            restore(previous);
        }
    }

    @Test
    void blankDeployDirFallsThroughToTheSystemProperty() {
        String previous = System.getProperty(DeploymentStore.DIR_PROPERTY);
        try {
            System.setProperty(DeploymentStore.DIR_PROPERTY, tempDir.resolve("sys").toString());
            WebServerProperties properties = new WebServerProperties();
            properties.setDeployDir("   ");
            assertThat(new DeploymentStore(properties).file().getParent())
                    .isEqualTo(tempDir.resolve("sys"));
        } finally {
            restore(previous);
        }
    }

    @Test
    void blankSystemPropertyFallsBackToTheHomeDefault() {
        String previous = System.getProperty(DeploymentStore.DIR_PROPERTY);
        try {
            System.setProperty(DeploymentStore.DIR_PROPERTY, "   ");
            Path parent = new DeploymentStore(new WebServerProperties()).file().getParent();
            assertThat(parent.endsWith(Path.of(".lg3d", "webserver"))).isTrue();
        } finally {
            restore(previous);
        }
    }

    @Test
    void saveSwallowsAnIoFailureRatherThanThrowing() throws IOException {
        Path notADirectory = tempDir.resolve("afile");
        Files.writeString(notADirectory, "i am a regular file, not a directory");
        DeploymentStore store = new DeploymentStore(notADirectory);
        // createDirectories() on an existing file raises FileAlreadyExistsException;
        // save is best effort, so it logs and returns rather than taking the API down.
        store.save(List.of(sample("a")));
        assertThat(store.file()).isEqualTo(notADirectory.resolve("deployments.json"));
    }
}
