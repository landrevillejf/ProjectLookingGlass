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

import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Persistence round-trip tests for {@link PrefsTaskStore}.
 *
 * <p>The store is exercised against a <em>unique, throwaway</em> preferences node
 * that is removed in {@link #tearDown()} - the test JVM does not isolate
 * {@code java.util.prefs}, so the node is confined to a test-only path (never the
 * production package node) and always cleaned up, leaving the real user store
 * untouched.</p>
 */
class PrefsTaskStoreTest {

    private Preferences node;
    private PrefsTaskStore store;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("/lg3d/taskscheduler-test/" + UUID.randomUUID());
        store = new PrefsTaskStore(node);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (node != null) {
            node.removeNode();
            Preferences.userRoot().flush();
        }
    }

    private static ScheduledTask sample() {
        ScheduledTask t = new ScheduledTask("task-1");
        t.setName("Nightly backup");
        t.setDescription("tar the home dir");
        t.setEnabled(true);
        t.setTrigger(Trigger.cron("30 2 * * *"));
        t.setAction(ScheduledTask.Action.COMMAND);
        t.setArgv(List.of("/usr/bin/tar", "-cf", "/tmp/x.tar", "a b", "c=d,e;f"));
        t.setWorkingDir("/tmp");
        t.getEnv().put("FOO", "bar baz");
        t.getEnv().put("EMPTY", "");
        t.setTimeoutSeconds(120);
        t.setMisfirePolicy(MisfirePolicy.IGNORE);
        t.setSkipIfRunning(false);
        t.setRetryCount(3);
        t.setRetryDelayMillis(5000);
        return t;
    }

    @Test
    @DisplayName("an empty store loads no tasks")
    void emptyLoad() {
        assertTrue(store.load().isEmpty());
        assertTrue(store.history("nope").isEmpty());
    }

    @Test
    @DisplayName("a task round-trips through the store with tricky argv and env intact")
    void roundTrip() {
        store.save(sample());
        List<ScheduledTask> loaded = store.load();
        assertEquals(1, loaded.size());
        ScheduledTask t = loaded.get(0);
        assertEquals("task-1", t.getId());
        assertEquals("Nightly backup", t.getName());
        assertEquals("tar the home dir", t.getDescription());
        assertEquals(Trigger.cron("30 2 * * *"), t.getTrigger());
        // Arguments with spaces, commas, equals and semicolons survive exactly.
        assertEquals(List.of("/usr/bin/tar", "-cf", "/tmp/x.tar", "a b", "c=d,e;f"), t.getArgv());
        assertEquals("/tmp", t.getWorkingDir());
        assertEquals("bar baz", t.getEnv().get("FOO"));
        assertEquals("", t.getEnv().get("EMPTY"));
        assertEquals(120, t.getTimeoutSeconds());
        assertEquals(MisfirePolicy.IGNORE, t.getMisfirePolicy());
        assertFalse(t.isSkipIfRunning());
        assertEquals(3, t.getRetryCount());
        assertEquals(5000, t.getRetryDelayMillis());
    }

    @Test
    @DisplayName("interval and one-shot triggers round-trip")
    void triggerKinds() {
        ScheduledTask interval = new ScheduledTask("i");
        interval.setName("i");
        interval.setTrigger(Trigger.interval(60_000L));
        interval.setArgv(List.of("prog"));
        store.save(interval);

        ScheduledTask once = new ScheduledTask("o");
        once.setName("o");
        once.setTrigger(Trigger.once(1_800_000_000_000L));
        once.setArgv(List.of("prog"));
        store.save(once);

        List<ScheduledTask> loaded = store.load();
        assertEquals(2, loaded.size());
        ScheduledTask li = loaded.stream().filter(x -> x.getId().equals("i")).findFirst().orElseThrow();
        assertEquals(Trigger.Kind.INTERVAL, li.getTrigger().getKind());
        assertEquals(60_000L, li.getTrigger().getPeriodMillis());
        ScheduledTask lo = loaded.stream().filter(x -> x.getId().equals("o")).findFirst().orElseThrow();
        assertEquals(Trigger.Kind.ONCE, lo.getTrigger().getKind());
        assertEquals(1_800_000_000_000L, lo.getTrigger().getAtEpochMillis());
    }

    @Test
    @DisplayName("save is idempotent (updating does not duplicate the id list)")
    void saveIsUpdate() {
        store.save(sample());
        ScheduledTask again = sample();
        again.setName("Renamed");
        store.save(again);
        List<ScheduledTask> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals("Renamed", loaded.get(0).getName());
    }

    @Test
    @DisplayName("delete removes the task and its history")
    void delete() {
        store.save(sample());
        store.appendHistory("task-1", new TaskExecutionRecord(
                1L, 2L, TaskExecutionRecord.Status.SUCCESS, 0, "ok"), 20);
        assertEquals(1, store.history("task-1").size());
        store.delete("task-1");
        assertTrue(store.load().isEmpty());
        assertTrue(store.history("task-1").isEmpty());
    }

    @Test
    @DisplayName("history is newest-first and trimmed to the limit")
    void historyCapped() {
        store.save(sample());
        for (int i = 0; i < 10; i++) {
            store.appendHistory("task-1", new TaskExecutionRecord(
                    i, 1L, TaskExecutionRecord.Status.SUCCESS, 0, "run" + i), 5);
        }
        List<TaskExecutionRecord> h = store.history("task-1");
        assertEquals(5, h.size());
        assertEquals("run9", h.get(0).getOutput(), "most recent first");
        assertEquals("run5", h.get(4).getOutput());
    }

    @Test
    @DisplayName("a task's last record and run-state fields persist")
    void runStatePersists() {
        ScheduledTask t = sample();
        t.setLastRunMillis(12345L);
        t.setNextRunMillis(67890L);
        t.setConsecutiveFailures(2);
        t.setLastRecord(new TaskExecutionRecord(
                12345L, 99L, TaskExecutionRecord.Status.FAILURE, 7, "boom"));
        store.save(t);
        ScheduledTask l = store.load().get(0);
        assertEquals(12345L, l.getLastRunMillis());
        assertEquals(67890L, l.getNextRunMillis());
        assertEquals(2, l.getConsecutiveFailures());
        assertNotNull(l.getLastRecord());
        assertEquals(TaskExecutionRecord.Status.FAILURE, l.getLastRecord().getStatus());
        assertEquals(7, l.getLastRecord().getExitCode());
    }

    @Test
    @DisplayName("null ids and records are ignored rather than throwing")
    void defensive() {
        store.save(null);
        store.delete(null);
        store.appendHistory(null, null, 5);
        assertTrue(store.load().isEmpty());
    }
}
