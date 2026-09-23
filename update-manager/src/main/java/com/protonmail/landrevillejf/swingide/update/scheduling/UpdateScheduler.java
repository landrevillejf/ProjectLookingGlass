package com.protonmail.landrevillejf.swingide.update.scheduling;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Manages scheduled update installation at user-specified times.
 * Allows scheduling updates for off-hours installation with automatic restart.
 * <p>
 * The scheduler does not know how to install an update: the host registers an
 * {@link InstallHandler} performing the real download, verification and
 * installation, and a {@link ScheduledUpdateListener} to react to the lifecycle
 * events (notifications, restart, ...).
 * </p>
 */
@Slf4j
public class UpdateScheduler {

    /** Default delay between the end of an installation and the restart. */
    public static final int DEFAULT_RESTART_DELAY_SECONDS = 5;

    private final ScheduledExecutorService executor;
    private final Map<String, ScheduledUpdateTask> scheduledTasks;
    private volatile ScheduledUpdateListener listener;
    private volatile InstallHandler installHandler;
    private volatile int restartDelaySeconds = DEFAULT_RESTART_DELAY_SECONDS;

    public UpdateScheduler() {
        this.executor = Executors.newScheduledThreadPool(2, daemonThreadFactory());
        this.scheduledTasks = Collections.synchronizedMap(new HashMap<>());
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "UpdateScheduler");
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Schedule an update to install at a specific time.
     */
    public String scheduleUpdate(String updateVersion, LocalDateTime installTime, boolean autoRestart) {
        LocalDateTime now = LocalDateTime.now();
        if (installTime.isBefore(now)) {
            throw new IllegalArgumentException("Install time must be in the future");
        }

        String taskId = UUID.randomUUID().toString();
        long delaySeconds = ChronoUnit.SECONDS.between(now, installTime);

        ScheduledUpdateTask task = new ScheduledUpdateTask(
            taskId,
            updateVersion,
            installTime,
            autoRestart
        );

        // Publish the task before handing it to the executor. An install time
        // within the current second truncates to a zero delay, so the worker can
        // fire immediately; if it ran before the put, its remove() would be a
        // no-op and the task would linger in scheduledTasks forever, leaving
        // getPendingCount() stuck above zero. schedule() establishes a
        // happens-before edge, so this put is always visible to the worker.
        scheduledTasks.put(taskId, task);

        ScheduledFuture<?> future = executor.schedule(
            () -> runScheduledUpdate(task),
            delaySeconds,
            TimeUnit.SECONDS
        );

        task.setScheduledFuture(future);

        log.info("Update {} scheduled for {} (in {} seconds)",
            updateVersion, installTime, delaySeconds);

        if (listener != null) {
            listener.onUpdateScheduled(taskId, installTime);
        }

        return taskId;
    }

    /**
     * Cancel a scheduled update.
     */
    public boolean cancelScheduledUpdate(String taskId) {
        ScheduledUpdateTask task = scheduledTasks.get(taskId);
        if (task == null) {
            return false;
        }

        ScheduledFuture<?> future = task.getScheduledFuture();
        boolean cancelled = future == null || future.cancel(false);
        scheduledTasks.remove(taskId);

        if (cancelled) {
            log.info("Scheduled update {} cancelled", taskId);

            if (listener != null) {
                listener.onUpdateScheduleCancelled(taskId);
            }
        }

        return cancelled;
    }

    /**
     * Get all scheduled updates.
     */
    public List<ScheduledUpdateTask> getScheduledUpdates() {
        synchronized (scheduledTasks) {
            return new ArrayList<>(scheduledTasks.values());
        }
    }

    /**
     * Get a specific scheduled update.
     */
    public ScheduledUpdateTask getScheduledUpdate(String taskId) {
        return scheduledTasks.get(taskId);
    }

    /**
     * Number of updates waiting for their installation time.
     */
    public int getPendingCount() {
        return scheduledTasks.size();
    }

    /**
     * Reschedule an update to a different time.
     */
    public boolean rescheduleUpdate(String taskId, LocalDateTime newInstallTime) {
        ScheduledUpdateTask task = scheduledTasks.get(taskId);
        if (task == null) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        if (newInstallTime.isBefore(now)) {
            throw new IllegalArgumentException("New install time must be in the future");
        }

        ScheduledFuture<?> currentFuture = task.getScheduledFuture();
        if (currentFuture != null && !currentFuture.cancel(false)) {
            log.warn("Scheduled update {} already running, cannot reschedule it", taskId);
            return false;
        }

        long delaySeconds = ChronoUnit.SECONDS.between(now, newInstallTime);

        ScheduledFuture<?> future = executor.schedule(
            () -> runScheduledUpdate(task),
            delaySeconds,
            TimeUnit.SECONDS
        );

        task.setInstallTime(newInstallTime);
        task.setScheduledFuture(future);

        log.info("Update {} rescheduled to {} (in {} seconds)",
            task.getUpdateVersion(), newInstallTime, delaySeconds);

        if (listener != null) {
            listener.onUpdateRescheduled(taskId, newInstallTime);
        }

        return true;
    }

