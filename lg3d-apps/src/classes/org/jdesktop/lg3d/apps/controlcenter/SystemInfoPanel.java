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
package org.jdesktop.lg3d.apps.controlcenter;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Timer;
import org.jdesktop.lg3d.utils.system.ProcessService;
import org.jdesktop.lg3d.utils.system.SystemInfoService;

/**
 * System / About panel backed by {@link SystemInfoService}: CPU, memory,
 * per-mount disk usage, kernel, distro, hostname, uptime, session and user,
 * with live-updating CPU and memory gauges and a manual Refresh.
 */
public class SystemInfoPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JTextArea text = new JTextArea();
    private final JProgressBar cpuBar = new JProgressBar(0, 100);
    private final JProgressBar memBar = new JProgressBar(0, 100);
    private final JLabel cpuLabel = new JLabel("CPU 0%");
    private final JLabel memLabel = new JLabel("Memory 0%");
    private final Timer timer;

    public SystemInfoPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        text.setEditable(false);
        text.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));

        cpuBar.setStringPainted(false);
        memBar.setStringPainted(false);
        JPanel gauges = new JPanel(new GridLayout(2, 2, 8, 4));
        gauges.add(cpuLabel);
        gauges.add(cpuBar);
        gauges.add(memLabel);
        gauges.add(memBar);

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refresh());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        south.add(refresh);

        root.add(gauges, BorderLayout.NORTH);
        root.add(new JScrollPane(text), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        timer = new Timer(2000, e -> updateGauges());
        refresh();
    }

    @Override
    public String displayName() {
        return "System";
    }

    @Override
    public javax.swing.Icon icon() {
        return null;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void onShow() {
        refresh();
        timer.start();
    }

    @Override
    public void onHide() {
        timer.stop();
    }

    // ------------------------------------------------------------------

    private void refresh() {
        updateGauges();
        StringBuilder sb = new StringBuilder();
        SystemInfoService.Cpu cpu = SystemInfoService.cpu();
        sb.append("Hostname:   ").append(SystemInfoService.hostname()).append('\n');
        sb.append("Distro:     ").append(SystemInfoService.distro()).append('\n');
        sb.append("Kernel:     ").append(SystemInfoService.kernelVersion())
                .append(" (").append(SystemInfoService.architecture()).append(")\n");
        sb.append("Session:    ").append(SystemInfoService.sessionType());
        String desktop = SystemInfoService.currentDesktop();
        if (desktop != null && !desktop.isBlank()) {
            sb.append(" (").append(desktop).append(")");
        }
        sb.append('\n');
        sb.append("User:       ").append(SystemInfoService.currentUser()).append('\n');
        sb.append("Uptime:     ").append(
                ProcessService.formatUptime(SystemInfoService.uptimeSeconds())).append('\n');
        double[] load = SystemInfoService.loadAverage();
        if (load != null && load.length >= 3) {
            sb.append("Load avg:   ").append(String.format("%.2f  %.2f  %.2f",
                    load[0], load[1], load[2])).append('\n');
        }
        sb.append('\n');
        sb.append("CPU:        ").append(cpu.getModel()).append('\n');
        sb.append("Cores:      ").append(cpu.getLogicalCores()).append(" logical")
                .append("  (").append(cpu.getVendor()).append(")\n");
        sb.append('\n');

        SystemInfoService.Memory mem = SystemInfoService.memory();
        sb.append("Memory:     ").append(ProcessService.formatBytes(mem.getUsedBytes()))
                .append(" used of ").append(ProcessService.formatBytes(mem.getTotalBytes()))
                .append(String.format(" (%.0f%%)%n", mem.getUsedPercent()));
        sb.append("Swap:       ").append(ProcessService.formatBytes(mem.getSwapUsedKb() * 1024L))
                .append(" used of ").append(ProcessService.formatBytes(mem.getSwapTotalKb() * 1024L))
                .append('\n');
        sb.append('\n');

        sb.append("Filesystems:\n");
        List<SystemInfoService.Disk> disks = SystemInfoService.disks();
        if (disks.isEmpty()) {
            sb.append("  (none reported)\n");
        }
        for (SystemInfoService.Disk d : disks) {
            sb.append(String.format("  %-22s %-18s %8s  %5.0f%% used%n",
                    d.getMountPoint(), d.getFsType(),
                    ProcessService.formatBytes(d.getTotalBytes()), d.getUsedPercent()));
        }
        text.setText(sb.toString());
        text.setCaretPosition(0);
    }

    private void updateGauges() {
        double cpu = ProcessService.totalCpuPercent();
        SystemInfoService.Memory mem = SystemInfoService.memory();
        int cpuPct = (int) Math.round(Math.max(0, Math.min(100, cpu)));
        int memPct = (int) Math.round(Math.max(0, Math.min(100, mem.getUsedPercent())));
        cpuBar.setValue(cpuPct);
        memBar.setValue(memPct);
        cpuLabel.setText("CPU " + cpuPct + "%");
        memLabel.setText("Memory " + memPct + "%  ("
                + ProcessService.formatBytes(mem.getUsedBytes()) + " / "
                + ProcessService.formatBytes(mem.getTotalBytes()) + ")");
    }
}
