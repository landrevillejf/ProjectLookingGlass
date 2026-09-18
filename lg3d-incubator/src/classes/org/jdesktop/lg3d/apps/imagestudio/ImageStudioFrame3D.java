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

import org.jdesktop.lg3d.scenemanager.utils.taskbar.Taskbar;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Vector3f;

/**
 * The Image Studio application window: a {@link Frame3D} that lays out the
 * lg3d-native 3D editing surface and owns the {@link EditorModel}.
 *
 * <pre>
 *   +-------------------------------------------------------------+
 *   | Image Studio                              [ _ ][ X ] (deco) |
 *   +-----------+-----------------------------------+-------------+
 *   | Toolbar3D |          ImageCanvas3D            | Histogram3D |
 *   | (ops +    |          (textured quad,          | (RGB plot)  |
 *   |  slider)  |           wheel zoom)             |             |
 *   +-----------+-----------------------------------+-------------+
 *   | FileStrip3D: [Open][Save][Save As]  Pictures > thumb thumb...|
 *   | status line                                                 |
 *   +-------------------------------------------------------------+
 * </pre>
 *
 * <p>As a {@code Frame3D} that does not set
 * {@code Frame3DWindowDecoration.OPT_OUT_PROPERTY}, the window automatically
 * receives the standard decoration (minimize/maximize/close, right-click flip,
 * middle-drag spin), so the top-right corner is left clear of controls.</p>
 *
 * <p>The frame is an {@link EditorModel.Listener} solely to mirror the model's
 * status string onto the bottom status line.</p>
 */
public class ImageStudioFrame3D extends Frame3D implements EditorModel.Listener {

    private static final Color4f WINDOW_BG = new Color4f(0.05f, 0.07f, 0.11f, 0.55f);

    private final EditorModel model;
    private final ImageCanvas3D canvas;
    private GlassyText2D statusText;

    public ImageStudioFrame3D() {
        setName("Image Studio");

        Toolkit3D tk = Toolkit3D.getToolkit3D();
        // Match the window aspect to the usable screen area (full width above
        // the taskbar reserve). The decoration's maximize scales uniformly to
        // preserve aspect, so only a matching aspect fills the viewport on
        // both axes; a narrower window would hit the height bound first and
        // maximize into a band with wide empty margins left and right.
        float usableH = tk.getScreenHeight() - Taskbar.getReservedBottomHeight();
        float H = usableH * 0.70f;
        float W = H * tk.getScreenWidth() / usableH;
        setPreferredSize(new Vector3f(W, H, 0.01f));

        model = new EditorModel();

        // ---- layout metrics (origin at window centre, +x right, +y up) ----
        float pad = Math.min(W, H) * 0.022f;
        float topMargin = H * 0.075f;   // clear of the decoration buttons
        float statusH = H * 0.05f;
        float stripH = H * 0.17f;

        float contentTop = H * 0.5f - topMargin;
        float contentBottom = -H * 0.5f + pad;

        float statusCY = contentBottom + statusH * 0.5f;
        float stripBottom = contentBottom + statusH + pad * 0.6f;
        float stripCY = stripBottom + stripH * 0.5f;
        float mainBottom = stripBottom + stripH + pad * 0.6f;
        float mainCY = (contentTop + mainBottom) * 0.5f;
        float mainH = contentTop - mainBottom;

        float toolbarW = W * 0.235f;
        float histW = W * 0.17f;
        float xLeft = -W * 0.5f + pad;
        float xRight = W * 0.5f - pad;
        float toolbarCX = xLeft + toolbarW * 0.5f;
        float histCX = xRight - histW * 0.5f;
        float canvasLeft = xLeft + toolbarW + pad;
        float canvasRight = xRight - histW - pad;
        float canvasCX = (canvasLeft + canvasRight) * 0.5f;
        float canvasW = canvasRight - canvasLeft;

        // ---- window backdrop (drawn first, behind everything) ----
        // A Frame3D is a Container3D, which only accepts Component3D children,
        // so each raw scene-graph node here is wrapped via Ui3D.component(...).
        addChild(Ui3D.component(
                Ui3D.at(Ui3D.panel(W, H, 0.006f, WINDOW_BG), 0f, 0f, -0.008f)));

        // ---- title (top-left, away from the decoration's top-right buttons) ----
        float titleH = topMargin * 0.46f;
        GlassyText2D title = Ui3D.makeText("Image Studio", W * 0.5f, titleH,
                Ui3D.TEXT_BRIGHT, GlassyText2D.Alignment.LEFT);
        addChild(Ui3D.component(
                Ui3D.at(title, xLeft, H * 0.5f - topMargin * 0.62f, 0.002f)));

        // ---- central canvas (created first so the toolbar's Fit can reach it) ----
        canvas = new ImageCanvas3D(canvasW, mainH);
        canvas.setTranslation(canvasCX, mainCY, 0.002f);
        canvas.setModel(model);
        addChild(canvas);

        // ---- left toolbar ----
        Toolbar3D toolbar = new Toolbar3D(toolbarW, mainH, model, new Runnable() {
            public void run() {
                canvas.resetView();
            }
        });
        toolbar.setTranslation(toolbarCX, mainCY, 0.002f);
        addChild(toolbar);

        // ---- right histogram ----
        Histogram3D histogram = new Histogram3D(histW, mainH);
        histogram.setTranslation(histCX, mainCY, 0.002f);
        histogram.setModel(model);
        addChild(histogram);

        // ---- bottom filmstrip ----
        FileStrip3D fileStrip = new FileStrip3D(W - 2 * pad, stripH, model);
        fileStrip.setTranslation(0f, stripCY, 0.002f);
        addChild(fileStrip);

        // ---- status line ----
        float statusTextH = statusH * 0.52f;
        statusText = Ui3D.makeText(model.getStatus(), W * 0.95f, statusTextH,
                Ui3D.TEXT_DIM, GlassyText2D.Alignment.LEFT);
        addChild(Ui3D.component(
                Ui3D.at(statusText, xLeft, statusCY - statusTextH * 0.5f, 0.002f)));

        model.addListener(this);

        // Start with a usable placeholder so operations work before any open.
        model.newImage(720, 480);
    }

    /** EditorModel.Listener: mirror the model status onto the status line. */
    public void modelChanged(EditorModel m) {
        if (statusText != null) {
            statusText.setText(m.getStatus());
        }
    }
}
