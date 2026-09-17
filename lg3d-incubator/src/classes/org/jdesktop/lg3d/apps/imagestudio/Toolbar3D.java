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
package org.jdesktop.lg3d.apps.imagestudio;

import java.awt.image.BufferedImage;

import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Color4f;

/**
 * The left-hand control panel: category tabs, a grid of operation buttons, the
 * parameter {@link Slider3D}, and an Undo/Redo/Reset/Fit action row.
 *
 * <p>Operations are described by a static {@linkplain OpDef catalog}. A
 * one-shot operation (flip, invert, emboss, ...) applies immediately through
 * {@link EditorModel#apply}. A parameterized operation (brightness, blur, scale,
 * ...) is <em>armed</em> on click: the model captures a baseline
 * ({@link EditorModel#beginContinuousEdit}) and the slider then drives
 * {@link EditorModel#preview} against that same baseline, so dragging is
 * absolute (no compounding). The armed edit is committed as a single undo entry
 * ({@link EditorModel#endContinuousEdit}) when another operation runs.</p>
 *
 * <p>Selecting a category rebuilds the tab strip (to light the active tab) and
 * the operation grid. Both are cheap and avoid invisible-but-pickable pages.</p>
 */
public class Toolbar3D extends Component3D {

    // Operation codes.
    private static final int
        FLIP_H = 1, FLIP_V = 2, ROT90 = 3, ROTATE = 4, SCALE = 5, PIXELATE = 6, BORDER = 7,
        BRIGHT = 10, CONTRAST = 11, GAMMA = 12, GRAY = 13, SEPIA = 14, INVERT = 15,
        POSTERIZE = 16, THRESHOLD = 17,
        BLUR = 20, SHARPEN = 21, EMBOSS = 22, EDGE = 23,
        ADD = 30, SUB = 31, MUL = 32, ABS = 33, AND = 34, OR = 35, XOR = 36, NOISE = 37;

    private static final Color4f TAB_ACTIVE = new Color4f(0.30f, 0.48f, 0.70f, 0.94f);

    /** One catalogued operation. */
    private static final class OpDef {
        final String label;
        final int code;
        final boolean param;
        final float min;
        final float max;
        final float init;
        final String fmt;
        final boolean intFmt;

        OpDef(String label, int code) {
            this.label = label; this.code = code; this.param = false;
            this.min = 0f; this.max = 1f; this.init = 0f; this.fmt = "%.0f"; this.intFmt = true;
        }

        OpDef(String label, int code, float min, float max, float init,
                String fmt, boolean intFmt) {
            this.label = label; this.code = code; this.param = true;
            this.min = min; this.max = max; this.init = init; this.fmt = fmt; this.intFmt = intFmt;
        }
    }

    private static final String[] CATEGORIES = { "Geometry", "Color", "Filter", "Math" };

