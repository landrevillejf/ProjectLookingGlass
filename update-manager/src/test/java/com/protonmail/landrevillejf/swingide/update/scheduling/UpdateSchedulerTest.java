package com.protonmail.landrevillejf.swingide.update.scheduling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class UpdateSchedulerTest {

    private static final int AWAIT_SECONDS = 10;

    private UpdateScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new UpdateScheduler();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void testSchedulerInitialization() {
        assertThat(scheduler).isNotNull();
        assertThat(scheduler.getScheduledUpdates()).isEmpty();
        assertThat(scheduler.getPendingCount()).isZero();
        assertThat(scheduler.getRestartDelaySeconds())
            .isEqualTo(UpdateScheduler.DEFAULT_RESTART_DELAY_SECONDS);
    }

    @Test
    void testScheduleUpdateWithFutureTime() {
        LocalDateTime installTime = LocalDateTime.now().plusHours(1);

        String taskId = scheduler.scheduleUpdate("1.2.3", installTime, true);

        assertThat(taskId).isNotBlank();

        UpdateScheduler.ScheduledUpdateTask task = scheduler.getScheduledUpdate(taskId);
        assertThat(task).isNotNull();
        assertThat(task.getTaskId()).isEqualTo(taskId);
        assertThat(task.getUpdateVersion()).isEqualTo("1.2.3");
        assertThat(task.getInstallTime()).isEqualTo(installTime);
        assertThat(task.isAutoRestart()).isTrue();
        assertThat(task.getCreatedAt()).isPositive();
        assertThat((Object) task.getScheduledFuture()).isNotNull();
        assertThat(scheduler.getPendingCount()).isEqualTo(1);
    }

    @Test
    void testScheduleUpdateWithPastTimeFails() {
        LocalDateTime pastTime = LocalDateTime.now().minusHours(1);

        assertThatThrownBy(() -> scheduler.scheduleUpdate("1.2.3", pastTime, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("future");

        assertThat(scheduler.getPendingCount()).isZero();
    }

    @Test
    void testCancelScheduledUpdate() {
        String taskId = scheduler.scheduleUpdate("1.2.3", LocalDateTime.now().plusHours(1), false);

        assertThat(scheduler.cancelScheduledUpdate(taskId)).isTrue();
        assertThat(scheduler.getScheduledUpdate(taskId)).isNull();
        assertThat(scheduler.getPendingCount()).isZero();
    }

    @Test
    void testCancelNonexistentTask() {
        assertThat(scheduler.cancelScheduledUpdate("nonexistent")).isFalse();
    }

    @Test
    void testGetScheduledUpdates() {
        LocalDateTime futureTime = LocalDateTime.now().plusHours(1);
        scheduler.scheduleUpdate("1.2.3", futureTime, false);
        scheduler.scheduleUpdate("1.2.4", futureTime.plusHours(1), true);

        List<UpdateScheduler.ScheduledUpdateTask> tasks = scheduler.getScheduledUpdates();

        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(UpdateScheduler.ScheduledUpdateTask::getUpdateVersion)
            .containsExactlyInAnyOrder("1.2.3", "1.2.4");
        assertThat(scheduler.getPendingCount()).isEqualTo(2);
    }

    @Test
    void testRescheduleUpdate() {
        String taskId = scheduler.scheduleUpdate("1.2.3", LocalDateTime.now().plusHours(1), false);
        LocalDateTime newTime = LocalDateTime.now().plusHours(3);

        assertThat(scheduler.rescheduleUpdate(taskId, newTime)).isTrue();
        assertThat(scheduler.getScheduledUpdate(taskId).getInstallTime()).isEqualTo(newTime);
        assertThat(scheduler.getPendingCount()).isEqualTo(1);
    }

    @Test
    void testRescheduleToPastTimeFailsAndKeepsTheTask() {
        String taskId = scheduler.scheduleUpdate("1.2.3", LocalDateTime.now().plusHours(1), false);

        assertThatThrownBy(() -> scheduler.rescheduleUpdate(taskId, LocalDateTime.now().minusHours(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("future");

        assertThat(scheduler.getScheduledUpdate(taskId)).isNotNull();
    }

    @Test
    void testRescheduleUnknownTask() {
        assertThat(scheduler.rescheduleUpdate("nonexistent", LocalDateTime.now().plusHours(1))).isFalse();
    }

    @Test
    void testInstallHandlerIsInvoked() throws Exception {
        CountDownLatch installed = new CountDownLatch(1);
        AtomicReference<UpdateScheduler.ScheduledUpdateTask> executed = new AtomicReference<>();
        scheduler.setInstallHandler(task -> {
            executed.set(task);
            installed.countDown();
        });

        scheduler.scheduleUpdate("1.2.3", LocalDateTime.now().plusSeconds(1), false);

        assertThat(installed.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(executed.get()).isNotNull();
        assertThat(executed.get().getUpdateVersion()).isEqualTo("1.2.3");
        awaitPendingCount(0);
    }

    @Test
    void testListenerReceivesLifecycleEvents() throws Exception {
        RecordingListener listener = new RecordingListener();
        scheduler.setListener(listener);
        scheduler.setInstallHandler(task -> { });

        String taskId = scheduler.scheduleUpdate("1.2.3", LocalDateTime.now().plusSeconds(1), false);

        assertThat(listener.scheduled.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.complete.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.events).contains("scheduled", "starting", "complete");
        awaitPendingCount(0);
        assertThat(scheduler.cancelScheduledUpdate(taskId)).isFalse();
    }

    @Test
    void testListenerIsNotifiedOnCancellationAndRescheduling() throws Exception {
        RecordingListener listener = new RecordingListener();
        scheduler.setListener(listener);

        String taskId = scheduler.scheduleUpdate("1.2.3", LocalDateTime.now().plusHours(1), false);
        assertThat(scheduler.rescheduleUpdate(taskId, LocalDateTime.now().plusHours(2))).isTrue();
        assertThat(listener.rescheduled.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        assertThat(scheduler.cancelScheduledUpdate(taskId)).isTrue();
        assertThat(listener.cancelled.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void testFailingInstallHandlerNotifiesListener() throws Exception {
        RecordingListener listener = new RecordingListener();
        scheduler.setListener(listener);
        scheduler.setInstallHandler(task -> {
            throw new IllegalStateException("boom");
        });

        scheduler.scheduleUpdate("1.2.3", LocalDateTime.now().plusSeconds(1), false);

        assertThat(listener.failed.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.failure.get())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");
        awaitPendingCount(0);
    }

    @Test
    void testMissingInstallHandlerReportsFailure() throws Exception {
        RecordingListener listener = new RecordingListener();
        scheduler.setListener(listener);

        scheduler.scheduleUpdate("1.2.3", LocalDateTime.now().plusSeconds(1), false);

        assertThat(listener.failed.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.failure.get())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No install handler registered");
        awaitPendingCount(0);
    }

    @Test
    void testAutoRestartTriggersRestartNotification() throws Exception {
        RecordingListener listener = new RecordingListener();
        scheduler.setListener(listener);
        scheduler.setInstallHandler(task -> { });
        scheduler.setRestartDelaySeconds(0);

        scheduler.scheduleUpdate("1.2.3", LocalDateTime.now().plusSeconds(1), true);

        assertThat(listener.complete.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.restart.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.events).contains("restart");
    }

    @Test
    void testScheduleRestartDirectly() throws Exception {
        RecordingListener listener = new RecordingListener();
        scheduler.setListener(listener);

        scheduler.scheduleRestart(0);

        assertThat(listener.restart.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void testRestartDelayIsClampedToZero() {
        scheduler.setRestartDelaySeconds(15);
        assertThat(scheduler.getRestartDelaySeconds()).isEqualTo(15);

        scheduler.setRestartDelaySeconds(-3);
        assertThat(scheduler.getRestartDelaySeconds()).isZero();
    }

    @Test
    void testShutdownClearsPendingTasks() {
        scheduler.scheduleUpdate("1.2.3", LocalDateTime.now().plusHours(1), false);

        assertThatCode(() -> scheduler.shutdown()).doesNotThrowAnyException();

        assertThat(scheduler.getScheduledUpdates()).isEmpty();
        assertThat(scheduler.getPendingCount()).isZero();
    }

    private void awaitPendingCount(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(AWAIT_SECONDS);
        while (scheduler.getPendingCount() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(scheduler.getPendingCount()).isEqualTo(expected);
    }

    /**
     * Records every scheduler callback so tests can wait on them.
     */
    private static final class RecordingListener implements UpdateScheduler.ScheduledUpdateListener {

        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch scheduled = new CountDownLatch(1);
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final CountDownLatch rescheduled = new CountDownLatch(1);
        private final CountDownLatch starting = new CountDownLatch(1);
        private final CountDownLatch complete = new CountDownLatch(1);
        private final CountDownLatch failed = new CountDownLatch(1);
        private final CountDownLatch restart = new CountDownLatch(1);
        private final AtomicReference<Exception> failure = new AtomicReference<>();

        @Override
        public void onUpdateScheduled(String taskId, LocalDateTime installTime) {
            events.add("scheduled");
            scheduled.countDown();
        }

        @Override
        public void onUpdateScheduleCancelled(String taskId) {
            events.add("cancelled");
            cancelled.countDown();
        }

        @Override
        public void onUpdateRescheduled(String taskId, LocalDateTime newInstallTime) {
            events.add("rescheduled");
            rescheduled.countDown();
        }

        @Override
        public void onScheduledUpdateStarting(String taskId) {
            events.add("starting");
            starting.countDown();
        }

        @Override
        public void onScheduledUpdateComplete(String taskId, boolean willRestart) {
            events.add("complete");
            complete.countDown();
        }

        @Override
        public void onScheduledUpdateFailed(String taskId, Exception cause) {
            failure.set(cause);
            events.add("failed");
            failed.countDown();
        }

        @Override
        public void onRestartTriggered() {
            events.add("restart");
            restart.countDown();
        }
    }
}
