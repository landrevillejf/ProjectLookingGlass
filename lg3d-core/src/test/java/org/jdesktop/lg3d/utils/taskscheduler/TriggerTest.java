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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link Trigger} value type. */
class TriggerTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    @DisplayName("a cron trigger validates eagerly and computes its next fire time")
    void cronTrigger() {
        Trigger t = Trigger.cron("0 0 * * *");
        assertEquals(Trigger.Kind.CRON, t.getKind());
        assertEquals("0 0 * * *", t.getCron());
        ZonedDateTime from = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, UTC);
        assertEquals(ZonedDateTime.of(2026, 1, 2, 0, 0, 0, 0, UTC),
                t.nextFireTime(from, UTC).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> Trigger.cron("bogus"));
    }

    @Test
    @DisplayName("an interval trigger advances by exactly its period")
    void intervalTrigger() {
        Trigger t = Trigger.interval(90_000L);
        assertEquals(Trigger.Kind.INTERVAL, t.getKind());
        ZonedDateTime from = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, UTC);
        assertEquals(from.plusSeconds(90), t.nextFireTime(from, UTC).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> Trigger.interval(0));
        assertThrows(IllegalArgumentException.class, () -> Trigger.interval(-5));
    }

    @Test
    @DisplayName("a one-shot trigger fires only while its instant is in the future")
    void onceTrigger() {
        long future = ZonedDateTime.of(2030, 1, 1, 0, 0, 0, 0, UTC)
                .toInstant().toEpochMilli();
        Trigger t = Trigger.once(future);
        assertEquals(Trigger.Kind.ONCE, t.getKind());
        assertTrue(t.nextFireTime(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, UTC), UTC).isPresent());
        assertFalse(t.nextFireTime(ZonedDateTime.of(2031, 1, 1, 0, 0, 0, 0, UTC), UTC).isPresent(),
                "a spent one-shot never fires again");
    }

    @Test
    @DisplayName("describe() produces a human summary for each kind")
    void describe() {
        assertTrue(Trigger.cron("@daily").describe().contains("cron"));
        assertTrue(Trigger.interval(3_600_000L).describe().contains("hour"));
        assertTrue(Trigger.interval(60_000L).describe().contains("minute"));
        assertTrue(Trigger.interval(1000L).describe().contains("second"));
        assertTrue(Trigger.interval(1500L).describe().contains("ms"));
        assertTrue(Trigger.once(System.currentTimeMillis()).describe().contains("once"));
    }

    @Test
    @DisplayName("value equality is by kind and payload")
    void equality() {
        assertEquals(Trigger.cron("0 0 * * *"), Trigger.cron("0 0 * * *"));
        assertEquals(Trigger.cron("0 0 * * *").hashCode(), Trigger.cron("0 0 * * *").hashCode());
        assertNotEquals(Trigger.cron("0 0 * * *"), Trigger.cron("0 1 * * *"));
        assertEquals(Trigger.interval(1000L), Trigger.interval(1000L));
        assertNotEquals(Trigger.interval(1000L), Trigger.interval(2000L));
        assertNotEquals(Trigger.cron("0 0 * * *"), Trigger.interval(1000L));
        assertFalse(Trigger.cron("0 0 * * *").equals("not a trigger"));
        assertEquals(Trigger.cron("0 0 * * *"), Trigger.cron("0 0 * * *"));
        assertTrue(Trigger.cron("0 0 * * *").toString().contains("cron"));
    }
}
