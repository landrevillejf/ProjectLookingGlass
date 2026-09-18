/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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

import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.jdesktop.lg3d.apps.uikit.Button3D;
import org.jdesktop.lg3d.apps.uikit.Gauge3D;
import org.jdesktop.lg3d.apps.uikit.Ui3D;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.system.ProcessService;
import org.jdesktop.lg3d.utils.system.SystemInfoService;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Color4f;

/**
 * The System page: live CPU/memory gauges plus host, kernel, session, load
 * and filesystem details from {@link SystemInfoService}, refreshed every two
 * seconds while the page is shown ({@code onShow}/{@code onHide} gate the
 * timer). Sampling happens off the scene thread; the UI is updated on the
 * EDT.
 */
public class SystemPage3D implements ControlPanel {

    private static final int REFRESH_MS = 2000;
    private static final int INFO_LINES = 17;
    private static final int DISK_LINES = 5;

    private static final Color4f WARN = new Color4f(0.95f, 0.75f, 0.40f, 1.0f);

    private Component3D root;
    private Gauge3D cpuGauge;
    private Gauge3D memGauge;
    private GlassyText2D cpuLabel;
    private GlassyText2D memLabel;
    private final GlassyText2D[] info = new GlassyText2D[INFO_LINES];
    private final Timer timer;
    private boolean sampling;

    public SystemPage3D() {
        timer = new Timer(REFRESH_MS, e -> refresh());
    }

    @Override
    public String displayName() {
        return "System";
    }

    @Override
    public Component3D component(float w, float h) {
        if (root != null) {
            return root;
        }
        root = new Component3D();

        float pad = h * 0.02f;
        float gaugeW = w * 0.4f;
        float gaugeH = h * 0.035f;
        float labelH = h * 0.045f;

        // ---- gauges + captions across the top ----
        float labelY = h * 0.5f - labelH;
        float gaugeY = labelY - gaugeH * 1.6f;
        float lx = -w * 0.5f + pad;
        float rx = w * 0.5f - pad - gaugeW;

        cpuLabel = Ui3D.makeText("CPU", gaugeW, labelH, Ui3D.TEXT_BRIGHT,
                GlassyText2D.Alignment.LEFT);
        root.addChild(Ui3D.component(Ui3D.at(cpuLabel, lx, labelY - labelH * 0.5f, 0.001f)));
        cpuGauge = new Gauge3D(gaugeW, gaugeH, Ui3D.TRACK, Ui3D.FILL);
        cpuGauge.setTranslation(lx + gaugeW * 0.5f, gaugeY, 0.001f);
        root.addChild(cpuGauge);

        memLabel = Ui3D.makeText("Memory", gaugeW, labelH, Ui3D.TEXT_BRIGHT,
                GlassyText2D.Alignment.LEFT);
        root.addChild(Ui3D.component(Ui3D.at(memLabel, rx, labelY - labelH * 0.5f, 0.001f)));
        memGauge = new Gauge3D(gaugeW, gaugeH, Ui3D.TRACK, Ui3D.FILL);
        memGauge.setTranslation(rx + gaugeW * 0.5f, gaugeY, 0.001f);
        root.addChild(memGauge);

        // ---- refresh button (top-right corner of the page) ----
        float btnH = h * 0.07f;
        Button3D refreshBtn = new Button3D("Refresh", w * 0.12f, btnH, btnH * 0.4f,
                Ui3D.BUTTON_OFF, Ui3D.BUTTON_ON, Ui3D.TEXT_BRIGHT,
                new ActionNoArg() {
                    public void performAction(LgEventSource s) {
                        refresh();
                    }
                });
        refreshBtn.setTranslation(w * 0.5f - pad - w * 0.06f,
                h * 0.5f - btnH * 0.5f, 0.001f);
        root.addChild(refreshBtn);

        // ---- information lines ----
        float lineTop = gaugeY - gaugeH - h * 0.03f;
        float lineBottom = -h * 0.5f + pad;
        float lineH = (lineTop - lineBottom) / INFO_LINES;
        float textH = lineH * 0.72f;
        for (int i = 0; i < INFO_LINES; i++) {
            info[i] = Ui3D.makeText("", w * 0.96f, textH,
                    i == 0 ? WARN : Ui3D.TEXT_DIM, GlassyText2D.Alignment.LEFT);
            root.addChild(Ui3D.component(Ui3D.at(info[i],
                    -w * 0.5f + pad, lineTop - i * lineH - lineH * 0.5f - textH * 0.5f,
                    0.001f)));
        }

        refresh();
        return root;
    }

