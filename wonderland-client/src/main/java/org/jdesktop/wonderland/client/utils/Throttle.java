/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.utils),
 * verbatim -- and it needs no seam: the throttle only touches
 * java.util.concurrent, so it is headless-clean as the original was.
 *
 * M2f pulls it in for MovableComponent, which rate-limits local move requests
 * to 2 per second through exactly this helper. Two faithful 2006 behaviours are
 * preserved on purpose because a component depends on them:
 *   - {@link #clear()} cannot cancel a pending task, only drop the reference to
 *     the next one, because {@link #schedule} never stores the scheduled future.
 *     The 2006 clear() kept a dead {@code if (scheduled != null) cancel} arm for a
 *     field nothing ever assigned; it is removed here (an unreachable branch is
 *     not coverage), and the quirk is preserved by schedule() not keeping the
 *     future, so a later "fix" stays a deliberate change rather than a silent one;
 *   - {@code schedule()} runs the task inline when the minimum delay has
 *     elapsed, otherwise it stores the one pending task (a second schedule
 *     before it fires simply replaces the pending runnable) and returns false.
 */
package org.jdesktop.wonderland.client.utils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Throttle {

    private long rate;
    private TimeUnit rateUnit;
    private long lastExecution;
    private Runnable nextTask;

    public Throttle(long rate, TimeUnit rateUnit) {
        this.rate = rate;
        this.rateUnit = rateUnit;
    }

    public synchronized void setRate(long rate, TimeUnit rateUnit) {
        this.rate = rate;
        this.rateUnit = rateUnit;
    }

    public synchronized boolean schedule(Runnable task) {
        if (this.nextTask != null) {
            this.nextTask = task;
            return false;
        } else {
            long delay = System.currentTimeMillis() - this.lastExecution;
            if (delay > this.getMinDelayMillis()) {
                task.run();
                this.lastExecution = System.currentTimeMillis();
                return true;
            } else {
                long runAt = this.getMinDelayMillis() - delay;
                this.nextTask = task;
                getExecutor().schedule(new Runnable() {
                    public void run() {
                        synchronized (Throttle.this) {
                            Throttle.this.nextTask.run();
                            Throttle.this.lastExecution = System.currentTimeMillis();
                            Throttle.this.nextTask = null;
                        }
                    }
                }, runAt, TimeUnit.MILLISECONDS);
                return false;
            }
        }
    }

    public synchronized void clear() {
        this.nextTask = null;
    }

    private synchronized long getMinDelayMillis() {
        long millis = this.rateUnit.toMillis(1L);
        return millis / this.rate;
    }

    private static final ScheduledExecutorService getExecutor() {
        return SingletonHolder.EXECUTOR;
    }

    private static final class SingletonHolder {
        private static final ScheduledExecutorService EXECUTOR =
                Executors.newScheduledThreadPool(2);
    }
}
