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

import com.protonmail.landrevillejf.swingide.core.bus.EventBus;
import com.protonmail.landrevillejf.swingide.update.channels.UpdateChannel;
import com.protonmail.landrevillejf.swingide.update.events.UpdateAvailableEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@Slf4j
public class UpdateChecker {
    
    private static final long DEFAULT_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours
    
    private final UpdateRepository repository;
    private final ScheduledExecutorService scheduler;
    private final long checkIntervalMs;
    private final String currentVersion;
    private final EventBus eventBus;
    private final UpdateChannel channel;

    /**
     * Host supplied veto applied before an update is published. The service uses
     * it to honour the version the user asked to skip, which would otherwise be
     * proposed again by every periodic check.
     */
    private volatile Predicate<UpdateInfo> updateFilter = update -> true;
    
    public UpdateChecker(UpdateRepository repository) {
        this(repository, DEFAULT_CHECK_INTERVAL_MS, new EventBus());
    }
    
    public UpdateChecker(UpdateRepository repository, long checkIntervalMs) {
        this(repository, checkIntervalMs, new EventBus());
    }
    
    public UpdateChecker(UpdateRepository repository, long checkIntervalMs, EventBus eventBus) {
        this(repository, checkIntervalMs, eventBus, UpdateChannel.NIGHTLY);
    }
    
    public UpdateChecker(UpdateRepository repository, long checkIntervalMs, EventBus eventBus,
                         UpdateChannel channel) {
        this.repository = repository;
        this.checkIntervalMs = checkIntervalMs;
        this.eventBus = eventBus;
        this.channel = channel != null ? channel : UpdateChannel.STABLE;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "UpdateChecker");
            thread.setDaemon(true);
            return thread;
        });
        this.currentVersion = ApplicationVersion.current();
    }
    
    public UpdateInfo checkForUpdates() {
        try {
            log.debug("Checking for updates on channel {}...", channel);
            UpdateInfo latest = repository.fetchLatestVersion();
            
            if (latest == null) {
                log.debug("Update server returned no metadata");
                return null;
            }
            
            if (!isNewerVersion(latest.getVersion(), currentVersion)) {
                log.debug("Already up to date: {}", currentVersion);
                return null;
            }
            
            if (!channel.accepts(latest.getVersion())) {
                log.info("Version {} ignored: it does not belong to the {} channel",
                    latest.getVersion(), channel);
                return null;
            }
            
            log.info("New version available: {} (current: {})", 
                latest.getVersion(), currentVersion);
            return latest;
            
        } catch (UpdateException e) {
            if (isServerUnreachable(e)) {
                // No release server, offline start-up or DNS failure: an expected,
                // recoverable condition, not a defect. Keep it short and quiet so
                // neither entry point spams a stack trace on every launch.
                log.warn("Update check skipped, the update server is unreachable ({})",
                    rootCauseMessage(e));
            } else {
                log.error("Failed to check for updates", e);
            }
            return null;
        } catch (RuntimeException e) {
            log.error("Unexpected error while checking for updates", e);
            return null;
        }
    }

    /**
     * Whether the failure only reflects an unavailable update server (no route,
     * unknown host, refused or timed-out connection, or a non-2xx answer from
     * the metadata endpoint) rather than a real defect. Such a condition is
     * normal whenever the release endpoint is down, not deployed yet, or
     * protected by authentication the client does not carry, so it must not be
     * reported as an error with a full stack trace.
     */
    private static boolean isServerUnreachable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof UpdateServerUnavailableException
                    || t instanceof java.nio.channels.UnresolvedAddressException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.http.HttpConnectTimeoutException
                    || t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * Concise description of the deepest cause, used for the quiet warning.
     */
    private static String rootCauseMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
            ? root.getClass().getSimpleName()
            : root.getClass().getSimpleName() + ": " + message;
    }
    
    /**
     * Channel the checker filters releases through.
     */
    public UpdateChannel getChannel() {
        return channel;
    }
    
    /**
     * Version the checker compares the server metadata against.
     */
    public String getCurrentVersion() {
        return currentVersion;
    }
    
    public void scheduleAutomaticChecks() {
        scheduleAutomaticChecks(0);
    }

    /**
     * Starts the periodic checks.
     *
     * @param initialDelayMs delay before the first check; {@code 0} checks
     *                       immediately, which is how a startup check is run
     *                       without blocking the caller
     */
    public void scheduleAutomaticChecks(long initialDelayMs) {
        long firstDelay = Math.max(0, initialDelayMs);
        scheduler.scheduleAtFixedRate(
            this::checkAndNotify,
            firstDelay,
            checkIntervalMs,
            TimeUnit.MILLISECONDS
        );
        log.info("Scheduled automatic update checks every {} ms (first in {} ms)",
            checkIntervalMs, firstDelay);
    }

    /**
     * Registers the veto applied to every update before it is published.
     *
     * @param filter predicate returning {@code true} to publish the update,
     *               {@code null} restores the accept-all default
     */
    public void setUpdateFilter(Predicate<UpdateInfo> filter) {
        this.updateFilter = filter != null ? filter : update -> true;
    }
    
    public void stopAutomaticChecks() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Stopped automatic update checks");
    }
    
    private void checkAndNotify() {
        UpdateInfo latest = checkForUpdates();
        if (latest == null) {
            return;
        }

        if (!updateFilter.test(latest)) {
            log.info("Version {} rejected by the host filter, not publishing it", latest.getVersion());
            return;
        }

        eventBus.publish(new UpdateAvailableEvent(latest, currentVersion));
        log.info("Update available: {}", latest.getVersion());
    }
    
    private boolean isNewerVersion(String latest, String current) {
        return VersionComparator.isNewerVersion(latest, current);
    }
}
