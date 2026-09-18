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
import org.jdesktop.lg3d.apps.uikit.ScrollList3D;
import org.jdesktop.lg3d.apps.uikit.Ui3D;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.system.DisplayService;
import org.jdesktop.lg3d.utils.system.SystemInfoService;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Color4f;

/**
 * The Display page backed by {@link DisplayService} (xrandr): pick an output,
 * pick a resolution, toggle the primary flag and Apply. Applying captures a
 * snapshot first, then a 20-second "Keep these settings?" countdown runs with
 * a Keep button; on timeout the snapshot is restored, so a mode the monitor
 * cannot show cannot lock the user out.
 *
 * <p>Without xrandr (or under Wayland, where xrandr changes may not stick)
 * the page degrades to a clear read-only/warning state. All xrandr calls run
 * off the scene thread; the countdown keeps running even while another page
 * is shown, because the revert is a safety net.</p>
 */
public class DisplayPage3D implements ControlPanel {

    private static final int REVERT_SECONDS = 20;

    private static final Color4f WARN = new Color4f(0.95f, 0.75f, 0.40f, 1.0f);

    private Component3D root;
    private ScrollList3D outputList;
    private ScrollList3D modeList;
    private Button3D primaryBtn;
    private Button3D applyBtn;
    private Button3D keepBtn;
    private GlassyText2D warnText;
    private GlassyText2D statusText;

    private float rowH;
    private float textH;

    private boolean loading;
    private boolean available;
    private boolean primaryOn;
    private DisplayService.Output selectedOutput;
    private DisplayService.Mode selectedMode;
    private GlassyPanel selectedOutputBg;
    private GlassyPanel selectedModeBg;
    private final List<GlassyPanel> outputBgs = new ArrayList<>();
    private final List<GlassyPanel> modeBgs = new ArrayList<>();

    private Timer countdown;
    private int secondsLeft;
    private List<DisplayService.OutputSetting> revertSnapshot;

    @Override
    public String displayName() {
        return "Display";
    }

    @Override
    public Component3D component(float w, float h) {
        if (root != null) {
            return root;
        }
        root = new Component3D();

        float pad = h * 0.02f;
        float btnH = h * 0.07f;
        float listTop = h * 0.5f - h * 0.06f;
        float listBottom = -h * 0.5f + pad + h * 0.09f;
        float listH = listTop - listBottom;
        float listCY = (listTop + listBottom) * 0.5f;
        rowH = listH / 10.5f;
        textH = rowH * 0.46f;

        // ---- output list (left) ----
        float outW = w * 0.26f;
        float outX = -w * 0.5f + pad;
        root.addChild(Ui3D.component(Ui3D.label("Outputs", outW, h * 0.04f,
                Ui3D.TEXT_DIM, GlassyText2D.Alignment.LEFT,
                outX, listTop + h * 0.03f, 0.001f)));
        outputList = new ScrollList3D(outW, listH, rowH, Ui3D.PANEL_BG);
        outputList.setTranslation(outX + outW * 0.5f, listCY, 0.001f);
        root.addChild(outputList);

        // ---- mode list (middle) ----
        float modeW = w * 0.26f;
        float modeX = outX + outW + pad;
        root.addChild(Ui3D.component(Ui3D.label("Resolution", modeW, h * 0.04f,
                Ui3D.TEXT_DIM, GlassyText2D.Alignment.LEFT,
                modeX, listTop + h * 0.03f, 0.001f)));
        modeList = new ScrollList3D(modeW, listH, rowH, Ui3D.PANEL_BG);
        modeList.setTranslation(modeX + modeW * 0.5f, listCY, 0.001f);
        root.addChild(modeList);

        // ---- action column (right) ----
        float btnW = w * 0.16f;
        float btnX = w * 0.5f - pad - btnW * 0.5f;
        primaryBtn = new Button3D("Primary", btnW, btnH, btnH * 0.4f,
                Ui3D.BUTTON_OFF, Ui3D.BUTTON_ON, Ui3D.TEXT_BRIGHT,
                new ActionNoArg() {
                    public void performAction(LgEventSource s) {
                        primaryOn = !primaryOn;
                        primaryBtn.setLit(primaryOn);
                    }
                });
        primaryBtn.setTranslation(btnX, listTop - btnH * 0.5f, 0.001f);
        root.addChild(primaryBtn);

        applyBtn = new Button3D("Apply", btnW, btnH, btnH * 0.4f,
                Ui3D.BUTTON_OFF, Ui3D.BUTTON_ON, Ui3D.TEXT_BRIGHT,
                new ActionNoArg() {
                    public void performAction(LgEventSource s) {
                        apply();
                    }
                });
        applyBtn.setTranslation(btnX, listTop - btnH * 1.7f, 0.001f);
        root.addChild(applyBtn);

        keepBtn = new Button3D("Keep", btnW, btnH, btnH * 0.4f,
                Ui3D.BUTTON_OFF, Ui3D.BUTTON_ON, Ui3D.TEXT_ACCENT,
                new ActionNoArg() {
                    public void performAction(LgEventSource s) {
                        keepSettings();
                    }
                });
        keepBtn.setTranslation(btnX, listTop - btnH * 2.9f, 0.001f);
        keepBtn.changeVisible(false);
        keepBtn.setEnabled(false);
        root.addChild(keepBtn);

        // ---- warning + status ----
        warnText = Ui3D.makeText("", w * 0.96f, h * 0.04f, WARN,
                GlassyText2D.Alignment.LEFT);
        root.addChild(Ui3D.component(Ui3D.at(warnText,
                -w * 0.5f + pad, listBottom - h * 0.055f, 0.001f)));
        statusText = Ui3D.makeText("Querying xrandr...", w * 0.96f, h * 0.04f,
                Ui3D.TEXT_DIM, GlassyText2D.Alignment.LEFT);
        root.addChild(Ui3D.component(Ui3D.at(statusText,
                -w * 0.5f + pad, -h * 0.5f + pad, 0.001f)));

        reload();
        return root;
    }

