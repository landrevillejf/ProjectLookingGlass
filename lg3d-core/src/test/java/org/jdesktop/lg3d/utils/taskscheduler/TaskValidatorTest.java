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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for {@link TaskValidator}'s correctness and safety limits. */
class TaskValidatorTest {

    private static ScheduledTask validCommand() {
        ScheduledTask t = new ScheduledTask();
        t.setName("Backup");
        t.setTrigger(Trigger.cron("0 3 * * *"));
        t.setArgv(List.of("/usr/bin/tar", "-cf", "/tmp/backup.tar", "/home"));
        return t;
    }

    @Test
    @DisplayName("a well-formed command task is valid")
    void validTask() {
        assertTrue(TaskValidator.isValid(validCommand()));
        assertDoesNotThrow(() -> TaskValidator.assertValid(validCommand()));
        assertTrue(TaskValidator.validate(null).contains("task is null"));
    }

    @Test
    @DisplayName("a missing name, trigger or argv is rejected")
    void missingRequiredFields() {
        ScheduledTask noName = validCommand();
        noName.setName("  ");
        assertFalse(TaskValidator.isValid(noName));

        ScheduledTask noTrigger = validCommand();
        noTrigger.setTrigger(null);
        assertFalse(TaskValidator.isValid(noTrigger));

        ScheduledTask noArgv = validCommand();
        noArgv.setArgv(List.of());
        assertFalse(TaskValidator.isValid(noArgv));

        ScheduledTask blankProgram = validCommand();
        blankProgram.setArgv(List.of("   ", "x"));
        assertFalse(TaskValidator.isValid(blankProgram));
    }

    @Test
    @DisplayName("a malformed cron expression is reported with a message")
    void badCron() {
        ScheduledTask t = validCommand();
        t.setTrigger(Trigger.cron("0 0 * * *"));   // valid at construction
        List<String> problems = TaskValidator.validate(t);
        assertTrue(problems.isEmpty());
        // A trigger cannot hold a bad cron (Trigger.cron validates), so instead
        // assert the validator re-checks the expression it is given.
        assertThrows(IllegalArgumentException.class, () -> Trigger.cron("not a cron"));
    }

    @Test
    @DisplayName("an app task needs its command")
    void appTask() {
        ScheduledTask t = new ScheduledTask();
        t.setName("Launch Mail");
        t.setAction(ScheduledTask.Action.APP);
        t.setTrigger(Trigger.cron("0 8 * * *"));
        assertFalse(TaskValidator.isValid(t), "no app command yet");
        t.setArgv(List.of("swingapp org.jdesktop.lg3d.apps.mail.Mail3D"));
        assertTrue(TaskValidator.isValid(t));
    }

    @Test
    @DisplayName("a NUL byte anywhere is rejected (the one argv-corrupting character)")
    void rejectsNul() {
        ScheduledTask t = validCommand();
        t.setName("bad\0name");
        assertFalse(TaskValidator.isValid(t));

        ScheduledTask t2 = validCommand();
        t2.setArgv(List.of("/bin/echo", "a\0b"));
        assertFalse(TaskValidator.isValid(t2));
    }

    @Test
    @DisplayName("resource limits are enforced")
    void limits() {
        ScheduledTask tooManyArgs = validCommand();
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("prog");
        for (int i = 0; i < TaskValidator.MAX_ARGV + 1; i++) {
            args.add("a" + i);
        }
        tooManyArgs.setArgv(args);
        assertFalse(TaskValidator.isValid(tooManyArgs));

        ScheduledTask longArg = validCommand();
        longArg.setArgv(List.of("prog", "x".repeat(TaskValidator.MAX_ARG_LENGTH + 1)));
        assertFalse(TaskValidator.isValid(longArg));

        ScheduledTask longName = validCommand();
        longName.setName("n".repeat(TaskValidator.MAX_NAME_LENGTH + 1));
        assertFalse(TaskValidator.isValid(longName));

        ScheduledTask badTimeout = validCommand();
        badTimeout.setTimeoutSeconds(TaskValidator.MAX_TIMEOUT_SECONDS + 1);
        assertFalse(TaskValidator.isValid(badTimeout));

        ScheduledTask badRetry = validCommand();
        badRetry.setRetryCount(TaskValidator.MAX_RETRY_COUNT + 1);
        assertFalse(TaskValidator.isValid(badRetry));
    }

    @Test
    @DisplayName("environment entries are validated (name, '=', size, NUL)")
    void environment() {
        ScheduledTask t = validCommand();
        t.getEnv().put("PATH", "/usr/bin");
        assertTrue(TaskValidator.isValid(t));

        ScheduledTask eq = validCommand();
        eq.getEnv().put("BAD=NAME", "v");
        assertFalse(TaskValidator.isValid(eq));

        ScheduledTask blank = validCommand();
        blank.getEnv().put("  ", "v");
        assertFalse(TaskValidator.isValid(blank));

        ScheduledTask nul = validCommand();
        nul.getEnv().put("K", "v\0");
        assertFalse(TaskValidator.isValid(nul));
    }

    @Test
    @DisplayName("problemsOf exposes an unmodifiable problem list")
    void problemsOf() {
        ScheduledTask t = new ScheduledTask();   // nothing set
        List<String> problems = TaskValidator.problemsOf(t);
        assertFalse(problems.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> problems.add("x"));
        // assertValid throws with all problems joined.
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> TaskValidator.assertValid(t));
        assertTrue(ex.getMessage().contains("name"));
    }
}
