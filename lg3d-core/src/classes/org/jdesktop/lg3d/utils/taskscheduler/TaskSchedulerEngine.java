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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The reliable scheduling loop at the heart of the desktop task scheduler. It
 * owns the set of {@link ScheduledTask}s loaded from a {@link TaskStore}, decides
 * when each is due, and fires due tasks through a {@link TaskRunner} on a worker
 * pool - never on the timing thread, so one slow or hung task cannot delay the
 * others.
 *
 * <p><b>Reliability.</b> A single daemon "tick" thread wakes every
 * {@code tickMillis} (one second by default, which is what gives sub-minute cron
 * expressions their resolution) and fires everything whose next-fire time has
 * arrived. The tick body is wrapped so no exception can ever cancel the periodic
 * task - the classic way a naive {@code scheduleAtFixedRate} scheduler silently
 * dies. On start-up the engine reconciles tasks that came due while the desktop
 * was not running according to each task's {@link MisfirePolicy}, and honours
 * {@code @reboot} cron jobs by running them once.</p>
 *
 * <p><b>Concurrency guard.</b> Each task tracks how many of its own runs are in
 * flight; when {@link ScheduledTask#isSkipIfRunning()} is set and a run is still
 * going, the new occurrence is recorded as {@link TaskExecutionRecord.Status#SKIPPED}
 * rather than overlapping. A next-fire time is recomputed at <em>dispatch</em>
 * time (before the run), so a long-running task never causes an immediate
 * re-fire storm when it finally completes.</p>
 *
 * <p><b>Retries.</b> A failed run is retried up to {@link ScheduledTask#getRetryCount()}
 * times with {@link ScheduledTask#getRetryDelayMillis()} between attempts.</p>
 *
 * <p>The engine is thread-safe: the task/next-fire/running maps are guarded by a
 * single lock, listeners are notified outside that lock, and store I/O is done off
 * the timing thread.</p>
 */
public final class TaskSchedulerEngine {

    private static final Logger logger =
            Logger.getLogger("lg.utils.taskscheduler");

    /** Default tick period; one second gives cron its minute/sub-minute resolution. */
    public static final long DEFAULT_TICK_MILLIS = 1000L;

    /**
     * How far in the past a stored next-run time must be before it counts as a
     * genuine misfire (missed while the desktop was down) rather than a task that
     * simply came due a moment ago during normal ticking.
     */
    static final long MISFIRE_THRESHOLD_MILLIS = 60_000L;

    /** Notified when the task set changes or a task finishes a run. */
    public interface Listener {
        /** The task list was (re)loaded or edited. */
        default void tasksChanged() { }

        /** A task finished one execution (including skips). */
        default void taskExecuted(ScheduledTask task, TaskExecutionRecord record) { }
    }

    private final TaskStore store;
    private final TaskRunner runner;
    private final ZoneId zone;
    private final long tickMillis;

    private final Object lock = new Object();
    private final Map<String, ScheduledTask> tasks = new LinkedHashMap<String, ScheduledTask>();
    private final Map<String, Long> nextFire = new HashMap<String, Long>();
    private final Map<String, Integer> runningCounts = new HashMap<String, Integer>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    private ScheduledExecutorService ticker;
    private ExecutorService workers;
    private volatile boolean started;

    /** Builds an engine on the system time zone with the default one-second tick. */
    public TaskSchedulerEngine(TaskStore store, TaskRunner runner) {
        this(store, runner, ZoneId.systemDefault(), DEFAULT_TICK_MILLIS);
    }

    /**
     * Builds an engine with an explicit zone and tick period; visible so tests can
     * drive a fast, deterministic loop.
     */
    public TaskSchedulerEngine(TaskStore store, TaskRunner runner,
            ZoneId zone, long tickMillis) {
        if (store == null) {
            throw new IllegalArgumentException("store is null");
        }
        if (runner == null) {
            throw new IllegalArgumentException("runner is null");
        }
        this.store = store;
        this.runner = runner;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        this.tickMillis = tickMillis <= 0 ? DEFAULT_TICK_MILLIS : tickMillis;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Loads tasks and starts the tick loop. Idempotent. */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        ticker = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("lg-task-scheduler-tick"));
        workers = Executors.newCachedThreadPool(daemonFactory("lg-task-runner"));
        reloadInternal(true);
        ticker.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                tickSafe();
            }
        }, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
        int n;
        synchronized (lock) {
            n = tasks.size();
        }
        logger.info("Task scheduler started with " + n + " task(s), tick="
                + tickMillis + "ms, zone=" + zone);
    }

    /** Stops the loop and shuts down the worker pool. Idempotent. */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        synchronized (lock) {
            nextFire.clear();
            runningCounts.clear();
        }
        logger.info("Task scheduler stopped");
    }

    public boolean isStarted() {
        return started;
    }

    // ------------------------------------------------------------------
    // Task set
    // ------------------------------------------------------------------

    /**
     * Re-reads the task set from the store and recomputes next-fire times. Called
     * by the facade after the UI adds/edits/removes a task. Does <em>not</em>
     * re-run start-up semantics ({@code @reboot}, misfire catch-up).
     */
    public void reload() {
        reloadInternal(false);
    }

    private void reloadInternal(boolean startup) {
        List<ScheduledTask> loaded;
        try {
            loaded = store.load();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not load tasks; continuing with none", e);
            loaded = Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        List<ScheduledTask> reboot = new ArrayList<ScheduledTask>();
        List<ScheduledTask> catchUp = new ArrayList<ScheduledTask>();
        synchronized (lock) {
            tasks.clear();
            nextFire.clear();
            for (ScheduledTask t : loaded) {
                if (t == null || t.getId() == null) {
                    continue;
                }
                tasks.put(t.getId(), t);
                long storedNext = t.getNextRunMillis();
                computeNext(t, now);
                if (!t.isEnabled() || t.getTrigger() == null) {
                    continue;
                }
                if (startup && isReboot(t.getTrigger())) {
                    reboot.add(t);
                } else if (startup && storedNext > 0
                        && storedNext < now - MISFIRE_THRESHOLD_MILLIS) {
                    switch (t.getMisfirePolicy()) {
                        case FIRE_ONCE_NOW:
                            catchUp.add(t);
                            break;
                        case FIRE_ON_NEXT_TICK:
                            nextFire.put(t.getId(), storedNext);
                            break;
                        case IGNORE:
                        default:
                            break;
                    }
                }
            }
        }
        if (startup) {
            long fire = System.currentTimeMillis();
            for (ScheduledTask t : reboot) {
                dispatch(t, fire, true);
            }
            for (ScheduledTask t : catchUp) {
                logger.info("Misfire catch-up for task " + t.getId());
                dispatch(t, fire, true);
            }
        }
        notifyChanged();
    }

    /** A snapshot of the current task list (safe to hand to the UI). */
    public List<ScheduledTask> tasks() {
        synchronized (lock) {
            return new ArrayList<ScheduledTask>(tasks.values());
        }
    }

    /** The live task with {@code id}, or null if absent. */
    public ScheduledTask task(String id) {
        synchronized (lock) {
            return id == null ? null : tasks.get(id);
        }
    }

    /** The epoch-millis next fire time for {@code id}, or 0 if none is scheduled. */
    public long nextFireMillis(String id) {
        synchronized (lock) {
            Long nf = id == null ? null : nextFire.get(id);
            return nf == null ? 0L : nf.longValue();
        }
    }

    /** True while at least one run of {@code id} is in flight. */
    public boolean isRunning(String id) {
        synchronized (lock) {
            Integer c = id == null ? null : runningCounts.get(id);
            return c != null && c > 0;
        }
    }

    /** Runs {@code id} immediately (the UI's "Run now"), off-schedule. */
    public void runNow(String id) {
        ScheduledTask t = task(id);
        if (t == null) {
            return;
        }
        dispatch(t, System.currentTimeMillis(), true);
    }

    // ------------------------------------------------------------------
    // Timing
    // ------------------------------------------------------------------

    /** Runs one tick, swallowing everything so the periodic task is never cancelled. */
    private void tickSafe() {
        try {
            if (!started) {
                return;
            }
            tick();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Scheduler tick failed; continuing", e);
        } catch (Error e) {
            logger.log(Level.SEVERE, "Scheduler tick hit an error; continuing", e);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        List<ScheduledTask> due = new ArrayList<ScheduledTask>();
        synchronized (lock) {
            for (ScheduledTask t : tasks.values()) {
                if (!t.isEnabled()) {
                    continue;
                }
                Long nf = nextFire.get(t.getId());
                if (nf != null && nf.longValue() <= now) {
                    due.add(t);
                }
            }
        }
        for (ScheduledTask t : due) {
            dispatch(t, now, false);
        }
    }

    /**
     * Recomputes and stores the next fire time for {@code t} strictly after
     * {@code nowMillis}. A CRON {@code @reboot} or a spent ONCE trigger yields no
     * next time, which removes it from the schedule.
     */
    private void computeNext(ScheduledTask t, long nowMillis) {
        String id = t.getId();
        Trigger tr = t.getTrigger();
        if (!t.isEnabled() || tr == null || isReboot(tr)) {
            nextFire.remove(id);
            t.setNextRunMillis(0);
            return;
        }
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
        Optional<ZonedDateTime> next = tr.nextFireTime(now, zone);
        if (next.isPresent()) {
            long nf = next.get().toInstant().toEpochMilli();
            nextFire.put(id, Long.valueOf(nf));
            t.setNextRunMillis(nf);
        } else {
            nextFire.remove(id);
            t.setNextRunMillis(0);
        }
    }

    private static boolean isReboot(Trigger tr) {
        if (tr.getKind() != Trigger.Kind.CRON || tr.getCron() == null) {
            return false;
        }
        try {
            return CronExpression.parse(tr.getCron()).isReboot();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Dispatch / execution
    // ------------------------------------------------------------------

    private void dispatch(ScheduledTask t, long now, boolean manual) {
        final String id = t.getId();
        boolean startRun = false;
        TaskExecutionRecord skip = null;
        synchronized (lock) {
            if (!started && !manual) {
                return;                     // loop stopped between tick and dispatch
            }
            int count = runningCounts.containsKey(id)
                    ? runningCounts.get(id).intValue() : 0;
            if (count > 0 && t.isSkipIfRunning()) {
                skip = new TaskExecutionRecord(now, 0,
                        TaskExecutionRecord.Status.SKIPPED, -1,
                        "skipped: a previous run is still in progress");
                if (!manual) {
                    computeNext(t, now);
                }
            } else {
                runningCounts.put(id, Integer.valueOf(count + 1));
                if (!manual) {
                    computeNext(t, now);    // advance before running: no re-fire storm
                }
                startRun = true;
            }
        }
        if (skip != null) {
            store.appendHistory(id, skip, ScheduledTask.DEFAULT_HISTORY_LIMIT);
            notifyExecuted(t, skip);
            return;
        }
        if (startRun) {
            final ScheduledTask ft = t;
            ExecutorService w = workers;
            if (w == null || w.isShutdown()) {
                decrementRunning(id);
                return;
            }
            try {
                w.submit(new Runnable() {
                    @Override
                    public void run() {
                        execute(id, ft);
                    }
                });
            } catch (RuntimeException e) {
                decrementRunning(id);
                logger.log(Level.WARNING, "Could not submit task " + id, e);
            }
        }
    }

    private void execute(String id, ScheduledTask task) {
        TaskExecutionRecord record;
        try {
            record = runWithRetries(task);
            if (record == null) {
                record = new TaskExecutionRecord(System.currentTimeMillis(), 0,
                        TaskExecutionRecord.Status.ERROR, -1, "runner returned no record");
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Runner threw for task " + id, e);
            record = new TaskExecutionRecord(System.currentTimeMillis(), 0,
                    TaskExecutionRecord.Status.ERROR, -1, "runner error: " + e);
        }
        finish(id, record);
    }

    private TaskExecutionRecord runWithRetries(ScheduledTask task) {
        TaskExecutionRecord r = runner.run(task);
        int attempts = 0;
        int max = Math.max(0, task.getRetryCount());
        while (r != null && !r.isSuccess()
                && r.getStatus() != TaskExecutionRecord.Status.SKIPPED
                && attempts < max) {
            attempts++;
            long delay = task.getRetryDelayMillis();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            r = runner.run(task);
        }
        return r;
    }

    private void finish(String id, TaskExecutionRecord record) {
        ScheduledTask live;
        synchronized (lock) {
            decrementRunning(id);
            live = tasks.get(id);
            if (live != null) {
                live.setLastRunMillis(record.getStartedAtMillis());
                live.setLastRecord(record);
                live.setConsecutiveFailures(record.isSuccess()
                        ? 0 : live.getConsecutiveFailures() + 1);
            }
        }
        if (live == null) {
            // Task was deleted (or reloaded away) while it ran: keep the history
            // entry but do not resurrect the task.
            store.appendHistory(id, record, ScheduledTask.DEFAULT_HISTORY_LIMIT);
            notifyExecuted(null, record);
            return;
        }
        try {
            store.appendHistory(id, record, ScheduledTask.DEFAULT_HISTORY_LIMIT);
            store.save(live);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not persist result for " + id, e);
        }
        notifyExecuted(live, record);
    }

    private void decrementRunning(String id) {
        synchronized (lock) {
            Integer c = runningCounts.get(id);
            if (c == null) {
                return;
            }
            if (c.intValue() <= 1) {
                runningCounts.remove(id);
            } else {
                runningCounts.put(id, Integer.valueOf(c.intValue() - 1));
            }
        }
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        for (Listener l : listeners) {
            try {
                l.tasksChanged();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "tasksChanged listener failed", e);
            }
        }
    }

    private void notifyExecuted(ScheduledTask task, TaskExecutionRecord record) {
        for (Listener l : listeners) {
            try {
                l.taskExecuted(task, record);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "taskExecuted listener failed", e);
            }
        }
    }

    private static ThreadFactory daemonFactory(final String prefix) {
        return new ThreadFactory() {
            private int n;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + (++n));
                t.setDaemon(true);
                return t;
            }
        };
    }
}
