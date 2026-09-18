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
package org.jdesktop.lg3d.apps.taskmanager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.jdesktop.lg3d.apps.uikit.Button3D;
import org.jdesktop.lg3d.apps.uikit.ScrollList3D;
import org.jdesktop.lg3d.apps.uikit.Ui3D;
import org.jdesktop.lg3d.scenemanager.utils.taskbar.Taskbar;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.system.ProcessService;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Vector3f;

/**
 * The Task Manager application window: a {@link Frame3D} with a 100%
 * lg3d-native 3D process monitor (no SwingNode), backed by the core
 * {@link ProcessService} (/proc + {@code ps} parsing).
 *
 * <pre>
 *   +-------------------------------------------------------------+
 *   | Task Manager                              [ _ ][ X ] (deco) |
 *   | CPU 12% | Memory 5.1 GB / 31.3 GB (16%) | Load ... | up ... |
 *   | [By CPU][By MEM][By NAME]  [End Task][Force Quit][Refresh]  |
 *   | PID NAME                       CPU     MEMORY        STATE  |
 *   +-------------------------------------------------------------+
 *   | 1204 java3d-desktop            8.2%    412.0 MB     Running  |
 *   | ...                     (wheel scrolls, click selects)      |
 *   +-------------------------------------------------------------+
 *   | SIGTERM sent to pid 1204                                    |
 *   +-------------------------------------------------------------+
 * </pre>
 *
 * <p>The snapshot refreshes every two seconds while the window is enabled and
 * visible; {@link #setEnabled}/{@link #setVisible} gate the timer so a closed
 * or minimized window stops polling /proc. Sorting is by CPU, memory or name;
 * End Task sends SIGTERM and Force Quit SIGKILL through
 * {@code ProcessService} (with its pkexec fallback for foreign processes).</p>
 */
public class TaskManagerFrame3D extends Frame3D {

    private static final Color4f WINDOW_BG = new Color4f(0.05f, 0.07f, 0.11f, 0.55f);
    private static final int REFRESH_MS = 2000;
    private static final int ROW_CAP = 300;

    private static final int SORT_CPU = 0;
    private static final int SORT_MEM = 1;
    private static final int SORT_NAME = 2;

    private final GlassyText2D headerText;
    private final GlassyText2D statusText;
    private final ScrollList3D list;
    private final Button3D[] sortButtons = new Button3D[3];

    private final float rowW;
    private final float rowH;
    private final float textH;

    private final Timer refreshTimer;
    private boolean refreshing;
    private int sortMode = SORT_CPU;

    private List<ProcessService.ProcessInfo> procs = new ArrayList<>();
    private long selectedPid = -1;
    private GlassyPanel selectedBg;

