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

/**
 * Persistence for the desktop's scheduled tasks and their run history. The
 * production implementation is {@link PrefsTaskStore} (a {@code java.util.prefs}
 * node beside the other per-user desktop settings); tests substitute an
 * in-memory store, which is why the engine depends on this interface rather than
 * on Preferences directly.
 *
 * <p>Implementations must never throw on a backing-store failure: losing a
 * scheduled task must not stop the desktop from starting, so a store swallows and
 * logs I/O errors and returns whatever it could read.</p>
 */
public interface TaskStore {

    /** Every persisted task, in a stable order. Never null. */
    List<ScheduledTask> load();

    /** Persists one task (insert or update), keyed by its id. */
    void save(ScheduledTask task);

    /** Removes a task and its history. */
    void delete(String taskId);

    /** The most-recent-first execution history for a task (possibly empty). */
    List<TaskExecutionRecord> history(String taskId);

    /**
     * Prepends one execution record to a task's history, trimming it to at most
     * {@code limit} entries.
     */
    void appendHistory(String taskId, TaskExecutionRecord record, int limit);
}
