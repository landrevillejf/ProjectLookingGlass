/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.imagestudio;

import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Color4f;

/**
 * The left-hand control panel: category tabs, a grid of operation buttons, the
 * parameter {@link Slider3D}, and an Undo/Redo/Reset/Fit action row.
 *
 * <p>Operations are described by the shared {@link OpCatalog} (also rendered
 * by the 2D desktop's {@link ImageStudioPanel}, so both surfaces offer the
 * same edits). A one-shot operation (flip, invert, emboss, ...) applies
 * immediately through {@link EditorModel#apply}. A parameterized operation
 * (brightness, blur, scale, ...) is <em>armed</em> on click: the model
 * captures a baseline ({@link EditorModel#beginContinuousEdit}) and the slider
 * then drives {@link EditorModel#preview} against that same baseline, so
 * dragging is absolute (no compounding). The armed edit is committed as a
 * single undo entry ({@link EditorModel#endContinuousEdit}) when another
 * operation runs.</p>
 *
 * <p>Selecting a category rebuilds the tab strip (to light the active tab) and
 * the operation grid. Both are cheap and avoid invisible-but-pickable pages.</p>
 */
public class Toolbar3D extends Component3D {

    private static final Color4f TAB_ACTIVE = new Color4f(0.30f, 0.48f, 0.70f, 0.94f);

    private final EditorModel model;
    private final Runnable onFitView;

    private final Component3D tabArea;
    private final Component3D opArea;
    private final Slider3D slider;

    // Layout metrics retained for rebuilds.
    private final float innerW;
    private final float tabH;
    private final float opH;

    private int currentCategory = 0;
    private int armedCode = 0;
    private String armedLabel = "";
    private boolean armedActive = false;

    public Toolbar3D(float width, float height, EditorModel model, Runnable onFitView) {
        this.model = model;
        this.onFitView = onFitView;

        float pad = width * 0.05f;
        float gap = height * 0.018f;
        this.innerW = width - 2 * pad;
        this.tabH = height * 0.062f;
        float actionH = height * 0.072f;
        float sliderH = height * 0.14f;

        float tabsCY = height * 0.5f - pad - tabH * 0.5f;
        float actionCY = -height * 0.5f + pad + actionH * 0.5f;
        float sliderCY = actionCY + actionH * 0.5f + gap + sliderH * 0.5f;
        float opTop = tabsCY - tabH * 0.5f - gap;
        float opBottom = sliderCY + sliderH * 0.5f + gap;
        float opCY = (opTop + opBottom) * 0.5f;
        this.opH = opTop - opBottom;

        // Background panel (added first so controls blend over it).
        addChild(Ui3D.at(Ui3D.panel(width, height, 0.004f, Ui3D.PANEL_BG), 0f, 0f, 0f));

        // Category tabs.
        tabArea = new Component3D();
        tabArea.setTranslation(0f, tabsCY, 0.004f);
        addChild(tabArea);

        // Operation grid.
        opArea = new Component3D();
        opArea.setTranslation(0f, opCY, 0.004f);
        addChild(opArea);

        // Parameter slider.
        slider = new Slider3D(innerW, sliderH);
        slider.setTranslation(0f, sliderCY, 0.004f);
        slider.setIdle();
        slider.setListener(new Slider3D.Listener() {
            public void sliderAdjusted(float v) {
                if (armedCode == 0) {
                    return;
                }
                if (!armedActive) {
                    Toolbar3D.this.model.beginContinuousEdit();
                    armedActive = true;
                }
                Toolbar3D.this.model.preview(OpCatalog.opFor(armedCode, v), armedLabel);
            }
        });
        addChild(slider);

        // Action row.
        buildActions(innerW, actionH, actionCY);

        rebuild();
    }

    // ------------------------------------------------------------------
    // Build / rebuild
    // ------------------------------------------------------------------

