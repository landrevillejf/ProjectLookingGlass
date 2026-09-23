package com.protonmail.landrevillejf.swingide.update;

import com.protonmail.landrevillejf.swingide.core.bus.EventBus;
import com.protonmail.landrevillejf.swingide.update.channels.UpdateChannel;
import com.protonmail.landrevillejf.swingide.update.changelog.ChangelogManager;
import com.protonmail.landrevillejf.swingide.update.downgrade.DowngradeManager;
import com.protonmail.landrevillejf.swingide.update.downgrade.DowngradeManager.DowngradeException;
import com.protonmail.landrevillejf.swingide.update.downgrade.DowngradeManager.DowngradeVersion;
import com.protonmail.landrevillejf.swingide.update.events.UpdateAvailableEvent;
import com.protonmail.landrevillejf.swingide.update.events.UpdateProgressEvent;
import com.protonmail.landrevillejf.swingide.update.notifications.UpdateNotificationManager;
import com.protonmail.landrevillejf.swingide.update.privilege.InstallLocationGuard;
import com.protonmail.landrevillejf.swingide.update.rollback.RollbackTrigger;
import com.protonmail.landrevillejf.swingide.update.scheduling.UpdateScheduler;
import com.protonmail.landrevillejf.swingide.update.security.PGPKeyManager;
import com.protonmail.landrevillejf.swingide.update.security.UpdateSecurityException;
import com.protonmail.landrevillejf.swingide.update.security.UpdateSignatureGate;
import com.protonmail.landrevillejf.swingide.update.security.UpdateSignatureVerifier;
import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the whole update pipeline: detection, download, verification,
 * installation, notifications, scheduled installations, changelog rendering and
 * version downgrade.
 */
@Slf4j
public class UpdateService {

    /** Configuration key enabling desktop notifications. */
    public static final String CONFIG_TRAY_ENABLED = "update.notifications.tray.enabled";

    /** Configuration key holding the downgrade archive directory. */
    public static final String CONFIG_DOWNGRADE_DIR = "update.downgrade.dir";

    /** Configuration key holding the number of downgrade archives to keep. */
    public static final String CONFIG_DOWNGRADE_KEEP = "update.downgrade.keep";

    /** Configuration key holding the local changelog path. */
    public static final String CONFIG_CHANGELOG_PATH = "update.changelog.path";

    /** Configuration key holding the restart delay of a scheduled installation. */
    public static final String CONFIG_RESTART_DELAY = "update.schedule.restart.delay.seconds";

    /** System property redirecting the directory holding the user configuration file. */
    public static final String CONFIG_DIR_PROPERTY = "lg3d.config.dir";

    /** Configuration key holding the version the user refused to be offered again. */
    public static final String CONFIG_SKIP_VERSION = "update.skip.version";

    /** Configuration key enabling OpenPGP signature verification. */
    public static final String CONFIG_SIGNATURE_ENABLED = "update.signature.enabled";

    /** Configuration key holding the classpath resource of the bundled release key. */
    public static final String CONFIG_SIGNATURE_KEY_RESOURCE = "update.signature.public.key.resource";

    /** Configuration key holding the default signing key id. */
    public static final String CONFIG_SIGNATURE_KEY_ID = "update.signature.key.id";

    /** Configuration key holding the expected signing key fingerprint. */
    public static final String CONFIG_SIGNATURE_FINGERPRINT = "update.signature.fingerprint";

    /** Configuration key making a verifiable signature mandatory. */
    public static final String CONFIG_SIGNATURE_REQUIRED = "update.signature.required";

    /** Configuration key enabling privilege escalation for protected installs. */
    public static final String CONFIG_PRIVILEGE_ESCALATION = "update.privilege.escalation.enabled";

    /** Configuration key enabling the failed-update rollback trigger. */
    public static final String CONFIG_ROLLBACK_ENABLED = "update.rollback.enabled";

    /** Configuration key holding the update metadata endpoint URL. */
    public static final String CONFIG_UPDATE_URL = "update.url";

    /**
     * Configuration key holding a bearer token for a private release host. When
     * blank the {@code LG3D_UPDATE_TOKEN} then {@code GITHUB_TOKEN}
     * environment variables are consulted.
     */
    public static final String CONFIG_GITHUB_TOKEN = "update.github.token";

    /** Name of the user configuration file overriding the packaged defaults. */
    public static final String USER_CONFIG_FILE_NAME = "update-config.properties";

    private static final String CONFIG_RESOURCE = USER_CONFIG_FILE_NAME;
    private static final String DEFAULT_CONFIG_DIR_NAME = ".lg3d";
    private static final int DEFAULT_DOWNGRADE_KEEP = 3;
    private static final String DEFAULT_CHANGELOG_FILE = "changelog.md";

    private final UpdateRepository repository;
    private final UpdateDownloader downloader;
    private final UpdateInstaller installer;
    private final UpdateVerifier verifier;
    private final Properties config;
    private final String currentVersion;
    private final EventBus eventBus;
    private final UpdateNotificationManager notifications;

    /**
     * Whether this service created its event bus. A bus handed over by the host
     * is shared with the plugins and must survive {@link #shutdown()}.
     */
    private final boolean ownsEventBus;

    private final Map<String, UpdateInfo> scheduledInstalls = new ConcurrentHashMap<>();
    private final Map<String, String> scheduledVersions = new ConcurrentHashMap<>();

    private volatile UpdateChecker checker;
    private volatile UpdateChannel channel;
    private volatile long checkIntervalMs;
    private volatile boolean automaticChecksActive;
    private UpdateScheduler scheduler;
    private DowngradeManager downgradeManager;
    private UpdateActionListener actionListener;

