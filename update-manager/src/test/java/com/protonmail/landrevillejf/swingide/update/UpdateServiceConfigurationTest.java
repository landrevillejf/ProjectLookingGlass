package com.protonmail.landrevillejf.swingide.update;

import com.protonmail.landrevillejf.swingide.core.bus.EventBus;
import com.protonmail.landrevillejf.swingide.update.channels.UpdateChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuration layering of {@link UpdateService}: the packaged
 * {@code update-config.properties} is overridden by the user file, and the
 * settings edited at runtime are applied and persisted.
 */
class UpdateServiceConfigurationTest {

    private static final String FORCED_VERSION = "0.5.0";

    @TempDir
    Path tempDir;

    private Path configDir;
    private RecordingUpdateRepository repository;
    private UpdateService service;
    private String previousConfigDir;
    private String previousVersion;

    @BeforeEach
    void setUp() {
        previousConfigDir = System.getProperty(UpdateService.CONFIG_DIR_PROPERTY);
        previousVersion = System.getProperty(ApplicationVersion.VERSION_PROPERTY);
        configDir = tempDir.resolve("config");
        System.setProperty(UpdateService.CONFIG_DIR_PROPERTY, configDir.toString());
        System.setProperty(ApplicationVersion.VERSION_PROPERTY, FORCED_VERSION);

        repository = new RecordingUpdateRepository();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
            service = null;
        }

        // Restore the JVM's original forced version instead of clearing it: the
        // suite shares one JVM, and clearing lg3d.version here would leak to
        // later classes so ApplicationVersion.current() falls back to the
        // packaged 1.9.0-dev and breaks their version comparisons.
        if (previousVersion == null) {
            System.clearProperty(ApplicationVersion.VERSION_PROPERTY);
        } else {
            System.setProperty(ApplicationVersion.VERSION_PROPERTY, previousVersion);
        }

