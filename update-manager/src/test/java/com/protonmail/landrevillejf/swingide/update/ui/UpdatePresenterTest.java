package com.protonmail.landrevillejf.swingide.update.ui;

import com.protonmail.landrevillejf.swingide.core.bus.EventBus;
import com.protonmail.landrevillejf.swingide.update.UpdateInfo;
import com.protonmail.landrevillejf.swingide.update.UpdateService;
import com.protonmail.landrevillejf.swingide.update.events.UpdateAvailableEvent;
import com.protonmail.landrevillejf.swingide.update.events.UpdateProgressEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The presenter never opens a dialog in this suite: the module runs headless, so
 * every Swing branch is expected to degrade to a status message. What is asserted
 * here is the wiring - the service calls, the event subscriptions and the messages
 * the hosts display.
 */
class UpdatePresenterTest {

    private static final String CURRENT_VERSION = "1.2.3";
    private static final long STATUS_TIMEOUT_SECONDS = 5;

    private UpdateService service;
    private EventBus eventBus;
    private UpdatePresenter presenter;
    private List<String> statuses;

    @BeforeEach
    void setUp() {
        service = mock(UpdateService.class);
        eventBus = new EventBus();
        when(service.getEventBus()).thenReturn(eventBus);
        when(service.getCurrentVersion()).thenReturn(CURRENT_VERSION);
        when(service.checkForUpdatesQuietly()).thenReturn(CompletableFuture.completedFuture(null));

        presenter = new UpdatePresenter(service);
        statuses = Collections.synchronizedList(new ArrayList<>());
        presenter.setStatusListener(statuses::add);
    }