    private static final OpDef[][] CATALOG = {
        {
            new OpDef("Flip H", FLIP_H),
            new OpDef("Flip V", FLIP_V),
            new OpDef("Rot 90", ROT90),
            new OpDef("Rotate", ROTATE, -180f, 180f, 0f, "%.0f\u00B0", true),
            new OpDef("Scale", SCALE, 0.1f, 3.0f, 1.0f, "x%.2f", false),
            new OpDef("Pixelate", PIXELATE, 2f, 40f, 6f, "%.0f", true),
            new OpDef("Border", BORDER, 0f, 40f, 8f, "%.0f", true),
        },
        {
            new OpDef("Brighter", BRIGHT, -128f, 128f, 0f, "%+.0f", true),
            new OpDef("Contrast", CONTRAST, 0f, 3f, 1f, "x%.2f", false),
            new OpDef("Gamma", GAMMA, 0.2f, 3f, 1f, "%.2f", false),
            new OpDef("Gray", GRAY),
            new OpDef("Sepia", SEPIA),
            new OpDef("Invert", INVERT),
            new OpDef("Posterize", POSTERIZE, 2f, 32f, 4f, "%.0f", true),
            new OpDef("Threshold", THRESHOLD, 0f, 255f, 128f, "%.0f", true),
        },
        {
            new OpDef("Blur", BLUR, 1f, 20f, 3f, "%.0f", true),
            new OpDef("Sharpen", SHARPEN, 0.05f, 2f, 0.6f, "%.2f", false),
            new OpDef("Emboss", EMBOSS),
            new OpDef("Edges", EDGE),
        },
        {
            new OpDef("Add", ADD, -128f, 128f, 20f, "%+.0f", true),
            new OpDef("Subtract", SUB, -128f, 128f, 20f, "%+.0f", true),
            new OpDef("Multiply", MUL, 0.2f, 3f, 1.1f, "x%.2f", false),
            new OpDef("Abs", ABS),
            new OpDef("AND", AND, 0f, 255f, 240f, "0x%02X", true),
            new OpDef("OR", OR, 0f, 255f, 15f, "0x%02X", true),
            new OpDef("XOR", XOR, 0f, 255f, 255f, "0x%02X", true),
            new OpDef("Noise", NOISE, 0f, 64f, 20f, "%.0f", true),
        },
    };

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
                Toolbar3D.this.model.preview(opFor(armedCode, v), armedLabel);
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
        int n = CATEGORIES.length;
        float tabGap = innerW * 0.03f;
        float tabW = (innerW - (n - 1) * tabGap) / n;
        float textH = tabH * 0.42f;
        for (int i = 0; i < n; i++) {
            final int index = i;
            boolean active = (i == currentCategory);
            Color4f off = active ? TAB_ACTIVE : Ui3D.TAB_OFF;
            Component3D tab = Ui3D.button(CATEGORIES[i], tabW, tabH, textH,
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
        OpDef[] ops = CATALOG[currentCategory];
        int cols = 2;
        int rows = (ops.length + cols - 1) / cols;
        float colGap = innerW * 0.05f;
        float btnW = (innerW - (cols - 1) * colGap) / cols;
        float rowH = opH / rows;
        float btnH = Math.min(rowH * 0.74f, tabH * 1.6f);
        float textH = btnH * 0.40f;
        for (int i = 0; i < ops.length; i++) {
            final OpDef op = ops[i];
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

    private void runOp(OpDef op) {
        if (op.param) {
            selectParamOp(op);
        } else {
            commitArmed();
            slider.setIdle();
            model.apply(opFor(op.code, 0f), op.label);
        }
    }

    private void selectParamOp(OpDef op) {
        commitArmed();
        armedCode = op.code;
        armedLabel = op.label;
        slider.configure(op.label, op.min, op.max, op.init, op.fmt, op.intFmt);
        armedActive = true;
        model.beginContinuousEdit();
        model.preview(opFor(op.code, op.init), op.label);
    }

    private void commitArmed() {
        if (armedActive) {
            model.endContinuousEdit();
            armedActive = false;
        }
    }

    /** Build the {@link EditorModel.Op} for an operation code at a given value. */
    private static EditorModel.Op opFor(final int code, final float v) {
        return new EditorModel.Op() {
            public BufferedImage apply(BufferedImage src) {
                switch (code) {
                    // Geometry
                    case FLIP_H:    return JaiProcessor.flipHorizontal(src);
                    case FLIP_V:    return JaiProcessor.flipVertical(src);
                    case ROT90:     return JaiProcessor.rotate90(src);
                    case ROTATE:    return JaiProcessor.rotate(src, v);
                    case SCALE:     return JaiProcessor.scale(src, v);
                    case PIXELATE:  return JaiProcessor.pixelate(src, Math.round(v));
                    case BORDER:    return JaiProcessor.border(src, Math.round(v), 0xF0F0F0);
                    // Colour
                    case BRIGHT:    return JaiProcessor.brightness(src, v);
                    case CONTRAST:  return JaiProcessor.contrast(src, v);
                    case GAMMA:     return JaiProcessor.gamma(src, v);
                    case GRAY:      return JaiProcessor.grayscale(src);
                    case SEPIA:     return JaiProcessor.sepia(src);
                    case INVERT:    return JaiProcessor.invert(src);
                    case POSTERIZE: return JaiProcessor.posterize(src, Math.round(v));
                    case THRESHOLD: return JaiProcessor.threshold(src, Math.round(v));
                    // Filters
                    case BLUR:      return JaiProcessor.blur(src, Math.round(v));
                    case SHARPEN:   return JaiProcessor.sharpen(src, v);
                    case EMBOSS:    return JaiProcessor.emboss(src);
                    case EDGE:      return JaiProcessor.edge(src);
                    // Math / logic
                    case ADD:       return JaiProcessor.addConst(src, v);
                    case SUB:       return JaiProcessor.subtractConst(src, v);
                    case MUL:       return JaiProcessor.multiplyConst(src, v);
                    case ABS:       return JaiProcessor.absolute(src);
                    case AND:       return JaiProcessor.andConst(src, Math.round(v));
                    case OR:        return JaiProcessor.orConst(src, Math.round(v));
                    case XOR:       return JaiProcessor.xorConst(src, Math.round(v));
                    case NOISE:     return JaiProcessor.noise(src, Math.round(v));
                    default:        return src;
                }
            }
        };
    }
}
