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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * The Security Center's user interface: an <em>Antivirus</em> tab that scans a
 * chosen target through an installed ClamAV, a <em>Security Overview</em> tab
 * that aggregates the host's SELinux / firewall / antivirus posture, and a scan
 * history dock on the right. One panel serves both the 3D desktop (hosted on a
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
    private static final Color MUTED = new Color(90, 90, 90);

    private final SecurityCenterStore store;
    private final SecurityCenterSettings settings;
    private final List<ScanRecord> history = new ArrayList<>();

    private final DefaultListModel<ScanRecord> historyModel = new DefaultListModel<>();
    private final JList<ScanRecord> historyList = new JList<>(historyModel);
    private final DefaultListModel<Detection> findingsModel = new DefaultListModel<>();
    private final JList<Detection> findingsList = new JList<>(findingsModel);
    private final DefaultListModel<String> concernsModel = new DefaultListModel<>();
    private final JList<String> concernsList = new JList<>(concernsModel);
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

    // Overview tab
    private final JLabel ratingLabel = new JLabel("Security status: not checked yet");
    private final JLabel selinuxValue = new JLabel("-");
    private final JLabel firewallValue = new JLabel("-");
    private final JLabel antivirusValue = new JLabel("-");
    private final JLabel lastScanLabel = new JLabel("Last scan: never");
    private final JButton refreshBtn = new JButton("Refresh");

    private Runnable onClose;
    private volatile boolean scanning;
    private volatile boolean updating;
    private volatile boolean probing;
    private volatile boolean cancelled;
    private volatile boolean clamdFailed;
    private volatile Process scanProcess;
    private volatile String statusMessage = "Ready";
    private long scanStartMillis;
    private SecuritySnapshot snapshot;

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
        wireListeners();
        refreshScannerBox();
        refreshLastScanLabel();
        updateButtons();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private Component buildTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Antivirus", buildAntivirusTab());
        tabs.addTab("Security Overview", buildOverviewTab());
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
        ratingLabel.setBorder(BorderFactory.createEmptyBorder(6, 6, 12, 6));
        panel.add(ratingLabel);

        JPanel posture = new JPanel(new GridLayout(3, 2, 8, 8));
        posture.setBorder(BorderFactory.createTitledBorder("Host posture"));
        posture.add(new JLabel("SELinux:"));
        posture.add(selinuxValue);
        posture.add(new JLabel("Firewall:"));
        posture.add(firewallValue);
        posture.add(new JLabel("Antivirus:"));
        posture.add(antivirusValue);
        posture.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        panel.add(posture);

        concernsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane concerns = new JScrollPane(concernsList);
        concerns.setBorder(BorderFactory.createTitledBorder("Recommendations"));
        concerns.setPreferredSize(new Dimension(100, 160));
        panel.add(concerns);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        south.add(lastScanLabel);
        south.add(refreshBtn);
        panel.add(south);
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
                scanner.get(), target, recursive, quarantine, quarantineDir);
        if (command.isEmpty()) {
            setStatus("Nothing to scan - choose a valid target.");
            return;
        }
        cancelled = false;
        scanning = true;
        scanStartMillis = System.currentTimeMillis();
        findingsModel.clear();
        summaryLabel.setText("Scanning...");
        updateButtons();
        setStatus("Scanning " + ScanRecord.shorten(target) + " with " + scanner.get() + " ...");

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
            ProcessResult update = exec(AntivirusBackend.updateCommand(), false);
            final String message = AntivirusBackend.describeUpdate(update.lines, update.exitCode);
            SwingUtilities.invokeLater(() -> setStatus(message));
        }
        ProcessResult result = exec(command, true);
        ScanReport report = AntivirusBackend.parseScanOutput(result.lines, result.exitCode);

        // clamdscan talks to the clamd daemon; if that daemon is stopped the scan
        // never really runs, so fall back to the always-available standalone
        // clamscan (and remember it for the rest of the session).
        if (!report.ranSuccessfully() && !report.hasThreats()
                && "clamdscan".equals(usedScanner) && onPath("clamscan")) {
            clamdFailed = true;
            List<String> fallback = AntivirusBackend.scanCommand(
                    "clamscan", target, recursive, quarantine, quarantineDir);
            ProcessResult retry = exec(fallback, true);
            report = AntivirusBackend.parseScanOutput(retry.lines, retry.exitCode);
            usedScanner = "clamscan";
        }

        if (cancelled) {
            SwingUtilities.invokeLater(() -> {
                scanning = false;
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
        Thread thread = new Thread(() -> {
            ProcessResult result = exec(AntivirusBackend.updateCommand(), true);
            String message = AntivirusBackend.describeUpdate(result.lines, result.exitCode);
            SwingUtilities.invokeLater(() -> {
                updating = false;
                updateButtons();
                setStatus(message);
            });
        }, "lg3d-av-update");
        thread.setDaemon(true);
        thread.start();
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
        SecuritySnapshot probed = new SecuritySnapshot(
                selinux, firewall, scanner.isPresent(), scanner.orElse(""), version);
        SwingUtilities.invokeLater(() -> applySnapshot(probed));
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
        concernsModel.clear();
        List<String> concerns = value.concerns();
        if (concerns.isEmpty()) {
            concernsModel.addElement("No action needed - the system looks protected.");
        } else {
            for (String concern : concerns) {
                concernsModel.addElement(concern);
            }
        }
        setStatus("Security posture updated.");
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
}
