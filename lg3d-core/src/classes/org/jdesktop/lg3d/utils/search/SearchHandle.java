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
package org.jdesktop.lg3d.utils.search;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The live control object returned by {@link SearchEngine#search}. It lets a
 * caller {@linkplain #cancel() stop} a running walk, poll whether it has
 * {@linkplain #isDone() finished}, {@linkplain #await block} until it does, and
 * read how many matches were {@linkplain #getMatchedCount() emitted} so far.
 *
 * <p>Thread-safe: the flags are atomic and completion is a {@link CountDownLatch},
 * so the EDT can cancel while worker threads are mid-walk and be told, exactly
 * once, when the last worker has wound down.</p>
 */
public final class SearchHandle {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger matched = new AtomicInteger();
    private final CountDownLatch done = new CountDownLatch(1);
    private final Runnable onDone;

    /**
     * Builds a handle. {@code onDone} (may be null) runs exactly once on the
     * thread that observes the walk complete, after the latch is released.
     */
    SearchHandle(Runnable onDone) {
        this.onDone = onDone;
    }

    /** Requests cancellation; the walk stops at the next safe point. Idempotent. */
    public void cancel() {
        cancelled.set(true);
    }

    /** True once {@link #cancel()} has been called. */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /** True when the walk has finished (completed, capped or cancelled). */
    public boolean isDone() {
        return done.getCount() == 0;
    }

    /** How many matches have been emitted so far. */
    public int getMatchedCount() {
        return matched.get();
    }

    /**
     * Blocks until the walk finishes or the timeout elapses.
     *
     * @return true if the walk finished, false on timeout
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }

    /** Records one emitted match. */
    void countMatch() {
        matched.incrementAndGet();
    }

    /** Signals completion exactly once and fires the {@code onDone} callback. */
    void finish() {
        done.countDown();
        if (onDone != null) {
            onDone.run();
        }
    }
}