    public TaskManagerFrame3D() {
        setName("Task Manager");

        // Match the window aspect to the usable screen area (above the
        // taskbar) so the decoration's aspect-preserving maximize fills the
        // viewport on both axes.
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        float usableH = tk.getScreenHeight() - Taskbar.getReservedBottomHeight();
        float H = usableH * 0.58f;
        float W = H * tk.getScreenWidth() / usableH;
        setPreferredSize(new Vector3f(W, H, 0.01f));

        // ---- layout metrics (origin at window centre, +x right, +y up) ----
        float pad = Math.min(W, H) * 0.022f;
        float topMargin = H * 0.075f;   // clear of the decoration buttons
        float headH = H * 0.055f;
        float toolH = H * 0.085f;
        float colH = H * 0.045f;
        float statusH = H * 0.05f;

        float contentTop = H * 0.5f - topMargin;
        float contentBottom = -H * 0.5f + pad;
        float xLeft = -W * 0.5f + pad;

        float statusCY = contentBottom + statusH * 0.5f;
        float listBottom = contentBottom + statusH + pad * 0.6f;
        float headCY = contentTop - headH * 0.5f;
        float toolCY = headCY - headH * 0.5f - pad * 0.4f - toolH * 0.5f;
        float colCY = toolCY - toolH * 0.5f - pad * 0.4f - colH * 0.5f;
        float listTop = colCY - colH * 0.5f - pad * 0.4f;
        float listCY = (listTop + listBottom) * 0.5f;
        float listH = listTop - listBottom;

        rowW = W - 2 * pad;
        rowH = listH / 12.5f;
        textH = rowH * 0.44f;

        // ---- window backdrop ----
        addChild(Ui3D.component(
                Ui3D.at(Ui3D.panel(W, H, 0.006f, WINDOW_BG), 0f, 0f, -0.008f)));

        // ---- title ----
        float titleH = topMargin * 0.46f;
        addChild(Ui3D.component(Ui3D.label("Task Manager", W * 0.5f, titleH,
                Ui3D.TEXT_BRIGHT, GlassyText2D.Alignment.LEFT,
                xLeft, H * 0.5f - topMargin * 0.62f, 0.002f)));

        // ---- aggregate load header ----
        headerText = Ui3D.makeText("Sampling...", W * 0.96f, headH,
                Ui3D.TEXT_ACCENT, GlassyText2D.Alignment.LEFT);
        addChild(Ui3D.component(
                Ui3D.at(headerText, xLeft, headCY - headH * 0.5f, 0.002f)));

        // ---- sort + action buttons ----
        String[] names = {"By CPU", "By MEM", "By Name", "End Task", "Force Quit", "Refresh"};
        ActionNoArg[] acts = {
            new ActionNoArg() { public void performAction(LgEventSource s) { setSortMode(SORT_CPU); } },
            new ActionNoArg() { public void performAction(LgEventSource s) { setSortMode(SORT_MEM); } },
            new ActionNoArg() { public void performAction(LgEventSource s) { setSortMode(SORT_NAME); } },
            new ActionNoArg() { public void performAction(LgEventSource s) { endTask(false); } },
            new ActionNoArg() { public void performAction(LgEventSource s) { endTask(true); } },
            new ActionNoArg() { public void performAction(LgEventSource s) { refresh(); } },
        };
        float slot = rowW / names.length;
        for (int i = 0; i < names.length; i++) {
            Button3D b = new Button3D(names[i], slot * 0.92f, toolH * 0.72f,
                    toolH * 0.34f, Ui3D.BUTTON_OFF, Ui3D.BUTTON_ON, Ui3D.TEXT_BRIGHT, acts[i]);
            b.setTranslation(xLeft + (i + 0.5f) * slot, toolCY, 0.002f);
            addChild(b);
            if (i < 3) {
                sortButtons[i] = b;
            }
        }
        sortButtons[sortMode].setLit(true);

        // ---- column header ----
        addChild(Ui3D.component(Ui3D.label("PID  NAME", rowW * 0.5f, colH,
                Ui3D.TEXT_DIM, GlassyText2D.Alignment.LEFT, xLeft, colCY, 0.002f)));
        addChild(Ui3D.component(Ui3D.label("CPU  MEMORY  STATE", rowW * 0.45f, colH,
                Ui3D.TEXT_DIM, GlassyText2D.Alignment.RIGHT,
                xLeft + rowW, colCY, 0.002f)));

        // ---- process list ----
        list = new ScrollList3D(rowW, listH, rowH, Ui3D.PANEL_BG);
        list.setTranslation(0f, listCY, 0.002f);
        addChild(list);

        // ---- status line ----
        statusText = Ui3D.makeText("", W * 0.95f, statusH * 0.52f, Ui3D.TEXT_DIM,
                GlassyText2D.Alignment.LEFT);
        addChild(Ui3D.component(
                Ui3D.at(statusText, xLeft, statusCY - statusH * 0.26f, 0.002f)));

        refreshTimer = new Timer(REFRESH_MS, e -> refresh());
        refreshTimer.start();
        refresh();
    }

