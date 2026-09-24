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
package org.jdesktop.lg3d.apps.mediawriter;

import org.jdesktop.lg3d.apps.mediawriter.MediaWriterEngine.DeviceInfo;
import org.jdesktop.lg3d.apps.mediawriter.MediaWriterEngine.DeviceKind;
import org.jdesktop.lg3d.apps.mediawriter.MediaWriterEngine.MediaMode;
import org.jdesktop.lg3d.apps.mediawriter.MediaWriterEngine.ProgressHandler;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Swing content of the Media Writer desktop window: burn an ISO to CD/DVD,
 * write a raw image or ISO to a USB key, clone a disc/device, format a
 * removable key, or build a data disc from a folder. All write operations are
 * real (see {@link MediaWriterEngine}) and therefore destructive, so the panel
 * gates every write behind an explicit inline confirmation.
 *
 * <p>The panel uses a {@code null} layout with explicit bounds for every child
 * ({@code SwingNode} paints hosted panels offscreen without a layout pass - see
 * {@code docs/swingnode.md}). It is fully self-contained: no modal dialogs
 * ({@code JOptionPane} / {@code JFileChooser} open separate top-level windows a
 * SwingNode cannot capture), so file selection uses an in-panel browser and the
 * destructive-write prompt is an inline overlay.</p>
 */
public class MediaWriterPanel extends JPanel {

    /** Panel size in native pixels; the wrapper hands these to TitledSwingWindow. */
    public static final int WIDTH_PX = 720;
    public static final int HEIGHT_PX = 512;

    private static final int M = 10;          // margin
    private static final int FW = 700;        // full inner width (WIDTH_PX - 2*M)

    private static final Color BG = new Color(0x1B, 0x21, 0x2B);
    private static final Color FG = new Color(0xE6, 0xED, 0xF3);
    private static final Color MUTED = new Color(0x9A, 0xA4, 0xB2);
    private static final Color ACCENT = new Color(0x3D, 0x6B, 0xC4);
    private static final Color DANGER = new Color(0xB3, 0x3A, 0x3A);

    private static final String[] MODE_LABELS = {
        "Burn ISO to CD/DVD", "Write image to USB key", "Clone disc / device",
        "Format USB key", "Create data disc",
    };
    private static final MediaMode[] MODES = {
        MediaMode.BURN_ISO, MediaMode.WRITE_USB, MediaMode.CLONE_DISC,
        MediaMode.FORMAT_USB, MediaMode.DATA_DISC,
    };
    private static final String[] FILESYSTEMS = {"vfat", "exfat", "ntfs", "ext4", "ext2"};
    private static final String[] SPEEDS = {"Max", "1x", "2x", "4x", "8x", "16x", "24x", "40x"};

    private final MediaWriterEngine engine = new MediaWriterEngine();

    // mode
    private final JComboBox<String> modeCombo = new JComboBox<>(MODE_LABELS);
    // source
    private final JLabel sourceLabel = new JLabel("Source:");
    private final JTextField sourceField = new JTextField();
    private final JButton browseBtn = new JButton("Browse…");
    private final JComboBox<DeviceInfo> srcDeviceCombo = new JComboBox<>();
    // target
    private final JLabel targetLabel = new JLabel("Target:");
    private final JComboBox<DeviceInfo> targetCombo = new JComboBox<>();
    private final JButton refreshBtn = new JButton("Refresh");
    // data-disc output
    private final JLabel outLabel = new JLabel("Out ISO:");
    private final JTextField outField = new JTextField();
    private final JButton outBrowseBtn = new JButton("Browse…");
    // options
    private final JLabel fsLabel = new JLabel("Filesys:");
    private final JComboBox<String> fsCombo = new JComboBox<>(FILESYSTEMS);
    private final JTextField labelField = new JTextField();
    private final JLabel speedLabel = new JLabel("Speed:");
    private final JComboBox<String> speedCombo = new JComboBox<>(SPEEDS);
    private final JCheckBox verifyCheck = new JCheckBox("Verify after write");
    private final JCheckBox bootableCheck = new JCheckBox("Bootable (isohybrid)");
    private final JCheckBox partitionCheck = new JCheckBox("New partition table");
    // progress + log
    private final JLabel statusLabel = new JLabel("Ready");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea();
    private final JScrollPane logScroll = new JScrollPane(logArea);
    // buttons
    private final JButton startBtn = new JButton("Start");
    private final JButton cancelBtn = new JButton("Cancel");
    private final JButton clearBtn = new JButton("Clear Log");
    // confirm overlay
    private final JPanel confirmPanel = new JPanel(null);
    private final JLabel confirmLabel = new JLabel();
    private final JButton confirmYes = new JButton("Yes, erase & write");
    private final JButton confirmNo = new JButton("Abort");
    // browser overlay
    private final JPanel browserPanel = new JPanel(null);
    private final JLabel browserPath = new JLabel();
    private final DefaultListModel<String> browserModel = new DefaultListModel<>();
    private final JList<String> browserList = new JList<>(browserModel);
    private final JButton browserUp = new JButton("Up");
    private final JButton browserSelect = new JButton("Select");
    private final JButton browserCancel = new JButton("Cancel");

