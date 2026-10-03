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
 * What the {@link TaskSchedulerEngine} does when it discovers that a task's
 * scheduled fire time passed while the desktop (and therefore the scheduler) was
 * not running - the classic "misfire" a laptop that sleeps overnight hits.
 *
 * <p>This is what makes the scheduler <em>reliable</em>: a missed job is never
 * silently lost, and the user chooses whether a late catch-up run is desirable.</p>
 */
public enum MisfirePolicy {

    /**
     * Run the task once immediately on discovery, then resume the normal
     * schedule. The default: a missed backup still runs, just late.
     */
    FIRE_ONCE_NOW,

    /**
     * Skip the missed occurrence entirely and wait for the next scheduled time.
     * Right for jobs that are only meaningful at their exact time (a reminder).
     */
    IGNORE,

    /**
     * Treat the missed occurrence as still pending and fire it on the next
     * scheduler tick regardless of how late it is, then reschedule. Similar to
     * {@link #FIRE_ONCE_NOW} but never fires during the initial start-up sweep.
     */
    FIRE_ON_NEXT_TICK
}