    /** Lazily created PGP signature gate; injectable for tests. */
    private volatile UpdateSignatureGate signatureGate;

    /** Lazily created guard checking the installation target is writable. */
    private volatile InstallLocationGuard installGuard;

    /** Lazily created rollback trigger evaluating the start-up health. */
    private volatile RollbackTrigger rollbackTrigger;

    /** Verified update JARs staged by the auto-download feature, by version. */
    private final Map<String, Path> stagedUpdates = new ConcurrentHashMap<>();

    /** Versions currently being staged, to avoid concurrent duplicate downloads. */
    private final Set<String> stagingInProgress = ConcurrentHashMap.newKeySet();

    /** Shutdown hook applying a staged update when auto-install is enabled. */
    private volatile Thread autoInstallHook;

    /**
     * Creates a service with its own event bus and the layered configuration.
     * <p>
     * The metadata endpoint is taken from {@code update.url}; see
     * {@link #createDefault()} for the recommended factory that reads the
     * configuration once and shares it with the repository.
     * </p>
     */
    public UpdateService() {
        this(createDefaultRepository(), null, null);
    }

    /**
     * Creates a service with its own event bus and the layered configuration.
     *
     * @param repository source of the server metadata
     */
    public UpdateService(UpdateRepository repository) {
        this(repository, null, null);
    }

    /**
     * Creates a service publishing on an existing bus, typically the one shared
     * by the host and its plugins.
     *
     * @param repository source of the server metadata
     * @param eventBus   bus to publish on; {@code null} creates a private one
     */
    public UpdateService(UpdateRepository repository, EventBus eventBus) {
        this(repository, eventBus, null);
    }

    /**
     * @param repository source of the server metadata
     * @param eventBus   bus to publish on; {@code null} creates a private one,
     *                   which {@link #shutdown()} then closes
     * @param config     settings to run with; {@code null} loads the packaged
     *                   defaults overlaid by the user configuration file
     */
    public UpdateService(UpdateRepository repository, EventBus eventBus, Properties config) {
        this.config = config != null ? config : loadConfig();
        this.repository = repository;
        this.eventBus = eventBus != null ? eventBus : new EventBus();
        this.ownsEventBus = eventBus == null;
        this.currentVersion = ApplicationVersion.current();
        this.channel = UpdateChannel.parse(this.config.getProperty("update.channel", "stable"));
        this.checkIntervalMs = resolveCheckIntervalMs(this.config, channel);

        this.checker = newChecker(this.checkIntervalMs, this.channel);
        this.downloader = new UpdateDownloader(null, resolveAuthToken(this.config));
        this.installer = new UpdateInstaller();
        this.verifier = new UpdateVerifier();
        this.notifications = new UpdateNotificationManager();
    }

    /**
     * Builds a service on a shared event bus using the layered configuration.
     * <p>
     * Preferred entry point for hosts that already own the bus: it loads the
     * configuration once, honours {@code update.url} when constructing the
     * {@link UpdateRepository}, then hands both over to the service.
     * </p>
     *
     * @param eventBus bus to publish on; {@code null} creates a private one
     * @return the wired service
     */
    public static UpdateService createDefault(EventBus eventBus) {
        Properties cfg = loadConfig();
        return new UpdateService(createRepository(cfg), eventBus, cfg);
    }

    /**
     * Builds a service with its own event bus using the layered configuration.
     *
     * @return the wired service
     */
    public static UpdateService createDefault() {
        return createDefault(null);
    }

    /**
     * Repository pointing at the URL configured under {@value #CONFIG_UPDATE_URL},
     * or at {@link UpdateRepository}'s built-in default when the key is absent
     * or blank.
     */
    private static UpdateRepository createDefaultRepository() {
        return createRepository(loadConfig());
    }

    private static UpdateRepository createRepository(Properties cfg) {
        String url = cfg.getProperty(CONFIG_UPDATE_URL, "").trim();
        String token = resolveAuthToken(cfg);
        return new UpdateRepository(url.isEmpty() ? null : url, token);
    }

    /**
     * Resolves the bearer token used to reach a private release host: the
     * {@value #CONFIG_GITHUB_TOKEN} property first, then the
     * {@code LG3D_UPDATE_TOKEN} and {@code GITHUB_TOKEN} environment
     * variables.
     *
     * @return the token, or {@code null} when none is configured
     */
    private static String resolveAuthToken(Properties cfg) {
        return resolveAuthToken(cfg, System::getenv);
    }

