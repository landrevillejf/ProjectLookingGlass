/*
 * M2f: the pure rate-limiter MovableComponent leans on. Deterministic except for
 * the one genuinely time-based branch (the pending task actually firing off the
 * shared executor), which is bounded by a latch + @Timeout rather than a sleep.
 * The minDelay is set to 50 ms (SECONDS / 20) so an immediately-following
 * schedule lands inside the window and is buffered, and the buffered task still
 * runs well inside the test timeout.
 */
package org.jdesktop.wonderland.client.utils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;

class ThrottleTest {

    @Test
    @Timeout(5)
    void firstScheduleRunsInlineWithinTheWindow() throws Exception {
        Throttle throttle = new Throttle(20L, TimeUnit.SECONDS); // min delay 50 ms
        AtomicInteger ran = new AtomicInteger();

        boolean inline = throttle.schedule(ran::incrementAndGet);

        assertThat(inline).isTrue();
        assertThat(ran).hasValue(1);

        // now inside the window (lastExecution was just stamped): buffered
        CountDownLatch latch = new CountDownLatch(1);
        boolean buffered = throttle.schedule(latch::countDown);
        assertThat(buffered).isFalse();

        // a second buffered schedule REPLACES the pending task and returns false
        AtomicInteger replaced = new AtomicInteger();
        boolean third = throttle.schedule(() -> {
            replaced.incrementAndGet();
            latch.countDown();
        });
        assertThat(third).isFalse();

        // the executor eventually runs the LAST buffered task
        assertThat(latch.await(4, TimeUnit.SECONDS)).isTrue();
        assertThat(replaced).hasValue(1);
    }

    @Test
    @Timeout(5)
    void setRateAndClearAreSafe() {
        Throttle throttle = new Throttle(20L, TimeUnit.SECONDS);
        throttle.setRate(1L, TimeUnit.MINUTES); // widen the window
        AtomicInteger ran = new AtomicInteger();
        assertThat(throttle.schedule(ran::incrementAndGet)).isTrue(); // lastExec 0 -> inline
        // clear on a throttle with no tracked future: the original never stores
        // the ScheduledFuture, so the cancel branch is unreachable -- clear just
        // drops the (already null) next task and returns
        throttle.clear();
        assertThat(ran).hasValue(1);
    }
}
