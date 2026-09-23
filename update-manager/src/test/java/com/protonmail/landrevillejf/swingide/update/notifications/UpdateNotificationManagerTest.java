package com.protonmail.landrevillejf.swingide.update.notifications;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The notification manager must degrade gracefully: every entry point is called
 * from the update pipeline, including in headless environments such as CI.
 */
class UpdateNotificationManagerTest {

    private UpdateNotificationManager notificationManager;

    @BeforeEach
    void setUp() {
        notificationManager = new UpdateNotificationManager();
    }

    @AfterEach
    void tearDown() {
        notificationManager.removeTray();
    }

    @Test
    void testNotificationManagerInitialization() {
        assertThat(notificationManager).isNotNull();
        assertThat(notificationManager.isTrayAvailable()).isFalse();
    }

    @Test
    void testInitializeTrayIsSafe() {
        assertThatCode(() -> notificationManager.initializeTray()).doesNotThrowAnyException();
    }

    @Test
    void testInitializeTrayIsIdempotent() {
        assertThatCode(() -> {
            notificationManager.initializeTray();
            notificationManager.initializeTray();
        }).doesNotThrowAnyException();
    }

    @Test
    void testInitializeTrayWithCustomImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);

        assertThatCode(() -> notificationManager.initializeTray(image)).doesNotThrowAnyException();
    }

    @Test
    void testTrayStaysUnavailableInHeadlessEnvironments() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless(), "requires a headless environment");

        notificationManager.initializeTray();

        assertThat(notificationManager.isTrayAvailable()).isFalse();
    }

    @Test
    void testNotifyUpdateAvailable() {
        assertThatCode(() -> notificationManager.notifyUpdateAvailable("1.2.3", "150MB"))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotifyDownloadProgress() {
        assertThatCode(() -> notificationManager.notifyDownloadProgress("1.2.3", 50))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotifyDownloadComplete() {
        assertThatCode(() -> notificationManager.notifyDownloadComplete("1.2.3"))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotifyInstallationStarted() {
        assertThatCode(() -> notificationManager.notifyInstallationStarted("1.2.3"))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotifyInstallationComplete() {
        assertThatCode(() -> {
            notificationManager.notifyInstallationComplete("1.2.3", true);
            notificationManager.notifyInstallationComplete("1.2.3", false);
        }).doesNotThrowAnyException();
    }

    @Test
    void testNotifyVerificationFailed() {
        assertThatCode(() -> notificationManager.notifyVerificationFailed("Invalid signature"))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotifyInfo() {
        assertThatCode(() -> notificationManager.notifyInfo("Update scheduled", "Tonight at 02:00"))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotifyError() {
        assertThatCode(() -> notificationManager.notifyError("Error", "Something went wrong"))
            .doesNotThrowAnyException();
    }

    @Test
    void testRegisterCustomNotification() {
        UpdateNotificationManager.NotificationConfig config =
            new UpdateNotificationManager.NotificationConfig(TrayIcon.MessageType.INFO, 5000);

        assertThatCode(() -> notificationManager.registerNotification("custom", config))
            .doesNotThrowAnyException();
        assertThatCode(() -> notificationManager.showCustomNotification("custom", "Title", "Message"))
            .doesNotThrowAnyException();
    }

    @Test
    void testShowUnknownCustomNotificationIsIgnored() {
        assertThatCode(() -> notificationManager.showCustomNotification("unknown", "Title", "Message"))
            .doesNotThrowAnyException();
    }

    @Test
    void testNotificationConfigAccessors() {
        UpdateNotificationManager.NotificationConfig config =
            new UpdateNotificationManager.NotificationConfig(TrayIcon.MessageType.WARNING, 3000);

        assertThat(config.getMessageType()).isEqualTo(TrayIcon.MessageType.WARNING);
        assertThat(config.getTimeout()).isEqualTo(3000);
    }

    @Test
    void testTrayActionListenerCanBeRegistered() {
        assertThatCode(() -> notificationManager.setTrayActionListener(
            new UpdateNotificationManager.TrayActionListener() { })).doesNotThrowAnyException();
    }

    @Test
    void testRemoveTray() {
        notificationManager.initializeTray();

        assertThatCode(() -> notificationManager.removeTray()).doesNotThrowAnyException();
        assertThat(notificationManager.isTrayAvailable()).isFalse();
    }

    @Test
    void testRemoveTrayIsSafeWhenNeverInitialized() {
        assertThatCode(() -> notificationManager.removeTray()).doesNotThrowAnyException();
    }

    /**
     * A shutdown hook removes the tray while the EDT may itself be blocked inside
     * {@code System.exit}, waiting for that very hook. Waiting for the EDT there
     * deadlocks and the JVM never terminates, so the removal has to be posted and
     * never awaited.
     */
    @Test
    void testTrayRemovalIsPostedToTheEdtAndNeverAwaited() throws Exception {
        SystemTray tray = mock(SystemTray.class);
        TrayIcon icon = mock(TrayIcon.class);
        CountDownLatch edtBusy = new CountDownLatch(1);
        CountDownLatch edtReleased = new CountDownLatch(1);

        try {
            SwingUtilities.invokeLater(() -> {
                edtBusy.countDown();
                try {
                    edtReleased.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(edtBusy.await(5, TimeUnit.SECONDS)).isTrue();

            // The EDT is stuck, the calling thread must not be: the removal is posted.
            assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> notificationManager.detachWithoutBlocking(tray, icon));
            verify(tray, never()).remove(icon);

            edtReleased.countDown();
            verify(tray, timeout(5_000)).remove(icon);
        } finally {
            edtReleased.countDown();
        }
    }

    /**
     * On the EDT the removal stays synchronous, so a host shutting the platform
     * down before {@code System.exit} still detaches its icon.
     */
    @Test
    void testTrayRemovalIsSynchronousOnTheEdt() throws Exception {
        SystemTray tray = mock(SystemTray.class);
        TrayIcon icon = mock(TrayIcon.class);
        CountDownLatch done = new CountDownLatch(1);

        SwingUtilities.invokeLater(() -> {
            try {
                notificationManager.detachWithoutBlocking(tray, icon);
            } finally {
                done.countDown();
            }
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        verify(tray).remove(icon);
    }

    @Test
    void testLimitLengthKeepsShortText() {
        assertThat(notificationManager.limitLength("short", 20)).isEqualTo("short");
    }

    @Test
    void testLimitLengthTruncatesLongText() {
        String limited = notificationManager.limitLength("a".repeat(60), 20);

        assertThat(limited).hasSize(20).endsWith("...");
    }

    @Test
    void testLimitLengthHandlesNull() {
        assertThat(notificationManager.limitLength(null, 20)).isEmpty();
    }
}