    /**
     * Testable variant of {@link #resolveAuthToken(Properties)}.
     *
     * @param cfg settings to read {@value #CONFIG_GITHUB_TOKEN} from
     * @param env environment lookup, so a test can inject variables
     * @return the token, or {@code null} when none is configured
     */
    static String resolveAuthToken(Properties cfg, java.util.function.Function<String, String> env) {
        String token = cfg.getProperty(CONFIG_GITHUB_TOKEN, "").trim();
        if (!token.isEmpty()) {
            return token;
        }
        token = env.apply("LG3D_UPDATE_TOKEN");
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        token = env.apply("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        return null;
    }

    /**
     * Creates a checker wired to this service.
     * <p>
     * Periodic checks publish straight to the event bus without going through
     * {@link #detectUpdate()}, so the skip list is enforced through the checker
     * filter; otherwise a version the user refused would be proposed again at
     * every interval.
     * </p>
     */
    private UpdateChecker newChecker(long intervalMs, UpdateChannel updateChannel) {
        UpdateChecker created = new UpdateChecker(repository, intervalMs, eventBus, updateChannel);
        created.setUpdateFilter(update -> !isSkipped(update));
        return created;
    }

    /**
     * Event bus the update events are published on.
     * <p>
     * Hosts need it to follow the download progress; when the bus was injected
     * it is the shared application bus.
     * </p>
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    public void initialize() {
        if (!isUpdateEnabled()) {
            log.info("Update service disabled");
            return;
        }

        log.info("Update service initialized (current version: {}, channel: {})",
            currentVersion, channel.getConfigValue());

        initializeNotifications();
        subscribeAutoProcessing();

        boolean notifyOnStartup = Boolean.parseBoolean(
            config.getProperty("update.notify.on.startup", "true")
        );

        // The first check runs on the checker thread. initialize() is called from
        // the EDT by both IDE entry points, so a synchronous check here would
        // freeze the UI for the duration of a network round trip - and the
        // scheduler used to fire immediately anyway, checking twice.
        checker.scheduleAutomaticChecks(notifyOnStartup ? 0 : checkIntervalMs);
        automaticChecksActive = true;
    }

    public void shutdown() {
        log.info("Shutting down update service");
        automaticChecksActive = false;
        checker.stopAutomaticChecks();
        downloader.shutdown();

        UpdateScheduler currentScheduler;
        synchronized (this) {
            currentScheduler = scheduler;
            scheduler = null;
        }
        if (currentScheduler != null) {
            currentScheduler.shutdown();
        }

        notifications.removeTray();
        scheduledInstalls.clear();
        scheduledVersions.clear();

        if (ownsEventBus) {
            eventBus.shutdown();
        } else {
            // The bus belongs to the host and is still used by the plugins.
            log.debug("Leaving the shared event bus running");
        }
    }

    /**
     * Synchronous update check, filtered through the configured channel.
     *
     * @return the available update, or {@code null} when the IDE is up to date
     */
    public UpdateInfo checkForUpdatesNow() {
        log.info("Manual update check requested");
        return detectAndNotify();
    }

    /**
     * Runs an update check on a background thread and publishes an
     * {@link UpdateAvailableEvent} when a newer version is found.
     *
     * @return a future holding the available update, or {@code null}
     */
    public CompletableFuture<UpdateInfo> checkForUpdatesAsync() {
        return CompletableFuture.supplyAsync(() -> {
            UpdateInfo update = detectUpdate();

            if (update != null) {
                eventBus.publish(new UpdateAvailableEvent(update, currentVersion));
                notifications.notifyUpdateAvailable(update.getVersion(), update.getFormattedSize());
            }

            return update;
        });
    }

    /**
     * Runs an update check on a background thread without publishing an
     * {@link UpdateAvailableEvent}.
     * <p>
     * Hosts already displaying their own dialog use this variant so the user is
     * not asked twice.
     * </p>
     *
     * @return a future holding the available update, or {@code null}
     */
    public CompletableFuture<UpdateInfo> checkForUpdatesQuietly() {
        return CompletableFuture.supplyAsync(this::detectAndNotify);
    }

    private UpdateInfo detectAndNotify() {
        UpdateInfo update = detectUpdate();

        if (update != null) {
            notifications.notifyUpdateAvailable(update.getVersion(), update.getFormattedSize());
        }

        return update;
    }

    /**
     * Update proposed by the server, filtered through the channel and the version
     * the user asked to skip.
     */
    private UpdateInfo detectUpdate() {
        UpdateInfo update = checker.checkForUpdates();

        if (isSkipped(update)) {
            log.info("Version {} was skipped by the user, not proposing it", update.getVersion());
            return null;
        }

        return update;
    }

    /**
     * Critical updates are always proposed, whatever the user skipped before.
     */
    private boolean isSkipped(UpdateInfo update) {
        if (update == null || update.isCritical()) {
            return false;
        }

        String skipped = getSkippedVersion();
        return skipped != null && skipped.equals(update.getVersion());
    }

    /**
     * Remembers a version the user does not want to be offered again, and stores
     * the choice in {@link #getUserConfigFile()}.
     *
     * @param version version to ignore, blank values are ignored
     */
    public void skipVersion(String version) {
        if (version == null || version.isBlank()) {
            log.debug("No version to skip");
            return;
        }

        config.setProperty(CONFIG_SKIP_VERSION, version.trim());

        if (saveConfiguration(config)) {
            log.info("Version {} will not be proposed again", version.trim());
        }
    }

    /**
     * Proposes the skipped version again.
     */
    public void clearSkippedVersion() {
        if (config.remove(CONFIG_SKIP_VERSION) != null) {
            saveConfiguration(config);
            log.info("The skipped version has been forgotten");
        }
    }

    /**
     * Version the user asked to skip, or {@code null} when none is recorded.
     */
    public String getSkippedVersion() {
        String skipped = config.getProperty(CONFIG_SKIP_VERSION, "").trim();
        return skipped.isEmpty() ? null : skipped;
    }

    public CompletableFuture<Void> downloadAndInstall(UpdateInfo update) {
        return CompletableFuture.runAsync(() -> {
            try {
                installNow(update);
            } catch (UpdateException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * Downloads, verifies and installs an update on the calling thread. The JVM
     * is restarted by the platform specific installation script, so this method
     * never returns on success.
     *
     * @param update the update to install
     * @throws UpdateException when any stage of the pipeline fails
     */
    public void installNow(UpdateInfo update) throws UpdateException {
        try {
            Path downloadedFile = obtainVerifiedJar(update);
            verifySignature(downloadedFile, update);
            verify(downloadedFile, update);

            ensureTargetWritable();

            Path rollbackArchive = prepareDowngradeBackup();
            markRollbackPending();

            notifications.notifyInstallationStarted(update.getVersion());
            installer.installUpdate(downloadedFile, rollbackArchive);

            // Not reached: installUpdate exits the JVM
        } catch (UpdateException e) {
            log.error("Update to {} failed: {}", update.getVersion(), e.getMessage());
            notifications.notifyError("Update failed", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("Update failed", e);
            notifications.notifyError("Update failed", String.valueOf(e.getMessage()));
            throw new UpdateException("Update installation failed", e);
        }
    }

    /**
     * Returns the update JAR to install, reusing a copy already downloaded and
     * staged by the auto-download feature instead of fetching it twice.
     */
    private Path obtainVerifiedJar(UpdateInfo update) throws UpdateException {
        Path staged = stagedUpdates.get(update.getVersion());

        if (staged != null && Files.isRegularFile(staged)) {
            log.info("Reusing the staged download for version {}: {}", update.getVersion(), staged);
            return staged;
        }

        return download(update);
    }

    /**
     * Verifies the OpenPGP detached signature when the feature is enabled. A
     * disabled gate is a no-op; a present-but-invalid signature always fails.
     */
    private void verifySignature(Path downloadedFile, UpdateInfo update) throws UpdateException {
        if (!isSignatureVerificationEnabled()) {
            return;
        }

        try {
            getSignatureGate().verify(
                downloadedFile,
                update.getSignatureUrl(),
                update.getSigningKeyId()
            );
        } catch (UpdateSecurityException e) {
            notifications.notifyVerificationFailed(e.getMessage());
            throw new UpdateException("Signature verification failed: " + e.getMessage(), e);
        }
    }

    /**
     * Makes sure the running JAR can be replaced, requesting elevated privileges
     * when the IDE lives in a protected location.
     */
    private void ensureTargetWritable() throws UpdateException {
        if (!JarLocator.isRunningFromJar()) {
            log.debug("Not running from a JAR, skipping the writability check");
            return;
        }

        getInstallLocationGuard().ensureWritable(installer.getCurrentJarPath());
    }

    /**
     * Records the running version so the next start can roll back to it when the
     * update turns out to be broken.
     */
    private void markRollbackPending() {
        if (!isRollbackEnabled() || !JarLocator.isRunningFromJar()) {
            return;
        }

        getRollbackTrigger().markPendingUpdate(currentVersion);
    }

    /**
     * Subscribes the automatic download/install pipeline to update availability.
     * Every path that publishes an {@link UpdateAvailableEvent} (periodic checks
     * and asynchronous manual checks) then honours {@code update.auto.download}
     * and {@code update.auto.install}.
     */
    private void subscribeAutoProcessing() {
        eventBus.subscribe(UpdateAvailableEvent.class, event -> autoProcess(event.getUpdateInfo()));
    }

    /**
     * Silently downloads (and, when requested, defers the installation of) an
     * update, driven purely by the configuration.
     */
    private void autoProcess(UpdateInfo update) {
        if (update == null || !isUpdateEnabled() || !isAutoDownloadEnabled()) {
            return;
        }

        stageUpdate(update).whenComplete((staged, failure) -> {
            if (failure != null || staged == null) {
                log.warn("Automatic download of version {} failed: {}",
                    update.getVersion(), failure != null ? failure.getMessage() : "no file");
                return;
            }

            if (isAutoInstallEnabled()) {
                scheduleInstallOnExit(update, staged);
            }
        });
    }

    /**
     * Downloads and verifies an update in the background without installing it,
     * so a later installation (manual or deferred) reuses the staged file.
     *
     * @param update the update to stage
     * @return a future holding the verified JAR path
     */
    public CompletableFuture<Path> stageUpdate(UpdateInfo update) {
        Path alreadyStaged = stagedUpdates.get(update.getVersion());
        if (alreadyStaged != null && Files.isRegularFile(alreadyStaged)) {
            return CompletableFuture.completedFuture(alreadyStaged);
        }

        if (!stagingInProgress.add(update.getVersion())) {
            log.debug("Version {} is already being downloaded", update.getVersion());
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                Path file = download(update);
                verifySignature(file, update);
                verify(file, update);

                stagedUpdates.put(update.getVersion(), file);
                notifications.notifyInfo(
                    "Update downloaded",
                    "Version " + update.getVersion() + " is ready to be installed."
                );
                log.info("Version {} staged at {}", update.getVersion(), file);
                return file;
            } catch (UpdateException e) {
                throw new CompletionException(e);
            } finally {
                stagingInProgress.remove(update.getVersion());
            }
        });
    }

    /**
     * Verified update staged by {@link #stageUpdate(UpdateInfo)}, if any.
     */
    public Optional<Path> getStagedUpdate(String version) {
        Path staged = stagedUpdates.get(version);
        return staged != null && Files.isRegularFile(staged) ? Optional.of(staged) : Optional.empty();
    }

    /**
     * Registers a shutdown hook applying the staged update when the IDE closes,
     * so the new version is active on the next launch without interrupting work.
     */
    private synchronized void scheduleInstallOnExit(UpdateInfo update, Path stagedJar) {
        if (autoInstallHook != null) {
            log.debug("A deferred installation is already scheduled");
            return;
        }

        Thread hook = new Thread(
            () -> applyStagedUpdateOnExit(update, stagedJar),
            "Update-AutoInstall"
        );

        try {
            Runtime.getRuntime().addShutdownHook(hook);
            autoInstallHook = hook;
            notifications.notifyInfo(
                "Update scheduled",
                "Version " + update.getVersion() + " will be installed when Project Looking Glass closes."
            );
            log.info("Version {} will be applied on exit", update.getVersion());
        } catch (IllegalStateException e) {
            log.warn("The JVM is already shutting down, applying the update immediately");
            applyStagedUpdateOnExit(update, stagedJar);
        }
    }

    private void applyStagedUpdateOnExit(UpdateInfo update, Path stagedJar) {
        try {
            ensureTargetWritable();
            Path rollbackArchive = prepareDowngradeBackup();
            markRollbackPending();
            installer.installOnExit(stagedJar, rollbackArchive);
            log.info("Update to {} applied on exit", update.getVersion());
        } catch (UpdateException | RuntimeException e) {
            log.error("Could not apply the staged update on exit: {}", e.getMessage());
            notifications.notifyError("Update failed", String.valueOf(e.getMessage()));
        }
    }

    private Path download(UpdateInfo update) throws UpdateException {
        log.info("Starting download for version: {}", update.getVersion());

        UpdateDownloader.DownloadResult downloadResult = downloader
            .downloadUpdate(
                update.getDownloadUrl(),
                (percent, status) -> {
                    log.debug("Download progress: {}% - {}", percent, status);
                    eventBus.publish(new UpdateProgressEvent(percent, status));
                })
            .join();

        if (!downloadResult.isSuccess()) {
            throw new UpdateException("Download failed: " + downloadResult.getMessage());
        }

        log.info("Download completed: {}", downloadResult.getFile());
        notifications.notifyDownloadComplete(update.getVersion());

        return downloadResult.getFile();
    }

    private void verify(Path downloadedFile, UpdateInfo update) throws UpdateException {
        UpdateVerifier.VerificationResult verificationResult =
            verifier.verify(downloadedFile, update.getSha256());

        if (!verificationResult.isValid()) {
            notifications.notifyVerificationFailed(verificationResult.getMessage());
            throw new UpdateException("Verification failed: " + verificationResult.getMessage());
        }

        log.info("Verification successful");
    }

    /**
     * Archives the running version so it can be restored later, and returns the
     * archive handed over to the installer as its rollback copy.
     *
     * @return the archived JAR, or {@code null} when no archive could be created
     */
    private Path prepareDowngradeBackup() {
        if (!JarLocator.isRunningFromJar()) {
            log.debug("Not running from a JAR, skipping the downgrade archive");
            return null;
        }

        try {
            DowngradeManager manager = getDowngradeManager();
            Path currentJar = JarLocator.getCurrentJarPath();

            DowngradeVersion archived = manager.registerBackup(
                currentVersion,
                currentJar,
                ApplicationVersion.buildTimestamp()
            );
            manager.cleanupOldBackups(getDowngradeKeepCount());

            return archived.getJarPath();

        } catch (DowngradeException | RuntimeException e) {
            log.warn("Could not archive the running version, the installer will create its own backup", e);
            return null;
        }
    }

    /**
     * Downloads, verifies and installs an update at the requested time.
     *
     * @param update       the update to install
     * @param installTime  when the installation must run
     * @param autoRestart  whether a restart is requested once installed
     * @return the scheduled task identifier, usable to cancel or reschedule it
     */
    public String scheduleInstall(UpdateInfo update, LocalDateTime installTime, boolean autoRestart) {
        UpdateScheduler updateScheduler = getScheduler();
        String taskId = updateScheduler.scheduleUpdate(update.getVersion(), installTime, autoRestart);
        scheduledInstalls.put(taskId, update);
        scheduledVersions.put(taskId, update.getVersion());

        notifications.notifyInfo(
            "Update scheduled",
            String.format("Version %s will be installed at %s", update.getVersion(), installTime)
        );

        return taskId;
    }

    /**
     * Cancels a scheduled installation.
     */
    public boolean cancelScheduledInstall(String taskId) {
        scheduledInstalls.remove(taskId);
        scheduledVersions.remove(taskId);
        return getScheduler().cancelScheduledUpdate(taskId);
    }

    /**
     * Scheduled installations waiting for their time.
     */
    public List<UpdateScheduler.ScheduledUpdateTask> getScheduledInstalls() {
        return getScheduler().getScheduledUpdates();
    }

    private void installScheduledUpdate(UpdateScheduler.ScheduledUpdateTask task) throws UpdateException {
        UpdateInfo update = scheduledInstalls.get(task.getTaskId());

        if (update == null) {
            throw new UpdateException(
                "No update registered for the scheduled task " + task.getTaskId()
            );
        }

        scheduledInstalls.remove(task.getTaskId());
        installNow(update);
    }

    /**
     * Versions that can be restored from the local downgrade archives.
     */
    public List<DowngradeVersion> getAvailableDowngrades() {
        return getDowngradeManager().getAvailableVersions();
    }

    /**
     * Restores a previous version. The application must be restarted afterwards.
     *
     * @param version the version to restore
     * @return {@code true} when the JAR was replaced successfully
     */
    public boolean downgradeTo(String version) {
        try {
            Path currentJar = JarLocator.getCurrentJarPath();
            getDowngradeManager().restoreVersion(version, currentJar, currentVersion);

            log.info("Downgraded to version {}, a restart is required", version);
            notifications.notifyInfo(
                "Downgrade complete",
                "Version " + version + " has been restored. Please restart the IDE."
            );
            return true;

        } catch (DowngradeException | RuntimeException e) {
            log.error("Downgrade to version {} failed", version, e);
            notifications.notifyError("Downgrade failed", String.valueOf(e.getMessage()));
            return false;
        }
    }

    /**
     * Renders the changelog of an update as HTML, falling back on the local
     * changelog when the remote one cannot be fetched.
     *
     * @param update the update whose changelog is requested
     * @return the HTML page, empty when no changelog is available
     */
    public Optional<String> getChangelogHtml(UpdateInfo update) {
        if (update == null) {
            return getLocalChangelogHtml(null);
        }

        String changelogUrl = update.getChangelogUrl();
        if (changelogUrl == null || changelogUrl.isBlank()) {
            return getLocalChangelogHtml(update.getVersion());
        }

        try {
            String markdown = repository.fetchChangelog(changelogUrl);
            ChangelogManager manager = ChangelogManager.fromMarkdown(markdown);

            Optional<String> entryHtml = manager.getEntryHtml(update.getVersion());
            if (entryHtml.isPresent()) {
                return entryHtml;
            }

            return manager.getAllEntries().isEmpty()
                ? getLocalChangelogHtml(update.getVersion())
                : Optional.of(manager.toHtml());

        } catch (UpdateException | RuntimeException e) {
            log.warn("Could not fetch the changelog from {}: {}", changelogUrl, e.getMessage());
            return getLocalChangelogHtml(update.getVersion());
        }
    }

    /**
     * Renders the changelog shipped with the IDE (or the one pointed at by
     * {@code update.changelog.path}).
     *
     * @param version version to display, or {@code null} for the whole history
     * @return the HTML page, empty when no local changelog exists
     */
    public Optional<String> getLocalChangelogHtml(String version) {
        Path changelogPath = getLocalChangelogPath();

        if (changelogPath == null || !Files.isRegularFile(changelogPath)) {
            log.debug("No local changelog available");
            return Optional.empty();
        }

        ChangelogManager manager = new ChangelogManager(changelogPath);

        if (version != null) {
            Optional<String> entryHtml = manager.getEntryHtml(version);
            if (entryHtml.isPresent()) {
                return entryHtml;
            }
        }

        return manager.getAllEntries().isEmpty()
            ? Optional.empty()
            : Optional.of(manager.toHtml());
    }

    private Path getLocalChangelogPath() {
        String configured = config.getProperty(CONFIG_CHANGELOG_PATH, "").trim();

        if (!configured.isEmpty()) {
            return Path.of(configured);
        }

        Path workingDirCandidate = Path.of(System.getProperty("user.dir"), DEFAULT_CHANGELOG_FILE);
        return Files.isRegularFile(workingDirCandidate) ? workingDirCandidate : null;
    }

    /**
     * Desktop notification component used by the update pipeline.
     */
    public UpdateNotificationManager getNotificationManager() {
        return notifications;
    }

    /**
     * Lazily created scheduler driving the deferred installations.
     */
    public synchronized UpdateScheduler getScheduler() {
        if (scheduler == null) {
            scheduler = new UpdateScheduler();
            scheduler.setRestartDelaySeconds(getRestartDelaySeconds());
            scheduler.setInstallHandler(this::installScheduledUpdate);
            scheduler.setListener(new SchedulerNotifications());
        }
        return scheduler;
    }

    /**
     * Lazily created manager holding the versioned downgrade archives.
     */
    public synchronized DowngradeManager getDowngradeManager() {
        if (downgradeManager == null) {
            downgradeManager = new DowngradeManager(getDowngradeDirectory());
        }
        return downgradeManager;
    }

    /**
     * Lazily created PGP signature gate built from the current configuration.
     */
    public synchronized UpdateSignatureGate getSignatureGate() {
        if (signatureGate == null) {
            PGPKeyManager keyManager = new PGPKeyManager();
            UpdateSignatureVerifier verifier = new UpdateSignatureVerifier(keyManager);
            signatureGate = new UpdateSignatureGate(
                keyManager,
                verifier,
                repository::downloadTo,
                config.getProperty(CONFIG_SIGNATURE_KEY_RESOURCE, "public-key.asc").trim(),
                config.getProperty(CONFIG_SIGNATURE_KEY_ID, "").trim(),
                config.getProperty(CONFIG_SIGNATURE_FINGERPRINT, "").trim(),
                isSignatureRequired()
            );
        }
        return signatureGate;
    }

    /**
     * Lazily created guard checking that the installation target is writable.
     */
    public synchronized InstallLocationGuard getInstallLocationGuard() {
        if (installGuard == null) {
            installGuard = new InstallLocationGuard(isPrivilegeEscalationEnabled());
        }
        return installGuard;
    }

    /**
     * Lazily created rollback trigger reusing the downgrade archives.
     */
    public synchronized RollbackTrigger getRollbackTrigger() {
        if (rollbackTrigger == null) {
            rollbackTrigger = new RollbackTrigger(getDowngradeManager());
        }
        return rollbackTrigger;
    }

    /**
     * Overrides the signature gate, used by tests to inject a deterministic
     * verification chain.
     */
    public synchronized void setSignatureGate(UpdateSignatureGate signatureGate) {
        this.signatureGate = signatureGate;
    }

    /**
     * Overrides the install location guard, used by tests.
     */
    public synchronized void setInstallLocationGuard(InstallLocationGuard installGuard) {
        this.installGuard = installGuard;
    }

    /**
     * Overrides the rollback trigger, used by tests and hosts providing a real
     * restart action.
     */
    public synchronized void setRollbackTrigger(RollbackTrigger rollbackTrigger) {
        this.rollbackTrigger = rollbackTrigger;
    }

    /**
     * Whether OpenPGP signature verification runs before an install.
     */
    public boolean isSignatureVerificationEnabled() {
        return Boolean.parseBoolean(config.getProperty(CONFIG_SIGNATURE_ENABLED, "false"));
    }

    /**
     * Whether a signature that cannot be verified must fail the update.
     */
    public boolean isSignatureRequired() {
        return Boolean.parseBoolean(config.getProperty(CONFIG_SIGNATURE_REQUIRED, "false"));
    }

    /**
     * Whether the IDE may prompt for administrative privileges on install.
     */
    public boolean isPrivilegeEscalationEnabled() {
        return Boolean.parseBoolean(config.getProperty(CONFIG_PRIVILEGE_ESCALATION, "true"));
    }

    /**
     * Whether a failed update is rolled back automatically on the next start.
     */
    public boolean isRollbackEnabled() {
        return Boolean.parseBoolean(config.getProperty(CONFIG_ROLLBACK_ENABLED, "true"));
    }

    /**
     * Evaluates the start-up health right after an update and rolls the IDE back
     * to the previous version when the new one is broken (roadmap §10.2). The
     * host calls this once, early in the start-up sequence, before the update
     * service starts proposing new updates.
     *
     * @param health reports whether the application started correctly
     * @return the outcome of the evaluation
     */
    public RollbackTrigger.Outcome evaluateRollbackOnStartup(RollbackTrigger.HealthCheck health) {
        if (!isRollbackEnabled()) {
            return RollbackTrigger.Outcome.NO_PENDING_UPDATE;
        }
        return getRollbackTrigger().evaluateOnStartup(health);
    }

    /**
     * Registers the host callbacks used by the tray menu and the dialogs.
     */
    public void setActionListener(UpdateActionListener actionListener) {
        this.actionListener = actionListener;
    }

    private void initializeNotifications() {
        if (!isTrayNotificationsEnabled()) {
            log.info("Desktop notifications disabled by configuration");
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            log.debug("Headless environment, skipping the tray initialization");
            return;
        }

        Runnable initializer = () -> {
            notifications.initializeTray();
            notifications.setTrayActionListener(new TrayActions());
        };

        if (SwingUtilities.isEventDispatchThread()) {
            initializer.run();
        } else {
            SwingUtilities.invokeLater(initializer);
        }
    }

    /**
     * Adds or removes the tray icon so it matches the current configuration.
     */
    private void applyTrayNotifications() {
        if (!isTrayNotificationsEnabled()) {
            notifications.removeTray();
            return;
        }

        initializeNotifications();
    }

    private static long resolveCheckIntervalMs(Properties config, UpdateChannel channel) {
        String hours = config.getProperty("update.check.interval.hours", "").trim();

        if (!hours.isEmpty()) {
            try {
                long parsed = Long.parseLong(hours);
                if (parsed > 0) {
                    return parsed * 60 * 60 * 1000L;
                }
                log.warn("Invalid update.check.interval.hours '{}', using the channel default", hours);
            } catch (NumberFormatException e) {
                log.warn("Unparsable update.check.interval.hours '{}', using the channel default", hours);
            }
        }

        return channel.getCheckIntervalMs();
    }

    private Path getDowngradeDirectory() {
        String configured = config.getProperty(CONFIG_DOWNGRADE_DIR, "").trim();

        if (!configured.isEmpty()) {
            return Path.of(configured);
        }

        return Path.of(System.getProperty("user.home"), ".lg3d", "backups", "versions");
    }

    private int getDowngradeKeepCount() {
        String configured = config.getProperty(CONFIG_DOWNGRADE_KEEP, "").trim();

        try {
            int keep = configured.isEmpty()
                ? DEFAULT_DOWNGRADE_KEEP
                : Integer.parseInt(configured);
            return Math.max(1, keep);
        } catch (NumberFormatException e) {
            log.warn("Unparsable {} '{}', keeping {} archives",
                CONFIG_DOWNGRADE_KEEP, configured, DEFAULT_DOWNGRADE_KEEP);
            return DEFAULT_DOWNGRADE_KEEP;
        }
    }

    private int getRestartDelaySeconds() {
        String configured = config.getProperty(CONFIG_RESTART_DELAY, "").trim();

        try {
            return configured.isEmpty()
                ? UpdateScheduler.DEFAULT_RESTART_DELAY_SECONDS
                : Math.max(0, Integer.parseInt(configured));
        } catch (NumberFormatException e) {
            log.warn("Unparsable {} '{}'", CONFIG_RESTART_DELAY, configured);
            return UpdateScheduler.DEFAULT_RESTART_DELAY_SECONDS;
        }
    }

    private static Properties loadConfig() {
        Properties props = loadDefaultConfig();
        overlayUserConfig(props);
        return props;
    }

    private static Properties loadDefaultConfig() {
        Properties props = new Properties();
        try (InputStream configStream = UpdateService.class.getClassLoader()
                .getResourceAsStream(CONFIG_RESOURCE)) {

            if (configStream != null) {
                props.load(configStream);
            } else {
                log.warn("{} not found, using defaults", CONFIG_RESOURCE);
            }
        } catch (Exception e) {
            log.warn("Failed to load update config, using defaults", e);
        }
        return props;
    }

    /**
     * Applies the settings saved by a previous run on top of the packaged
     * defaults. An unreadable user file never prevents the service from starting.
     */
    private static void overlayUserConfig(Properties props) {
        Path userFile = getUserConfigFile();

        if (!Files.isRegularFile(userFile)) {
            log.debug("No user update configuration at {}", userFile);
            return;
        }

        try (InputStream in = Files.newInputStream(userFile)) {
            Properties overrides = new Properties();
            overrides.load(in);
            overrides.stringPropertyNames()
                .forEach(name -> props.setProperty(name, overrides.getProperty(name)));

            log.info("Loaded {} user update setting(s) from {}", overrides.size(), userFile);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read {}, keeping the packaged defaults", userFile, e);
        }
    }

    /**
     * File the user settings are persisted to.
     * <p>
     * Defaults to {@code <user.home>/.lg3d/update-config.properties} and can
     * be redirected with the {@value #CONFIG_DIR_PROPERTY} system property.
     * </p>
     */
    public static Path getUserConfigFile() {
        String configured = System.getProperty(CONFIG_DIR_PROPERTY, "").trim();

        Path configDir = configured.isEmpty()
            ? Path.of(System.getProperty("user.home"), DEFAULT_CONFIG_DIR_NAME)
            : Path.of(configured);

        return configDir.resolve(USER_CONFIG_FILE_NAME);
    }

    /**
     * Writes a configuration to {@link #getUserConfigFile()} so it is reloaded on
     * the next start.
     *
     * @param configuration settings to persist, {@code null} is ignored
     * @return {@code true} when the file was written
     */
    public static boolean saveConfiguration(Properties configuration) {
        if (configuration == null) {
            log.warn("No update configuration to save");
            return false;
        }

        Path target = getUserConfigFile();

        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (OutputStream out = Files.newOutputStream(target)) {
                configuration.store(out, "Project Looking Glass update settings");
            }

            log.info("Update configuration saved to {}", target);
            return true;

        } catch (IOException | RuntimeException e) {
            log.error("Could not save the update configuration to {}", target, e);
            return false;
        }
    }

    /**
     * Configuration in effect: the packaged defaults overridden by the user file.
     *
     * @return a copy, so callers cannot alter the running service by mistake
     */
    public Properties getConfiguration() {
        Properties copy = new Properties();
        config.stringPropertyNames()
            .forEach(name -> copy.setProperty(name, config.getProperty(name)));
        return copy;
    }

    /**
     * Applies settings changed at runtime, without touching the disk.
     * <p>
     * Desktop notifications are toggled immediately and the periodic checks are
     * rescheduled when the channel or the interval changed. Call
     * {@link #saveConfiguration(Properties)} to make the change permanent.
     * </p>
     *
     * @param updates settings to merge into the running configuration
     */
    public void applyConfiguration(Properties updates) {
        if (updates == null || updates.isEmpty()) {
            log.debug("No update configuration change to apply");
            return;
        }

        boolean restartChecks;
        synchronized (this) {
            updates.stringPropertyNames()
                .forEach(name -> config.setProperty(name, updates.getProperty(name)));

            UpdateChannel requested = UpdateChannel.parse(
                config.getProperty("update.channel", "stable")
            );
            long requestedIntervalMs = resolveCheckIntervalMs(config, requested);

            restartChecks = automaticChecksActive
                && (requested != channel || requestedIntervalMs != checkIntervalMs);

            channel = requested;
            checkIntervalMs = requestedIntervalMs;

            if (restartChecks) {
                checker.stopAutomaticChecks();
                checker = newChecker(checkIntervalMs, channel);
            }
        }

        if (restartChecks) {
            checker.scheduleAutomaticChecks();
            log.info("Automatic update checks restarted on channel {}", channel.getConfigValue());
        }

        applyTrayNotifications();
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public boolean isUpdateEnabled() {
        return Boolean.parseBoolean(config.getProperty("update.enabled", "true"));
    }

    public boolean isAutoDownloadEnabled() {
        return Boolean.parseBoolean(
            config.getProperty("update.auto.download", "false")
        );
    }

    public boolean isAutoInstallEnabled() {
        return Boolean.parseBoolean(
            config.getProperty("update.auto.install", "false")
        );
    }

    public boolean isTrayNotificationsEnabled() {
        return Boolean.parseBoolean(
            config.getProperty(CONFIG_TRAY_ENABLED, "true")
        );
    }

    public String getUpdateChannel() {
        return channel.getConfigValue();
    }

    /**
     * Channel releases are filtered through.
     */
    public UpdateChannel getChannel() {
        return channel;
    }

    /**
     * Host callbacks triggered by the update UI and the tray menu.
     */
    public interface UpdateActionListener {
        default void onCheckForUpdates() { }
        default void onShowSettings() { }
        default void onShowChangelog() { }
    }

    /**
     * Default tray behaviour: check for updates unless the host registered its
     * own listener.
     */
    private class TrayActions implements UpdateNotificationManager.TrayActionListener {

        @Override
        public void onCheckForUpdates() {
            UpdateActionListener listener = actionListener;
            if (listener != null) {
                listener.onCheckForUpdates();
            } else {
                checkForUpdatesAsync();
            }
        }

        @Override
        public void onShowSettings() {
            UpdateActionListener listener = actionListener;
            if (listener != null) {
                listener.onShowSettings();
            } else {
                log.debug("No action listener registered, cannot display the update settings");
            }
        }

        @Override
        public void onShowChangelog() {
            UpdateActionListener listener = actionListener;
            if (listener != null) {
                listener.onShowChangelog();
            } else {
                log.debug("No action listener registered, cannot display the changelog");
            }
        }
    }

    /**
     * Forwards the scheduler lifecycle to the desktop notifications.
     */
    private class SchedulerNotifications implements UpdateScheduler.ScheduledUpdateListener {

        @Override
        public void onScheduledUpdateStarting(String taskId) {
            scheduledVersion(taskId).ifPresent(notifications::notifyInstallationStarted);
        }

        @Override
        public void onScheduledUpdateComplete(String taskId, boolean willRestart) {
            scheduledVersion(taskId).ifPresent(version ->
                notifications.notifyInstallationComplete(version, willRestart)
            );
            scheduledVersions.remove(taskId);
        }

        @Override
        public void onScheduledUpdateFailed(String taskId, Exception cause) {
            notifications.notifyError(
                "Scheduled update failed",
                String.valueOf(cause != null ? cause.getMessage() : taskId)
            );
            scheduledVersions.remove(taskId);
        }

        @Override
        public void onRestartTriggered() {
            log.warn("A restart is required to finish the scheduled installation");
            notifications.notifyInfo(
                "Restart required",
                "The update has been installed. Please restart Project Looking Glass."
            );
        }

        private Optional<String> scheduledVersion(String taskId) {
            return Optional.ofNullable(scheduledVersions.get(taskId));
        }
    }
}
