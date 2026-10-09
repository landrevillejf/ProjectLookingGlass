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
package org.jdesktop.lg3d.apps.securitycenter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.apps.vpn.VpnBackend;
import org.jdesktop.lg3d.apps.vpn.VpnProfile;
import org.jdesktop.lg3d.apps.vpn.VpnStatus;
import org.jdesktop.lg3d.apps.vpn.VpnStore;
import org.jdesktop.lg3d.utils.system.NetworkCut;
import org.jdesktop.lg3d.utils.system.ProcessRunner;
import org.jdesktop.lg3d.utils.system.SecurityPosture;
import org.jdesktop.lg3d.utils.system.SecurityService;
import org.jdesktop.lg3d.utils.system.TorPrivateMode;

/**
 * The Security Center's user interface: an <em>Antivirus</em> tab that scans a
 * chosen target through an installed ClamAV, a <em>Security Overview</em> tab
 * that aggregates the host's SELinux / firewall / AppArmor / SSH / antivirus
 * posture, a <em>Privacy</em> tab ({@link PrivacyPanel}) that drives the tor
 * service and shows its read-only config and log, and a scan history dock on the
 * right. One panel serves both the 3D desktop (hosted on a
 * SwingNode inside a Frame3D by the {@code SecurityCenter} wrapper) and the
 * 2D/Swing desktop (opened as an MDI internal frame via
 * {@code Desktop2DAppRegistry.PANEL_APPS}).
 *
 * <p>The desktop ships no virus engine, so scanning is delegated honestly to
 * ClamAV as described on {@link AntivirusBackend}, and the posture probes are
 * built by {@link SecurityProbe}. No process is started, no dialog shown and no
 * probe run until the user acts (Scan / Update / Refresh, or first selecting the
 * overview tab), so the panel constructs and is asserted on headless - the
 * {@link AntivirusBackend} / {@link SecurityProbe} logic behind it is pure and
 * unit-testable.</p>
 */
public class SecurityCenterPanel extends JPanel {

    /** Preferred width in pixels. */
    public static final int WIDTH_PX = 880;
    /** Preferred height in pixels. */
    public static final int HEIGHT_PX = 600;

    private static final Color GOOD = new Color(0, 140, 0);
    private static final Color ATTENTION = new Color(200, 80, 0);
    private static final Color BAD = new Color(200, 0, 0);
    private static final Color MUTED = new Color(90, 90, 90);

    private final SecurityCenterStore store;
    private final SecurityCenterSettings settings;
    private final List<ScanRecord> history = new ArrayList<>();
    private final List<AuditEvent> audit = new ArrayList<>();

    private final DefaultListModel<ScanRecord> historyModel = new DefaultListModel<>();
    private final JList<ScanRecord> historyList = new JList<>(historyModel);
    private final DefaultListModel<Detection> findingsModel = new DefaultListModel<>();
    private final JList<Detection> findingsList = new JList<>(findingsModel);
    private final DefaultListModel<HardeningRules.Recommendation> concernsModel =
            new DefaultListModel<>();
    private final JList<HardeningRules.Recommendation> concernsList = new JList<>(concernsModel);
    private final DefaultListModel<AuditEvent> auditModel = new DefaultListModel<>();
    private final JList<AuditEvent> auditList = new JList<>(auditModel);
    private final JLabel statusLabel = new JLabel("Ready");

    // Antivirus tab
    private final JTextField targetField = new JTextField(26);
    private final JCheckBox recursiveCheck = new JCheckBox("Scan subfolders");
    private final JCheckBox quarantineCheck = new JCheckBox("Quarantine infected files");
    private final JCheckBox updateBeforeScanCheck = new JCheckBox("Update definitions first");
    private final JComboBox<String> scannerBox = new JComboBox<>();
    private final JButton scanBtn = new JButton("Scan");
    private final JButton stopBtn = new JButton("Stop");
    private final JButton updateBtn = new JButton("Update Definitions");
    private final JLabel summaryLabel = new JLabel("No scan run yet.");

    /** Live scan / definition-update progress bar (determinate or pulsing). */
    private final JProgressBar progress = new JProgressBar();

    // Overview tab
    private final JLabel ratingLabel = new JLabel("Security status: not checked yet");
    private final JLabel scoreLabel = new JLabel("Security score: not checked yet");
    private final JLabel selinuxValue = new JLabel("-");
    private final JLabel firewallValue = new JLabel("-");
    private final JLabel antivirusValue = new JLabel("-");
    private final JLabel apparmorValue = new JLabel("-");
    private final JLabel sshValue = new JLabel("-");
    private final JLabel torValue = new JLabel("-");
    private final JLabel vpnValue = new JLabel("-");
    private final JLabel lastScanLabel = new JLabel("Last scan: never");
    private final JButton refreshBtn = new JButton("Refresh");
    private final JButton remediateBtn = new JButton("Remediate");

    /** The tabbed pane (Antivirus / Security Overview / Privacy); a field so a test can inspect it. */
    private final JTabbedPane tabs = new JTabbedPane();

    private Runnable onClose;
    private volatile boolean scanning;
    private volatile boolean updating;
    private volatile boolean probing;
    private volatile boolean cancelled;
    private volatile boolean clamdFailed;
    private volatile Process scanProcess;
    private volatile String statusMessage = "Ready";

    // Progress-bar state. Mirrored in plain volatile fields (set synchronously)
    // so a headless test can read them back deterministically even though the
    // JProgressBar itself is only ever repainted on the EDT.
    private volatile int progressValue;
    private volatile int progressMax = 100;
    private volatile boolean progressIndeterminate;
    private volatile String progressText = "";
    private volatile long lastProgressPush;
    private int lastThreatCount;

    private long scanStartMillis;
    private SecuritySnapshot snapshot;
    private SecurityScore.Result lastScore;
    private Boolean lastVpnConnected;

