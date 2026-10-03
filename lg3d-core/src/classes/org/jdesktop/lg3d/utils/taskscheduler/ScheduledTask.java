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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One user-defined scheduled job: <em>what</em> to run, <em>when</em> to run it
 * (a {@link Trigger}), and <em>how</em> the scheduler should behave around it
 * (timeout, concurrency guard, misfire policy, retries). This is the persistent
 * unit the {@link TaskStore} saves and the {@link TaskSchedulerEngine} fires, and
 * the model the scheduler UI edits.
 *
 * <p>A task runs either an external {@link Action#COMMAND} - an argv vector
 * executed directly with {@link ProcessBuilder}, never through a shell, so an
 * argument can never be re-interpreted as a shell metacharacter - or a start-menu
 * {@link Action#APP}, launched in-JVM exactly as clicking the menu item would.
 * The argv list and the environment map are held as structured data rather than a
 * single command string, which is what keeps execution injection-safe.</p>
 *
 * <p>The class is a mutable bean (the UI edits fields in place and the engine
 * updates the run-state fields after each execution); individual field updates
 * are published through the {@link TaskStore} under the engine's lock.</p>
 */
public final class ScheduledTask {

    /** What a task actually does when it fires. */
    public enum Action {
        /** Run an external argv command as a child process. */
        COMMAND,
        /** Launch a start-menu application in the desktop JVM. */
        APP
    }

    /** Default per-run wall-clock limit; 0 would mean "no timeout". */
    public static final long DEFAULT_TIMEOUT_SECONDS = 300;
    /** Default history depth retained per task. */
    public static final int DEFAULT_HISTORY_LIMIT = 20;

    private String id;
    private String name;
    private String description = "";
    private boolean enabled = true;

    private Trigger trigger;

    private Action action = Action.COMMAND;
    private final List<String> argv = new ArrayList<String>();
    private String workingDir = "";
    private final Map<String, String> env = new LinkedHashMap<String, String>();

    private long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private MisfirePolicy misfirePolicy = MisfirePolicy.FIRE_ONCE_NOW;
    /** When true a fire is skipped if the previous run is still going. */
    private boolean skipIfRunning = true;
    private int retryCount = 0;
    private long retryDelayMillis = 0;

    // --- Run state (updated by the engine, persisted for the UI) ---------
    private long lastRunMillis = 0;
    private long nextRunMillis = 0;
    private TaskExecutionRecord lastRecord;
    private int consecutiveFailures = 0;

    /** Creates a new task with a fresh random id and no trigger yet. */
    public ScheduledTask() {
        this.id = UUID.randomUUID().toString();
    }

    /** Creates a task with an explicit id (used when loading from the store). */
    public ScheduledTask(String id) {
        this.id = (id == null || id.trim().isEmpty())
                ? UUID.randomUUID().toString() : id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description == null ? "" : description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public void setTrigger(Trigger trigger) {
        this.trigger = trigger;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action == null ? Action.COMMAND : action;
    }

    /** The live argv list; mutate through {@link #setArgv} for a bulk replace. */
    public List<String> getArgv() {
        return argv;
    }

    public void setArgv(List<String> args) {
        argv.clear();
        if (args != null) {
            for (String a : args) {
                if (a != null) {
                    argv.add(a);
                }
            }
        }
    }

    public String getWorkingDir() {
        return workingDir == null ? "" : workingDir;
    }

    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir == null ? "" : workingDir;
    }

    /** The live environment overlay added to the child process environment. */
    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> values) {
        env.clear();
        if (values != null) {
            env.putAll(values);
        }
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
    }

    public MisfirePolicy getMisfirePolicy() {
        return misfirePolicy;
    }

    public void setMisfirePolicy(MisfirePolicy policy) {
        this.misfirePolicy = policy == null ? MisfirePolicy.FIRE_ONCE_NOW : policy;
    }

    public boolean isSkipIfRunning() {
        return skipIfRunning;
    }

    public void setSkipIfRunning(boolean skipIfRunning) {
        this.skipIfRunning = skipIfRunning;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = Math.max(0, retryCount);
    }

    public long getRetryDelayMillis() {
        return retryDelayMillis;
    }

    public void setRetryDelayMillis(long retryDelayMillis) {
        this.retryDelayMillis = Math.max(0, retryDelayMillis);
    }

    public long getLastRunMillis() {
        return lastRunMillis;
    }

    public void setLastRunMillis(long lastRunMillis) {
        this.lastRunMillis = lastRunMillis;
    }

    public long getNextRunMillis() {
        return nextRunMillis;
    }

    public void setNextRunMillis(long nextRunMillis) {
        this.nextRunMillis = nextRunMillis;
    }

    public TaskExecutionRecord getLastRecord() {
        return lastRecord;
    }

    public void setLastRecord(TaskExecutionRecord lastRecord) {
        this.lastRecord = lastRecord;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = Math.max(0, consecutiveFailures);
    }

    /** The command's first token (the program), or "" when unset. */
    public String getProgram() {
        return argv.isEmpty() ? "" : argv.get(0);
    }

    /** A single-line summary shown in the scheduler's task list. */
    public String summary() {
        String when = trigger == null ? "(no schedule)" : trigger.describe();
        return (enabled ? "" : "[off] ") + (name == null ? id : name) + "  \u2014  " + when;
    }

    /** A deep copy, so the UI can edit a draft without mutating the live task. */
    public ScheduledTask copy() {
        ScheduledTask t = new ScheduledTask(id);
        t.name = name;
        t.description = description;
        t.enabled = enabled;
        t.trigger = trigger;
        t.action = action;
        t.setArgv(argv);
        t.workingDir = workingDir;
        t.setEnv(env);
        t.timeoutSeconds = timeoutSeconds;
        t.misfirePolicy = misfirePolicy;
        t.skipIfRunning = skipIfRunning;
        t.retryCount = retryCount;
        t.retryDelayMillis = retryDelayMillis;
        t.lastRunMillis = lastRunMillis;
        t.nextRunMillis = nextRunMillis;
        t.lastRecord = lastRecord;
        t.consecutiveFailures = consecutiveFailures;
        return t;
    }

    @Override
    public String toString() {
        return summary();
    }
}
