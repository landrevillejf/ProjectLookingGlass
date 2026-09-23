package com.protonmail.landrevillejf.swingide.update;

import com.protonmail.landrevillejf.swingide.core.bus.EventBus;
import com.protonmail.landrevillejf.swingide.update.channels.UpdateChannel;
import com.protonmail.landrevillejf.swingide.update.downgrade.DowngradeManager.DowngradeVersion;
import com.protonmail.landrevillejf.swingide.update.scheduling.UpdateScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;

/**
 * Covers the Phase 6 wiring of {@link UpdateService}: release channels,
 * scheduled installations, downgrade archives and changelog rendering.
 */
class UpdateServicePhase6Test {

    private static final String FORCED_VERSION = "0.5.0";
    private static final String REMOTE_CHANGELOG_URL = "https://example.com/changelog.md";

    @TempDir
    Path tempDir;

    private RecordingUpdateRepository repository;
    private Path changelogFile;
    private Path downgradeDirectory;
    private UpdateService service;

    /** The forced version the test JVM started with (the test task sets 0.0.0). */
    private String originalVersionProperty;

    @BeforeEach
    void setUp() {
        originalVersionProperty = System.getProperty(ApplicationVersion.VERSION_PROPERTY);
        System.setProperty(ApplicationVersion.VERSION_PROPERTY, FORCED_VERSION);
        repository = new RecordingUpdateRepository();
        changelogFile = tempDir.resolve("changelog.md");
        downgradeDirectory = tempDir.resolve("versions");
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
        if (originalVersionProperty == null) {
            System.clearProperty(ApplicationVersion.VERSION_PROPERTY);
        } else {
            System.setProperty(ApplicationVersion.VERSION_PROPERTY, originalVersionProperty);
        }
    }

    private UpdateService newService(Properties config) {
        if (service != null) {
            service.shutdown();
        }
        // Each service owns its bus: UpdateService.shutdown() closes it.
        service = new UpdateService(repository, new EventBus(), config);
        return service;
    }

    private Properties baseConfig() {
        Properties config = new Properties();
        config.setProperty("update.enabled", "true");
        config.setProperty("update.notify.on.startup", "false");
        config.setProperty("update.channel", "stable");
        config.setProperty(UpdateService.CONFIG_TRAY_ENABLED, "false");
        config.setProperty(UpdateService.CONFIG_DOWNGRADE_DIR, downgradeDirectory.toString());
        config.setProperty(UpdateService.CONFIG_DOWNGRADE_KEEP, "3");
        config.setProperty(UpdateService.CONFIG_CHANGELOG_PATH, changelogFile.toString());
        config.setProperty(UpdateService.CONFIG_RESTART_DELAY, "5");
        return config;
    }

    private static UpdateInfo update(String version) {
        return update(version, REMOTE_CHANGELOG_URL);
    }

    private static UpdateInfo update(String version, String changelogUrl) {
        return new UpdateInfo(
            version,
            Instant.now(),
            "https://example.com/swing-ide-" + version + "-all.jar",
            52_428_800L,
            "sha256-" + version,
            "https://example.com/swing-ide-" + version + "-all.jar.asc",
            false,
            changelogUrl,
            "21",
            false
        );
    }

    @Test
    void testCurrentVersionIsReported() {
        UpdateService updateService = newService(baseConfig());

        assertThat(updateService.getCurrentVersion()).isEqualTo(FORCED_VERSION);
        assertThat(updateService.getNotificationManager()).isNotNull();
    }

