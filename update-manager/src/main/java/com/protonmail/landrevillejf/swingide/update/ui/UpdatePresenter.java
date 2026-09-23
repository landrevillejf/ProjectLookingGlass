package com.protonmail.landrevillejf.swingide.update.ui;

import com.protonmail.landrevillejf.swingide.core.bus.EventBus;
import com.protonmail.landrevillejf.swingide.update.UpdateInfo;
import com.protonmail.landrevillejf.swingide.update.UpdateService;
import com.protonmail.landrevillejf.swingide.update.events.UpdateAvailableEvent;
import com.protonmail.landrevillejf.swingide.update.events.UpdateProgressEvent;
import lombok.extern.slf4j.Slf4j;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Drives the user facing half of the update pipeline: the manual check, the
 * proposal dialog, the deferred installation, the download progress, the
 * settings form and the changelog.
 * <p>
 * The IDE has two entry points - the plugin host shell and the legacy main
 * window - and both need exactly the same flow, so it lives next to the dialogs
 * it drives instead of being duplicated in each host. A host only has to build a
 * presenter, register it and hand it a parent frame supplier.
 * </p>
 * <p>
 * Every entry point is safe to call from the event dispatch thread: the network
 * work is delegated to the service, which runs it on its own executor. In a
 * headless environment the dialogs are skipped and the outcome is only logged
 * and reported through the status listener.
 * </p>
 *
 * @author landrevillejf
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class UpdatePresenter {

    /** Labels of the deferred installation proposals, mapped to {@link #SCHEDULE_DELAYS_HOURS}. */
    private static final String[] SCHEDULE_LABELS = {
        "In 2 hours", "Tonight (8 hours)", "Tomorrow (24 hours)", "In 3 days"
    };

    /** Delays offered by the scheduling dialog, in hours. */
    private static final int[] SCHEDULE_DELAYS_HOURS = {2, 8, 24, 72};

    /** Index of the delay selected by default: tomorrow. */
    private static final int DEFAULT_SCHEDULE_INDEX = 2;

    private static final String CHANGELOG_THREAD_NAME = "Changelog-Loader";
    private static final String CHANGELOG_TITLE = "What's new in Project Looking Glass";
    private static final String CHECK_DIALOG_TITLE = "Check for Updates";
    private static final String SCHEDULE_DIALOG_TITLE = "Schedule the update";

    private final UpdateService service;
    private final Supplier<Frame> parentSupplier;

    /** Sink for the one line messages the hosts display in their status bar. */
    private volatile Consumer<String> statusListener;

    /**
     * Creates a presenter without owning frame: the dialogs are then unowned,
     * which is acceptable for a host showing them before its window exists.
     *
     * @param service the update service to drive
     */
    public UpdatePresenter(UpdateService service) {
        this(service, () -> null);
    }

    /**
     * @param service        the update service to drive
     * @param parentSupplier owning frame of every dialog, resolved on each use so
     *                       a window created after the presenter is still picked
     *                       up; {@code null} means unowned dialogs
     */
    public UpdatePresenter(UpdateService service, Supplier<Frame> parentSupplier) {
        this.service = service;
        this.parentSupplier = parentSupplier != null ? parentSupplier : () -> null;
    }

    /**
     * Service this presenter drives.
     */
    public UpdateService getService() {
        return service;
    }

    /**
     * Registers the sink receiving the short progress messages, typically the
     * host status bar.
     *
     * @param statusListener consumer of the status messages, {@code null} to mute
     */
    public void setStatusListener(Consumer<String> statusListener) {
        this.statusListener = statusListener;
    }

    /**
     * Wires the presenter to the service callbacks used by the tray menu, so the
     * desktop notifications open the same dialogs as the Help menu.
     */
    public void registerActionListener() {
        if (service == null) {
            log.warn("No update service, the tray menu stays inert");
            return;
        }

        service.setActionListener(new UpdateService.UpdateActionListener() {
            @Override
            public void onCheckForUpdates() {
                onEventDispatchThread(UpdatePresenter.this::checkForUpdates);
            }

            @Override
            public void onShowSettings() {
                onEventDispatchThread(UpdatePresenter.this::showSettings);
            }

            @Override
            public void onShowChangelog() {
                showChangelog();
            }
        });
    }

    /**
     * Subscribes to {@link UpdateAvailableEvent} so a version found by a periodic
     * check is proposed without any host specific wiring.
     */
    public void subscribeToUpdateEvents() {
        if (service == null) {
            log.warn("No update service, the update events are ignored");
            return;
        }

        service.getEventBus().subscribe(UpdateAvailableEvent.class,
            event -> onEventDispatchThread(() -> propose(event.getUpdateInfo()))
        );
    }

    /**
     * Runs a check in the background and proposes the result to the user.
     * <p>
     * The quiet variant of the service is used so the {@link UpdateAvailableEvent}
     * subscriber does not open a second dialog for the same version. While the
     * network round trip runs, a small non-modal dialog shows an indeterminate
     * {@link javax.swing.JProgressBar} so the user gets a visual cue that the
     * IDE is working; the dialog is disposed as soon as the check completes,
     * whatever the outcome. In a headless environment the dialog is skipped
     * and only the status listener is fed.
     * </p>
     */
    public void checkForUpdates() {
        if (service == null) {
            log.warn("The update service is not available, cannot check for updates");
            setStatus("Update service unavailable");
            return;
        }

        setStatus("Checking for updates...");

        // The dialog is built on the EDT so Swing is never touched from the
        // caller thread; the reference is published through an AtomicReference
        // so the completion callback, whatever thread it runs on, can dispose
        // the very same instance.
        final AtomicReference<CheckUpdateProgressDialog> dialogRef = new AtomicReference<>();
        if (!isHeadless()) {
            onEventDispatchThread(() -> dialogRef.set(CheckUpdateProgressDialog.show(parent())));
        }

        service.checkForUpdatesQuietly().whenComplete((update, failure) ->
            onEventDispatchThread(() -> {
                CheckUpdateProgressDialog dialog = dialogRef.getAndSet(null);
                if (dialog != null) {
                    dialog.close();
                }
                reportCheckResult(update, failure);
            })
        );
    }

    /**
     * Displays the "update available" dialog and acts on the user choice.
     * <p>
     * Must be called on the event dispatch thread: the dialog is modal.
     * </p>
     *
     * @param update the proposed update, {@code null} is ignored
     */
    public void propose(UpdateInfo update) {
        if (update == null) {
            return;
        }

        if (service == null) {
            log.warn("The update service is not available, cannot propose version {}",
                update.getVersion());
            return;
        }

        if (isHeadless()) {
            log.info("Headless environment, version {} is only reported", update.getVersion());
            setStatus("Update available: " + update.getVersion());
            return;
        }

        try {
            UpdateAvailableDialog dialog = new UpdateAvailableDialog(
                parent(), update, service.getCurrentVersion(), () -> changelogHtml(update)
            );
            dialog.setVisible(true);
            handleChoice(update, dialog.getChoice());
        } catch (RuntimeException e) {
            log.error("Error showing the update dialog", e);
        }
    }

    /**
     * Opens the update settings dialog and applies the validated values to the
     * running service.
     */
    public void showSettings() {
        if (service == null) {
            log.warn("The update service is not available, cannot display the update settings");
            return;
        }

        UpdateSettingsDialog.show(parent(), service.getConfiguration(), service)
            .ifPresentOrElse(
                configuration -> log.info("Update settings applied (channel: {})",
                    configuration.getProperty("update.channel")),
                () -> log.debug("Update settings cancelled")
            );
    }

    /**
     * Renders the changelog shipped with the IDE off the event dispatch thread and
     * displays it.
     */
    public void showChangelog() {
        if (service == null) {
            log.warn("The update service is not available, cannot display the changelog");
            return;
        }

        if (isHeadless()) {
            log.debug("Headless environment, the changelog dialog is skipped");
            return;
        }

        Thread loader = new Thread(() -> {
            final String html = service.getLocalChangelogHtml(null).orElse(null);
            SwingUtilities.invokeLater(() -> ChangelogDialog.show(parent(), CHANGELOG_TITLE, html));
        }, CHANGELOG_THREAD_NAME);
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * Downloads, verifies and installs an update while reporting the progress in a
     * non-modal dialog.
     * <p>
     * A successful installation exits the JVM, so the dialog is only closed on
     * failure.
     * </p>
     *
     * @param update the update to install, {@code null} is ignored
     */
    public void install(UpdateInfo update) {
        if (update == null) {
            return;
        }

        if (service == null) {
            log.warn("The update service is not available, cannot install version {}",
                update.getVersion());
            return;
        }

        // Without a display the installation still runs, only the reporting is lost.
        final UpdateProgressDialog progress = isHeadless()
            ? null
            : UpdateProgressDialog.showProgressDialog(parent(), false);

        final EventBus bus = service.getEventBus();
        final Consumer<UpdateProgressEvent> listener = event -> {
            if (progress != null) {
                progress.updateProgress(event.getPercent(), event.getStatus());
            }
        };
        bus.subscribe(UpdateProgressEvent.class, listener);

        if (progress != null) {
            progress.updateStatus("Downloading " + update.getVersion() + "...");
        }
        setStatus("Installing " + update.getVersion() + "...");

        service.downloadAndInstall(update).whenComplete((ignored, failure) -> {
            bus.unsubscribe(UpdateProgressEvent.class, listener);

            if (failure == null) {
                // Not reached in practice: the installer restarts the JVM.
                if (progress != null) {
                    progress.closeWithSuccess();
                }
                return;
            }

            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;

            log.error("Update to {} failed", update.getVersion(), cause);
            setStatus("Update to " + update.getVersion() + " failed");
            if (progress != null) {
                progress.closeWithError(String.valueOf(cause.getMessage()));
            }
        });
    }

    /**
     * Asks when the update must be installed and registers the deferred task.
     *
     * @param update the update to defer, {@code null} is ignored
     */
    public void schedule(UpdateInfo update) {
        if (update == null) {
            return;
        }

        if (service == null) {
            log.warn("The update service is not available, cannot schedule version {}",
                update.getVersion());
            return;
        }

        if (isHeadless()) {
            log.info("Headless environment, version {} is not scheduled", update.getVersion());
            return;
        }

        Object selected = JOptionPane.showInputDialog(
            parent(),
            "Version " + update.getVersion() + " will be downloaded and installed:",
            SCHEDULE_DIALOG_TITLE,
            JOptionPane.QUESTION_MESSAGE,
            null,
            SCHEDULE_LABELS,
            SCHEDULE_LABELS[DEFAULT_SCHEDULE_INDEX]
        );

        if (selected == null) {
            log.info("Scheduled installation cancelled for version {}", update.getVersion());
            return;
        }

        int index = List.of(SCHEDULE_LABELS).indexOf(selected);
        if (index < 0) {
            log.warn("Unknown scheduling choice '{}', ignoring it", selected);
            return;
        }

        int hours = SCHEDULE_DELAYS_HOURS[index];
        String taskId = service.scheduleInstall(
            update, LocalDateTime.now().plusHours(hours), true
        );

        log.info("Update {} scheduled in {} hour(s) (task {})", update.getVersion(), hours, taskId);
        setStatus("Update " + update.getVersion() + " scheduled in " + hours + " h");
    }

    /**
     * Records that the user does not want to be offered this version again.
     * <p>
     * Exposed separately from the proposal dialog so a host can offer the choice
     * from its own UI as well.
     * </p>
     *
     * @param update the version to forget, {@code null} is ignored
     */
    public void skip(UpdateInfo update) {
        if (update == null) {
            return;
        }

        if (service == null) {
            log.warn("The update service is not available, cannot skip version {}",
                update.getVersion());
            return;
        }

        log.info("User skipped version: {}", update.getVersion());
        service.skipVersion(update.getVersion());
        setStatus("Version " + update.getVersion() + " skipped");
    }

    /**
     * Labels offered by the scheduling dialog, in the same order as the delays.
     *
     * @return a copy of the proposal labels
     */
    public static List<String> scheduleLabels() {
        return List.of(SCHEDULE_LABELS);
    }

    private void handleChoice(UpdateInfo update, UpdateAvailableDialog.Choice choice) {
        switch (choice) {
            case UPDATE_NOW -> {
                log.info("User accepted update to version: {}", update.getVersion());
                install(update);
            }
            case SCHEDULE_LATER -> schedule(update);
            case SKIP_VERSION -> skip(update);
            case DISMISSED -> log.info("User dismissed the update proposal for {}",
                update.getVersion());
        }
    }

    private void reportCheckResult(UpdateInfo update, Throwable failure) {
        if (failure != null) {
            log.error("Update check failed: {}", failure.getMessage(), failure);
            setStatus("Update check failed");
            showMessage(JOptionPane.ERROR_MESSAGE, CHECK_DIALOG_TITLE,
                "The update check failed:\n" + failure.getMessage());
            return;
        }

        if (update == null) {
            setStatus("No updates available");
            showMessage(JOptionPane.INFORMATION_MESSAGE, CHECK_DIALOG_TITLE,
                "Project Looking Glass " + service.getCurrentVersion() + " is up to date.");
            return;
        }

        // propose() reports the version itself (status in headless mode, dialog
        // otherwise); setting the status here as well would emit it twice.
        propose(update);
    }

    /**
     * Blocking changelog source used by the proposal dialog: it may fetch the
     * remote changelog, hence the dialog runs it on its own thread.
     */
    private String changelogHtml(UpdateInfo update) {
        return service.getChangelogHtml(update).orElse(null);
    }

    private void showMessage(int messageType, String title, String message) {
        if (isHeadless()) {
            log.info("{}: {}", title, message);
            return;
        }
        JOptionPane.showMessageDialog(parent(), message, title, messageType);
    }

    private Frame parent() {
        return parentSupplier.get();
    }

    private void setStatus(String message) {
        Consumer<String> listener = statusListener;
        if (listener != null) {
            listener.accept(message);
        }
        log.debug("Update status: {}", message);
    }

    private static void onEventDispatchThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private static boolean isHeadless() {
        return GraphicsEnvironment.isHeadless();
    }
}
