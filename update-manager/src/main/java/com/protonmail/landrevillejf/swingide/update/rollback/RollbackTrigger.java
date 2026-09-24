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
package com.protonmail.landrevillejf.swingide.update.rollback;

import com.protonmail.landrevillejf.swingide.update.JarLocator;
import com.protonmail.landrevillejf.swingide.update.downgrade.DowngradeManager;
import com.protonmail.landrevillejf.swingide.update.downgrade.DowngradeManager.DowngradeException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Detects a failed self-update on the next start and restores the previous
 * version (roadmap §10.2).
 * <p>
 * The installer drops a marker recording the version that was running before the
 * update. When the IDE comes back up, the host calls
 * {@link #evaluateOnStartup(HealthCheck)}: if the marker is still there and the
 * health check fails, the previous version is restored from the downgrade
 * archives and a restart is requested. When the health check passes, the marker
 * is simply cleared, so a rollback is only ever attempted once per update.
 * </p>
 * <p>
 * The {@link RestartAction} and the current-JAR lookup are injected so the whole
 * flow can be tested without exiting the JVM or touching the real installation.
 * </p>
 */
@Slf4j
public class RollbackTrigger {

    /** Name of the marker file recording a pending (just applied) update. */
    public static final String PENDING_FILE_NAME = "update-pending.properties";

    /** Marker property holding the version to roll back to. */
    public static final String PENDING_VERSION_KEY = "rollback.to.version";

    /** System property redirecting the directory holding the marker file. */
    public static final String CONFIG_DIR_PROPERTY = "lg3d.config.dir";

    private static final String DEFAULT_CONFIG_DIR_NAME = ".lg3d";

    /**
     * Reports whether the freshly updated IDE started correctly.
     */
    @FunctionalInterface
    public interface HealthCheck {
        /**
         * @return {@code true} when the application is usable
         */
        boolean isHealthy();
    }

    /**
     * Restarts the IDE once the previous JAR has been restored.
     */
    @FunctionalInterface
    public interface RestartAction {
        void restart();
    }

    /**
     * Result of a start-up evaluation.
     */
    public enum Outcome {
        /** No update marker was present, nothing to do. */
        NO_PENDING_UPDATE,
        /** A pending update was confirmed healthy and its marker cleared. */
        HEALTHY,
        /** The update was unhealthy and the previous version was restored. */
        ROLLED_BACK,
        /** The update was unhealthy but the rollback could not be performed. */
        ROLLBACK_FAILED
    }

    private final DowngradeManager downgradeManager;
    private final Path markerFile;
    private final Supplier<Path> currentJarSupplier;
    private final RestartAction restartAction;

    /**
     * Creates a trigger using the default marker location, the real running JAR
     * and a restart action that only logs (the host should inject a real one).
     *
     * @param downgradeManager archive holding the previous versions
     */
    public RollbackTrigger(DowngradeManager downgradeManager) {
        this(downgradeManager, defaultMarkerFile(), JarLocator::getCurrentJarPath,
            () -> log.warn("A restart is required to finish rolling back the update"));
    }

    /**
     * Full constructor, used by tests and by hosts providing a real restart.
     *
     * @param downgradeManager archive holding the previous versions
     * @param markerFile       file recording the pending update
     * @param currentJarSupplier supplies the JAR a rollback overwrites
     * @param restartAction    callback restarting the IDE after a rollback
     */
    public RollbackTrigger(DowngradeManager downgradeManager,
                           Path markerFile,
                           Supplier<Path> currentJarSupplier,
                           RestartAction restartAction) {
        this.downgradeManager = downgradeManager;
        this.markerFile = markerFile;
        this.currentJarSupplier = currentJarSupplier;
        this.restartAction = restartAction;
    }

    /**
     * Records that {@code previousVersion} must be restored if the next start
     * fails. Called by the installer just before the JVM exits.
     *
     * @param previousVersion version running before the update
     * @return {@code true} when the marker was written
     */
    public boolean markPendingUpdate(String previousVersion) {
        if (previousVersion == null || previousVersion.isBlank()) {
            log.debug("No previous version to record, skipping the rollback marker");
            return false;
        }

        Properties props = new Properties();
        props.setProperty(PENDING_VERSION_KEY, previousVersion.trim());

        try {
            Path parent = markerFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(markerFile)) {
                props.store(out, "Project Looking Glass pending update rollback marker");
            }
            log.info("Recorded {} as the rollback target for the next start", previousVersion.trim());
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write the rollback marker {}: {}", markerFile, e.getMessage());
            return false;
        }
    }

    /**
     * Version recorded as the rollback target, if any.
     */
    public Optional<String> readPendingVersion() {
        if (!Files.isRegularFile(markerFile)) {
            return Optional.empty();
        }

        try (InputStream in = Files.newInputStream(markerFile)) {
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty(PENDING_VERSION_KEY, "").trim();
            return version.isEmpty() ? Optional.empty() : Optional.of(version);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the rollback marker {}: {}", markerFile, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Removes the pending-update marker.
     */
    public void clearPendingUpdate() {
        try {
            if (Files.deleteIfExists(markerFile)) {
                log.debug("Rollback marker cleared: {}", markerFile);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not clear the rollback marker {}: {}", markerFile, e.getMessage());
        }
    }

    /**
     * Evaluates the health of a freshly updated IDE and rolls back when needed.
     *
     * @param health reports whether the application started correctly
     * @return the outcome of the evaluation
     */
    public Outcome evaluateOnStartup(HealthCheck health) {
        Optional<String> pending = readPendingVersion();
        if (pending.isEmpty()) {
            return Outcome.NO_PENDING_UPDATE;
        }

        String target = pending.get();

        boolean healthy;
        try {
            healthy = health != null && health.isHealthy();
        } catch (RuntimeException e) {
            log.warn("The health check failed with an exception, treating the update as unhealthy", e);
            healthy = false;
        }

        if (healthy) {
            log.info("Update to the running version is healthy, clearing the rollback marker");
            clearPendingUpdate();
            return Outcome.HEALTHY;
        }

        log.warn("Startup health check failed, rolling back to version {}", target);
        return rollbackTo(target);
    }

    private Outcome rollbackTo(String target) {
        try {
            Path currentJar = currentJarSupplier.get();
            downgradeManager.restoreVersion(target, currentJar, target);
            clearPendingUpdate();

            log.info("Rolled back to version {}, restarting", target);
            restartAction.restart();
            return Outcome.ROLLED_BACK;
        } catch (DowngradeException | RuntimeException e) {
            log.error("Rollback to version {} failed", target, e);
            // Keep the marker so a manual recovery is still possible, but do not
            // loop forever: the host decides what to do next.
            return Outcome.ROLLBACK_FAILED;
        }
    }

    /**
     * Default marker location, honouring the {@value #CONFIG_DIR_PROPERTY}
     * system property like the rest of the update configuration.
     */
    public static Path defaultMarkerFile() {
        String configured = System.getProperty(CONFIG_DIR_PROPERTY, "").trim();

        Path configDir = configured.isEmpty()
            ? Path.of(System.getProperty("user.home"), DEFAULT_CONFIG_DIR_NAME)
            : Path.of(configured);

        return configDir.resolve(PENDING_FILE_NAME);
    }
}