    /**
     * Mirrors live private-mode transitions into the activity log. Registered in
     * {@link #addNotify()} / removed in {@link #removeNotify()}, so a panel built
     * but never shown (a headless test) leaves no global listener behind; the
     * monitor thread fires it, so the Swing work hops to the EDT.
     */
    private final TorPrivateMode.Listener torAuditListener =
            (from, to) -> SwingUtilities.invokeLater(() -> logEvent(AuditEvent.CATEGORY_PRIVACY,
                    "Private (Tor) mode: " + TorPrivateMode.describe(from)
                            + " -> " + TorPrivateMode.describe(to)));

    /** Mirrors the desktop-wide network cut into the activity log; same lifecycle. */
    private final Runnable cutAuditListener =
            () -> SwingUtilities.invokeLater(() -> logEvent(AuditEvent.CATEGORY_CUT,
                    NetworkCut.isCut()
                            ? "Network CUT - the privacy guarantee is down"
                            : "Network restored - the privacy guarantee is back"));

    /** Builds the panel with the default store. */
    public SecurityCenterPanel() {
        this(new SecurityCenterStore());
    }

    /**
     * Builds the panel over an explicit store (package-private for tests).
     *
     * @param store the settings / history store
     */
    SecurityCenterPanel(SecurityCenterStore store) {
        this.store = store;
        this.settings = store.loadSettings();

        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        add(buildTabs(), BorderLayout.CENTER);
        add(buildHistoryPane(), BorderLayout.EAST);
        add(buildBottomPane(), BorderLayout.SOUTH);

        applySettingsToWidgets();
        history.addAll(store.loadHistory());
        refreshHistory();
        audit.addAll(store.loadAudit());
        refreshAudit();
        wireListeners();
        refreshScannerBox();
        refreshLastScanLabel();
        updateButtons();
    }

    /**
     * Registers the live private-mode and network-cut listeners when the panel
     * joins a realized hierarchy, so a panel built but never shown (a headless
     * test) leaves no global listener behind. Pairing this with
     * {@link #removeNotify()} keeps the shared listener lists clean.
     */
    @Override
    public void addNotify() {
        super.addNotify();
        TorPrivateMode.addListener(torAuditListener);
        NetworkCut.addListener(cutAuditListener);
    }

