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
package org.jdesktop.lg3d.apps.bugreporter;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.jdesktop.lg3d.displayserver.crash.CrashReport;
import org.jdesktop.lg3d.displayserver.crash.CrashReporter;
import org.jdesktop.lg3d.displayserver.crash.IssuePrefill;

/**
 * The Bug Reporter's Swing face: a form for writing a bug report by hand -
 * subject, repro steps, expected/actual, severity, named evidence - with a
 * live preview of the exact issue text and the same prefilled GitHub link the
 * automatic crash capture produces.
 *
 * <p>The panel is plain Swing and touches no Java&nbsp;3D, so the one class
 * serves both desktops: in 3D the {@link BugReporter} wrapper hosts it on a
 * {@code SwingNode} inside a {@code Frame3D} via {@code TitledSwingWindow};
 * in the 2D/Swing desktop {@code Desktop2DAppRegistry.PANEL_APPS} opens the
 * very same panel as an MDI internal frame. It uses only layout managers and
 * stock Metal widgets - no Synth combo boxes, no modal dialogs, no file
 * chooser - so it paints correctly into the SwingNode offscreen buffer and
 * nothing can escape the capture. Evidence files are <em>typed or pasted</em>
 * into a path field for that reason; the report only ever names them, and the
 * person drags the real file onto the issue page.</p>
 *
 * <p>Everything with a consequence lives in {@link BugReportDraft}; this
 * class mirrors widgets into it and paints what it says back. Reading the
 * report archive and filing a report do disk and browser work, so they run on
 * a named daemon executor and are marshalled back to the EDT with
 * {@code invokeLater}, the way the Weather panel fetches.</p>
 *
 * <p>Submitting never posts anything: the engine spools the report and hands
 * a prefilled {@code github.com/.../issues/new} link to the browser, where a
 * human reads the half-written issue and presses the real button.</p>
 */
