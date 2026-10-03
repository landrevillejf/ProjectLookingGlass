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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link TaskSchedulerEngine} over an in-memory store and a
 * controllable fake runner, so no real process is spawned and no preference is
 * written. A fast tick (25 ms) keeps the loop-driven tests quick.
 */
class TaskSchedulerEngineTest {

    private static final long TICK = 25L;

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** In-memory {@link TaskStore}. */
    static final class FakeTaskStore implements TaskStore {
        final Map<String, ScheduledTask> tasks = new LinkedHashMap<>();
        final Map<String, List<TaskExecutionRecord>> hist = new LinkedHashMap<>();

        @Override
        public synchronized List<ScheduledTask> load() {
            return new ArrayList<>(tasks.values());
        }

        @Override
        public synchronized void save(ScheduledTask task) {
            if (task != null) {
                tasks.put(task.getId(), task);
            }
        }

        @Override
        public synchronized void delete(String id) {
            tasks.remove(id);
            hist.remove(id);
        }

        @Override
        public synchronized List<TaskExecutionRecord> history(String id) {
            List<TaskExecutionRecord> l = hist.get(id);
            return l == null ? new ArrayList<>() : new ArrayList<>(l);
        }

        @Override
        public synchronized void appendHistory(String id, TaskExecutionRecord r, int limit) {
            if (id == null || r == null) {
                return;
            }
            List<TaskExecutionRecord> l = hist.computeIfAbsent(id, k -> new ArrayList<>());
            l.add(0, r);
            while (l.size() > Math.max(1, limit)) {
                l.remove(l.size() - 1);
            }
        }
    }

    /** A {@link TaskRunner} that can block on a gate and fail a set number of times. */
    static final class FakeTaskRunner implements TaskRunner {
        final AtomicInteger count = new AtomicInteger();
        volatile CountDownLatch gate;
        volatile int failuresBeforeSuccess = 0;

        @Override
        public TaskExecutionRecord run(ScheduledTask task) {
            int n = count.incrementAndGet();
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            boolean fail = n <= failuresBeforeSuccess;
            return new TaskExecutionRecord(System.currentTimeMillis(), 1,
                    fail ? TaskExecutionRecord.Status.FAILURE
                         : TaskExecutionRecord.Status.SUCCESS,
                    fail ? 1 : 0, "run" + n);
        }
    }

    private static ScheduledTask task(String id, Trigger trigger) {
        ScheduledTask t = new ScheduledTask(id);
        t.setName(id);
        t.setTrigger(trigger);
        t.setArgv(List.of("/bin/echo", id));
        return t;
    }

    private static TaskSchedulerEngine engine(FakeTaskStore store, FakeTaskRunner runner) {
        return new TaskSchedulerEngine(store, runner, ZoneId.systemDefault(), TICK);
    }

    private static void awaitTrue(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(cond.getAsBoolean(), "condition not met within " + timeoutMs + "ms");
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the tick loop fires an interval task on schedule")
    void intervalFires() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        store.save(task("i", Trigger.interval(60)));
        TaskSchedulerEngine e = engine(store, runner);
        e.start();
        try {
            awaitTrue(() -> runner.count.get() >= 1, 3000);
        } finally {
            e.stop();
        }
        assertTrue(runner.count.get() >= 1);
    }