    @Test
    @DisplayName("A manual check reports its progress and the up to date outcome")
    void testCheckForUpdatesReportsTheOutcome() throws InterruptedException {
        CountDownLatch reported = awaitStatuses(2);

        presenter.checkForUpdates();

        assertThat(reported.await(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(statuses).containsExactly("Checking for updates...", "No updates available");
    }

    @Test
    @DisplayName("A manual check proposing a version reports it instead of the dialog")
    void testCheckForUpdatesReportsAnAvailableVersion() throws InterruptedException {
        when(service.checkForUpdatesQuietly())
            .thenReturn(CompletableFuture.completedFuture(update("2.0.0")));
        CountDownLatch reported = awaitStatuses(2);

        presenter.checkForUpdates();

        assertThat(reported.await(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(statuses).containsExactly("Checking for updates...",
            "Update available: 2.0.0");
    }

    @Test
    @DisplayName("A failing check reports the failure")
    void testCheckForUpdatesReportsAFailure() throws InterruptedException {
        CompletableFuture<UpdateInfo> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("server unreachable"));
        when(service.checkForUpdatesQuietly()).thenReturn(failed);
        CountDownLatch reported = awaitStatuses(2);

        presenter.checkForUpdates();

        assertThat(reported.await(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(statuses).containsExactly("Checking for updates...", "Update check failed");
    }

    @Test
    @DisplayName("Without a service the check only reports the problem")
    void testCheckForUpdatesWithoutService() {
        UpdatePresenter orphan = new UpdatePresenter(null);
        List<String> reported = new ArrayList<>();
        orphan.setStatusListener(reported::add);

        orphan.checkForUpdates();

        assertThat(reported).containsExactly("Update service unavailable");
    }

    @Test
    @DisplayName("The tray menu is wired to the same flows as the Help menu")
    void testRegisterActionListenerWiresTheTrayMenu() {
        presenter.registerActionListener();

        ArgumentCaptor<UpdateService.UpdateActionListener> captor =
            ArgumentCaptor.forClass(UpdateService.UpdateActionListener.class);
        verify(service).setActionListener(captor.capture());

        UpdateService.UpdateActionListener listener = captor.getValue();
        assertThatCode(() -> listener.onShowChangelog()).doesNotThrowAnyException();
        assertThatCode(() -> listener.onShowSettings()).doesNotThrowAnyException();

        // Headless: the changelog is not even rendered.
        verify(service, never()).getLocalChangelogHtml(any());
    }

    @Test
    @DisplayName("Registering the tray callbacks without a service is a no-op")
    void testRegisterActionListenerWithoutService() {
        assertThatCode(() -> new UpdatePresenter(null).registerActionListener())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A version found by a periodic check is proposed to the user")
    void testSubscribeToUpdateEvents() throws InterruptedException {
        CountDownLatch proposed = awaitStatuses(1);
        presenter.subscribeToUpdateEvents();

        eventBus.publish(new UpdateAvailableEvent(update("2.0.0"), CURRENT_VERSION));

        assertThat(proposed.await(STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(statuses).containsExactly("Update available: 2.0.0");
    }

    @Test
    @DisplayName("Subscribing without a service is a no-op")
    void testSubscribeToUpdateEventsWithoutService() {
        assertThatCode(() -> new UpdatePresenter(null).subscribeToUpdateEvents())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("An installation reports its progress and unsubscribes when done")
    void testInstallFollowsTheProgress() {
        UpdateInfo update = update("2.0.0");
        CompletableFuture<Void> pending = new CompletableFuture<>();
        when(service.downloadAndInstall(update)).thenReturn(pending);

        presenter.install(update);
        eventBus.publish(new UpdateProgressEvent(42, "Downloading"));
        pending.complete(null);

        verify(service).downloadAndInstall(update);
        assertThat(statuses).containsExactly("Installing 2.0.0...");
    }

    @Test
    @DisplayName("A failed installation is reported")
    void testInstallReportsAFailure() {
        UpdateInfo update = update("2.0.0");
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("checksum mismatch"));
        when(service.downloadAndInstall(update)).thenReturn(failed);

        presenter.install(update);

        assertThat(statuses).containsExactly("Installing 2.0.0...",
            "Update to 2.0.0 failed");
    }

    @Test
    @DisplayName("A null update or a missing service never starts an installation")
    void testInstallGuardsItsArguments() {
        assertThatCode(() -> presenter.install(null)).doesNotThrowAnyException();
        assertThatCode(() -> new UpdatePresenter(null).install(update("2.0.0")))
            .doesNotThrowAnyException();
        assertThat(statuses).isEmpty();
    }

    @Test
    @DisplayName("A deferred installation is not registered without a display")
    void testScheduleIsSkippedWhenHeadless() {
        presenter.schedule(update("2.0.0"));

        verify(service, never()).scheduleInstall(any(), any(), anyBoolean());
        assertThat(statuses).isEmpty();
    }

    @Test
    @DisplayName("Proposing nothing is ignored")
    void testProposeIgnoresNull() {
        assertThatCode(() -> presenter.propose(null)).doesNotThrowAnyException();
        assertThatCode(() -> new UpdatePresenter(null).propose(update("2.0.0")))
            .doesNotThrowAnyException();
        assertThat(statuses).isEmpty();
    }

    @Test
    @DisplayName("A skipped version is recorded by the service")
    void testSkipVersionIsRecorded() {
        // skip() is public so a host can offer the choice outside the dialog too.
        UpdateInfo update = update("2.0.0");

        presenter.skip(update);

        verify(service).skipVersion("2.0.0");
        assertThat(statuses).containsExactly("Version 2.0.0 skipped");
    }

    @Test
    @DisplayName("Skipping nothing or without a service is a no-op")
    void testSkipGuardsItsArguments() {
        assertThatCode(() -> presenter.skip(null)).doesNotThrowAnyException();
        assertThatCode(() -> new UpdatePresenter(null).skip(update("2.0.0")))
            .doesNotThrowAnyException();
        assertThat(statuses).isEmpty();
    }

    @Test
    @DisplayName("The scheduling proposals stay in sync with their delays")
    void testScheduleLabels() {
        assertThat(UpdatePresenter.scheduleLabels())
            .containsExactly("In 2 hours", "Tonight (8 hours)", "Tomorrow (24 hours)", "In 3 days");
    }

    @Test
    @DisplayName("The driven service is exposed to the hosts")
    void testGetService() {
        assertThat(presenter.getService()).isSameAs(service);
        assertThat(new UpdatePresenter(null).getService()).isNull();
    }

    @Test
    @DisplayName("A null parent supplier or status listener is tolerated")
    void testNullCollaborators() {
        UpdatePresenter tolerant = new UpdatePresenter(service, null);
        tolerant.setStatusListener(null);

        assertThatCode(tolerant::checkForUpdates).doesNotThrowAnyException();
        assertThatCode(tolerant::showSettings).doesNotThrowAnyException();
        assertThatCode(tolerant::showChangelog).doesNotThrowAnyException();
        assertThatCode(() -> tolerant.schedule(update("2.0.0"))).doesNotThrowAnyException();
    }

    /**
     * Latch released once the given number of status messages were reported. The
     * presenter hands the async outcomes over to the event dispatch thread, so the
     * assertions have to wait for it.
     */
    private CountDownLatch awaitStatuses(int count) {
        CountDownLatch latch = new CountDownLatch(1);
        presenter.setStatusListener(message -> {
            statuses.add(message);
            if (statuses.size() >= count) {
                latch.countDown();
            }
        });
        return latch;
    }

    private static UpdateInfo update(String version) {
        return new UpdateInfo(version, Instant.now(), "https://example.com/ide.jar",
            1024L, "sha256", null, false, null, "21", false);
    }
}
