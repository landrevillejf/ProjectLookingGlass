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

/**
 * Executes a {@link ScheduledTask} once and reports the outcome. The engine
 * depends on this interface rather than on {@link ProcessTaskRunner} directly, so
 * the scheduling loop can be unit-tested with a fake runner that returns canned
 * results without ever spawning a real process.
 *
 * <p>Implementations run <em>synchronously</em>: the call blocks until the task
 * has finished, timed out, or failed to start, and always returns a non-null
 * {@link TaskExecutionRecord}. They never throw - every failure mode is captured
 * in the returned record's status - because a single bad task must never take
 * down the scheduler thread.</p>
 */
public interface TaskRunner {

    /**
     * Runs {@code task} to completion and returns what happened. The returned
     * record is never null; the implementation swallows and reports all errors.
     */
    TaskExecutionRecord run(ScheduledTask task);
}