    @Test
    @DisplayName("runNow executes a task off-schedule and records history")
    void runNow() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        store.save(task("r", Trigger.cron("0 0 1 1 *")));   // yearly: tick won't fire it
        TaskSchedulerEngine e = engine(store, runner);
        e.start();
        try {
            e.runNow("r");
            awaitTrue(() -> runner.count.get() == 1, 3000);
            awaitTrue(() -> !store.history("r").isEmpty(), 3000);
            assertEquals(TaskExecutionRecord.Status.SUCCESS, store.history("r").get(0).getStatus());
            assertNotNull(e.task("r").getLastRecord());
            assertEquals(0, e.task("r").getConsecutiveFailures());
        } finally {
            e.stop();
        }
    }

    @Test
    @DisplayName("the concurrency guard skips an overlap when skipIfRunning is set")
    void concurrencyGuard() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        runner.gate = new CountDownLatch(1);
        ScheduledTask t = task("c", Trigger.cron("0 0 1 1 *"));
        t.setSkipIfRunning(true);
        store.save(t);
        TaskSchedulerEngine e = engine(store, runner);
        e.start();
        try {
            e.runNow("c");
            awaitTrue(() -> e.isRunning("c"), 3000);
            e.runNow("c");                                   // overlaps -> skipped
            awaitTrue(() -> store.history("c").stream()
                    .anyMatch(r -> r.getStatus() == TaskExecutionRecord.Status.SKIPPED), 3000);
            assertEquals(1, runner.count.get(), "the blocked run is the only real execution");
        } finally {
            runner.gate.countDown();
            e.stop();
        }
    }

    @Test
    @DisplayName("without the guard a second run is allowed to overlap")
    void overlapAllowed() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        runner.gate = new CountDownLatch(1);
        ScheduledTask t = task("o", Trigger.cron("0 0 1 1 *"));
        t.setSkipIfRunning(false);
        store.save(t);
        TaskSchedulerEngine e = engine(store, runner);
        e.start();
        try {
            e.runNow("o");
            awaitTrue(() -> e.isRunning("o"), 3000);
            e.runNow("o");
            awaitTrue(() -> runner.count.get() == 2, 3000);
        } finally {
            runner.gate.countDown();
            e.stop();
        }
    }

    @Test
    @DisplayName("a failing run is retried up to retryCount then succeeds")
    void retries() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        runner.failuresBeforeSuccess = 2;
        ScheduledTask t = task("x", Trigger.cron("0 0 1 1 *"));
        t.setRetryCount(2);
        t.setRetryDelayMillis(0);
        store.save(t);
        TaskSchedulerEngine e = engine(store, runner);
        e.start();
        try {
            e.runNow("x");
            awaitTrue(() -> runner.count.get() == 3, 3000);
            awaitTrue(() -> !store.history("x").isEmpty(), 3000);
            assertEquals(TaskExecutionRecord.Status.SUCCESS, store.history("x").get(0).getStatus());
        } finally {
            e.stop();
        }
    }

    @Test
    @DisplayName("@reboot tasks run once on start and have no next fire time")
    void rebootRunsOnStart() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        store.save(task("b", Trigger.cron("@reboot")));
        TaskSchedulerEngine e = engine(store, runner);
        e.start();
        try {
            awaitTrue(() -> runner.count.get() == 1, 3000);
            assertEquals(0, e.nextFireMillis("b"));
        } finally {
            e.stop();
        }
    }

    @Test
    @DisplayName("a task missed while down is caught up on start under FIRE_ONCE_NOW")
    void misfireCatchUp() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        ScheduledTask t = task("m", Trigger.cron("0 0 1 1 *"));
        t.setMisfirePolicy(MisfirePolicy.FIRE_ONCE_NOW);
        t.setNextRunMillis(System.currentTimeMillis() - 120_000);   // due 2 min ago
        store.save(t);
        TaskSchedulerEngine e = engine(store, runner);
        e.start();
        try {
            awaitTrue(() -> runner.count.get() == 1, 3000);
        } finally {
            e.stop();
        }
    }

    @Test
    @DisplayName("a missed task under IGNORE is not caught up")
    void misfireIgnored() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        ScheduledTask t = task("n", Trigger.cron("0 0 1 1 *"));
        t.setMisfirePolicy(MisfirePolicy.IGNORE);
        t.setNextRunMillis(System.currentTimeMillis() - 120_000);
        store.save(t);
        TaskSchedulerEngine e = engine(store, runner);
        e.start();
        try {
            Thread.sleep(300);
            assertEquals(0, runner.count.get(), "IGNORE skips the missed occurrence");
        } finally {
            e.stop();
        }
    }

    @Test
    @DisplayName("a disabled task is never scheduled")
    void disabledNotScheduled() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        ScheduledTask t = task("d", Trigger.interval(50));
        t.setEnabled(false);
        store.save(t);
        TaskSchedulerEngine e = engine(store, runner);
        e.start();
        try {
            Thread.sleep(300);
            assertEquals(0, runner.count.get());
            assertEquals(0, e.nextFireMillis("d"));
        } finally {
            e.stop();
        }
    }

    @Test
    @DisplayName("reload recomputes next fire times without starting the loop")
    void reloadComputesNextFire() {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        store.save(task("daily", Trigger.cron("0 0 * * *")));
        TaskSchedulerEngine e = engine(store, runner);
        e.reload();
        assertTrue(e.nextFireMillis("daily") > System.currentTimeMillis());
        assertEquals(1, e.tasks().size());
        assertEquals(0, runner.count.get(), "reload alone fires nothing");
    }

    @Test
    @DisplayName("listeners are notified when a task executes")
    void listenerNotified() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        store.save(task("l", Trigger.cron("0 0 1 1 *")));
        TaskSchedulerEngine e = engine(store, runner);
        AtomicReference<TaskExecutionRecord> seen = new AtomicReference<>();
        e.addListener(new TaskSchedulerEngine.Listener() {
            @Override
            public void taskExecuted(ScheduledTask task, TaskExecutionRecord record) {
                seen.set(record);
            }
        });
        e.start();
        try {
            e.runNow("l");
            awaitTrue(() -> seen.get() != null, 3000);
            assertEquals(TaskExecutionRecord.Status.SUCCESS, seen.get().getStatus());
        } finally {
            e.stop();
        }
    }

    @Test
    @DisplayName("a runner that throws is captured as ERROR, never crashing the loop")
    void runnerThrows() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        TaskRunner bad = new TaskRunner() {
            @Override
            public TaskExecutionRecord run(ScheduledTask task) {
                throw new RuntimeException("boom");
            }
        };
        store.save(task("z", Trigger.cron("0 0 1 1 *")));
        TaskSchedulerEngine e = new TaskSchedulerEngine(store, bad, ZoneId.systemDefault(), TICK);
        e.start();
        try {
            e.runNow("z");
            awaitTrue(() -> !store.history("z").isEmpty(), 3000);
            assertEquals(TaskExecutionRecord.Status.ERROR, store.history("z").get(0).getStatus());
            assertTrue(e.isStarted(), "the engine survives a throwing runner");
        } finally {
            e.stop();
        }
    }

    @Test
    @DisplayName("stop halts the loop and clears the schedule")
    void stopClears() throws Exception {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        store.save(task("s", Trigger.interval(50)));
        TaskSchedulerEngine e = engine(store, runner);
        e.start();
        awaitTrue(() -> runner.count.get() >= 1, 3000);
        e.stop();
        assertFalse(e.isStarted());
        int afterStop = runner.count.get();
        Thread.sleep(200);
        assertEquals(afterStop, runner.count.get(), "no further runs after stop");
    }

    @Test
    @DisplayName("start is idempotent and null collaborators are rejected")
    void lifecycleGuards() {
        FakeTaskStore store = new FakeTaskStore();
        FakeTaskRunner runner = new FakeTaskRunner();
        TaskSchedulerEngine e = engine(store, runner);
        e.start();
        try {
            e.start();                       // second start is a no-op
            assertTrue(e.isStarted());
        } finally {
            e.stop();
        }
        e.stop();                            // second stop is a no-op
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new TaskSchedulerEngine(null, runner));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new TaskSchedulerEngine(store, null));
    }
}