        if (previousConfigDir == null) {
            System.clearProperty(UpdateService.CONFIG_DIR_PROPERTY);
        } else {
            System.setProperty(UpdateService.CONFIG_DIR_PROPERTY, previousConfigDir);
        }
    }

    private UpdateService newService() {
        if (service != null) {
            service.shutdown();
        }
        // The two argument constructor is the only one reading the user file.
        service = new UpdateService(repository, new EventBus());
        return service;
    }

    private void writeUserConfig(Properties properties) throws IOException {
        Files.createDirectories(configDir);
        Path target = configDir.resolve(UpdateService.USER_CONFIG_FILE_NAME);

        try (OutputStream out = Files.newOutputStream(target)) {
            properties.store(out, "test configuration");
        }
    }

    @Test
    void testUserConfigFileHonoursTheConfigDirProperty() {
        assertThat(UpdateService.getUserConfigFile())
            .isEqualTo(configDir.resolve(UpdateService.USER_CONFIG_FILE_NAME));
    }

    @Test
    void testUserConfigFileDefaultsToTheUserHome() {
        System.clearProperty(UpdateService.CONFIG_DIR_PROPERTY);

        assertThat(UpdateService.getUserConfigFile())
            .isEqualTo(Path.of(System.getProperty("user.home"), ".lg3d",
                UpdateService.USER_CONFIG_FILE_NAME));
    }

    @Test
    void testPackagedDefaultsAreLoadedWhenNoUserFileExists() {
        UpdateService updateService = newService();

        assertThat(updateService.getConfiguration())
            .containsEntry("update.url",
                "https://github.com/landrevillejf/ProjectLookingGlass/releases/latest/download/version.json");
        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.STABLE);
        assertThat(UpdateService.getUserConfigFile()).doesNotExist();
    }

    @Test
    void testUserFileOverridesThePackagedDefaults() throws IOException {
        Properties overrides = new Properties();
        overrides.setProperty("update.channel", "nightly");
        overrides.setProperty("update.enabled", "false");
        writeUserConfig(overrides);

        UpdateService updateService = newService();

        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.NIGHTLY);
        assertThat(updateService.isUpdateEnabled()).isFalse();
        // Keys absent from the user file keep their packaged value.
        assertThat(updateService.getConfiguration())
            .containsEntry("update.url",
                "https://github.com/landrevillejf/ProjectLookingGlass/releases/latest/download/version.json");
    }

    @Test
    void testUserFileWithUnrelatedEntriesKeepsTheDefaults() throws IOException {
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve(UpdateService.USER_CONFIG_FILE_NAME),
            "# hand edited\nsome.other.key=value\n");

        UpdateService updateService = newService();

        // Unknown keys must never disturb the packaged defaults.
        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.STABLE);
        assertThat(updateService.isUpdateEnabled()).isTrue();
    }

    @Test
    void testSaveConfigurationWritesTheUserFile() throws IOException {
        Properties configuration = new Properties();
        configuration.setProperty("update.channel", "beta");
        configuration.setProperty("update.check.interval.hours", "6");

        assertThat(UpdateService.saveConfiguration(configuration)).isTrue();

        Path saved = UpdateService.getUserConfigFile();
        assertThat(saved).isRegularFile();

        Properties reloaded = new Properties();
        try (java.io.InputStream in = Files.newInputStream(saved)) {
            reloaded.load(in);
        }
        assertThat(reloaded)
            .containsEntry("update.channel", "beta")
            .containsEntry("update.check.interval.hours", "6");
    }

    @Test
    void testSaveConfigurationRejectsNull() {
        assertThat(UpdateService.saveConfiguration(null)).isFalse();
    }

    @Test
    void testSavedConfigurationIsReadBackByTheNextService() throws IOException {
        Properties configuration = new Properties();
        configuration.setProperty("update.channel", "beta");
        assertThat(UpdateService.saveConfiguration(configuration)).isTrue();

        UpdateService updateService = newService();

        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.BETA);
    }

    @Test
    void testGetConfigurationReturnsACopy() {
        UpdateService updateService = newService();

        Properties copy = updateService.getConfiguration();
        copy.setProperty("update.channel", "nightly");

        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.STABLE);
    }

    @Test
    void testApplyConfigurationChangesTheChannelAtRuntime() {
        UpdateService updateService = newService();
        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.STABLE);

        Properties updates = new Properties();
        updates.setProperty("update.channel", "beta");
        updateService.applyConfiguration(updates);

        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.BETA);
        assertThat(updateService.getUpdateChannel()).isEqualTo("beta");
        assertThat(updateService.getConfiguration()).containsEntry("update.channel", "beta");
    }

    @Test
    void testApplyConfigurationKeepsUnrelatedKeys() {
        UpdateService updateService = newService();

        Properties updates = new Properties();
        updates.setProperty("update.enabled", "false");
        updateService.applyConfiguration(updates);

        assertThat(updateService.isUpdateEnabled()).isFalse();
        assertThat(updateService.getConfiguration())
            .containsEntry("update.url",
                "https://github.com/landrevillejf/ProjectLookingGlass/releases/latest/download/version.json");
    }

    @Test
    void testApplyConfigurationDoesNotPersist() {
        UpdateService updateService = newService();

        Properties updates = new Properties();
        updates.setProperty("update.channel", "nightly");
        updateService.applyConfiguration(updates);

        assertThat(UpdateService.getUserConfigFile()).doesNotExist();
    }

    @Test
    void testApplyConfigurationIgnoresNullOrEmpty() {
        UpdateService updateService = newService();

        updateService.applyConfiguration(null);
        updateService.applyConfiguration(new Properties());

        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.STABLE);
    }

    @Test
    void testApplyConfigurationFallsBackOnStableForAnUnknownChannel() {
        UpdateService updateService = newService();

        Properties updates = new Properties();
        updates.setProperty("update.channel", "not-a-channel");
        updateService.applyConfiguration(updates);

        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.STABLE);
    }

    @Test
    void testSkippedVersionIsNotProposedAgain() throws IOException {
        UpdateService updateService = newService();
        repository.setAvailableUpdate(update("0.6.0", false));

        assertThat(updateService.checkForUpdatesNow()).isNotNull();

        updateService.skipVersion("0.6.0");

        assertThat(updateService.getSkippedVersion()).isEqualTo("0.6.0");
        assertThat(updateService.checkForUpdatesNow()).isNull();

        // The choice is persisted for the next run.
        Properties saved = new Properties();
        try (java.io.InputStream in = Files.newInputStream(UpdateService.getUserConfigFile())) {
            saved.load(in);
        }
        assertThat(saved).containsEntry(UpdateService.CONFIG_SKIP_VERSION, "0.6.0");
    }

    @Test
    void testCriticalUpdateBypassesTheSkippedVersion() {
        UpdateService updateService = newService();
        updateService.skipVersion("0.6.0");
        repository.setAvailableUpdate(update("0.6.0", true));

        UpdateInfo proposed = updateService.checkForUpdatesNow();

        assertThat(proposed).isNotNull();
        assertThat(proposed.isCritical()).isTrue();
    }

    @Test
    void testClearSkippedVersionProposesItAgain() {
        UpdateService updateService = newService();
        repository.setAvailableUpdate(update("0.6.0", false));
        updateService.skipVersion("0.6.0");
        assertThat(updateService.checkForUpdatesNow()).isNull();

        updateService.clearSkippedVersion();

        assertThat(updateService.getSkippedVersion()).isNull();
        assertThat(updateService.checkForUpdatesNow()).isNotNull();
    }

    @Test
    void testSkipVersionIgnoresBlankValues() {
        UpdateService updateService = newService();

        updateService.skipVersion(null);
        updateService.skipVersion("   ");

        assertThat(updateService.getSkippedVersion()).isNull();
        assertThat(UpdateService.getUserConfigFile()).doesNotExist();
    }

    @Test
    void testSkipVersionIsTrimmed() {
        UpdateService updateService = newService();

        updateService.skipVersion("  0.6.0  ");

        assertThat(updateService.getSkippedVersion()).isEqualTo("0.6.0");
    }

    @Test
    void testAuthTokenPrefersTheConfiguredProperty() {
        Properties cfg = new Properties();
        cfg.setProperty(UpdateService.CONFIG_GITHUB_TOKEN, "  from-config  ");
        java.util.Map<String, String> env = java.util.Map.of(
            "LG3D_UPDATE_TOKEN", "from-env",
            "GITHUB_TOKEN", "from-github"
        );

        assertThat(UpdateService.resolveAuthToken(cfg, env::get)).isEqualTo("from-config");
    }

    @Test
    void testAuthTokenFallsBackOnTheEnvironment() {
        Properties cfg = new Properties();
        assertThat(UpdateService.resolveAuthToken(cfg,
            java.util.Map.of("LG3D_UPDATE_TOKEN", "lg3d-token")::get))
            .isEqualTo("lg3d-token");

        assertThat(UpdateService.resolveAuthToken(cfg,
            java.util.Map.of("GITHUB_TOKEN", "github-token")::get))
            .isEqualTo("github-token");
    }

    @Test
    void testAuthTokenIsNullWhenNothingIsConfigured() {
        assertThat(UpdateService.resolveAuthToken(new Properties(), name -> null)).isNull();
    }

    private static UpdateInfo update(String version, boolean critical) {
        return new UpdateInfo(
            version,
            Instant.now(),
            "https://example.com/swing-ide-" + version + "-all.jar",
            1024L,
            "abc123",
            "https://example.com/swing-ide-" + version + "-all.jar.asc",
            critical,
            "https://example.com/changelog.md",
            "21",
            false
        );
    }

    /**
     * Repository serving a canned update without touching the network.
     */
    private static final class RecordingUpdateRepository extends UpdateRepository {

        private UpdateInfo availableUpdate;

        RecordingUpdateRepository() {
            super("http://mock.local");
        }

        void setAvailableUpdate(UpdateInfo update) {
            this.availableUpdate = update;
        }

        @Override
        public UpdateInfo fetchLatestVersion() {
            return availableUpdate;
        }
    }
}