    @Override
    public void onShow() {
        if (!timer.isRunning()) {
            timer.start();
            refresh();
        }
    }

    @Override
    public void onHide() {
        timer.stop();
    }

    // ------------------------------------------------------------------

    /** Samples the system services off the scene thread, applies on the EDT. */
    private void refresh() {
        if (sampling || root == null) {
            return;
        }
        sampling = true;
        Thread sampler = new Thread(() -> {
            List<String> lines;
            double cpuPct;
            SystemInfoService.Memory mem;
            try {
                cpuPct = ProcessService.totalCpuPercent();
                mem = SystemInfoService.memory();
                lines = buildLines(mem);
            } catch (RuntimeException ex) {
                sampling = false;
                return;
            }
            final List<String> ls = lines;
            final double cp = cpuPct;
            final SystemInfoService.Memory m = mem;
            SwingUtilities.invokeLater(() -> {
                sampling = false;
                apply(ls, cp, m);
            });
        }, "SystemPage3D:sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    private List<String> buildLines(SystemInfoService.Memory mem) {
        List<String> ls = new ArrayList<>();
        String session = SystemInfoService.sessionType();
        String desktop = SystemInfoService.currentDesktop();
        if (desktop != null && !desktop.isBlank()) {
            session = session + " (" + desktop + ")";
        }
        double[] load = SystemInfoService.loadAverage();
        String loadStr = (load != null && load.length >= 3)
                ? String.format("%.2f  %.2f  %.2f", load[0], load[1], load[2]) : "n/a";
        SystemInfoService.Cpu cpu = SystemInfoService.cpu();

        ls.add("Host:      " + SystemInfoService.hostname()
                + "     User: " + SystemInfoService.currentUser());
        ls.add("Distro:    " + SystemInfoService.distro());
        ls.add("Kernel:    " + SystemInfoService.kernelVersion()
                + " (" + SystemInfoService.architecture() + ")");
        ls.add("Session:   " + session);
        ls.add("Uptime:    " + ProcessService.formatUptime(SystemInfoService.uptimeSeconds())
                + "     Load avg: " + loadStr);
        ls.add("");
        ls.add("CPU:       " + cpu.getModel());
        ls.add("Cores:     " + cpu.getLogicalCores() + " logical  (" + cpu.getVendor() + ")");
        ls.add("Memory:    " + ProcessService.formatBytes(mem.getUsedBytes()) + " used of "
                + ProcessService.formatBytes(mem.getTotalBytes())
                + String.format(" (%.0f%%)", mem.getUsedPercent()));
        ls.add("Swap:      " + ProcessService.formatBytes(mem.getSwapUsedKb() * 1024L) + " used of "
                + ProcessService.formatBytes(mem.getSwapTotalKb() * 1024L));
        ls.add("");
        ls.add("Filesystems:");
        List<SystemInfoService.Disk> disks = SystemInfoService.disks();
        if (disks.isEmpty()) {
            ls.add("  (none reported)");
        }
        int shown = 0;
        for (SystemInfoService.Disk d : disks) {
            if (shown++ >= DISK_LINES) {
                ls.add("  ... " + (disks.size() - DISK_LINES) + " more");
                break;
            }
            ls.add(String.format("  %-20s %-14s %9s  %3.0f%% used",
                    d.getMountPoint(), d.getFsType(),
                    ProcessService.formatBytes(d.getTotalBytes()), d.getUsedPercent()));
        }
        return ls;
    }

    private void apply(List<String> lines, double cpuPct, SystemInfoService.Memory mem) {
        int cpuPctI = (int) Math.round(Math.max(0, Math.min(100, cpuPct)));
        int memPctI = (int) Math.round(Math.max(0, Math.min(100, mem.getUsedPercent())));
        cpuGauge.setFill(cpuPctI / 100.0f);
        memGauge.setFill(memPctI / 100.0f);
        cpuLabel.setText("CPU  " + cpuPctI + "%");
        memLabel.setText("Memory  " + memPctI + "%   ("
                + ProcessService.formatBytes(mem.getUsedBytes()) + " / "
                + ProcessService.formatBytes(mem.getTotalBytes()) + ")");
        for (int i = 0; i < info.length; i++) {
            info[i].setText(i < lines.size() ? lines.get(i) : "");
        }
    }
}
