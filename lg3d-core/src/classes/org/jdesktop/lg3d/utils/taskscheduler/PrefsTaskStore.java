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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.jdesktop.lg3d.utils.prefs.LgPreferencesHelper;
import org.jdesktop.lg3d.utils.taskscheduler.MisfirePolicy;
import org.jdesktop.lg3d.utils.taskscheduler.ScheduledTask;
import org.jdesktop.lg3d.utils.taskscheduler.TaskExecutionRecord;
import org.jdesktop.lg3d.utils.taskscheduler.TaskStore;
import org.jdesktop.lg3d.utils.taskscheduler.Trigger;

/**
 * The {@link TaskStore} backed by the user {@link Preferences} tree, following
 * the same pattern as the desktop's other per-user stores. Tasks live under the
 * package node: an {@code ids} key holds the ordered task-id list, each task is a
 * {@code task/<id>} child node, and each task's run history is a {@code hist/<id>}
 * child node holding indexed, pre-encoded {@link TaskExecutionRecord}s.
 *
 * <p>Structured fields (the argv vector and the environment map) are stored as a
 * count plus indexed keys rather than one delimited blob, so an argument or value
 * containing any character (spaces, commas, equals, quotes) round-trips exactly
 * and there is no escaping bug to exploit. Every backing-store failure is logged
 * and swallowed: a corrupt or unreadable node yields an empty list, never an
 * exception that could stop the desktop.</p>
 */
public final class PrefsTaskStore implements TaskStore {

    private static final Logger logger =
            Logger.getLogger("lg.utils.taskscheduler");

    static final String KEY_IDS = "ids";
    private static final String TASK_NODE = "task";
    private static final String HIST_NODE = "hist";

    private final Preferences root;

    /** Uses this package's user preferences node. */
    public PrefsTaskStore() {
        this(LgPreferencesHelper.userNodeForPackage(PrefsTaskStore.class));
    }

    /** Uses an explicit node; visible so tests can inject an isolated node. */
    public PrefsTaskStore(Preferences root) {
        this.root = root;
    }

    // ------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------

