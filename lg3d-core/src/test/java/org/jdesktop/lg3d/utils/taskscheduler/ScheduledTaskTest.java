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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link ScheduledTask} bean. */
class ScheduledTaskTest {

    @Test
    @DisplayName("a new task has a random id and production-safe defaults")
    void defaults() {
        ScheduledTask t = new ScheduledTask();
        assertTrue(t.getId() != null && !t.getId().isEmpty());
        assertTrue(t.isEnabled());
        assertEquals(ScheduledTask.Action.COMMAND, t.getAction());
        assertEquals(ScheduledTask.DEFAULT_TIMEOUT_SECONDS, t.getTimeoutSeconds());
        assertEquals(MisfirePolicy.FIRE_ONCE_NOW, t.getMisfirePolicy());
        assertTrue(t.isSkipIfRunning());
        assertEquals(0, t.getRetryCount());
        assertEquals("", t.getDescription());
        assertEquals("", t.getWorkingDir());
        assertEquals("", t.getProgram());
    }

    @Test
    @DisplayName("numeric setters clamp to non-negative values")
    void settersClamp() {
        ScheduledTask t = new ScheduledTask();
        t.setTimeoutSeconds(-10);
        assertEquals(0, t.getTimeoutSeconds());
        t.setRetryCount(-3);
        assertEquals(0, t.getRetryCount());
        t.setRetryDelayMillis(-1);
        assertEquals(0, t.getRetryDelayMillis());
        t.setConsecutiveFailures(-2);
        assertEquals(0, t.getConsecutiveFailures());
    }

    @Test
    @DisplayName("null-safe setters normalise to defaults")
    void nullSafe() {
        ScheduledTask t = new ScheduledTask();
        t.setName(null);
        assertEquals(null, t.getName());
        t.setDescription(null);
        assertEquals("", t.getDescription());
        t.setAction(null);
        assertEquals(ScheduledTask.Action.COMMAND, t.getAction());
        t.setMisfirePolicy(null);
        assertEquals(MisfirePolicy.FIRE_ONCE_NOW, t.getMisfirePolicy());
        t.setArgv(null);
        assertTrue(t.getArgv().isEmpty());
        t.setEnv(null);
        assertTrue(t.getEnv().isEmpty());
    }

    @Test
    @DisplayName("setArgv drops null entries and replaces the whole vector")
    void argvReplace() {
        ScheduledTask t = new ScheduledTask();
        t.setArgv(Arrays.asList("/bin/echo", null, "hi"));
        assertEquals(Arrays.asList("/bin/echo", "hi"), t.getArgv());
        assertEquals("/bin/echo", t.getProgram());
    }

    @Test
    @DisplayName("summary shows the name, schedule and off marker")
    void summary() {
        ScheduledTask t = new ScheduledTask();
        t.setName("Backup");
        t.setTrigger(Trigger.cron("0 3 * * *"));
        assertTrue(t.summary().contains("Backup"));
        assertTrue(t.summary().contains("cron"));
        t.setEnabled(false);
        assertTrue(t.summary().startsWith("[off]"));
        assertEquals(t.summary(), t.toString());
    }

    @Test
    @DisplayName("copy is a deep copy that does not share argv/env with the original")
    void deepCopy() {
        ScheduledTask t = new ScheduledTask("fixed-id");
        t.setName("Job");
        t.setTrigger(Trigger.interval(5000L));
        t.setArgv(Arrays.asList("prog", "arg"));
        t.getEnv().put("K", "V");
        t.setTimeoutSeconds(42);
        t.setRetryCount(2);

        ScheduledTask c = t.copy();
        assertNotSame(t, c);
        assertEquals("fixed-id", c.getId());
        assertEquals("Job", c.getName());
        assertEquals(t.getTrigger(), c.getTrigger());
        assertEquals(42, c.getTimeoutSeconds());
        assertEquals(2, c.getRetryCount());
        assertEquals(Arrays.asList("prog", "arg"), c.getArgv());
        assertEquals("V", c.getEnv().get("K"));

        // Mutating the copy must not touch the original.
        c.getArgv().add("extra");
        c.getEnv().put("K2", "V2");
        assertEquals(2, t.getArgv().size());
        assertFalse(t.getEnv().containsKey("K2"));
    }

    @Test
    @DisplayName("an explicit blank id is replaced by a generated one")
    void blankIdRegenerated() {
        ScheduledTask t = new ScheduledTask("  ");
        assertTrue(t.getId() != null && !t.getId().trim().isEmpty());
        ScheduledTask n = new ScheduledTask(null);
        assertTrue(n.getId() != null && !n.getId().isEmpty());
    }
}
