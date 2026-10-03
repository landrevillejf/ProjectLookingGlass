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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Execution tests for {@link ProcessTaskRunner}. The command tests are confined to
 * Unix where {@code /bin/echo}, {@code /bin/sh} and {@code /bin/sleep} exist (the
 * Windows CI leg has no such fixed paths), and each still guards on the binary
 * actually being executable. The APP-dispatch test overrides the in-JVM launch so
 * it runs anywhere without starting a desktop application.
 */
class ProcessTaskRunnerTest {

    private static boolean have(String path) {
        return new File(path).canExecute();
    }

    private static ScheduledTask command(String... argv) {
        ScheduledTask t = new ScheduledTask("t");
        t.setName("t");
        t.setTrigger(Trigger.cron("* * * * *"));
        List<String> a = new ArrayList<>();
        for (String s : argv) {
            a.add(s);
        }
        t.setArgv(a);
        t.setTimeoutSeconds(10);
        return t;
    }

    @Test
    @DisplayName("a null task yields an ERROR record rather than throwing")
    void nullTask() {
        TaskExecutionRecord r = new ProcessTaskRunner().run(null);
        assertEquals(TaskExecutionRecord.Status.ERROR, r.getStatus());
    }

    @Test
    @DisplayName("an empty argv yields an ERROR record")
    void emptyArgv() {
        ScheduledTask t = new ScheduledTask("t");
        t.setName("t");
        t.setAction(ScheduledTask.Action.COMMAND);
        TaskExecutionRecord r = new ProcessTaskRunner().run(t);
        assertEquals(TaskExecutionRecord.Status.ERROR, r.getStatus());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("a successful command reports SUCCESS, exit 0 and its output")
    void success() {
        assumeTrue(have("/bin/echo"));
        TaskExecutionRecord r = new ProcessTaskRunner().run(command("/bin/echo", "hello world"));
        assertEquals(TaskExecutionRecord.Status.SUCCESS, r.getStatus());
        assertEquals(0, r.getExitCode());
        assertTrue(r.isSuccess());
        assertTrue(r.getOutput().contains("hello world"));
        assertTrue(r.getDurationMillis() >= 0);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("a non-zero exit is reported as FAILURE with the exit code")
    void failure() {
        assumeTrue(have("/bin/sh"));
        TaskExecutionRecord r = new ProcessTaskRunner().run(command("/bin/sh", "-c", "exit 3"));
        assertEquals(TaskExecutionRecord.Status.FAILURE, r.getStatus());
        assertEquals(3, r.getExitCode());
        assertFalse(r.isSuccess());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("a run exceeding its timeout is force-killed and reported TIMED_OUT")
    void timeout() {
        assumeTrue(have("/bin/sleep"));
        ScheduledTask t = command("/bin/sleep", "10");
        t.setTimeoutSeconds(1);
        long before = System.currentTimeMillis();
        TaskExecutionRecord r = new ProcessTaskRunner().run(t);
        long elapsed = System.currentTimeMillis() - before;
        assertEquals(TaskExecutionRecord.Status.TIMED_OUT, r.getStatus());
        assertTrue(elapsed < 8000, "the run was killed near the 1s timeout, not left to finish");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("a missing executable is reported as ERROR")
    void missingProgram() {
        TaskExecutionRecord r = new ProcessTaskRunner().run(
                command("/nonexistent/program-xyz-123", "arg"));
        assertEquals(TaskExecutionRecord.Status.ERROR, r.getStatus());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("argv is passed verbatim: shell metacharacters are inert (no injection)")
    void argvIsNotShellInterpreted() {
        assumeTrue(have("/bin/echo"));
        File marker = new File("/tmp/lg3d-taskscheduler-pwned-" + System.nanoTime());
        String payload = "; touch " + marker.getAbsolutePath() + " ; echo injected";
        TaskExecutionRecord r = new ProcessTaskRunner().run(command("/bin/echo", payload));
        assertEquals(TaskExecutionRecord.Status.SUCCESS, r.getStatus());
        // The whole payload is one literal argument echoed back, not executed.
        assertTrue(r.getOutput().contains("touch " + marker.getAbsolutePath()));
        assertFalse(marker.exists(), "no shell ran, so the injected command never executed");
        if (marker.exists()) {
            marker.delete();
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("the environment overlay reaches the child process")
    void environmentOverlay() {
        assumeTrue(have("/bin/sh"));
        ScheduledTask t = command("/bin/sh", "-c", "printf %s \"$LG3D_TEST_VAR\"");
        t.getEnv().put("LG3D_TEST_VAR", "present");
        TaskExecutionRecord r = new ProcessTaskRunner().run(t);
        assertEquals(TaskExecutionRecord.Status.SUCCESS, r.getStatus());
        assertTrue(r.getOutput().contains("present"));
    }

    @Test
    @DisplayName("an APP task dispatches the in-JVM launch and reports SUCCESS")
    void appDispatch() {
        final String[] captured = new String[1];
        ProcessTaskRunner runner = new ProcessTaskRunner() {
            @Override
            void launchApp(String command) {
                captured[0] = command;
            }
        };
        ScheduledTask t = new ScheduledTask("app");
        t.setName("app");
        t.setAction(ScheduledTask.Action.APP);
        t.setArgv(List.of("swingapp", "com.example.Main"));
        TaskExecutionRecord r = runner.run(t);
        assertEquals(TaskExecutionRecord.Status.SUCCESS, r.getStatus());
        assertEquals("swingapp com.example.Main", captured[0]);
    }

    @Test
    @DisplayName("an APP task with no command reports ERROR")
    void appNoCommand() {
        ScheduledTask t = new ScheduledTask("app");
        t.setName("app");
        t.setAction(ScheduledTask.Action.APP);
        TaskExecutionRecord r = new ProcessTaskRunner().run(t);
        assertEquals(TaskExecutionRecord.Status.ERROR, r.getStatus());
    }
}