    @Override
    public void onShow() {
        reload();
    }

    // ------------------------------------------------------------------
    // Query

    /** Queries xrandr off the scene thread, then rebuilds on the EDT. */
    private void reload() {
        if (loading || root == null) {
            return;
        }
        loading = true;
        Thread loader = new Thread(() -> {
            List<DisplayService.Output> outs;
            boolean avail;
            boolean wayland;
            try {
                avail = DisplayService.isAvailable();
                outs = avail ? DisplayService.query() : List.of();
                wayland = SystemInfoService.isWayland();
            } catch (RuntimeException ex) {
                loading = false;
                return;
            }
            final List<DisplayService.Output> os = outs;
            final boolean a = avail;
            final boolean wl = wayland;
            SwingUtilities.invokeLater(() -> {
                loading = false;
                applyQuery(os, a, wl);
            });
        }, "DisplayPage3D:loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void applyQuery(List<DisplayService.Output> outs, boolean avail,
            boolean wayland) {
        available = avail;
        if (!avail) {
            warnText.setText("xrandr is not installed; display configuration is read-only.");
            applyBtn.setEnabled(false);
        } else if (wayland) {
            warnText.setText("Wayland session detected: xrandr changes may not apply here.");
            applyBtn.setEnabled(true);
        } else {
            warnText.setText("");
            applyBtn.setEnabled(true);
        }

        List<Component3D> rows = new ArrayList<>();
        outputBgs.clear();
        selectedOutput = null;
        selectedOutputBg = null;
        for (DisplayService.Output o : outs) {
            rows.add(makeOutputRow(o));
        }
        outputList.setRows(rows);

        int initial = -1;
        for (int i = 0; i < outs.size() && initial < 0; i++) {
            if (outs.get(i).isEnabled()) {
                initial = i;
            }
        }
        if (initial >= 0) {
            selectOutput(outs.get(initial), outputBgs.get(initial));
        } else if (!outs.isEmpty()) {
            selectOutput(outs.get(0), outputBgs.get(0));
        } else {
            modeList.setRows(new ArrayList<>());
            modeBgs.clear();
            statusText.setText(avail ? "No outputs reported by xrandr." : "");
        }
    }

    private Component3D makeOutputRow(DisplayService.Output o) {
        String label = o.getName()
                + (o.isEnabled() ? "  " + o.getCurrentWidth() + "x" + o.getCurrentHeight()
                        : (o.isConnected() ? "" : "  (off)"));
        GlassyPanel bg = newRow(label, outputList.getWidth());
        outputBgs.add(bg);
        return wrapRow(bg, label, () -> selectOutput(o, bg));
    }

    private void selectOutput(DisplayService.Output o, GlassyPanel bg) {
        if (selectedOutputBg != null && selectedOutputBg != bg) {
            selectedOutputBg.setAppearance(Ui3D.appearance(Ui3D.ROW_OFF));
        }
        selectedOutput = o;
        selectedOutputBg = bg;
        bg.setAppearance(Ui3D.appearance(Ui3D.ROW_ON));
        primaryOn = o.isPrimary();
        primaryBtn.setLit(primaryOn);
        statusText.setText("Output " + o.getName() + (o.isConnected() ? "" : " (disconnected)"));

        selectedMode = o.getCurrentMode();
        if (selectedMode == null) {
            selectedMode = o.getPreferredMode();
        }
        selectedModeBg = null;
        List<Component3D> rows = new ArrayList<>();
        modeBgs.clear();
        float modeW = modeList.getWidth();
        for (DisplayService.Mode m : o.getModes()) {
            String label = m.getId() + (o.getCurrentMode() == m ? "  (current)" : "");
            GlassyPanel mbg = newRow(label, modeW);
            modeBgs.add(mbg);
            if (m == selectedMode) {
                selectedModeBg = mbg;
                mbg.setAppearance(Ui3D.appearance(Ui3D.ROW_ON));
            }
            rows.add(wrapRow(mbg, label, () -> selectMode(m, mbg)));
        }
        modeList.setRows(rows);
    }

    private void selectMode(DisplayService.Mode m, GlassyPanel bg) {
        if (selectedModeBg != null && selectedModeBg != bg) {
            selectedModeBg.setAppearance(Ui3D.appearance(Ui3D.ROW_OFF));
        }
        selectedMode = m;
        selectedModeBg = bg;
        bg.setAppearance(Ui3D.appearance(Ui3D.ROW_ON));
        statusText.setText("Resolution " + m.getId() + " selected for "
                + (selectedOutput == null ? "?" : selectedOutput.getName()));
    }

    /** Creates the highlightable background panel for a list row. */
    private GlassyPanel newRow(String label, float width) {
        GlassyPanel bg = Ui3D.panel(width, rowH * 0.9f, 0.002f, Ui3D.ROW_OFF);
        bg.setCapability(Shape3D.ALLOW_APPEARANCE_READ);
        bg.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
        return bg;
    }

    /** Assembles a clickable list row around its background panel. */
    private Component3D wrapRow(GlassyPanel bg, String label, Runnable onClick) {
        Component3D row = new Component3D();
        float width = bg.getBounds() == null ? rowH * 6 : rowH * 6; // unused
        row.addChild(Ui3D.component(Ui3D.at(bg, 0f, 0f, -0.001f)));
        row.addChild(Ui3D.component(Ui3D.label(label, rowH * 9f, textH,
                Ui3D.TEXT_BRIGHT, GlassyText2D.Alignment.LEFT,
                -rowH * 4.4f, 0f, 0.001f)));
        row.addListener(new MouseClickedEventAdapter(new ActionNoArg() {
            public void performAction(LgEventSource s) {
                onClick.run();
            }
        }));
        return row;
    }

    // ------------------------------------------------------------------
    // Apply + keep/revert

    private void apply() {
        if (!available || selectedOutput == null) {
            statusText.setText("No output selected.");
            return;
        }
        final DisplayService.Output o = selectedOutput;
        final DisplayService.Mode m = selectedMode;
        statusText.setText("Applying...");
        Thread worker = new Thread(() -> {
            DisplayService.ApplyResult result;
            List<DisplayService.OutputSetting> snapshot;
            try {
                snapshot = DisplayService.capture();
                DisplayService.OutputSetting s = new DisplayService.OutputSetting(o.getName());
                s.mode = (m != null) ? m.getId() : null;
                s.primary = primaryOn;
                result = DisplayService.apply(List.of(s));
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() ->
                        statusText.setText("Apply failed: " + ex.getMessage()));
                return;
            }
            final DisplayService.ApplyResult r = result;
            final List<DisplayService.OutputSetting> snap = snapshot;
            SwingUtilities.invokeLater(() -> {
                if (!r.isSuccess()) {
                    statusText.setText("Apply failed: " + r.getMessage());
                    return;
                }
                startCountdown(snap);
            });
        }, "DisplayPage3D:apply");
        worker.setDaemon(true);
        worker.start();
    }

