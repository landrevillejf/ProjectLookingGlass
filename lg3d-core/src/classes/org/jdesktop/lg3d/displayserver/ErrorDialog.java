/**
 * Project Looking Glass
 *
 * $RCSfile: ErrorDialog.java,v $
 *
 * Copyright (c) 2005, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - modernization:
 * a live issue-tracker URL replaces the long-dead personal crash reporter, the
 * message text is assembled by a testable pure method, a "Copy details" button
 * was added, every Swing mutation is marshalled onto the EDT, and a
 * "Report this bug" button opens the prefilled issue the crash pipeline built -
 * the desktop files nothing itself, it hands the reporter a link with the
 * evidence already typed into the form. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 *
 * $Revision: 1.10 $
 * $Date: 2006-08-14 23:13:19 $
 * $State: Exp $
 */

package org.jdesktop.lg3d.displayserver;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.jdesktop.lg3d.utils.system.Opener;

/**
 * A small dialog shown by {@link LogHandler} when a severe error occurs.
 *
 * <p>It is non-modal and implements {@link Runnable} so it can be shown without
 * blocking the thread that logged the error; every Swing mutation happens on the
 * EDT. The message is assembled by {@link #buildMessage}, a pure function kept
 * apart from the widgetry so it can be unit-tested headlessly.</p>
 *
 * <p>The dialog is also the desktop's manual entry point to bug reporting: the
 * "Report this bug" button opens the prefilled issue the crash pipeline assembled
 * for this very error. Prefilling happens in the browser, not here, because this
 * desktop holds no credential - see
 * {@link org.jdesktop.lg3d.displayserver.crash.IssuePrefill}.</p>
 *
 * @author pinaraf
 */
public class ErrorDialog extends JDialog implements Runnable {

    /** Where a failure to open a browser is recorded, beside the crash itself. */
    private static final Logger logger = Logger.getLogger("lg.errorDialog");

    /**
     * Where a genuine crash should be reported. The original 2006 build pointed
     * at a personal server ({@code pinaraf.robertlan.eu.org}) that has not
     * existed for years; this is the project's live issue tracker. A downstream
     * build can override it with the {@value #REPORT_URL_PROPERTY} system
     * property.
     */
    static final String DEFAULT_REPORT_URL =
            "https://github.com/landrevillejf/ProjectLookingGlass/issues";

    /** System property that overrides {@link #DEFAULT_REPORT_URL}. */
    static final String REPORT_URL_PROPERTY = "lg.crashreport.url";

    /** The two "components" of the dialog. */
    private final JTextArea message;
    private final JTextArea trace;

    /**
     * The prefilled issue link the crash pipeline built for this error, or
     * {@code null} while it is still being assembled. Written by the reporter
     * thread and read on the EDT, hence {@code volatile}; the dialog is shown
     * before it exists on purpose, so a slow disk cannot delay the warning.
     */
    private volatile String reportLink;

    /**
     * The exit flag (issue 435): not every severe error is fatal, so the dialog
     * only quits the desktop when the triggering record carried a
     * {@link SevereRuntimeError}.
     */
    private boolean mustExit = false;

    /**
     * When false, the generic "save your work" preamble (and the report URL) is
     * suppressed and only the specific message is shown - used for the Java 3D
     * installation errors, which carry their own wording.
     */
    static boolean showGenericText = true;

    /** The severe {@link LogRecord} that caused this dialog to appear. */
    public LogRecord logRecord = null;