    @Override
    public synchronized List<ScheduledTask> load() {
        List<ScheduledTask> out = new ArrayList<ScheduledTask>();
        for (String id : readIds()) {
            try {
                ScheduledTask t = readTask(root.node(TASK_NODE).node(id), id);
                if (t != null) {
                    out.add(t);
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Skipping unreadable task " + id, e);
            }
        }
        return out;
    }

    @Override
    public synchronized void save(ScheduledTask task) {
        if (task == null || task.getId() == null) {
            return;
        }
        try {
            writeTask(root.node(TASK_NODE).node(task.getId()), task);
            List<String> ids = readIds();
            if (!ids.contains(task.getId())) {
                ids.add(task.getId());
                writeIds(ids);
            }
            root.flush();
        } catch (BackingStoreException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not save task " + task.getId(), e);
        }
    }

    @Override
    public synchronized void delete(String taskId) {
        if (taskId == null) {
            return;
        }
        try {
            Preferences tasks = root.node(TASK_NODE);
            if (tasks.nodeExists(taskId)) {
                tasks.node(taskId).removeNode();
            }
            Preferences hist = root.node(HIST_NODE);
            if (hist.nodeExists(taskId)) {
                hist.node(taskId).removeNode();
            }
            List<String> ids = readIds();
            if (ids.remove(taskId)) {
                writeIds(ids);
            }
            root.flush();
        } catch (BackingStoreException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not delete task " + taskId, e);
        }
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    @Override
    public synchronized List<TaskExecutionRecord> history(String taskId) {
        List<TaskExecutionRecord> out = new ArrayList<TaskExecutionRecord>();
        if (taskId == null) {
            return out;
        }
        Preferences node = root.node(HIST_NODE).node(taskId);
        int count = node.getInt("count", 0);
        for (int i = 0; i < count; i++) {
            TaskExecutionRecord r =
                    TaskExecutionRecord.decode(node.get("rec." + i, null));
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public synchronized void appendHistory(String taskId,
            TaskExecutionRecord record, int limit) {
        if (taskId == null || record == null) {
            return;
        }
        try {
            Preferences node = root.node(HIST_NODE).node(taskId);
            List<TaskExecutionRecord> all = history(taskId);
            all.add(0, record);                    // newest first
            int cap = Math.max(1, limit);
            while (all.size() > cap) {
                all.remove(all.size() - 1);
            }
            node.putInt("count", all.size());
            for (int i = 0; i < all.size(); i++) {
                node.put("rec." + i, all.get(i).encode());
            }
            // Clear any stale higher indices from a previous, longer history.
            for (int i = all.size(); ; i++) {
                String key = "rec." + i;
                if (node.get(key, null) == null) {
                    break;
                }
                node.remove(key);
            }
            root.flush();
        } catch (BackingStoreException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not append history for " + taskId, e);
        }
    }

    // ------------------------------------------------------------------
    // Serialisation
    // ------------------------------------------------------------------

    private List<String> readIds() {
        List<String> ids = new ArrayList<String>();
        String raw = root.get(KEY_IDS, "");
        for (String tok : raw.split(",")) {
            String id = tok.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void writeIds(List<String> ids) {
        root.put(KEY_IDS, String.join(",", ids));
    }

    private void writeTask(Preferences n, ScheduledTask t) {
        n.put("name", nz(t.getName()));
        n.put("description", t.getDescription());
        n.putBoolean("enabled", t.isEnabled());
        Trigger tr = t.getTrigger();
        n.put("trigger.kind", tr == null ? "" : tr.getKind().name());
        n.put("trigger.cron", tr == null ? "" : nz(tr.getCron()));
        n.putLong("trigger.period", tr == null ? 0 : tr.getPeriodMillis());
        n.putLong("trigger.at", tr == null ? 0 : tr.getAtEpochMillis());
        n.put("action", t.getAction().name());
        List<String> argv = t.getArgv();
        n.putInt("argc", argv.size());
        for (int i = 0; i < argv.size(); i++) {
            n.put("arg." + i, argv.get(i));
        }
        n.put("workdir", t.getWorkingDir());
        Map<String, String> env = t.getEnv();
        n.putInt("envc", env.size());
        int i = 0;
        for (Map.Entry<String, String> e : env.entrySet()) {
            n.put("env." + i + ".k", e.getKey());
            n.put("env." + i + ".v", nz(e.getValue()));
            i++;
        }
        n.putLong("timeout", t.getTimeoutSeconds());
        n.put("misfire", t.getMisfirePolicy().name());
        n.putBoolean("skipIfRunning", t.isSkipIfRunning());
        n.putInt("retryCount", t.getRetryCount());
        n.putLong("retryDelay", t.getRetryDelayMillis());
        n.putLong("lastRun", t.getLastRunMillis());
        n.putLong("nextRun", t.getNextRunMillis());
        n.putInt("consecutiveFailures", t.getConsecutiveFailures());
        n.put("lastRecord", t.getLastRecord() == null ? "" : t.getLastRecord().encode());
    }

    private ScheduledTask readTask(Preferences n, String id) {
        if (!nodeHasKeys(n)) {
            return null;
        }
        ScheduledTask t = new ScheduledTask(id);
        t.setName(n.get("name", ""));
        t.setDescription(n.get("description", ""));
        t.setEnabled(n.getBoolean("enabled", true));
        t.setTrigger(readTrigger(n));
        String action = n.get("action", ScheduledTask.Action.COMMAND.name());
        t.setAction(parseEnum(ScheduledTask.Action.class, action,
                ScheduledTask.Action.COMMAND));
        int argc = n.getInt("argc", 0);
        List<String> argv = new ArrayList<String>(argc);
        for (int i = 0; i < argc; i++) {
            argv.add(n.get("arg." + i, ""));
        }
        t.setArgv(argv);
        t.setWorkingDir(n.get("workdir", ""));
        int envc = n.getInt("envc", 0);
        Map<String, String> env = new LinkedHashMap<String, String>();
        for (int i = 0; i < envc; i++) {
            String k = n.get("env." + i + ".k", null);
            if (k != null) {
                env.put(k, n.get("env." + i + ".v", ""));
            }
        }
        t.setEnv(env);
        t.setTimeoutSeconds(n.getLong("timeout", ScheduledTask.DEFAULT_TIMEOUT_SECONDS));
        t.setMisfirePolicy(parseEnum(MisfirePolicy.class,
                n.get("misfire", MisfirePolicy.FIRE_ONCE_NOW.name()),
                MisfirePolicy.FIRE_ONCE_NOW));
        t.setSkipIfRunning(n.getBoolean("skipIfRunning", true));
        t.setRetryCount(n.getInt("retryCount", 0));
        t.setRetryDelayMillis(n.getLong("retryDelay", 0));
        t.setLastRunMillis(n.getLong("lastRun", 0));
        t.setNextRunMillis(n.getLong("nextRun", 0));
        t.setConsecutiveFailures(n.getInt("consecutiveFailures", 0));
        t.setLastRecord(TaskExecutionRecord.decode(emptyToNull(n.get("lastRecord", ""))));
        return t;
    }

    private Trigger readTrigger(Preferences n) {
        String kind = n.get("trigger.kind", "");
        if (kind.isEmpty()) {
            return null;
        }
        try {
            switch (Trigger.Kind.valueOf(kind)) {
                case CRON: {
                    String expr = n.get("trigger.cron", "");
                    return expr.isEmpty() ? null : Trigger.cron(expr);
                }
                case INTERVAL: {
                    long p = n.getLong("trigger.period", 0);
                    return p > 0 ? Trigger.interval(p) : null;
                }
                case ONCE:
                    return Trigger.once(n.getLong("trigger.at", 0));
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Ignoring malformed trigger: " + kind, e);
            return null;
        }
    }

    private static boolean nodeHasKeys(Preferences n) {
        try {
            return n.keys().length > 0;
        } catch (BackingStoreException e) {
            return false;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E dflt) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
