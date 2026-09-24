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
package com.protonmail.landrevillejf.swingide.update.ui;

import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;

/**
 * Small non-modal dialog showing that an update check is in progress.
 * <p>
 * The check queries a remote server whose latency is unknown, so the bar runs
 * in indeterminate mode: there is no meaningful percentage to paint until the
 * response arrives. The dialog is owned by the caller and disposed as soon as
 * the check completes, either with a version, an "up to date" outcome or an
 * error.
 * </p>
 * <p>
 * It is deliberately non-modal so the caller can keep working while the check
 * runs, and so the CI dialog watchdog never has to interfere with it.
 * </p>
 *
 * @author landrevillejf
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public class CheckUpdateProgressDialog extends JDialog {

    /** Default title, used when the caller does not provide one. */
    private static final String DEFAULT_TITLE = "Checking for Updates";

    /** Default message shown under the title while the check runs. */
    private static final String DEFAULT_MESSAGE = "Contacting the update server...";

    /**
     * Shortest time the dialog stays visible, in milliseconds.
     * <p>
     * The check may resolve in a few tens of milliseconds against a warm cache
     * or a nearby server, which is faster than the eye can register. Enforcing
     * a floor keeps the indeterminate bar perceivable so the user gets real
     * feedback that the IDE did something.
     * </p>
     */
    private static final long MIN_VISIBLE_MILLIS = 700L;

    private final JLabel messageLabel;
    private final JProgressBar progressBar;
    private final long shownAtMillis = System.currentTimeMillis();

    /**
     * Creates the check progress dialog.
     *
     * @param parent the owning frame, may be {@code null}
     */
    public CheckUpdateProgressDialog(Frame parent) {
        super(parent, DEFAULT_TITLE, false);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        messageLabel = new JLabel(DEFAULT_MESSAGE);
        messageLabel.setFont(new Font(messageLabel.getFont().getName(), Font.BOLD, 13));
        mainPanel.add(messageLabel, BorderLayout.NORTH);

        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setStringPainted(false);
        progressBar.setPreferredSize(new Dimension(360, 20));
        mainPanel.add(progressBar, BorderLayout.CENTER);

        add(mainPanel);

        setSize(420, 130);
        setResizable(false);
        // The dialog is closed by the presenter when the check completes; a
        // user-initiated close must not leave it half-visible.
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLocationRelativeTo(getParent());
    }

    /**
     * Replaces the message displayed above the progress bar.
     *
     * @param message the new message, {@code null} restores the default
     */
    public void setMessage(String message) {
        String text = message != null ? message : DEFAULT_MESSAGE;
        SwingUtilities.invokeLater(() -> messageLabel.setText(text));
    }

    /**
     * The indeterminate bar shown while the check runs. Exposed for tests.
     */
    public JProgressBar getProgressBar() {
        return progressBar;
    }

    /**
     * Shows the dialog on the event dispatch thread.
     *
     * @param parent the owning frame, may be {@code null}
     * @return the visible dialog
     */
    public static CheckUpdateProgressDialog show(Frame parent) {
        CheckUpdateProgressDialog dialog = new CheckUpdateProgressDialog(parent);
        Runnable display = () -> dialog.setVisible(true);
        if (SwingUtilities.isEventDispatchThread()) {
            display.run();
        } else {
            SwingUtilities.invokeLater(display);
        }
        log.debug("Check for updates progress dialog displayed");
        return dialog;
    }

    /**
     * Disposes the dialog on the event dispatch thread. Safe to call twice and
     * from any thread.
     * <p>
     * If the dialog has been visible for less than {@value #MIN_VISIBLE_MILLIS}
     * ms the dispose is deferred through a non-repeating {@link javax.swing.Timer}
     * so the progress bar stays on screen long enough to be seen. Callers that
     * need to chain work after the dialog is gone should use
     * {@link #closeAfterMinimumVisibility(Runnable)} instead.
     * </p>
     */
    public void close() {
        closeAfterMinimumVisibility(null);
    }

    /**
     * Disposes the dialog once the minimum visibility elapsed and then runs the
     * given follow-up on the event dispatch thread.
     * <p>
     * Used by the presenter so the outcome dialog (JOptionPane, proposal, ...)
     * opens only after the progress bar is gone, keeping the two windows from
     * stacking on top of each other.
     * </p>
     *
     * @param afterClose EDT task run once the dialog is disposed, may be {@code null}
     */
    public void closeAfterMinimumVisibility(Runnable afterClose) {
        long elapsed = System.currentTimeMillis() - shownAtMillis;
        long remaining = MIN_VISIBLE_MILLIS - elapsed;

        Runnable dispose = () -> {
            dispose();
            if (afterClose != null) {
                afterClose.run();
            }
        };

        if (remaining <= 0L) {
            if (SwingUtilities.isEventDispatchThread()) {
                dispose.run();
            } else {
                SwingUtilities.invokeLater(dispose);
            }
            return;
        }

        // The timer is one-shot and runs its action on the EDT, so the follow-up
        // is sequenced after the dispose without any extra hop.
        Runnable schedule = () -> {
            javax.swing.Timer timer = new javax.swing.Timer((int) remaining, event -> dispose.run());
            timer.setRepeats(false);
            timer.start();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            schedule.run();
        } else {
            SwingUtilities.invokeLater(schedule);
        }
    }

    /**
     * Timestamp captured when the dialog was built, used to enforce
     * {@value #MIN_VISIBLE_MILLIS}. Exposed for tests.
     */
    public long getShownAtMillis() {
        return shownAtMillis;
    }
}