    /**
     * Set listener for scheduled update events.
     */
    public void setListener(ScheduledUpdateListener listener) {
        this.listener = listener;
    }

    /**
     * Registers the component performing the real installation when a scheduled
     * task fires.
     */
    public void setInstallHandler(InstallHandler installHandler) {
        this.installHandler = installHandler;
    }

    /**
     * Delay applied between a successful scheduled installation and the restart
     * notification.
     */
    public void setRestartDelaySeconds(int restartDelaySeconds) {
        this.restartDelaySeconds = Math.max(0, restartDelaySeconds);
    }

    public int getRestartDelaySeconds() {
        return restartDelaySeconds;
    }

    private void runScheduledUpdate(ScheduledUpdateTask task) {
        try {
            executeScheduledUpdate(task);
        } catch (Exception e) {
            log.error("Failed to execute scheduled update {}", task.getTaskId(), e);
            if (listener != null) {
                listener.onScheduledUpdateFailed(task.getTaskId(), e);
            }
        }
    }

    /**
     * Execute the scheduled update.
     */
    private void executeScheduledUpdate(ScheduledUpdateTask task) {
        log.info("Executing scheduled update: {}", task.getUpdateVersion());

        if (listener != null) {
            listener.onScheduledUpdateStarting(task.getTaskId());
        }

        InstallHandler handler = this.installHandler;
        if (handler == null) {
            IllegalStateException missing = new IllegalStateException(
                "No install handler registered, scheduled update " + task.getTaskId() + " cannot be installed"
            );
            log.warn(missing.getMessage());
            scheduledTasks.remove(task.getTaskId());
            if (listener != null) {
                listener.onScheduledUpdateFailed(task.getTaskId(), missing);
            }
            return;
        }

        try {
            handler.install(task);
            scheduledTasks.remove(task.getTaskId());

            if (listener != null) {
                listener.onScheduledUpdateComplete(task.getTaskId(), task.isAutoRestart());
            }

            if (task.isAutoRestart()) {
                scheduleRestart(restartDelaySeconds);
            }

        } catch (Exception e) {
            scheduledTasks.remove(task.getTaskId());
            log.error("Scheduled update {} failed: {}", task.getTaskId(), e.getMessage(), e);
            if (listener != null) {
                listener.onScheduledUpdateFailed(task.getTaskId(), e);
            }
        }
    }

    /**
     * Schedule automatic restart after specified delay.
     */
    public void scheduleRestart(int delaySeconds) {
        executor.schedule(() -> {
            try {
                log.info("Restarting application in {} seconds", delaySeconds);
                if (listener != null) {
                    listener.onRestartTriggered();
                }
            } catch (Exception e) {
                log.error("Failed to trigger restart", e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * Shutdown the scheduler.
     */
    public void shutdown() {
        executor.shutdownNow();
        scheduledTasks.clear();
        log.info("UpdateScheduler shut down");
    }

    /**
     * Performs the real installation of a scheduled update.
     */
    @FunctionalInterface
    public interface InstallHandler {
        /**
         * Installs the update carried by the scheduled task.
         *
         * @param task the task reaching its installation time
         * @throws Exception when the installation fails; the listener is notified
         */
        void install(ScheduledUpdateTask task) throws Exception;
    }

    /**
     * Data class for scheduled update tasks.
     */
    public static class ScheduledUpdateTask {
        private final String taskId;
        private final String updateVersion;
        private final boolean autoRestart;
        private final long createdAt;
        private LocalDateTime installTime;
        private ScheduledFuture<?> scheduledFuture;

        public ScheduledUpdateTask(String taskId, String updateVersion, LocalDateTime installTime, boolean autoRestart) {
            this.taskId = taskId;
            this.updateVersion = updateVersion;
            this.installTime = installTime;
            this.autoRestart = autoRestart;
            this.createdAt = System.currentTimeMillis();
        }

        public String getTaskId() { return taskId; }
        public String getUpdateVersion() { return updateVersion; }
        public LocalDateTime getInstallTime() { return installTime; }
        public boolean isAutoRestart() { return autoRestart; }
        public ScheduledFuture<?> getScheduledFuture() { return scheduledFuture; }
        public long getCreatedAt() { return createdAt; }

        void setInstallTime(LocalDateTime installTime) { this.installTime = installTime; }
        void setScheduledFuture(ScheduledFuture<?> future) { this.scheduledFuture = future; }
    }

    /**
     * Listener interface for scheduled update events. Every method has a default
     * no-op implementation so hosts only override what they need.
     */
    public interface ScheduledUpdateListener {
        default void onUpdateScheduled(String taskId, LocalDateTime installTime) { }
        default void onUpdateScheduleCancelled(String taskId) { }
        default void onUpdateRescheduled(String taskId, LocalDateTime newInstallTime) { }
        default void onScheduledUpdateStarting(String taskId) { }
        default void onScheduledUpdateComplete(String taskId, boolean willRestart) { }
        default void onScheduledUpdateFailed(String taskId, Exception cause) { }
        default void onRestartTriggered() { }
    }
}
