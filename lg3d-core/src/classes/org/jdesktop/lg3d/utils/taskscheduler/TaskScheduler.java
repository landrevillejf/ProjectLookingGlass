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
package org.jdesktop.lg3d.utils.taskscheduler;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The desktop-wide entry point to the cron-style task scheduler: a process
 * singleton that wires a {@link PrefsTaskStore}, a {@link ProcessTaskRunner} and
 * a {@link TaskSchedulerEngine} together and exposes the small, safe API the
 * Control-Centre panel and the desktop start-up use.
 *
 * <p>This is the scheduler's facade in the same way {@code ScheduleService} is
 * for the wallpaper/lighting schedule - but it is a completely separate feature:
 * it runs <em>user-defined programs and start-menu apps on crontab schedules</em>,
 * and does not touch the day/night schedule.</p>
 *
 * <p>Every mutation goes through {@link TaskValidator} first, so an invalid or
 * unsafe task can never be persisted or scheduled. Mutations are written to the
 * store and then the engine is reloaded, keeping the store as the single source of
 * truth and the running schedule a projection of it.</p>
 */
public final class TaskScheduler {

    private static final Logger logger =
            Logger.getLogger("lg.utils.taskscheduler");

    private static final TaskScheduler INSTANCE = new TaskScheduler();

    private final TaskStore store;
    private final TaskSchedulerEngine engine;

    /** The process-wide scheduler. */
    public static TaskScheduler get() {
        return INSTANCE;
    }

    private TaskScheduler() {
        this(new PrefsTaskStore(), new ProcessTaskRunner());
    }

    /** Test/DI seam: build a facade over explicit collaborators. */
    TaskScheduler(TaskStore store, TaskRunner runner) {
        this.store = store;
        this.engine = new TaskSchedulerEngine(store, runner);
        // Load the persisted tasks up front so the Control Centre panel can show
        // and edit them even where the timing loop is never started (e.g. the 3D
        // desktop). This only reads the store; it starts no threads.
        this.engine.reload();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Starts the background scheduling loop. Safe to call more than once. */
    public void start() {
        try {
            engine.start();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not start task scheduler", e);
        }
    }

    /** Stops the background scheduling loop. */
    public void stop() {
        engine.stop();
    }

    public boolean isStarted() {
        return engine.isStarted();
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /** The current task list. Never null. */
    public List<ScheduledTask> tasks() {
        return engine.tasks();
    }

    /** The task with {@code id}, or null. */
    public ScheduledTask task(String id) {
        return engine.task(id);
    }

    /** The run history for {@code id}, newest first. Never null. */
    public List<TaskExecutionRecord> history(String id) {
        try {
            return store.history(id);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not read history for " + id, e);
            return java.util.Collections.emptyList();
        }
    }

    /** The epoch-millis next fire time for {@code id}, or 0 if none. */
    public long nextFireMillis(String id) {
        return engine.nextFireMillis(id);
    }

    /** True while a run of {@code id} is in flight. */
    public boolean isRunning(String id) {
        return engine.isRunning(id);
    }

    // ------------------------------------------------------------------
    // Write (validated)
    // ------------------------------------------------------------------

    /**
     * Validates and persists {@code task}, then refreshes the running schedule.
     *
     * @throws IllegalArgumentException if the task fails validation; nothing is
     *         persisted in that case.
     */
    public void save(ScheduledTask task) {
        TaskValidator.assertValid(task);
        store.save(task);
        engine.reload();
    }

    /** Removes a task and its history, then refreshes the schedule. */
    public void delete(String id) {
        if (id == null) {
            return;
        }
        store.delete(id);
        engine.reload();
    }

    /** Enables/disables a task and refreshes the schedule. */
    public void setEnabled(String id, boolean enabled) {
        ScheduledTask t = engine.task(id);
        if (t == null) {
            return;
        }
        t.setEnabled(enabled);
        store.save(t);
        engine.reload();
    }

    /** Fires {@code id} immediately, off-schedule (the UI's "Run now"). */
    public void runNow(String id) {
        engine.runNow(id);
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    public void addListener(TaskSchedulerEngine.Listener l) {
        engine.addListener(l);
    }

    public void removeListener(TaskSchedulerEngine.Listener l) {
        engine.removeListener(l);
    }
}