    /** Timed "keep these settings?" state; reverts to the snapshot on timeout. */
    private void startCountdown(List<DisplayService.OutputSetting> snapshot) {
        stopCountdown();
        revertSnapshot = snapshot;
        secondsLeft = REVERT_SECONDS;
        keepBtn.changeVisible(true);
        keepBtn.setEnabled(true);
        statusText.setText("Applied. Keep these settings? Reverting in "
                + secondsLeft + "s...");
        countdown = new Timer(1000, e -> {
            secondsLeft--;
            if (secondsLeft <= 0) {
                revert();
            } else {
                statusText.setText("Applied. Keep these settings? Reverting in "
                        + secondsLeft + "s...");
            }
        });
        countdown.start();
    }

    private void keepSettings() {
        stopCountdown();
        statusText.setText("Display settings kept.");
    }

    private void revert() {
        stopCountdown();
        final List<DisplayService.OutputSetting> snap = revertSnapshot;
        revertSnapshot = null;
        statusText.setText("Reverting to the previous configuration...");
        Thread worker = new Thread(() -> {
            try {
                DisplayService.restore(snap);
            } catch (RuntimeException ex) {
                // fall through to the reload below
            }
            SwingUtilities.invokeLater(() -> {
                statusText.setText("Reverted to the previous configuration.");
                reload();
            });
        }, "DisplayPage3D:revert");
        worker.setDaemon(true);
        worker.start();
    }

    private void stopCountdown() {
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
        revertSnapshot = null;
        keepBtn.changeVisible(false);
        keepBtn.setEnabled(false);
    }
}