    private void buildActions(float innerW, float actionH, float actionCY) {
        Component3D row = new Component3D();
        row.setTranslation(0f, actionCY, 0.004f);
        String[] labels = { "Undo", "Redo", "Reset", "Fit" };
        ActionNoArg[] acts = {
            new ActionNoArg() { public void performAction(LgEventSource s) { commitArmed(); model.undo(); } },
            new ActionNoArg() { public void performAction(LgEventSource s) { commitArmed(); model.redo(); } },
            new ActionNoArg() { public void performAction(LgEventSource s) { commitArmed(); model.reset(); } },
            new ActionNoArg() { public void performAction(LgEventSource s) { if (onFitView != null) onFitView.run(); } },
        };
        float actGap = innerW * 0.04f;
        float actW = (innerW - 3 * actGap) / 4f;
        float textH = actionH * 0.40f;
        for (int i = 0; i < labels.length; i++) {
            Component3D b = Ui3D.button(labels[i], actW, actionH, textH,
                    Ui3D.ACTION_OFF, Ui3D.ACTION_ON, Ui3D.TEXT_BRIGHT, acts[i]);
            b.setTranslation(-innerW * 0.5f + actW * 0.5f + i * (actW + actGap), 0f, 0f);
            row.addChild(b);
        }
        addChild(row);
    }

    private void rebuild() {
        rebuildTabs();
        rebuildOps();
    }

    private void rebuildTabs() {
        tabArea.removeAllChildren();
        int n = OpCatalog.CATEGORIES.length;
        float tabGap = innerW * 0.03f;
        float tabW = (innerW - (n - 1) * tabGap) / n;
        float textH = tabH * 0.42f;
        for (int i = 0; i < n; i++) {
            final int index = i;
            boolean active = (i == currentCategory);
            Color4f off = active ? TAB_ACTIVE : Ui3D.TAB_OFF;
            Component3D tab = Ui3D.button(OpCatalog.CATEGORIES[i], tabW, tabH, textH,
                    off, Ui3D.TAB_ON, Ui3D.TEXT_BRIGHT,
                    new ActionNoArg() {
                        public void performAction(LgEventSource s) {
                            selectCategory(index);
                        }
                    });
            tab.setTranslation(-innerW * 0.5f + tabW * 0.5f + i * (tabW + tabGap), 0f, 0f);
            tabArea.addChild(tab);
        }
    }

    private void rebuildOps() {
        opArea.removeAllChildren();
        OpCatalog.OpDef[] ops = OpCatalog.CATALOG[currentCategory];
        int cols = 2;
        int rows = (ops.length + cols - 1) / cols;
        float colGap = innerW * 0.05f;
        float btnW = (innerW - (cols - 1) * colGap) / cols;
        float rowH = opH / rows;
        float btnH = Math.min(rowH * 0.74f, tabH * 1.6f);
        float textH = btnH * 0.40f;
        for (int i = 0; i < ops.length; i++) {
            final OpCatalog.OpDef op = ops[i];
            int col = i % cols;
            int row = i / cols;
            float cx = -innerW * 0.5f + btnW * 0.5f + col * (btnW + colGap);
            float cy = opH * 0.5f - rowH * 0.5f - row * rowH;
            Component3D b = Ui3D.button(op.label, btnW, btnH, textH,
                    Ui3D.BUTTON_OFF, Ui3D.BUTTON_ON, Ui3D.TEXT_BRIGHT,
                    new ActionNoArg() {
                        public void performAction(LgEventSource s) {
                            runOp(op);
                        }
                    });
            b.setTranslation(cx, cy, 0f);
            opArea.addChild(b);
        }
    }

    // ------------------------------------------------------------------
    // Interaction
    // ------------------------------------------------------------------

    private void selectCategory(int index) {
        if (index == currentCategory) {
            return;
        }
        commitArmed();
        slider.setIdle();
        currentCategory = index;
        rebuild();
    }

    private void runOp(OpCatalog.OpDef op) {
        if (op.param) {
            selectParamOp(op);
        } else {
            commitArmed();
            slider.setIdle();
            model.apply(OpCatalog.opFor(op.code, 0f), op.label);
        }
    }

    private void selectParamOp(OpCatalog.OpDef op) {
        commitArmed();
        armedCode = op.code;
        armedLabel = op.label;
        slider.configure(op.label, op.min, op.max, op.init, op.fmt, op.intFmt);
        armedActive = true;
        model.beginContinuousEdit();
        model.preview(OpCatalog.opFor(op.code, op.init), op.label);
    }

    private void commitArmed() {
        if (armedActive) {
            model.endContinuousEdit();
            armedActive = false;
        }
    }
}
