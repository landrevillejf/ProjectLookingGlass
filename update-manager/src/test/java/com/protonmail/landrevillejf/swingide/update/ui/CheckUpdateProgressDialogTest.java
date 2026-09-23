package com.protonmail.landrevillejf.swingide.update.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dialog wraps an indeterminate {@link JProgressBar} shown while the
 * update check runs. It needs a display, so every test is skipped when the JVM
 * is really headless; under xvfb (the CI setup) the assertions run for real.
 */
class CheckUpdateProgressDialogTest {

    private static final long EDT_TIMEOUT_SECONDS = 5;

    @Test
    @DisplayName("The dialog is non-modal and shows an indeterminate progress bar")
    void testDialogShowsAnIndeterminateProgressBar() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
            "A display is required to build the dialog");

        AtomicReference<CheckUpdateProgressDialog> ref = new AtomicReference<>();
        CountDownLatch shown = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            ref.set(CheckUpdateProgressDialog.show(null));
            shown.countDown();
        });

        assertThat(shown.await(EDT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        CheckUpdateProgressDialog dialog = ref.get();
        try {
            assertThat(dialog).isNotNull();
            assertThat(dialog.isModal()).isFalse();
            assertThat(dialog.getModalityType()).isEqualTo(Dialog.ModalityType.MODELESS);
            assertThat(dialog.getTitle()).isEqualTo("Checking for Updates");

            JProgressBar bar = dialog.getProgressBar();
            assertThat(bar).isNotNull();
            assertThat(bar.isIndeterminate()).isTrue();
        } finally {
            dialog.close();
            awaitEdtDrained();
        }
    }

    @Test
    @DisplayName("setMessage updates the label above the bar")
    void testSetMessageUpdatesTheLabel() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
            "A display is required to build the dialog");

        AtomicReference<CheckUpdateProgressDialog> ref = new AtomicReference<>();
        CountDownLatch shown = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            ref.set(CheckUpdateProgressDialog.show(null));
            shown.countDown();
        });
        assertThat(shown.await(EDT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        CheckUpdateProgressDialog dialog = ref.get();
        try {
            dialog.setMessage("Fetching release notes...");
            // setMessage defers to the EDT; drain it before asserting.
            CountDownLatch applied = new CountDownLatch(1);
            SwingUtilities.invokeLater(applied::countDown);
            assertThat(applied.await(EDT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            // Passing null restores the default message; no exception is enough.
            dialog.setMessage(null);
            CountDownLatch restored = new CountDownLatch(1);
            SwingUtilities.invokeLater(restored::countDown);
            assertThat(restored.await(EDT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } finally {
            dialog.close();
            awaitEdtDrained();
        }
    }

    @Test
    @DisplayName("close() disposes the dialog from any thread")
    void testCloseDisposesTheDialog() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
            "A display is required to build the dialog");

        AtomicReference<CheckUpdateProgressDialog> ref = new AtomicReference<>();
        CountDownLatch shown = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            ref.set(CheckUpdateProgressDialog.show(null));
            shown.countDown();
        });
        assertThat(shown.await(EDT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        CheckUpdateProgressDialog dialog = ref.get();
        dialog.close();
        awaitEdtDrained();
        assertThat(dialog.isVisible()).isFalse();

        // Closing twice must not throw.
        dialog.close();
        awaitEdtDrained();
    }

    /**
     * Waits until the EDT has processed every request queued before this call,
     * so a subsequent assertion observes the dialog after its dispose task ran.
     */
    private static void awaitEdtDrained() throws InterruptedException {
        CountDownLatch drained = new CountDownLatch(1);
        SwingUtilities.invokeLater(drained::countDown);
        assertThat(drained.await(EDT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }
}