    /**
     * Creates a new instance of the ErrorDialog.
     */
    public ErrorDialog() {
        super((java.awt.Frame) null, false);
        setTitle("A severe error occurred");

        Container ccf = this.getContentPane();
        ccf.setLayout(new BorderLayout(4, 2));

        JTabbedPane tabs = new JTabbedPane();
        ccf.add(BorderLayout.CENTER, tabs);

        message = newTextArea(false);
        JScrollPane messageScroll = new JScrollPane(message);
        tabs.add(messageScroll);
        tabs.setTitleAt(0, "Message");

        trace = newTextArea(true);
        JScrollPane traceScroll = new JScrollPane(trace);
        tabs.add(traceScroll);
        tabs.setTitleAt(1, "Trace");

        ccf.add(BorderLayout.SOUTH, buildButtons());

        // The window-close (X) button behaves exactly like OK, so a fatal error
        // still exits instead of leaving a half-broken desktop running.
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dismiss();
            }
        });

        setSize(520, 420);
        setLocationRelativeTo(null);
    }

    /** Builds a read-only, wrapping text area; the trace pane is monospaced. */
    private static JTextArea newTextArea(boolean monospace) {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        if (monospace) {
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        }
        return area;
    }

    /** The button row: file the bug, copy the report details, then acknowledge. */
    private JPanel buildButtons() {
        JPanel buttons = new JPanel();
        JButton reportBtn = new JButton("Report this bug");
        reportBtn.addActionListener(e -> openReportLink());
        JButton copyBtn = new JButton("Copy details");
        copyBtn.addActionListener(e -> copyDetails());
        JButton okBtn = new JButton("OK");
        okBtn.addActionListener(e -> dismiss());
        buttons.add(reportBtn);
        buttons.add(copyBtn);
        buttons.add(okBtn);
        return buttons;
    }

    /** OK / window-close: quit only when the error is fatal, else just hide. */
    private void dismiss() {
        if (mustExit) {
            SplashWindow.destroySplashscreen();
            System.exit(1);
        }
        setVisible(false);
    }

    /** Copies the message and trace to the system clipboard for a bug report. */
    private void copyDetails() {
        String details = message.getText() + "\n\n--- Trace ---\n" + trace.getText();
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(details), null);
    }

    /**
     * Publishes the prefilled issue link the pipeline built for this error.
     * May be called from any thread, before or after the dialog is on screen.
     *
     * @param link the {@code github.com/.../issues/new} URL, {@code null} to keep
     *             the plain tracker page
     */
    public void setReportLink(String link) {
        reportLink = link;
    }

    /**
     * Opens the issue form. Falls back to the bare tracker page when the report is
     * not ready or could not be built, because a button that does nothing is worse
     * than a button that opens a page the user then fills in by hand.
     */
    private void openReportLink() {
        String link = reportLink;
        String target = (link != null && !link.isBlank()) ? link : reportUrl();
        boolean opened;
        try {
            opened = Opener.openUri(target);
        } catch (Throwable t) {
            opened = false;
        }
        if (!opened) {
            // The URL is the artefact; if no browser can be launched, the only
            // useful thing left is to put it where the user can paste it.
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(target), null);
            logger.log(Level.WARNING,
                    "no browser could be opened; the issue link was copied instead: {0}",
                    target);
        }
    }

    /**
     * Sets the trace content.
     *
     * @param traceMessage the stack-trace text to display (null is treated as
     *                     empty)
     */
    public void setTrace(String traceMessage) {
        trace.setText(traceMessage == null ? "" : traceMessage);
        trace.setCaretPosition(0);
    }

    /**
     * Fills in the message box and shows the dialog. Safe to call from any
     * thread; the Swing work is marshalled onto the EDT.
     */
    @Override
    public void run() {
        if (logRecord != null && logRecord.getThrown() instanceof SevereRuntimeError) {
            mustExit = true;
        }
        final String text = buildMessage(logRecord, mustExit, showGenericText);
        Runnable show = () -> {
            try {
                message.setText(text);
                message.setCaretPosition(0);
                setLocationRelativeTo(null);
                setVisible(true);
            } catch (Throwable th) {
                // A broken/headless toolkit must still surface the message.
                System.err.println("\n\n********************************\n"
                        + text
                        + "\n********************************\n\n");
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }

    /**
     * Resolves the crash-report URL: the {@value #REPORT_URL_PROPERTY} override
     * when set to a non-blank value, otherwise {@link #DEFAULT_REPORT_URL}.
     *
     * @return the URL the user is directed to for reporting
     */
    static String reportUrl() {
        String override = System.getProperty(REPORT_URL_PROPERTY);
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return DEFAULT_REPORT_URL;
    }

    /**
     * Builds the dialog's message text. A pure function (no widgets) so it can
     * be unit-tested headlessly.
     *
     * @param record   the triggering record (may be null)
     * @param mustExit true when the error is fatal and the desktop will quit
     * @param generic  false suppresses the generic preamble and the report URL
     * @return the full message text
     */
    static String buildMessage(LogRecord record, boolean mustExit, boolean generic) {
        StringBuilder sb = new StringBuilder();
        if (generic) {
            sb.append("A severe error occurred!\n")
              .append("You should save your work as quickly as possible.\n");
            sb.append(mustExit
                    ? "Looking Glass can't continue to work; it will stop when you click OK...\n"
                    : "Looking Glass may be able to continue to work, but no guarantee...\n");
            sb.append("Please report it, with the details below, using the\n")
              .append("\"Report this bug\" button - it opens the issue form already")
              .append(" filled in - or at:\n")
              .append(reportUrl())
              .append("\n\n");
        }
        if (record != null && record.getMessage() != null) {
            sb.append(record.getMessage());
        }
        return sb.toString();
    }
}