    private final List<File> browserEntries = new ArrayList<>();
    private File browserDir = new File(System.getProperty("user.home", "/"));
    private BrowserMode browserMode = BrowserMode.IMAGE_FILE;

    private enum BrowserMode { ISO_FILE, IMAGE_FILE, FOLDER, OUTPUT_ISO }

    private volatile boolean running;

    public MediaWriterPanel() {
        setLayout(null);
        setOpaque(true);
        setBackground(BG);
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        buildControls();
        layoutControls();
        buildOverlays();

        modeCombo.addActionListener(e -> applyMode());
        refreshBtn.addActionListener(e -> refreshDevices());
        browseBtn.addActionListener(e -> openSourceBrowser());
        outBrowseBtn.addActionListener(e -> openBrowser(BrowserMode.OUTPUT_ISO, outField));
        startBtn.addActionListener(e -> confirmAndStart());
        cancelBtn.addActionListener(e -> engine.cancel());
        clearBtn.addActionListener(e -> logArea.setText(""));
        labelField.setToolTipText("Volume / filesystem label (optional)");

        styleLog();
        applyMode();
        refreshDevices();
        appendLog("Media Writer ready. Select a mode, source and target.");
        appendLog("Tools: " + toolSummary());
    }

    // ------------------------------------------------------------------ build

    private void buildControls() {
        style(modeCombo);
        style(sourceField);
        style(browseBtn);
        style(srcDeviceCombo);
        style(targetCombo);
        style(refreshBtn);
        style(outField);
        style(outBrowseBtn);
        style(fsCombo);
        style(labelField);
        style(speedCombo);
        style(startBtn);
        style(cancelBtn);
        style(clearBtn);

        sourceLabel.setForeground(MUTED);
        targetLabel.setForeground(MUTED);
        outLabel.setForeground(MUTED);
        fsLabel.setForeground(MUTED);
        speedLabel.setForeground(MUTED);

        verifyCheck.setForeground(FG);
        verifyCheck.setOpaque(false);
        bootableCheck.setForeground(FG);
        bootableCheck.setOpaque(false);
        partitionCheck.setForeground(FG);
        partitionCheck.setOpaque(false);

        statusLabel.setForeground(FG);
        progressBar.setStringPainted(true);
        progressBar.setForeground(ACCENT);

        startBtn.setBackground(ACCENT);
        startBtn.setForeground(Color.WHITE);
        cancelBtn.setEnabled(false);

        srcDeviceCombo.setOpaque(true);
        targetCombo.setOpaque(true);
    }