    /** The refresh poll only runs while the window is enabled and visible. */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        updateTimer();
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        updateTimer();
    }

    private void updateTimer() {
        if (refreshTimer != null) {
            boolean live = isEnabled() && isVisible();
            if (live && !refreshTimer.isRunning()) {
                refreshTimer.start();
                refresh();
            } else if (!live && refreshTimer.isRunning()) {
                refreshTimer.stop();
            }
        }
    }

    // ------------------------------------------------------------------
    // Sampling

    /** Snapshots /proc off the scene thread, then applies on the EDT. */
    private void refresh() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        Thread sampler = new Thread(() -> {
            List<ProcessService.ProcessInfo> snapshot;
            ProcessService.SystemLoad load;
            try {
                snapshot = ProcessService.snapshot();
                load = ProcessService.systemLoad();
            } catch (RuntimeException ex) {
                refreshing = false;
                return;
            }
            final List<ProcessService.ProcessInfo> s = snapshot;
            final ProcessService.SystemLoad l = load;
            SwingUtilities.invokeLater(() -> {
                refreshing = false;
                apply(s, l);
            });
        }, "TaskManagerFrame3D:sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    private void apply(List<ProcessService.ProcessInfo> snapshot,
            ProcessService.SystemLoad load) {
        double[] la = load.getLoadAverage();
        headerText.setText(String.format(
                "CPU %.0f%%   |   Memory %s / %s (%.0f%%)   |   Load %s   |   Up %s   |   %d processes",
                load.getCpuPercent(),
                ProcessService.formatBytes(load.getMemUsedKb() * 1024L),
                ProcessService.formatBytes(load.getMemTotalKb() * 1024L),
                load.getMemUsedPercent(),
                (la != null && la.length >= 3)
                        ? String.format("%.2f %.2f %.2f", la[0], la[1], la[2]) : "n/a",
                ProcessService.formatUptime(load.getUptimeSeconds()),
                load.getProcessCount()));

        procs = new ArrayList<>(snapshot);
        if (procs.size() > ROW_CAP) {
            procs = new ArrayList<>(procs.subList(0, ROW_CAP));
        }
        Comparator<ProcessService.ProcessInfo> cmp;
        switch (sortMode) {
            case SORT_MEM:
                cmp = Comparator.comparingLong(ProcessService.ProcessInfo::getRssBytes).reversed();
                break;
            case SORT_NAME:
                cmp = Comparator.comparing(ProcessService.ProcessInfo::getName,
                        String.CASE_INSENSITIVE_ORDER);
                break;
            default:
                cmp = Comparator.comparingDouble(ProcessService.ProcessInfo::getCpuPercent).reversed();
                break;
        }
        procs.sort(cmp);

        List<Component3D> rows = new ArrayList<>();
        for (ProcessService.ProcessInfo p : procs) {
            rows.add(makeRow(p));
        }
        list.setRows(rows);
        if (selectedPid >= 0) {
            statusText.setText("Selected pid " + selectedPid);
        }
    }

    /** One process row: pid+name left, cpu/mem/state right; click selects. */
    private Component3D makeRow(ProcessService.ProcessInfo p) {
        Component3D row = new Component3D();
        GlassyPanel bg = Ui3D.panel(rowW, rowH * 0.9f, 0.002f,
                p.getPid() == selectedPid ? Ui3D.ROW_ON : Ui3D.ROW_OFF);
        bg.setCapability(Shape3D.ALLOW_APPEARANCE_READ);
        bg.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
        row.addChild(Ui3D.component(Ui3D.at(bg, 0f, 0f, -0.001f)));

        float rxLeft = -rowW * 0.5f;
        String left = String.format("%-7d %s", p.getPid(), p.getName());
        row.addChild(Ui3D.component(Ui3D.label(left, rowW * 0.52f, textH,
                Ui3D.TEXT_BRIGHT, GlassyText2D.Alignment.LEFT,
                rxLeft + rowH * 0.4f, 0f, 0.001f)));

        String right = String.format("%5.1f%%  %9s  %s",
                p.getCpuPercent(),
                ProcessService.formatBytes(p.getRssBytes()),
                p.getStateLabel());
        row.addChild(Ui3D.component(Ui3D.label(right, rowW * 0.44f, textH,
                Ui3D.TEXT_DIM, GlassyText2D.Alignment.RIGHT,
                rowW * 0.5f - rowH * 0.4f, 0f, 0.001f)));

        final long pid = p.getPid();
        row.addListener(new MouseClickedEventAdapter(new ActionNoArg() {
            public void performAction(LgEventSource s) {
                select(bg, pid, p);
            }
        }));
        if (p.getPid() == selectedPid) {
            selectedBg = bg;
        }
        return row;
    }

    private void select(GlassyPanel bg, long pid, ProcessService.ProcessInfo p) {
        if (selectedBg != null && selectedBg != bg) {
            selectedBg.setAppearance(Ui3D.appearance(Ui3D.ROW_OFF));
        }
        selectedPid = pid;
        selectedBg = bg;
        bg.setAppearance(Ui3D.appearance(Ui3D.ROW_ON));
        statusText.setText(String.format("Selected: pid %d %s (%s, %s, %d threads)",
                pid, p.getName(), p.getUser(),
                ProcessService.formatBytes(p.getRssBytes()), p.getThreads()));
    }

    private void setSortMode(int mode) {
        if (sortMode == mode) {
            return;
        }
        sortButtons[sortMode].setLit(false);
        sortMode = mode;
        sortButtons[sortMode].setLit(true);
        // Re-order immediately from the cached snapshot.
        refresh();
    }

    // ------------------------------------------------------------------
    // Signals

    private void endTask(boolean force) {
        if (selectedPid < 0) {
            statusText.setText("Select a process row first.");
            return;
        }
        ProcessService.TerminateResult r = force
                ? ProcessService.kill(selectedPid)
                : ProcessService.terminate(selectedPid);
        statusText.setText((force ? "SIGKILL" : "SIGTERM") + " to pid " + selectedPid
                + ": " + r.getKind()
                + (r.getMessage() == null || r.getMessage().isEmpty() ? "" : " - " + r.getMessage()));
        refresh();
    }
}