    /** Drops the live listeners when the panel leaves the screen. */
    @Override
    public void removeNotify() {
        TorPrivateMode.removeListener(torAuditListener);
        NetworkCut.removeListener(cutAuditListener);
        super.removeNotify();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private Component buildTabs() {
        tabs.addTab("Antivirus", buildAntivirusTab());
        tabs.addTab("Security Overview", buildOverviewTab());
        tabs.addTab("Privacy", new PrivacyPanel());
        tabs.addTab("Activity", buildActivityTab());
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedIndex() == 1 && snapshot == null
                    && settings.isRefreshOverviewOnOpen()) {
                refreshOverview();
            }
        });
        return tabs;
    }

    private Component buildAntivirusTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel target = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        target.setBorder(BorderFactory.createTitledBorder("Scan target"));
        target.add(new JLabel("Folder or file:"));
        targetField.setName("targetField");
        target.add(targetField);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(e -> chooseTarget());
        target.add(browse);
        panel.add(target);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        options.setBorder(BorderFactory.createTitledBorder("Options"));
        options.add(recursiveCheck);
        options.add(quarantineCheck);
        options.add(updateBeforeScanCheck);
        options.add(new JLabel("Scanner:"));
        scannerBox.setPreferredSize(new Dimension(140, 26));
        options.add(scannerBox);
        panel.add(options);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
        actions.add(updateBtn);
        actions.add(scanBtn);
        actions.add(stopBtn);
        panel.add(actions);

        progress.setStringPainted(true);
        progress.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
        progress.setPreferredSize(new Dimension(100, 22));
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        progress.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(progress);

        summaryLabel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        panel.add(summaryLabel);

        findingsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane findings = new JScrollPane(findingsList);
        findings.setBorder(BorderFactory.createTitledBorder("Findings"));
        findings.setPreferredSize(new Dimension(100, 180));
        panel.add(findings);

        JLabel note = new JLabel("<html><i>The desktop has no built-in virus "
                + "engine, so scanning is delegated to ClamAV (clamdscan or "
                + "clamscan). Install ClamAV to enable scanning.</i></html>");
        note.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        panel.add(note);
        return panel;
    }

    private Component buildOverviewTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        ratingLabel.setFont(ratingLabel.getFont().deriveFont(java.awt.Font.BOLD, 20f));
        ratingLabel.setForeground(MUTED);
        ratingLabel.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
        panel.add(ratingLabel);

        scoreLabel.setFont(scoreLabel.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        scoreLabel.setForeground(MUTED);
        scoreLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 12, 6));
        panel.add(scoreLabel);

        JPanel posture = new JPanel(new GridLayout(7, 2, 8, 8));
        posture.setBorder(BorderFactory.createTitledBorder("Host posture"));
        posture.add(new JLabel("SELinux:"));
        posture.add(selinuxValue);
        posture.add(new JLabel("Firewall:"));
        posture.add(firewallValue);
        posture.add(new JLabel("AppArmor:"));
        posture.add(apparmorValue);
        posture.add(new JLabel("SSH daemon:"));
        posture.add(sshValue);
        posture.add(new JLabel("Antivirus:"));
        posture.add(antivirusValue);
        posture.add(new JLabel("Private (Tor) mode:"));
        posture.add(torValue);
        posture.add(new JLabel("VPN tunnel:"));
        posture.add(vpnValue);
        posture.setMaximumSize(new Dimension(Integer.MAX_VALUE, 280));
        panel.add(posture);

        concernsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane concerns = new JScrollPane(concernsList);
        concerns.setBorder(BorderFactory.createTitledBorder("Recommendations (worst first)"));
        concerns.setPreferredSize(new Dimension(100, 130));
        panel.add(concerns);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        south.add(lastScanLabel);
        south.add(refreshBtn);
        south.add(remediateBtn);
        panel.add(south);
        return panel;
    }

    /**
     * Builds the <em>Activity</em> tab: the append-only audit trail of what the
     * hub observed and did - scans, definition updates, posture reads, private
     * (Tor) mode transitions, network cuts and VPN tunnel changes - newest
     * first. The trail is capped at {@link SecurityCenterStore#AUDIT_LIMIT}
     * entries so it cannot grow without bound; nothing here spawns a process, so
     * it constructs headless.
     */
    private Component buildActivityTab() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        auditList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(auditList);
        scroll.setBorder(BorderFactory.createTitledBorder("Activity (append-only)"));
        panel.add(scroll, BorderLayout.CENTER);

        JLabel note = new JLabel("<html><i>Every scan, definition update, posture "
                + "read, private-mode change, network cut and VPN transition is "
                + "recorded here. The trail keeps the most recent "
                + SecurityCenterStore.AUDIT_LIMIT + " entries.</i></html>");
        note.setBorder(BorderFactory.createEmptyBorder(6, 6, 2, 6));
        panel.add(note, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildHistoryPane() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Scan history"));
        panel.setPreferredSize(new Dimension(250, 100));
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(historyList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        buttons.add(action("Remove", this::removeSelectedHistory));
        buttons.add(action("Clear", this::clearHistory));
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildBottomPane() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            stopScan();
            if (onClose != null) {
                onClose.run();
            }
        });
        right.add(close);
        panel.add(right, BorderLayout.NORTH);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    private JButton action(String label, Runnable handler) {
        JButton button = new JButton(label);
        button.addActionListener(e -> handler.run());
        return button;
    }

    private void applySettingsToWidgets() {
        targetField.setText(settings.getScanPath());
        recursiveCheck.setSelected(settings.isRecursive());
        quarantineCheck.setSelected(settings.isQuarantine());
        updateBeforeScanCheck.setSelected(settings.isUpdateBeforeScan());
    }

    private void refreshScannerBox() {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement("Auto-detect");
        for (String scanner : AntivirusBackend.KNOWN_SCANNERS) {
            model.addElement(scanner);
        }
        scannerBox.setModel(model);
        String preferred = settings.getPreferredScanner();
        scannerBox.setSelectedItem(
                (preferred == null || preferred.isBlank()) ? "Auto-detect" : preferred);
    }

    private void wireListeners() {
        scanBtn.addActionListener(e -> startScan());
        stopBtn.addActionListener(e -> stopScan());
        updateBtn.addActionListener(e -> updateDefinitions());
        refreshBtn.addActionListener(e -> refreshOverview());
        remediateBtn.addActionListener(e -> remediate(concernsList.getSelectedIndex()));
        targetField.addActionListener(e -> readScanSettings());
        recursiveCheck.addActionListener(e -> readScanSettings());
        quarantineCheck.addActionListener(e -> readScanSettings());
        updateBeforeScanCheck.addActionListener(e -> readScanSettings());
        scannerBox.addActionListener(e -> {
            Object sel = scannerBox.getSelectedItem();
            settings.setPreferredScanner(
                    ("Auto-detect".equals(sel) || sel == null) ? "" : sel.toString());
            persist();
        });
    }

    private void readScanSettings() {
        settings.setScanPath(targetField.getText());
        settings.setRecursive(recursiveCheck.isSelected());
        settings.setQuarantine(quarantineCheck.isSelected());
        settings.setUpdateBeforeScan(updateBeforeScanCheck.isSelected());
        persist();
    }

    // ------------------------------------------------------------------
    // Scan target
    // ------------------------------------------------------------------

    private void chooseTarget() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            settings.setScanPath(chooser.getSelectedFile().getAbsolutePath());
            targetField.setText(settings.getScanPath());
            persist();
            setStatus("Scan target set to " + settings.getScanPath());
        }
    }

    // ------------------------------------------------------------------
    // Scanning (delegated to ClamAV)
    // ------------------------------------------------------------------

    private void startScan() {
        if (scanning) {
            setStatus("A scan is already running - stop it first.");
            return;
        }
        readScanSettings();
        Optional<String> scanner = resolveScannerForRun();
        if (scanner.isEmpty()) {
            setStatus("No antivirus scanner found. Install ClamAV (clamscan) to scan.");
            return;
        }
        String target = settings.getScanPath();
        boolean recursive = settings.isRecursive();
        boolean quarantine = settings.isQuarantine();
        String quarantineDir = quarantine
                ? store.resolveQuarantineDir(settings).toString() : "";
        List<String> command = AntivirusBackend.scanCommand(
                scanner.get(), target, recursive, quarantine, quarantineDir, false);
        if (command.isEmpty()) {
            setStatus("Nothing to scan - choose a valid target.");
            return;
        }
        cancelled = false;
        scanning = true;
        scanStartMillis = System.currentTimeMillis();
        lastThreatCount = 0;
        findingsModel.clear();
        summaryLabel.setText("Scanning...");
        updateButtons();
        setStatus("Scanning " + ScanRecord.shorten(target) + " with " + scanner.get() + " ...");
        setProgressIndeterminate("Preparing scan...");

        final String scannerName = scanner.get();
        final boolean updateFirst = settings.isUpdateBeforeScan();
        Thread thread = new Thread(() -> runScan(command, scannerName, target,
                recursive, quarantine, quarantineDir, updateFirst), "lg3d-av-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private void runScan(List<String> command, String scannerName, String target,
                         boolean recursive, boolean quarantine, String quarantineDir,
                         boolean updateFirst) {
        String usedScanner = scannerName;
        if (updateFirst) {
            final String message = runDefinitionUpdate();
            SwingUtilities.invokeLater(() -> setStatus(message));
        }
        // Pre-count the files so the scan bar can be determinate; -1 means the
        // count is unknown (missing target / I/O error) and the bar pulses.
        final long total = countFiles(Paths.get(target), recursive);
        AntivirusBackend.ScanOutputParser parser = new AntivirusBackend.ScanOutputParser();
        lastProgressPush = 0L;
        ProcessOutcome outcome = execStreaming(command, true, scanSink(parser, total));
        ScanReport report = parser.toReport(outcome.exitCode);

        // clamdscan talks to the clamd daemon; if that daemon is stopped the scan
        // never really runs, so fall back to the always-available standalone
        // clamscan (and remember it for the rest of the session).
        if (!report.ranSuccessfully() && !report.hasThreats()
                && "clamdscan".equals(usedScanner) && onPath("clamscan")) {
            clamdFailed = true;
            List<String> fallback = AntivirusBackend.scanCommand(
                    "clamscan", target, recursive, quarantine, quarantineDir, false);
            AntivirusBackend.ScanOutputParser retry = new AntivirusBackend.ScanOutputParser();
            ProcessOutcome retryOutcome = execStreaming(fallback, true, scanSink(retry, total));
            report = retry.toReport(retryOutcome.exitCode);
            usedScanner = "clamscan";
        }

        if (cancelled) {
            SwingUtilities.invokeLater(() -> {
                scanning = false;
                clearProgress();
                updateButtons();
                setStatus("Scan stopped.");
            });
            return;
        }
        long duration = System.currentTimeMillis() - scanStartMillis;
        final ScanReport finalReport = report;
        final String finalScanner = usedScanner;
        SwingUtilities.invokeLater(
                () -> applyReport(finalReport, duration, target, finalScanner));
    }

    /**
     * Builds the per-line sink for one streaming scan: it feeds the incremental
     * parser and, while the database is still loading (no file lines yet), turns
     * the {@code Loading:}/{@code Compiling:} ratio into a determinate bar; once
     * files start it drives the file-count progress instead.
     */
    private Consumer<String> scanSink(AntivirusBackend.ScanOutputParser parser, long total) {
        return line -> {
            OptionalInt load = AntivirusBackend.parseLoadProgress(line);
            if (load.isPresent() && parser.filesSeen() == 0) {
                int pct = load.getAsInt();
                setProgressValue(pct, 100, "Loading virus database... " + pct + "%");
                return;
            }
            parser.feed(line);
            pushScanProgress(parser, total);
        };
    }

    /**
     * Pushes one throttled progress tick for a streaming scan. ClamAV can emit
     * thousands of lines a second, so the EDT is updated at most ~10x/s - but a
     * newly-found threat is always pushed immediately so the user sees it.
     */
    private void pushScanProgress(AntivirusBackend.ScanOutputParser parser, long total) {
        int threats = parser.infectedSoFar();
        long now = System.currentTimeMillis();
        if (threats == lastThreatCount && now - lastProgressPush < 100L) {
            return;
        }
        lastProgressPush = now;
        lastThreatCount = threats;
        updateScanProgress(parser.filesSeen(), total, threats, parser.currentFile());
    }

    /**
     * Renders one scan-progress tick: a determinate bar when the total file count
     * is known, a pulsing one otherwise, plus a live status line (files scanned,
     * threats, elapsed time and the current file). Package-visible so a headless
     * test can drive both bar modes without spawning a scanner.
     *
     * @param seen        files scanned so far
     * @param total       total files to scan, or {@code <= 0} when unknown
     * @param threats     infected files found so far
     * @param currentFile the file the scanner is on (may be null/blank)
     */
    void updateScanProgress(long seen, long total, int threats, String currentFile) {
        String elapsed = describe(System.currentTimeMillis() - scanStartMillis);
        String detail = (currentFile == null || currentFile.isBlank())
                ? "" : ScanRecord.shorten(currentFile);
        String suffix = (threats > 0 ? "  -  " + threats + " threat(s)" : "")
                + "  -  " + elapsed
                + (detail.isBlank() ? "" : "  -  " + detail);
        if (total > 0) {
            int pct = (int) Math.min(100L, Math.round(seen * 100.0 / total));
            String bar = String.format("Scanning %,d/%,d (%d%%)", seen, total, pct);
            setProgressValue(seen, total, bar);
            setStatus(bar + suffix);
        } else {
            setProgressIndeterminate("Scanning...");
            setStatus(String.format("Scanning %,d file(s)", seen) + suffix);
        }
    }

    /**
     * Renders a finished scan: fills the findings list, sets the summary and
     * status, and records it in the history. Package-visible so a test can drive
     * the whole result path with a synthetic report, without spawning a scanner.
     *
     * @param report         the parsed scan result
     * @param durationMillis how long the scan took
     * @param target         the scanned path
     * @param scanner        the scanner that produced the report
     */
    void applyReport(ScanReport report, long durationMillis, String target, String scanner) {
        scanning = false;
        updateButtons();
        clearProgress();
        findingsModel.clear();
        if (report != null) {
            for (Detection d : report.detections()) {
                findingsModel.addElement(d);
            }
        }
        ScanReport r = (report == null) ? ScanReport.empty() : report;
        if (!r.ranSuccessfully()) {
            summaryLabel.setText("The scan did not run.");
            String why = r.errorMessage().isBlank()
                    ? "check that the scanner and target are valid."
                    : r.errorMessage();
            setStatus("Scan failed - " + why);
        } else if (r.hasThreats()) {
            int threats = Math.max(r.infectedFiles(), r.detectionCount());
            summaryLabel.setText(threats + " threat(s) found in " + r.scannedFiles() + " file(s).");
            setStatus("Scan complete: " + threats + " threat(s) found"
                    + (settings.isQuarantine() ? " and moved to quarantine." : "."));
        } else {
            summaryLabel.setText("No threats found in " + r.scannedFiles() + " file(s).");
            setStatus("Scan complete: no threats found (" + describe(durationMillis) + ").");
        }
        ScanRecord record = new ScanRecord(target, scanner, r);
        record.setStartedMillis(scanStartMillis);
        record.setDurationMillis(durationMillis);
        addRecord(record);
        refreshLastScanLabel();
        logEvent(AuditEvent.CATEGORY_SCAN, "Scan of " + ScanRecord.shorten(target)
                + " (" + scanner + "): " + summaryLabel.getText());
    }

    /** Stops a running scan (destroys the scanner process). */
    void stopScan() {
        if (!scanning) {
            return;
        }
        cancelled = true;
        Process process = scanProcess;
        if (process != null) {
            process.destroy();
        }
        setStatus("Stopping scan...");
    }

    private Optional<String> resolveScannerForRun() {
        String preferred = settings.getPreferredScanner();
        if ((preferred == null || preferred.isBlank()) && clamdFailed && onPath("clamscan")) {
            return Optional.of("clamscan");
        }
        return AntivirusBackend.resolveScanner(preferred, SecurityCenterPanel::onPath);
    }

    // ------------------------------------------------------------------
    // Definition update
    // ------------------------------------------------------------------

    private void updateDefinitions() {
        if (updating) {
            setStatus("Definitions are already updating.");
            return;
        }
        updating = true;
        updateButtons();
        setStatus("Updating virus definitions (freshclam)...");
        setProgressIndeterminate("Updating definitions...");
        Thread thread = new Thread(() -> {
            String message = runDefinitionUpdate();
            SwingUtilities.invokeLater(() -> {
                updating = false;
                clearProgress();
                updateButtons();
                setStatus(message);
                logEvent(AuditEvent.CATEGORY_DEFINITIONS, "Definition update: " + message);
            });
        }, "lg3d-av-update");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Runs {@code freshclam} on a worker thread, streaming its output so the
     * progress bar tracks the download percentage as it arrives, and returns the
     * honest one-line outcome from {@link AntivirusBackend#describeUpdate}. Shared
     * by the Update Definitions button and the scan's "update first" option.
     *
     * @return a short human-readable result, never null
     */
    private String runDefinitionUpdate() {
        List<String> lines = new ArrayList<>();
        ProcessOutcome outcome = execStreaming(AntivirusBackend.updateCommand(), false, line -> {
            lines.add(line);
            OptionalInt pct = AntivirusBackend.parseFreshclamProgress(line);
            if (pct.isPresent()) {
                setProgressValue(pct.getAsInt(), 100,
                        "Updating definitions... " + pct.getAsInt() + "%");
            }
        });
        return AntivirusBackend.describeUpdate(lines, outcome.exitCode);
    }

    // ------------------------------------------------------------------
    // Security overview
    // ------------------------------------------------------------------

    private void refreshOverview() {
        if (probing) {
            return;
        }
        probing = true;
        updateButtons();
        setStatus("Checking security posture...");
        Thread thread = new Thread(this::runProbe, "lg3d-sec-probe");
        thread.setDaemon(true);
        thread.start();
    }

    private void runProbe() {
        ProcessResult sel = exec(SecurityProbe.selinuxCommand(), false);
        SecurityProbe.SelinuxMode selinux = sel.ioError
                ? SecurityProbe.SelinuxMode.ABSENT
                : SecurityProbe.parseSelinux(String.join("\n", sel.lines));

        ProcessResult fw = exec(SecurityProbe.firewallStateCommand(), false);
        SecurityProbe.FirewallState firewall = fw.ioError
                ? SecurityProbe.FirewallState.ABSENT
                : SecurityProbe.parseFirewallState(fw.exitCode, String.join("\n", fw.lines));

        Optional<String> scanner = AntivirusBackend.resolveScanner(
                settings.getPreferredScanner(), SecurityCenterPanel::onPath);
        VersionInfo version = VersionInfo.unknown();
        if (scanner.isPresent()) {
            ProcessResult v = exec(AntivirusBackend.versionCommand(scanner.get()), false);
            if (!v.ioError) {
                version = AntivirusBackend.parseVersion(String.join("\n", v.lines));
            }
        }

        // The anonymity layers: private (Tor) mode is an in-memory read (no
        // process), and the VPN tunnel is a read-only nmcli query. Both degrade
        // to "off / not connected" rather than a false all-clear.
        TorPrivateMode.State tor = TorPrivateMode.state();
        VpnStatus vpn = readVpnStatus();
        boolean vpnConnected = vpn.connected();
        boolean vpnKillSwitch = vpnConnected && readVpnKillSwitch(vpn.profileName());

        SecuritySnapshot probed = new SecuritySnapshot(
                selinux, firewall, scanner.isPresent(), scanner.orElse(""), version,
                tor, vpnConnected, vpnKillSwitch);

        // §4.6: AppArmor and the SSH daemon are read through the shared
        // SecurityService (read-only, unprivileged). A missing tool or an
        // unprivileged read degrades to an honest label, never a fake "secure".
        String apparmor = readAppArmorLabel();
        String ssh = readSshdLabel();

        SwingUtilities.invokeLater(() -> {
            applySnapshot(probed);
            renderHostServices(apparmor, ssh);
        });
    }

    /**
     * Reads the AppArmor posture via {@link SecurityService} (read-only), on the
     * probe's background thread. A missing tool is "Not installed"; a non-zero
     * exit (e.g. it needs root) is "Unknown", never a false all-clear.
     */
    private String readAppArmorLabel() {
        ProcessRunner.Result r = SecurityService.runRead(SecurityService.Operation.APPARMOR_STATUS);
        if (!r.isStarted()) {
            return "Not installed";
        }
        if (!r.isSuccess()) {
            return "Unknown";
        }
        return SecurityService.parseAppArmor(r.getStdout()).describe();
    }

    /**
     * Reads the SSH daemon state via {@link SecurityService}, which maps the
     * init-system {@code status sshd} (§4.1), on the probe's background thread.
     * An undetectable init or an absent unit is "Unknown".
     */
    private String readSshdLabel() {
        ProcessRunner.Result r = SecurityService.runRead(SecurityService.Operation.SSHD_STATUS);
        if (!r.isStarted()) {
            return "Unknown";
        }
        String out = r.getStdout().isEmpty() ? r.getStderr() : r.getStdout();
        return SecurityService.describeSshd(
                SecurityService.parseSshdState(r.getExitCode(), out));
    }

    /**
     * Reads the live VPN tunnel state, read-only, on the probe's background
     * thread. Only {@code nmcli} reports active connections, so when it is not
     * the resolved backend (or is absent) this is an honest
     * {@link VpnStatus#unknown()} rather than a guessed "connected".
     */
    private VpnStatus readVpnStatus() {
        Optional<String> backend = VpnBackend.resolveBackend("", SecurityCenterPanel::onPath);
        if (backend.isEmpty() || !VpnBackend.NMCLI.equals(backend.get())) {
            return VpnStatus.unknown();
        }
        ProcessResult r = exec(VpnBackend.activeConnectionsCommand(), false);
        if (r.ioError) {
            return VpnStatus.unknown();
        }
        return VpnBackend.parseStatus(r.lines);
    }

    /**
     * True when the connected profile is armed with a kill switch, read from the
     * VPN app's own profile store (a missing profile is treated as unarmed).
     *
     * @param profileName the active connection's name
     */
    private boolean readVpnKillSwitch(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return false;
        }
        for (VpnProfile profile : new VpnStore().loadProfiles()) {
            if (profileName.equals(profile.getName())) {
                return profile.isKillSwitch();
            }
        }
        return false;
    }

    /**
     * Renders a posture snapshot on the overview tab. Package-visible so a test
     * can drive the overview with a synthetic snapshot, without running probes.
     *
     * @param value the snapshot to render (null is ignored)
     */
    void applySnapshot(SecuritySnapshot value) {
        if (value == null) {
            return;
        }
        this.snapshot = value;
        probing = false;
        updateButtons();
        ratingLabel.setText("Security status: " + value.rating());
        ratingLabel.setForeground(colorForRating(value.rating()));
        selinuxValue.setText(SecurityProbe.describeSelinux(value.selinux()));
        firewallValue.setText(SecurityProbe.describeFirewall(value.firewall()));
        antivirusValue.setText(value.scannerAvailable()
                ? value.scanner() + (value.version().isPresent()
                        ? "  -  " + value.version() : "")
                : "Not installed");
        torValue.setText(TorPrivateMode.describe(value.tor()));
        vpnValue.setText(SecurityProbe.describeVpn(value.vpnConnected(), value.vpnKillSwitch()));

        SecurityScore.Result score = SecurityScore.compute(value);
        lastScore = score;
        scoreLabel.setText("Security score: " + score.header());
        scoreLabel.setForeground(colorForGrade(score.grade()));
        // Publish the grade through the core seam so the taskbar privacy shield
        // tooltip can show it (apps -> core; core never computes the score).
        SecurityPosture.publish(String.valueOf(score.grade()));

        concernsModel.clear();
        List<HardeningRules.Recommendation> recommendations = HardeningRules.evaluate(value);
        if (recommendations.isEmpty()) {
            concernsModel.addElement(new HardeningRules.Recommendation(
                    "No action needed - the system looks protected.",
                    "Every hardening check passed.",
                    HardeningRules.Action.INFO, HardeningRules.Severity.LOW));
        } else {
            for (HardeningRules.Recommendation recommendation : recommendations) {
                concernsModel.addElement(recommendation);
            }
        }

        logVpnTransition(value.vpnConnected());
        logEvent(AuditEvent.CATEGORY_POSTURE,
                "Host posture read: " + postureSummary(value, score));
        setStatus("Security posture updated.");
    }

    /**
     * Renders the AppArmor / SSH-daemon posture rows on the overview tab.
     * Package-visible so a test can drive these rows with synthetic labels,
     * without running any probe.
     *
     * @param apparmorText the AppArmor label (null renders as "-")
     * @param sshText      the SSH daemon label (null renders as "-")
     */
    void renderHostServices(String apparmorText, String sshText) {
        apparmorValue.setText(apparmorText == null ? "-" : apparmorText);
        sshValue.setText(sshText == null ? "-" : sshText);
    }

    private static Color colorForRating(String rating) {
        if ("Good".equals(rating)) {
            return GOOD;
        }
        if ("Attention needed".equals(rating)) {
            return ATTENTION;
        }
        return MUTED;
    }

    /** Maps a letter grade onto the overview's colour vocabulary. */
    private static Color colorForGrade(SecurityScore.Grade grade) {
        if (grade == null) {
            return MUTED;
        }
        return switch (grade) {
            case A, B -> GOOD;
            case C -> ATTENTION;
            case D, F -> BAD;
        };
    }

    /** A one-line summary of the posture for the activity log. */
    private static String postureSummary(SecuritySnapshot value, SecurityScore.Result score) {
        return score.header()
                + ", SELinux " + SecurityProbe.describeSelinux(value.selinux())
                + ", firewall " + SecurityProbe.describeFirewall(value.firewall())
                + ", tor " + TorPrivateMode.describe(value.tor())
                + ", VPN " + SecurityProbe.describeVpn(
                        value.vpnConnected(), value.vpnKillSwitch());
    }

    /**
     * Records a VPN connect / disconnect in the activity log when the observed
     * state changes, then remembers it so the next probe only logs a real
     * transition. The first probe seeds the baseline without logging.
     */
    private void logVpnTransition(boolean connected) {
        if (lastVpnConnected == null) {
            lastVpnConnected = connected;
            return;
        }
        if (lastVpnConnected != connected) {
            logEvent(AuditEvent.CATEGORY_VPN, connected
                    ? "VPN tunnel connected"
                    : "VPN tunnel disconnected");
            lastVpnConnected = connected;
        }
    }

    /**
     * Appends one event to the in-memory trail (capped at
     * {@link SecurityCenterStore#AUDIT_LIMIT}), persists it and refreshes the
     * Activity tab. Called on the EDT.
     */
    private void logEvent(String category, String message) {
        audit.add(new AuditEvent(category, message));
        while (audit.size() > SecurityCenterStore.AUDIT_LIMIT) {
            audit.remove(0);
        }
        store.saveAudit(new ArrayList<>(audit));
        refreshAudit();
    }

    /** Repopulates the Activity list newest-first from the in-memory trail. */
    private void refreshAudit() {
        auditModel.clear();
        for (int i = audit.size() - 1; i >= 0; i--) {
            auditModel.addElement(audit.get(i));
        }
    }

    /**
     * Acts on the selected recommendation: switch to the relevant tab, run an
     * in-panel fix (update definitions / re-probe), or - where the fix lives
     * outside this app - surface the guidance in the status line. A negative or
     * out-of-range index is a safe no-op. Package-visible so a test can drive
     * the tab-switch / info paths without spawning a process.
     *
     * @param index the selected recommendation's row
     */
    void remediate(int index) {
        if (index < 0 || index >= concernsModel.size()) {
            setStatus("Select a recommendation to remediate.");
            return;
        }
        HardeningRules.Recommendation recommendation = concernsModel.getElementAt(index);
        switch (recommendation.action()) {
            case OPEN_PRIVACY -> selectTab("Privacy");
            case OPEN_ANTIVIRUS -> selectTab("Antivirus");
            case UPDATE_DEFINITIONS -> updateDefinitions();
            case REFRESH_OVERVIEW -> {
                selectTab("Security Overview");
                refreshOverview();
            }
            case INFO -> {
                // The fix lives outside this app; the detail line is the guidance.
            }
        }
        setStatus(recommendation.detail().isBlank()
                ? recommendation.title() : recommendation.detail());
    }

    /** Selects a tab by title; an unknown title leaves the selection unchanged. */
    private void selectTab(String title) {
        int index = tabs.indexOfTab(title);
        if (index >= 0) {
            tabs.setSelectedIndex(index);
        }
    }

    // ------------------------------------------------------------------
    // Process plumbing
    // ------------------------------------------------------------------

    /**
     * Runs a command, capturing its merged output and exit code. A missing
     * executable surfaces as {@link ProcessResult#ioError} (the overview maps
     * that to {@code ABSENT}); it never throws.
     */
    private ProcessResult exec(List<String> command, boolean trackForStop) {
        ProcessResult result = new ProcessResult();
        if (command == null || command.isEmpty()) {
            result.ioError = true;
            result.ioMessage = "empty command";
            return result;
        }
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();
            if (trackForStop) {
                scanProcess = process;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.lines.add(line);
                }
            }
            result.exitCode = process.waitFor();
        } catch (IOException e) {
            result.ioError = true;
            result.ioMessage = (e.getMessage() == null) ? e.toString() : e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.ioError = true;
            result.ioMessage = "interrupted";
            if (process != null) {
                process.destroy();
            }
        } catch (RuntimeException e) {
            result.ioError = true;
            result.ioMessage = (e.getMessage() == null) ? e.toString() : e.getMessage();
        } finally {
            if (trackForStop) {
                scanProcess = null;
            }
        }
        return result;
    }

    /** The captured output of one external command. */
    private static final class ProcessResult {
        final List<String> lines = new ArrayList<>();
        int exitCode;
        boolean ioError;
        String ioMessage = "";
    }

    /**
     * Runs a command, handing each output line to {@code sink} as it arrives (on
     * the calling worker thread) instead of buffering the whole run. This is what
     * lets the scan and the definition update show live progress; the sink owns
     * any EDT hop. A missing executable surfaces as {@link ProcessOutcome#ioError}
     * and it never throws.
     *
     * @param command      the argument list (null / empty is an ioError)
     * @param trackForStop true to expose the process to {@link #stopScan()}
     * @param sink         receives each line as it is read (may be null)
     * @return the exit status, never null
     */
    private ProcessOutcome execStreaming(List<String> command, boolean trackForStop,
                                         Consumer<String> sink) {
        ProcessOutcome result = new ProcessOutcome();
        if (command == null || command.isEmpty()) {
            result.ioError = true;
            result.ioMessage = "empty command";
            return result;
        }
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();
            if (trackForStop) {
                scanProcess = process;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sink != null) {
                        sink.accept(line);
                    }
                }
            }
            result.exitCode = process.waitFor();
        } catch (IOException e) {
            result.ioError = true;
            result.ioMessage = (e.getMessage() == null) ? e.toString() : e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.ioError = true;
            result.ioMessage = "interrupted";
            if (process != null) {
                process.destroy();
            }
        } catch (RuntimeException e) {
            result.ioError = true;
            result.ioMessage = (e.getMessage() == null) ? e.toString() : e.getMessage();
        } finally {
            if (trackForStop) {
                scanProcess = null;
            }
        }
        return result;
    }

    /** The exit status of a streamed command (its output is not buffered). */
    private static final class ProcessOutcome {
        int exitCode;
        boolean ioError;
        String ioMessage = "";
    }

    /** True when {@code exe} is found on the PATH. */
    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && new File(dir, exe).canExecute()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts the regular files under {@code target} so the scan bar can be
     * determinate: a single file counts as one, a folder is walked recursively or
     * one level deep to match the scan. Returns {@code -1} when the count is
     * unknown (missing target, I/O error or a security denial), so the caller
     * falls back to a pulsing bar rather than a wrong percentage. Package-visible
     * for headless tests.
     *
     * @param target    the scan target (may be null)
     * @param recursive true to walk subfolders
     * @return the file count, or {@code -1} when it cannot be determined
     */
    static long countFiles(Path target, boolean recursive) {
        if (target == null) {
            return -1L;
        }
        try {
            if (Files.isRegularFile(target)) {
                return 1L;
            }
            if (!Files.isDirectory(target)) {
                return -1L;
            }
            try (Stream<Path> walk = recursive
                    ? Files.walk(target)
                    : Files.list(target)) {
                return walk.filter(Files::isRegularFile).count();
            }
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    private void removeSelectedHistory() {
        int i = historyList.getSelectedIndex();
        if (i >= 0 && i < history.size()) {
            history.remove(history.size() - 1 - i);
            refreshHistory();
            persist();
            refreshLastScanLabel();
        }
    }

    private void clearHistory() {
        history.clear();
        refreshHistory();
        persist();
        refreshLastScanLabel();
        setStatus("Scan history cleared.");
    }

    /**
     * Adds a finished scan to the history and persists it. Package-visible so a
     * test can grow the history without running a scan.
     *
     * @param record the scan record to add
     */
    void addRecord(ScanRecord record) {
        if (record != null) {
            history.add(record);
            refreshHistory();
            persist();
        }
    }

    private void refreshHistory() {
        historyModel.clear();
        for (int i = history.size() - 1; i >= 0; i--) {
            historyModel.addElement(history.get(i));
        }
    }

    private void refreshLastScanLabel() {
        if (history.isEmpty()) {
            lastScanLabel.setText("Last scan: never");
            return;
        }
        lastScanLabel.setText("Last scan: " + history.get(history.size() - 1));
    }

    private void persist() {
        store.saveSettings(settings);
        store.saveHistory(new ArrayList<>(history));
    }

    // ------------------------------------------------------------------
    // Status / buttons
    // ------------------------------------------------------------------

    private void updateButtons() {
        boolean busy = scanning || updating;
        scanBtn.setEnabled(!busy);
        updateBtn.setEnabled(!busy);
        stopBtn.setEnabled(scanning);
        refreshBtn.setEnabled(!probing);
    }

    private static String describe(long millis) {
        long s = Math.max(0, millis / 1000);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void setStatus(String text) {
        final String message = text;
        // Track the latest status synchronously so it is observable the instant
        // it is set; the label itself is still updated on the EDT.
        statusMessage = message;
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(message);
        } else {
            SwingUtilities.invokeLater(() -> statusLabel.setText(message));
        }
    }

    // ------------------------------------------------------------------
    // Progress bar
    // ------------------------------------------------------------------

    /**
     * Shows a pulsing (indeterminate) bar with a short stage label, used while
     * the file total is still unknown or a stage has no measurable percentage.
     */
    private void setProgressIndeterminate(String text) {
        progressIndeterminate = true;
        progressValue = 0;
        progressText = (text == null) ? "" : text;
        applyProgress();
    }

    /**
     * Shows a determinate bar at {@code done}/{@code total} with a short label.
     * A non-positive {@code total} degrades to a 0/100 bar so the widget never
     * divides by zero.
     */
    private void setProgressValue(long done, long total, String text) {
        progressIndeterminate = false;
        progressMax = (total <= 0) ? 100 : (int) Math.min(total, Integer.MAX_VALUE);
        progressValue = (total <= 0) ? 0
                : (int) Math.min(Math.max(done, 0L), progressMax);
        progressText = (text == null) ? "" : text;
        applyProgress();
    }

    /** Returns the bar to its idle state (empty, determinate, zero). */
    private void clearProgress() {
        progressIndeterminate = false;
        progressMax = 100;
        progressValue = 0;
        progressText = "";
        applyProgress();
    }

    /**
     * Mirrors the volatile progress fields onto the widgets. The fields are set
     * synchronously by the callers above (so a headless test reads them back
     * deterministically); the JProgressBar repaint still hops to the EDT.
     */
    private void applyProgress() {
        final boolean indet = progressIndeterminate;
        final int max = progressMax;
        final int value = progressValue;
        final String text = progressText;
        if (SwingUtilities.isEventDispatchThread()) {
            paintProgress(indet, max, value, text);
        } else {
            SwingUtilities.invokeLater(() -> paintProgress(indet, max, value, text));
        }
    }

    private void paintProgress(boolean indet, int max, int value, String text) {
        progress.setIndeterminate(indet);
        if (!indet) {
            progress.setMaximum(max);
            progress.setValue(value);
        }
        progress.setString(text);
    }

    /** Wires the panel's Close button (used by both desktop hosts). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    // ------------------------------------------------------------------
    // Test hooks
    // ------------------------------------------------------------------

    /** The number of history rows shown (newest first). */
    int historySize() {
        return historyModel.size();
    }

    /** An unmodifiable view of the history, oldest first. */
    List<ScanRecord> history() {
        return List.copyOf(history);
    }

    /** The number of findings currently listed. */
    int findingsCount() {
        return findingsModel.size();
    }

    /** The antivirus-tab summary line. */
    String summaryText() {
        return summaryLabel.getText();
    }

    /** The overview rating header text. */
    String ratingText() {
        return ratingLabel.getText();
    }

    /** The number of recommendation lines on the overview tab. */
    int concernsCount() {
        return concernsModel.size();
    }

    /** True while a scan is running. */
    boolean isScanning() {
        return scanning;
    }

    /** The current status text. */
    String statusText() {
        return statusMessage;
    }

    /** The live settings bean (package-visible for tests). */
    SecurityCenterSettings settings() {
        return settings;
    }

    /** The last applied posture snapshot, or null before the first refresh. */
    SecuritySnapshot snapshot() {
        return snapshot;
    }

    /** The overview AppArmor posture row text. */
    String apparmorText() {
        return apparmorValue.getText();
    }

    /** The overview SSH-daemon posture row text. */
    String sshText() {
        return sshValue.getText();
    }

    /** The number of top-level tabs (Antivirus / Security Overview / Privacy / Activity). */
    int tabCount() {
        return tabs.getTabCount();
    }

    /** The title of tab {@code i}. */
    String tabTitle(int i) {
        return tabs.getTitleAt(i);
    }

    /** The index of the currently selected tab. */
    int selectedTabIndex() {
        return tabs.getSelectedIndex();
    }

    /** The overview score header text (e.g. "Security score: Grade B (80/100)"). */
    String scoreText() {
        return scoreLabel.getText();
    }

    /** The last computed score result, or null before the first posture read. */
    SecurityScore.Result score() {
        return lastScore;
    }

    /** The overview private-(Tor)-mode row text. */
    String torText() {
        return torValue.getText();
    }

    /** The overview VPN-tunnel row text. */
    String vpnText() {
        return vpnValue.getText();
    }

    /** The recommendation at row {@code i}, or null when out of range. */
    HardeningRules.Recommendation recommendationAt(int i) {
        return (i < 0 || i >= concernsModel.size()) ? null : concernsModel.getElementAt(i);
    }

    /** The number of activity-log rows shown (newest first). */
    int auditSize() {
        return auditModel.size();
    }

    /** An unmodifiable view of the activity trail, oldest first. */
    List<AuditEvent> audit() {
        return List.copyOf(audit);
    }

    /** The progress bar's current value (0 while idle or indeterminate). */
    int progressValue() {
        return progressValue;
    }

    /** The progress bar's maximum (100 while idle or indeterminate). */
    int progressMax() {
        return progressMax;
    }

    /** True while the progress bar is indeterminate (pulsing). */
    boolean progressIndeterminate() {
        return progressIndeterminate;
    }

    /** The progress bar's painted text (empty while idle). */
    String progressText() {
        return progressText;
    }
}