    private void layoutControls() {
        int y = M;
        add(modeCombo);           modeCombo.setBounds(M + 54, y, 320, 26);
        JLabel ml = new JLabel("Mode:"); ml.setForeground(MUTED);
        add(ml); ml.setBounds(M, y + 4, 50, 20);

        y += 34;
        add(sourceLabel);   sourceLabel.setBounds(M, y + 4, 70, 20);
        add(sourceField);   sourceField.setBounds(M + 74, y, 536, 26);
        add(browseBtn);     browseBtn.setBounds(M + 620, y, 80, 26);
        add(srcDeviceCombo); srcDeviceCombo.setBounds(M + 74, y, 536, 26);

        y += 34;
        add(targetLabel);   targetLabel.setBounds(M, y + 4, 70, 20);
        add(targetCombo);   targetCombo.setBounds(M + 74, y, 536, 26);
        add(refreshBtn);    refreshBtn.setBounds(M + 620, y, 80, 26);

        y += 34;
        add(outLabel);      outLabel.setBounds(M, y + 4, 70, 20);
        add(outField);      outField.setBounds(M + 74, y, 536, 26);
        add(outBrowseBtn);  outBrowseBtn.setBounds(M + 620, y, 80, 26);

        y += 34;
        add(fsLabel);       fsLabel.setBounds(M, y + 4, 70, 20);
        add(fsCombo);       fsCombo.setBounds(M + 74, y, 120, 26);
        add(labelField);    labelField.setBounds(M + 204, y, 210, 26);
        add(speedLabel);    speedLabel.setBounds(M + 424, y + 4, 50, 20);
        add(speedCombo);    speedCombo.setBounds(M + 478, y, 90, 26);

        y += 32;
        add(verifyCheck);     verifyCheck.setBounds(M, y, 160, 22);
        add(bootableCheck);   bootableCheck.setBounds(M + 170, y, 180, 22);
        add(partitionCheck);  partitionCheck.setBounds(M + 360, y, 200, 22);

        y += 28;
        add(statusLabel);   statusLabel.setBounds(M, y, FW, 20);
        y += 22;
        add(progressBar);   progressBar.setBounds(M, y, FW, 20);

        y += 26;
        add(logScroll);     logScroll.setBounds(M, y, FW, HEIGHT_PX - y - 44);

        int by = HEIGHT_PX - 38;
        add(startBtn);      startBtn.setBounds(M, by, 130, 30);
        add(cancelBtn);     cancelBtn.setBounds(M + 140, by, 110, 30);
        add(clearBtn);      clearBtn.setBounds(M + 260, by, 110, 30);
    }