public class BugReporterPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 760;
    public static final int HEIGHT_PX = 560;

    /** The one model; every widget mirrors into it, every paint reads from it. */
    private final BugReportDraft draft = new BugReportDraft();

    // The widgets the headless test drives are package-private, like the
    // engine's package-private budgets: the test pokes the form the way a
    // person does, and everything else stays this class's business.
    final JTextField titleField = new JTextField();
    final JTextArea stepsArea = new JTextArea(4, 0);
    final JTextField expectedField = new JTextField();
    final JTextField actualField = new JTextField();
    final JComboBox<CrashReport.Severity> severityBox =
            new JComboBox<>(new DefaultComboBoxModel<>(CrashReport.Severity.values()));
    final JTextField evidencePathField = new JTextField();
    final JButton addEvidenceButton = new JButton("Add");
    final JButton removeEvidenceButton = new JButton("Remove");
    final JButton attachCrashButton = new JButton("Attach to report");
    final JButton detachCrashButton = new JButton("Detach");
    final DefaultListModel<String> evidenceModel = new DefaultListModel<>();
    final JList<String> evidenceList = new JList<>(evidenceModel);
    final DefaultListModel<CrashReport> archiveModel = new DefaultListModel<>();
    final JList<CrashReport> archiveList = new JList<>(archiveModel);
    private final JLabel linkLabel = new JLabel("filing by hand - not linked to a captured crash");

    final JTextArea previewArea = new JTextArea();
    final JLabel statusLabel = new JLabel(" ");
    private final JLabel adviceLabel = new JLabel(" ");

    final JButton submitButton = new JButton("File issue on GitHub");
    private final JButton copyTextField = new JButton("Copy text");
    private final JButton copyLinkButton = new JButton("Copy link");
    private final JButton clearButton = new JButton("Clear");
    private final JButton refreshArchiveButton = new JButton("Reload");

    /**
     * Reads the spool and files reports off the EDT. Disk IO and the browser
     * handoff never happen on the paint thread; the results come back through
     * invokeLater. Single-threaded on purpose: two Submit clicks must spool
     * in the order they were clicked, or the archive tally misreports.
     */
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "BugReporterPanel-worker");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean disposed;
    private Runnable onClose;

    public BugReporterPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        wireForm();
        // The archive read is disk work, and a fresh desktop has an empty
        // spool; kicking it off here means the list is populated before the
        // first person scrolls to it, without blocking the window's paint.
        worker.execute(this::loadArchive);
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /**
     * Names the tracker and the delivery model up front. A reporter who
     * believes Submit POSTes the report will be surprised by a browser; the
     * header is where the surprise is pre-empted, in the panel that stays
     * visible the whole time, not a dialog nobody reads.
     */
    private JComponent buildHeader() {
        IssuePrefill.Target target = CrashReporter.shared().target();
        JPanel header = new JPanel(new BorderLayout(0, 2));
        header.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));

        JLabel title = new JLabel("Bug Reporter");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        header.add(title, BorderLayout.NORTH);

        StringBuilder where = new StringBuilder("Files against ");
        where.append(target.repo());
        if (!target.labels().isEmpty()) {
            where.append("  \u00b7  labels: ").append(String.join(", ", target.labels()));
        }
        if (!target.assignee().isBlank()) {
            where.append("  \u00b7  assignee: ").append(target.assignee());
        }
        where.append("  \u00b7  nothing is sent from this desktop: the browser opens a draft you submit");
        JLabel whereLabel = new JLabel(where.toString());
        header.add(whereLabel, BorderLayout.SOUTH);
        return header;
    }

    private JComponent buildBody() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 6));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        int row = 0;

        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(fieldLabel("Subject"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1.0;
        form.add(titleField, c);
        row++;

        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        form.add(fieldLabel("Steps"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1.0;
        c.weighty = 0.3;
        c.fill = GridBagConstraints.BOTH;
        form.add(scroller(stepsArea, 90), c);
        row++;

        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(fieldLabel("Expected"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1.0;
        form.add(expectedField, c);
        row++;

        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(fieldLabel("Actual"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1.0;
        form.add(actualField, c);
        row++;

        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(fieldLabel("Severity"), c);
        c.gridx = 1;
        c.weightx = 1.0;
        form.add(severityBox, c);
        c.gridx = 2;
        c.weightx = 0;
        form.add(linkLabel, c);
        row++;

        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        form.add(fieldLabel("Evidence"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1.0;
        JPanel evidenceRow = new JPanel(new BorderLayout(4, 0));
        evidenceRow.add(evidencePathField, BorderLayout.CENTER);
        evidenceRow.add(addEvidenceButton, BorderLayout.EAST);
        JPanel evidencePanel = new JPanel(new BorderLayout(0, 3));
        evidencePanel.add(evidenceRow, BorderLayout.NORTH);
        evidenceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        evidencePanel.add(scroller(evidenceList, 54), BorderLayout.SOUTH);
        form.add(evidencePanel, c);
        row++;

        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 0.7;
        form.add(buildArchiveColumn(refreshArchiveButton), c);

        removeEvidenceButton.addActionListener(e -> removeSelectedEvidence());
        addEvidenceButton.addActionListener(e -> addTypedEvidence());

        JPanel preview = buildPreview();

        JPanel split = new JPanel(new BorderLayout(6, 0));
        split.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        split.add(form, BorderLayout.CENTER);
        split.add(preview, BorderLayout.EAST);
        return split;
    }

    private JPanel buildArchiveColumn(JButton reload) {
        JPanel archive = new JPanel(new BorderLayout(0, 3));
        JLabel caption = fieldLabel("Captured crashes (optional)");
        archive.add(caption, BorderLayout.NORTH);
        archiveList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        archiveList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list,
                    Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                CrashReport report = (CrashReport) value;
                setText(report.title()
                        + (report.occurrences() > 1 ? "  \u00d7" + report.occurrences() : ""));
                return this;
            }
        });
        archive.add(scroller(archiveList, 80), BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(attachCrashButton);
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(detachCrashButton);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(reload);
        archive.add(buttons, BorderLayout.SOUTH);
        attachCrashButton.addActionListener(e -> attachSelectedCrash());
        detachCrashButton.addActionListener(e -> detachCrash());
        return archive;
    }

    private JPanel buildPreview() {
        JPanel panel = new JPanel(new BorderLayout(0, 3));
        panel.setPreferredSize(new Dimension(300, 0));
        JLabel caption = fieldLabel("Issue preview (exact text)");
        panel.add(caption, BorderLayout.NORTH);
        previewArea.setEditable(false);
        previewArea.setLineWrap(true);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        panel.add(scroller(previewArea, 0), BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(0, 2));
        footer.setBorder(BorderFactory.createEmptyBorder(2, 12, 8, 12));

        JPanel buttons = new JPanel(new BorderLayout(6, 0));
        JPanel right = new JPanel();
        right.add(copyTextField);
        right.add(copyLinkButton);
        right.add(clearButton);
        buttons.add(submitButton, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);
        footer.add(buttons, BorderLayout.NORTH);

        JPanel lines = new JPanel(new BorderLayout());
        lines.add(statusLabel, BorderLayout.NORTH);
        lines.add(adviceLabel, BorderLayout.SOUTH);
        footer.add(lines, BorderLayout.SOUTH);

        submitButton.addActionListener(e -> submit());
        copyTextField.addActionListener(e -> copyText());
        copyLinkButton.addActionListener(e -> copyLink());
        clearButton.addActionListener(e -> clearForm());
        refreshArchiveButton.setText("Reload");
        return footer;
    }

    private JLabel fieldLabel(String text) {
        return new JLabel(text);
    }

    private JScrollPane scroller(JComponent view, int minHeight) {
        JScrollPane scroll = new JScrollPane(view);
        if (minHeight > 0) {
            scroll.setPreferredSize(new Dimension(10, minHeight));
        }
        return scroll;
    }

    // ------------------------------------------------------------------
    // Draft wiring
    // ------------------------------------------------------------------

    /**
     * Every widget mirrors into the draft on change, and the draft's state
     * repaints the preview and advice. One direction at a time: widgets to
     * model, model to paint. Nothing reads a widget when composing a report,
     * which is what lets {@link BugReportDraft} be tested with no Swing at
     * all.
     */
    private void wireForm() {
        DocumentListener mirror = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                syncFromWidgets();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                syncFromWidgets();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                syncFromWidgets();
            }
        };
        titleField.getDocument().addDocumentListener(mirror);
        stepsArea.getDocument().addDocumentListener(mirror);
        expectedField.getDocument().addDocumentListener(mirror);
        actualField.getDocument().addDocumentListener(mirror);
        severityBox.addActionListener(e -> syncFromWidgets());
        // The combo would otherwise open on the enum's first constant; the
        // draft's default is the stated starting point, so the widget copies
        // it once before the first mirror pass.
        severityBox.setSelectedItem(draft.severity());
        syncFromWidgets();
    }

    /** Pulls the widget state into the draft, then repaints from the draft. */
    private void syncFromWidgets() {
        draft.title(titleField.getText())
             .steps(stepsArea.getText())
             .expected(expectedField.getText())
             .actual(actualField.getText())
             .severity((CrashReport.Severity) severityBox.getSelectedItem());
        paintFromDraft();
    }

    private void paintFromDraft() {
        if (draft.isSubmittable()) {
            CrashReport report = draft.toReport();
            previewArea.setText(report.title() + "\n\n" + report.body());
            previewArea.setCaretPosition(0);
        } else {
            previewArea.setText("");
        }
        List<String> gaps = draft.gaps();
        adviceLabel.setText(gaps.isEmpty() ? " "
                : "Notes: " + String.join("; ", gaps));
        submitButton.setEnabled(draft.isSubmittable());
        copyTextField.setEnabled(draft.isSubmittable());
        copyLinkButton.setEnabled(draft.isSubmittable());
    }

    private void addTypedEvidence() {
        String path = evidencePathField.getText();
        if (path == null || path.isBlank()) {
            setStatus("Type or paste a file path first - evidence is named, not uploaded.");
            return;
        }
        draft.addEvidence(path);
        evidenceModel.addElement(path.trim());
        evidencePathField.setText("");
        setStatus("Named " + path.trim() + " as evidence.");
        paintFromDraft();
    }

    private void removeSelectedEvidence() {
        String selected = evidenceList.getSelectedValue();
        if (selected == null) {
            setStatus("Select an evidence entry to remove it.");
            return;
        }
        draft.removeEvidence(selected);
        evidenceModel.removeElement(selected);
        setStatus("Removed " + selected + " from the evidence list.");
        paintFromDraft();
    }

    private void attachSelectedCrash() {
        CrashReport selected = archiveList.getSelectedValue();
        if (selected == null) {
            setStatus("Select a captured crash first.");
            return;
        }
        draft.linkedCrash(selected);
        linkLabel.setText("linked to report " + selected.id() + " - keeps its signature");
        setStatus("Filing against the captured crash; its trace and environment are kept.");
        paintFromDraft();
    }

    private void detachCrash() {
        draft.linkedCrash(null);
        archiveList.clearSelection();
        linkLabel.setText("filing by hand - not linked to a captured crash");
        setStatus("Detached the crash; this draft signs on its own words.");
        paintFromDraft();
    }

    // ------------------------------------------------------------------
    // Actions (off the EDT where they touch disk or the browser)
    // ------------------------------------------------------------------

    /**
     * Spools the draft and opens the prefilled issue in the browser, through
     * the shared reporter's explicit path - which bypasses every noise rule,
     * because suppressing a report someone clicked is the one outcome that
     * would make this tool untrustworthy.
     */
    private void submit() {
        if (!draft.isSubmittable()) {
            setStatus(draft.blockingReason());
            return;
        }
        submitButton.setEnabled(false);
        setStatus("Filing... the spool write and the browser handoff run off the paint thread.");
        CrashReport report = draft.toReport();
        worker.execute(() -> {
            CrashReporter.Outcome outcome;
            try {
                outcome = CrashReporter.shared().report(report);
            } catch (RuntimeException e) {
                // The engine promises never to throw; this is for the promise
                // the panel cannot vouch for (a broken shared instance).
                outcome = new CrashReporter.Outcome(report, null, null, 0, false, false,
                        "unexpected failure: " + e);
            }
            final CrashReporter.Outcome result = outcome;
            if (!disposed) {
                SwingUtilities.invokeLater(() -> {
                    StringBuilder status = new StringBuilder(result.note());
                    if (result.spoolFile() != null) {
                        status.append("  \u00b7  spooled to ").append(result.spoolFile());
                    }
                    if (result.prefill() != null && result.prefill().isTruncated()) {
                        status.append("  \u00b7  the body was trimmed to fit the URL; ")
                              .append("drag the spool file onto the issue for the full text");
                    }
                    setStatus(status.toString());
                    submitButton.setEnabled(draft.isSubmittable() && !disposed);
                    loadArchive();
                });
            }
        });
    }

    private void copyText() {
        if (!draft.isSubmittable()) {
            setStatus(draft.blockingReason());
            return;
        }
        copyToClipboard(draft.toReport().title() + "\n\n" + draft.toReport().body());
        setStatus("The issue text is on the clipboard.");
    }

    private void copyLink() {
        if (!draft.isSubmittable()) {
            setStatus(draft.blockingReason());
            return;
        }
        // prefill() is pure string work - no disk, no browser - so the EDT is
        // safe here and the person gets the link without a thread hop.
        IssuePrefill.PrefilledUrl url = CrashReporter.shared().prefill(draft.toReport());
        copyToClipboard(url.url());
        setStatus(url.isTruncated()
                ? "The link is on the clipboard, body trimmed to fit; the full text is copied separately."
                : "The prefilled GitHub link is on the clipboard.");
    }

    private void clearForm() {
        titleField.setText("");
        stepsArea.setText("");
        expectedField.setText("");
        actualField.setText("");
        severityBox.setSelectedItem(CrashReport.Severity.MINOR);
        evidenceModel.clear();
        draft.clearEvidence();
        detachCrash();
        setStatus("Form cleared.");
    }

    /** Reads the spool off the EDT; a broken archive shows as an empty list. */
    private void loadArchive() {
        List<CrashReport> reports;
        try {
            reports = CrashReporter.shared().store().list();
        } catch (RuntimeException e) {
            reports = List.of();
        }
        final List<CrashReport> found = reports;
        if (disposed) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            CrashReport keep = archiveList.getSelectedValue();
            String keepSignature = (keep != null) ? keep.signature() : null;
            archiveModel.clear();
            for (CrashReport report : found) {
                archiveModel.addElement(report);
            }
            if (keepSignature != null) {
                for (int i = 0; i < archiveModel.size(); i++) {
                    if (archiveModel.get(i).signature().equals(keepSignature)) {
                        archiveList.setSelectedIndex(i);
                        break;
                    }
                }
            }
            setStatus(archiveModel.isEmpty()
                    ? "No captured crashes in the archive."
                    : archiveModel.size() + " captured report(s) in the archive.");
        });
    }

    private void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                   .setContents(new StringSelection(text), null);
        } catch (RuntimeException e) {
            // Headless or a clipboard in use: say so in the status line
            // rather than throw from a button press.
            setStatus("The clipboard is unavailable (" + e.getMessage() + ").");
        }
    }

    /** @return the draft the widgets are mirrored into (package-private, for the test) */
    BugReportDraft draftForTest() {
        return draft;
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Optional close hook the 2D MDI host wires its window close button to. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /**
     * Runs the registered close hook and stops the background executor. The
     * window's close button in both desktops routes here, so no task is left
     * writing to a spool nobody is looking at.
     */
    public void close() {
        disposed = true;
        worker.shutdownNow();
        if (onClose != null) {
            onClose.run();
        }
    }
}