    @Test
    void testConfiguredChannelIsUsed() {
        Properties config = baseConfig();
        config.setProperty("update.channel", "beta");

        UpdateService updateService = newService(config);

        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.BETA);
        assertThat(updateService.getUpdateChannel()).isEqualTo("beta");
    }

    @Test
    void testUnknownChannelFallsBackToStable() {
        Properties config = baseConfig();
        config.setProperty("update.channel", "experimental");

        assertThat(newService(config).getChannel()).isEqualTo(UpdateChannel.STABLE);
    }

    @Test
    void testMissingConfigurationFallsBackToDefaults() {
        UpdateService updateService = newService(new Properties());

        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.STABLE);
        assertThat(updateService.isUpdateEnabled()).isTrue();
        assertThat(updateService.isTrayNotificationsEnabled()).isTrue();
        assertThat(updateService.isAutoDownloadEnabled()).isFalse();
        assertThat(updateService.isAutoInstallEnabled()).isFalse();
    }

    @Test
    void testNullConfigurationIsTolerated() {
        UpdateService updateService = newService(null);

        assertThat(updateService.isUpdateEnabled()).isTrue();
        assertThat(updateService.getChannel()).isEqualTo(UpdateChannel.STABLE);
    }

    @Test
    void testStableChannelIgnoresPreReleases() {
        repository.setAvailableUpdate(update("0.6.0-beta1"));

        assertThat(newService(baseConfig()).checkForUpdatesNow()).isNull();
    }

    @Test
    void testBetaChannelAcceptsPreReleases() {
        repository.setAvailableUpdate(update("0.6.0-beta1"));
        Properties config = baseConfig();
        config.setProperty("update.channel", "beta");

        UpdateInfo found = newService(config).checkForUpdatesNow();

        assertThat(found).isNotNull();
        assertThat(found.getVersion()).isEqualTo("0.6.0-beta1");
    }

    @Test
    void testStableChannelAcceptsFinalReleases() {
        repository.setAvailableUpdate(update("0.6.0"));

        UpdateInfo found = newService(baseConfig()).checkForUpdatesNow();

        assertThat(found).isNotNull();
        assertThat(found.getVersion()).isEqualTo("0.6.0");
    }

    @Test
    void testAsyncCheckPublishesTheAvailableUpdate() throws Exception {
        repository.setAvailableUpdate(update("0.6.0"));
        UpdateService updateService = newService(baseConfig());

        UpdateInfo found = updateService.checkForUpdatesAsync().get();

        assertThat(found).isNotNull();
        assertThat(found.getVersion()).isEqualTo("0.6.0");
    }

    @Test
    void testScheduleInstallRegistersAndCancelsTheTask() {
        UpdateService updateService = newService(baseConfig());
        LocalDateTime installTime = LocalDateTime.now().plusHours(2);

        String taskId = updateService.scheduleInstall(update("0.6.0"), installTime, true);

        assertThat(taskId).isNotBlank();
        assertThat(updateService.getScheduledInstalls()).hasSize(1);

        UpdateScheduler.ScheduledUpdateTask task = updateService.getScheduledInstalls().get(0);
        assertThat(task.getTaskId()).isEqualTo(taskId);
        assertThat(task.getUpdateVersion()).isEqualTo("0.6.0");
        assertThat(task.getInstallTime()).isEqualTo(installTime);
        assertThat(task.isAutoRestart()).isTrue();

        assertThat(updateService.cancelScheduledInstall(taskId)).isTrue();
        assertThat(updateService.getScheduledInstalls()).isEmpty();
        assertThat(updateService.cancelScheduledInstall(taskId)).isFalse();
    }

    @Test
    void testScheduleInstallRejectsPastTimes() {
        UpdateService updateService = newService(baseConfig());

        assertThatThrownBy(() ->
            updateService.scheduleInstall(update("0.6.0"), LocalDateTime.now().minusHours(1), false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("future");
    }

    @Test
    void testSchedulerIsSharedAndConfigured() {
        Properties config = baseConfig();
        config.setProperty(UpdateService.CONFIG_RESTART_DELAY, "17");
        UpdateService updateService = newService(config);

        UpdateScheduler scheduler = updateService.getScheduler();

        assertThat(scheduler).isSameAs(updateService.getScheduler());
        assertThat(scheduler.getRestartDelaySeconds()).isEqualTo(17);
    }

    @Test
    void testInvalidRestartDelayFallsBackToTheDefault() {
        Properties config = baseConfig();
        config.setProperty(UpdateService.CONFIG_RESTART_DELAY, "soon");

        assertThat(newService(config).getScheduler().getRestartDelaySeconds())
            .isEqualTo(UpdateScheduler.DEFAULT_RESTART_DELAY_SECONDS);
    }

    @Test
    void testDowngradeDirectoryHonoursConfiguration() {
        UpdateService updateService = newService(baseConfig());

        assertThat(updateService.getDowngradeManager().getBackupDirectory())
            .isEqualTo(downgradeDirectory);
        assertThat(updateService.getAvailableDowngrades()).isEmpty();
        assertThat(downgradeDirectory).doesNotExist();
    }

    @Test
    void testAvailableDowngradesListsArchives() throws Exception {
        UpdateService updateService = newService(baseConfig());
        Path archivedJar = tempDir.resolve("previous.jar");
        Files.writeString(archivedJar, "previous payload");

        updateService.getDowngradeManager().registerBackup("0.4.0", archivedJar, null);

        assertThat(updateService.getAvailableDowngrades())
            .extracting(DowngradeVersion::getVersion)
            .containsExactly("0.4.0");
    }

    @Test
    void testDowngradeOfUnknownVersionFailsGracefully() {
        UpdateService updateService = newService(baseConfig());

        assertThat(updateService.downgradeTo("9.9.9")).isFalse();
    }

    @Test
    void testLocalChangelogIsRendered() throws Exception {
        Files.writeString(changelogFile, """
            ## Version 0.6.0 (2026-09-11)
            - [NEW] Release channels
            - [FIX] Downgrade archives

            ## Version 0.5.0 (2026-08-01)
            - [NEW] Signature verification
            """);
        UpdateService updateService = newService(baseConfig());

        Optional<String> entryHtml = updateService.getLocalChangelogHtml("0.6.0");
        assertThat(entryHtml).isPresent();
        assertThat(entryHtml.get()).contains("Release channels").doesNotContain("Signature verification");

        Optional<String> fullHtml = updateService.getLocalChangelogHtml(null);
        assertThat(fullHtml).isPresent();
        assertThat(fullHtml.get()).contains("Signature verification");
    }

    @Test
    void testLocalChangelogIsAbsentWhenTheFileIsMissing() {
        UpdateService updateService = newService(baseConfig());

        assertThat(updateService.getLocalChangelogHtml("0.6.0")).isEmpty();
        assertThat(updateService.getChangelogHtml(update("0.6.0", ""))).isEmpty();
    }

    @Test
    void testRemoteChangelogIsPreferred() {
        repository.setChangelog("""
            ## Version 0.6.0 (2026-09-11)
            - [NEW] Remote entry
            """);
        UpdateService updateService = newService(baseConfig());

        Optional<String> html = updateService.getChangelogHtml(update("0.6.0"));

        assertThat(html).isPresent();
        assertThat(html.get()).contains("Remote entry");
    }

    @Test
    void testRemoteChangelogFallsBackToTheFullHistory() {
        repository.setChangelog("""
            ## Version 9.9.9 (2026-09-11)
            - [NEW] Unrelated entry
            """);
        UpdateService updateService = newService(baseConfig());

        Optional<String> html = updateService.getChangelogHtml(update("0.6.0"));

        assertThat(html).isPresent();
        assertThat(html.get()).contains("Unrelated entry");
    }

    @Test
    void testChangelogFallsBackToLocalWhenRemoteFails() throws Exception {
        repository.setChangelogFailure(true);
        Files.writeString(changelogFile, """
            ## Version 0.6.0 (2026-09-11)
            - [NEW] Local entry
            """);
        UpdateService updateService = newService(baseConfig());

        Optional<String> html = updateService.getChangelogHtml(update("0.6.0"));

        assertThat(html).isPresent();
        assertThat(html.get()).contains("Local entry");
    }

    @Test
    void testChangelogWithoutUpdateRendersTheLocalHistory() throws Exception {
        Files.writeString(changelogFile, """
            ## Version 0.6.0 (2026-09-11)
            - [NEW] Local entry
            """);
        UpdateService updateService = newService(baseConfig());

        Optional<String> html = updateService.getChangelogHtml(null);

        assertThat(html).isPresent();
        assertThat(html.get()).contains("Local entry");
    }

    @Test
    void testTrayNotificationsCanBeDisabled() {
        Properties config = baseConfig();
        assertThat(newService(config).isTrayNotificationsEnabled()).isFalse();

        config.setProperty(UpdateService.CONFIG_TRAY_ENABLED, "true");
        assertThat(newService(config).isTrayNotificationsEnabled()).isTrue();
    }

    @Test
    void testInitializeAndShutdown() {
        repository.setAvailableUpdate(update("0.6.0"));
        UpdateService updateService = newService(baseConfig());

        assertThatCode(() -> {
            updateService.initialize();
            updateService.shutdown();
        }).doesNotThrowAnyException();

        assertThat(updateService.getScheduledInstalls()).isEmpty();
        service = null;
    }

    @Test
    void testInitializeDoesNothingWhenUpdatesAreDisabled() {
        Properties config = baseConfig();
        config.setProperty("update.enabled", "false");
        UpdateService updateService = newService(config);

        assertThat(updateService.isUpdateEnabled()).isFalse();
        assertThatCode(updateService::initialize).doesNotThrowAnyException();
    }

    @Test
    void testActionListenerCanBeRegistered() {
        UpdateService updateService = newService(baseConfig());

        assertThatCode(() -> updateService.setActionListener(
            new UpdateService.UpdateActionListener() { })).doesNotThrowAnyException();
    }

    /**
     * Update repository returning canned metadata so no network access happens.
     */
    private static final class RecordingUpdateRepository extends UpdateRepository {

        private UpdateInfo availableUpdate;
        private String changelog;
        private boolean changelogFailure;

        RecordingUpdateRepository() {
            super("http://mock.local");
        }

        void setAvailableUpdate(UpdateInfo update) {
            this.availableUpdate = update;
        }

        void setChangelog(String changelog) {
            this.changelog = changelog;
        }

        void setChangelogFailure(boolean changelogFailure) {
            this.changelogFailure = changelogFailure;
        }

        @Override
        public UpdateInfo fetchLatestVersion() {
            return availableUpdate;
        }

        @Override
        public String fetchChangelog(String changelogUrl) throws UpdateException {
            if (changelogFailure) {
                throw new UpdateException("Changelog unreachable");
            }
            return changelog;
        }
    }
}