    private void buildOverlays() {
        // ---- inline confirmation (destructive write) ----
        confirmPanel.setOpaque(true);
        confirmPanel.setBackground(new Color(0x2A, 0x1E, 0x1E));
        confirmPanel.setBounds(M, 150, FW, 150);
        confirmLabel.setForeground(new Color(0xFF, 0xD9, 0xA0));
        confirmLabel.setHorizontalAlignment(SwingConstants.CENTER);
        confirmLabel.setBounds(10, 20, FW - 20, 70);
        confirmYes.setBackground(DANGER);
        confirmYes.setForeground(Color.WHITE);
        confirmYes.setBounds(FW / 2 - 190, 100, 180, 34);
        confirmNo.setBounds(FW / 2 + 10, 100, 180, 34);
        confirmPanel.add(confirmLabel);
        confirmPanel.add(confirmYes);
        confirmPanel.add(confirmNo);
        confirmYes.addActionListener(e -> {
            confirmPanel.setVisible(false);
            repaint();
            runWorker();
        });
        confirmNo.addActionListener(e -> {
            confirmPanel.setVisible(false);
            repaint();
        });
        confirmPanel.setVisible(false);
        add(confirmPanel);
        setComponentZOrder(confirmPanel, 0);

        // ---- in-panel file browser ----
        browserPanel.setOpaque(true);
        browserPanel.setBackground(new Color(0x22, 0x29, 0x35));
        browserPanel.setBounds(M, 44, FW, HEIGHT_PX - 88);
        browserPath.setForeground(FG);
        browserPath.setBounds(10, 8, FW - 20, 22);
        browserList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane js = new JScrollPane(browserList);
        js.setBounds(10, 34, FW - 20, browserPanel.getHeight() - 84);
        browserUp.setBounds(10, browserPanel.getHeight() - 42, 100, 30);
        browserSelect.setBounds(FW - 220, browserPanel.getHeight() - 42, 100, 30);
        browserCancel.setBounds(FW - 110, browserPanel.getHeight() - 42, 100, 30);
        browserPanel.add(browserPath);
        browserPanel.add(js);
        browserPanel.add(browserUp);
        browserPanel.add(browserSelect);
        browserPanel.add(browserCancel);
        browserPanel.setVisible(false);
        add(browserPanel);
        setComponentZOrder(browserPanel, 0);

        browserUp.addActionListener(e -> {
            File parent = browserDir.getParentFile();
            if (parent != null) {
                browserDir = parent;
                fillBrowser();
            }
        });
        browserCancel.addActionListener(e -> browserPanel.setVisible(false));
        browserSelect.addActionListener(e -> chooseBrowserSelection());
        browserList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    onBrowserActivate();
                }
            }
        });
    }

    private void styleLog() {
        logArea.setEditable(false);
        logArea.setBackground(new Color(0x11, 0x15, 0x1C));
        logArea.setForeground(new Color(0xCF, 0xD8, 0xE3));
        logArea.setCaretColor(FG);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setMargin(new Insets(4, 6, 4, 6));
        logScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
    }

    private void style(javax.swing.JComponent c) {
        c.setBackground(new Color(0x2B, 0x33, 0x40));
        c.setForeground(FG);
    }

    // ------------------------------------------------------------------- mode

    private MediaMode mode() {
        int i = modeCombo.getSelectedIndex();
        return MODES[i < 0 ? 0 : i];
    }

    /** Shows only the controls relevant to the selected mode. */
    private void applyMode() {
        MediaMode m = mode();
        boolean fileSrc = m == MediaMode.BURN_ISO || m == MediaMode.WRITE_USB;
        boolean clone = m == MediaMode.CLONE_DISC;
        boolean format = m == MediaMode.FORMAT_USB;
        boolean data = m == MediaMode.DATA_DISC;

        sourceLabel.setText(clone ? "From dev:" : (data ? "Folder:" : "Source:"));
        sourceField.setVisible(fileSrc || data);
        browseBtn.setVisible(fileSrc || data);
        srcDeviceCombo.setVisible(clone);

        targetLabel.setText(data ? "Burn to:" : "Target:");
        boolean targetUsed = !data || true; // data disc: optional burn drive
        targetCombo.setVisible(targetUsed);
        refreshBtn.setVisible(targetUsed);

        outLabel.setVisible(data);
        outField.setVisible(data);
        outBrowseBtn.setVisible(data);

        fsLabel.setVisible(format);
        fsCombo.setVisible(format);
        labelField.setVisible(format || data);
        labelField.setToolTipText(data ? "Volume label (ISO)" : "Filesystem label");

        speedLabel.setVisible(m == MediaMode.BURN_ISO || data);
        speedCombo.setVisible(m == MediaMode.BURN_ISO || data);

        verifyCheck.setVisible(!format);
        bootableCheck.setVisible(m == MediaMode.WRITE_USB);
        partitionCheck.setVisible(format);

        if (!data) {
            outField.setText("");
        }
        validate();
        repaint();
    }

    // ---------------------------------------------------------------- devices

    private void refreshDevices() {
        statusLabel.setText("Scanning devices…");
        new Thread(() -> {
            final List<DeviceInfo> all;
            try {
                all = engine.detectDevices();
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("Scan failed: " + ex.getMessage()));
                return;
            }
            SwingUtilities.invokeLater(() -> {
                DeviceInfo keepT = (DeviceInfo) targetCombo.getSelectedItem();
                DeviceInfo keepS = (DeviceInfo) srcDeviceCombo.getSelectedItem();
                DefaultComboBoxModel<DeviceInfo> targets = new DefaultComboBoxModel<>();
                DefaultComboBoxModel<DeviceInfo> sources = new DefaultComboBoxModel<>();
                int writable = 0;
                for (DeviceInfo d : all) {
                    if (d.isWritableTarget()) {
                        targets.addElement(d);
                        writable++;
                    }
                    // any readable device can be a clone source
                    sources.addElement(d);
                }
                targetCombo.setModel(targets);
                srcDeviceCombo.setModel(sources);
                if (keepT != null) {
                    targetCombo.setSelectedItem(keepT);
                }
                if (keepS != null) {
                    srcDeviceCombo.setSelectedItem(keepS);
                }
                statusLabel.setText(all.size() + " device(s), " + writable + " writable target(s)");
                applyMode();
            });
        }, "mw-scan").start();
    }

    private DeviceInfo target() {
        return (DeviceInfo) targetCombo.getSelectedItem();
    }

    private DeviceInfo sourceDevice() {
        return (DeviceInfo) srcDeviceCombo.getSelectedItem();
    }

    // ---------------------------------------------------------------- browser

    private void openSourceBrowser() {
        MediaMode m = mode();
        BrowserMode bm = (m == MediaMode.DATA_DISC) ? BrowserMode.FOLDER
                : (m == MediaMode.BURN_ISO ? BrowserMode.ISO_FILE : BrowserMode.IMAGE_FILE);
        openBrowser(bm, sourceField);
    }

    private JTextField browserTargetField;

    private void openBrowser(BrowserMode bm, JTextField target) {
        browserMode = bm;
        browserTargetField = target;
        String cur = target.getText();
        if (!cur.isEmpty()) {
            File f = new File(cur);
            browserDir = f.isDirectory() ? f
                    : (f.getParentFile() != null ? f.getParentFile() : browserDir);
        }
        fillBrowser();
        browserPanel.setVisible(true);
        browserPanel.requestFocusInWindow();
        repaint();
    }

    private void fillBrowser() {
        browserModel.clear();
        browserEntries.clear();
        browserPath.setText(browserDir.getAbsolutePath()
                + "   [" + describeBrowserMode() + "]");
        File[] kids = browserDir.listFiles();
        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        if (kids != null) {
            for (File f : kids) {
                if (f.isHidden()) {
                    continue;
                }
                if (f.isDirectory()) {
                    dirs.add(f);
                } else if (acceptsFiles() && matchesFilter(f)) {
                    files.add(f);
                }
            }
        }
        dirs.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        files.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        if (acceptsFiles()) {
            for (File f : files) {
                browserEntries.add(f);
                browserModel.addElement("    " + f.getName());
            }
        }
        for (File d : dirs) {
            browserEntries.add(d);
            browserModel.addElement("📁 " + d.getName());
        }
    }

    private String describeBrowserMode() {
        switch (browserMode) {
            case ISO_FILE:   return "select an .iso";
            case IMAGE_FILE: return "select an .iso/.img";
            case FOLDER:     return "select a folder";
            case OUTPUT_ISO: return "choose a save folder";
            default:         return "";
        }
    }

    private boolean acceptsFiles() {
        return browserMode == BrowserMode.ISO_FILE || browserMode == BrowserMode.IMAGE_FILE;
    }

    private boolean matchesFilter(File f) {
        String n = f.getName().toLowerCase();
        if (browserMode == BrowserMode.ISO_FILE) {
            return n.endsWith(".iso");
        }
        return n.endsWith(".iso") || n.endsWith(".img") || n.endsWith(".raw") || n.endsWith(".bin");
    }

    private File selectedEntry() {
        int i = browserList.getSelectedIndex();
        return (i >= 0 && i < browserEntries.size()) ? browserEntries.get(i) : null;
    }

    private void onBrowserActivate() {
        File f = selectedEntry();
        if (f == null) {
            return;
        }
        if (f.isDirectory()) {
            browserDir = f;
            fillBrowser();
        } else {
            chooseBrowserSelection();
        }
    }

    private void chooseBrowserSelection() {
        File f = selectedEntry();
        switch (browserMode) {
            case ISO_FILE:
            case IMAGE_FILE:
                if (f != null && f.isFile()) {
                    browserTargetField.setText(f.getAbsolutePath());
                    browserPanel.setVisible(false);
                }
                break;
            case FOLDER:
                browserTargetField.setText(browserDir.getAbsolutePath());
                browserPanel.setVisible(false);
                break;
            case OUTPUT_ISO:
                String label = labelField.getText().trim().replaceAll("[^A-Za-z0-9_-]", "_");
                String name = label.isEmpty() ? "lg3d-data.iso" : label + ".iso";
                browserTargetField.setText(new File(browserDir, name).getAbsolutePath());
                browserPanel.setVisible(false);
                break;
        }
        repaint();
    }

    // ------------------------------------------------------------ start/confirm

    private void confirmAndStart() {
        if (running) {
            return;
        }
        final String action;
        final DeviceInfo t;
        try {
            t = resolveTarget();
            action = describePlannedAction(t);
        } catch (MediaWriterEngine.MediaWriterException ex) {
            statusLabel.setText(ex.getMessage());
            appendLog("! " + ex.getMessage());
            return;
        }
        if (t == null) {
            // data disc with no burn target: no destruction, run directly
            runWorker();
            return;
        }
        confirmLabel.setText("<html><div style='text-align:center;width:"
                + (FW - 40) + "px'>" + action
                + "<br><br><b>This will permanently destroy all data on "
                + t.path + ".</b> This cannot be undone.</div></html>");
        confirmPanel.setVisible(true);
        setComponentZOrder(confirmPanel, 0);
        confirmPanel.repaint();
        repaint();
    }

    private DeviceInfo resolveTarget() {
        MediaMode m = mode();
        switch (m) {
            case BURN_ISO:
            case WRITE_USB:
            case FORMAT_USB:
                DeviceInfo d = target();
                if (d == null) {
                    throw new MediaWriterEngine.MediaWriterException("No target device selected");
                }
                return d;
            case CLONE_DISC:
                DeviceInfo dst = target();
                if (dst == null) {
                    throw new MediaWriterEngine.MediaWriterException("No destination selected");
                }
                return dst;
            case DATA_DISC:
                return null; // only destructive if a burn drive is chosen
            default:
                return null;
        }
    }

    private String describePlannedAction(DeviceInfo t) {
        switch (mode()) {
            case BURN_ISO:
                return "Burn <b>" + base(sourceField.getText()) + "</b> to optical drive " + t.path + ".";
            case WRITE_USB:
                return "Write image <b>" + base(sourceField.getText()) + "</b> to " + t.path
                        + (bootableCheck.isSelected() ? " (bootable)" : "") + ".";
            case FORMAT_USB:
                return "Format " + t.path + " as <b>" + fsCombo.getSelectedItem() + "</b>"
                        + (partitionCheck.isSelected() ? " with a new partition table" : "") + ".";
            case CLONE_DISC:
                DeviceInfo s = sourceDevice();
                return "Clone <b>" + (s == null ? "?" : s.path) + "</b> onto " + t.path + ".";
            default:
                return "Write media.";
        }
    }

    // ------------------------------------------------------------------ worker

    private void runWorker() {
        MediaMode m = mode();
        // pre-validate inputs on the EDT
        final String src = sourceField.getText().trim();
        final String out = outField.getText().trim();
        final DeviceInfo tgt = target();
        final DeviceInfo srcDev = sourceDevice();
        final Integer speed = parseSpeed();
        final boolean verify = verifyCheck.isSelected();
        final boolean bootable = bootableCheck.isSelected();
        final boolean partition = partitionCheck.isSelected();
        final String fs = (String) fsCombo.getSelectedItem();
        final String label = labelField.getText();

        try {
            switch (m) {
                case BURN_ISO:
                    requireFile(src, "Select an ISO file");
                    requireTarget(tgt);
                    break;
                case WRITE_USB:
                    requireFile(src, "Select an image file");
                    requireTarget(tgt);
                    break;
                case FORMAT_USB:
                    requireTarget(tgt);
                    break;
                case CLONE_DISC:
                    if (srcDev == null) {
                        throw new MediaWriterEngine.MediaWriterException("Select a source device");
                    }
                    requireTarget(tgt);
                    break;
                case DATA_DISC:
                    if (src.isEmpty() || !new File(src).isDirectory()) {
                        throw new MediaWriterEngine.MediaWriterException("Select a source folder");
                    }
                    break;
            }
        } catch (MediaWriterEngine.MediaWriterException ex) {
            statusLabel.setText(ex.getMessage());
            appendLog("! " + ex.getMessage());
            return;
        }

        setRunning(true);
        engine.resetCancel();
        ProgressHandler h = new UiHandler();
        Thread worker = new Thread(() -> {
            try {
                switch (m) {
                    case BURN_ISO:
                        engine.burnIsoToOptical(tgt, new File(src), speed, verify, h);
                        break;
                    case WRITE_USB:
                        engine.writeImageToUsb(tgt, new File(src), bootable, verify, h);
                        break;
                    case FORMAT_USB:
                        engine.formatUsb(tgt, fs, label, partition, h);
                        break;
                    case CLONE_DISC:
                        engine.cloneDisc(srcDev, tgt, verify, h);
                        break;
                    case DATA_DISC:
                        File outIso = out.isEmpty()
                                ? new File(System.getProperty("user.home", "/tmp"), "lg3d-data.iso")
                                : new File(out);
                        engine.createDataDisc(new File(src), outIso, label, tgt, speed, verify, h);
                        break;
                }
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Error: " + ex.getMessage());
                    appendLog("! " + ex.getMessage());
                    progressBar.setValue(0);
                    setRunning(false);
                });
            }
        }, "mw-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private Integer parseSpeed() {
        Object s = speedCombo.getSelectedItem();
        if (s == null || s.equals("Max")) {
            return null;
        }
        try {
            return Integer.parseInt(s.toString().replace("x", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void requireFile(String path, String msg) {
        if (path.isEmpty() || !new File(path).isFile()) {
            throw new MediaWriterEngine.MediaWriterException(msg);
        }
    }

    private void requireTarget(DeviceInfo d) {
        if (d == null) {
            throw new MediaWriterEngine.MediaWriterException("No writable target device selected");
        }
    }

    private void setRunning(boolean r) {
        running = r;
        startBtn.setEnabled(!r);
        cancelBtn.setEnabled(r);
        modeCombo.setEnabled(!r);
        refreshBtn.setEnabled(!r);
        if (r) {
            progressBar.setValue(0);
        }
    }

    // -------------------------------------------------------------- log/utils

    private void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static String base(String path) {
        if (path == null || path.isEmpty()) {
            return "(none)";
        }
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private String toolSummary() {
        List<String> have = new ArrayList<>();
        for (String t : Arrays.asList("dd", "wodim", "cdrecord", "growisofs",
                "xorriso", "genisoimage", "mkisofs", "isohybrid", "mkfs.vfat",
                "mkfs.ext4", "parted", "udisksctl", "pkexec")) {
            if (engine.hasTool(t)) {
                have.add(t);
            }
        }
        return have.isEmpty() ? "none detected (install wodim/xorriso/syslinux)"
                : String.join(", ", have);
    }

    /** Bridges engine callbacks (worker thread) onto the EDT for UI updates. */
    private final class UiHandler implements ProgressHandler {
        public void onLog(final String line) {
            SwingUtilities.invokeLater(() -> appendLog(line));
        }

        public void onProgress(final double fraction, final String stage) {
            SwingUtilities.invokeLater(() -> {
                if (fraction < 0) {
                    progressBar.setIndeterminate(true);
                    progressBar.setString(stage);
                } else {
                    progressBar.setIndeterminate(false);
                    int pct = (int) Math.round(fraction * 100);
                    progressBar.setValue(pct);
                    progressBar.setString(stage + " " + pct + "%");
                }
                statusLabel.setText(stage);
            });
        }

        public void onFinished(final boolean success, final String message) {
            SwingUtilities.invokeLater(() -> {
                progressBar.setIndeterminate(false);
                progressBar.setValue(success ? 100 : 0);
                progressBar.setString(success ? "Done" : "Failed");
                statusLabel.setText(message);
                statusLabel.setForeground(success ? FG : new Color(0xFF, 0x9A, 0x9A));
                appendLog((success ? "✓ " : "✗ ") + message);
                setRunning(false);
                refreshDevices();
            });
        }
    }
}
