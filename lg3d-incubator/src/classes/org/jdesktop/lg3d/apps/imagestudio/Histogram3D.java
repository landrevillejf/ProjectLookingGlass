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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javax.media.jai.Histogram;

import org.jdesktop.lg3d.sg.Appearance;
import org.jdesktop.lg3d.sg.GeometryArray;
import org.jdesktop.lg3d.sg.ImageComponent2D;
import org.jdesktop.lg3d.sg.PolygonAttributes;
import org.jdesktop.lg3d.sg.QuadArray;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.sg.Texture2D;
import org.jdesktop.lg3d.sg.TextureAttributes;
import org.jdesktop.lg3d.sg.TransparencyAttributes;
import org.jdesktop.lg3d.sg.utils.transparency.TransparencyOrderedGroup;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.wg.Component3D;

/**
 * The right-hand 256-bin RGB histogram, redrawn after every model change.
 *
 * <p>The plot is rasterized with {@link Graphics2D} into a power-of-two
 * {@code TYPE_INT_ARGB} image and shown on a textured quad using the same
 * opaque-texture recipe as {@link ImageCanvas3D} (REPLACE + CULL_NONE +
 * BLENDED at 0.0). Frequencies are log-scaled so a few hot bins do not flatten
 * the rest, and the R/G/B channels are drawn as translucent filled polygons
 * that overlay additively -- the classic editor look.</p>
 *
 * <p>Histograms are recomputed on a downsampled copy (max
 * {@value #MAX_SAMPLE}px) so live slider drags stay responsive; the shape is
 * unaffected because only the distribution matters.</p>
 */
public class Histogram3D extends Component3D implements EditorModel.Listener {

    private static final int TW = 256;
    private static final int TH = 256;
    private static final int MAX_SAMPLE = 256;
    private static final Color BG = new Color(0x14, 0x18, 0x20);

    // JAI's histogram bands follow the sample model's band order, which for
    // the TYPE_3BYTE_BGR working image is already R, G, B (band offsets
    // {2, 1, 0} map band 0 to the R byte), so the identity mapping applies.
    private static final int[] BAND_FOR_RGB = { 0, 1, 2 };
    private static final Color[] RGB_COLORS = {
        new Color(255, 70, 70, 130),
        new Color(70, 255, 90, 130),
        new Color(90, 130, 255, 130),
    };

    private final BufferedImage canvas;
    private final ImageComponent2D imageComponent;

    private EditorModel model;

    public Histogram3D(float width, float height) {
        TransparencyOrderedGroup tog = new TransparencyOrderedGroup();

        // Panel + title.
        tog.addChild(Ui3D.at(Ui3D.panel(width, height, 0.004f, Ui3D.PANEL_BG), 0f, 0f, 0f));
        float titleH = Math.max(0.0022f, height * 0.05f);
        tog.addChild(Ui3D.label("Histogram", width * 0.9f, titleH, Ui3D.TEXT_DIM,
                GlassyText2D.Alignment.CENTER, 0f, height * 0.5f - titleH * 1.2f, 0.005f));

        // Textured plot quad.
        canvas = new BufferedImage(TW, TH, BufferedImage.TYPE_INT_ARGB);
        imageComponent = new ImageComponent2D(
                ImageComponent2D.FORMAT_RGBA, TW, TH, false, true);
        imageComponent.setCapability(ImageComponent2D.ALLOW_IMAGE_WRITE);

        Texture2D texture = new Texture2D(Texture2D.BASE_LEVEL, Texture2D.RGBA, TW, TH);
        texture.setMinFilter(Texture2D.BASE_LEVEL_LINEAR);
        texture.setMagFilter(Texture2D.BASE_LEVEL_LINEAR);
        texture.setBoundaryModeS(Texture2D.CLAMP);
        texture.setBoundaryModeT(Texture2D.CLAMP);
        texture.setImage(0, imageComponent);

        Appearance appearance = new Appearance();
        TextureAttributes texAttr = new TextureAttributes();
        texAttr.setTextureMode(TextureAttributes.REPLACE);
        appearance.setTextureAttributes(texAttr);
        appearance.setTexture(texture);
        appearance.setPolygonAttributes(new PolygonAttributes(
                PolygonAttributes.POLYGON_FILL, PolygonAttributes.CULL_NONE,
                0.0f, false, 0.0f));
        appearance.setTransparencyAttributes(new TransparencyAttributes(
                TransparencyAttributes.BLENDED, 0.0f,
                TransparencyAttributes.BLEND_SRC_ALPHA,
                TransparencyAttributes.BLEND_ONE_MINUS_SRC_ALPHA));

        float plotW = width * 0.90f;
        float plotH = height * 0.80f;
        float hx = plotW * 0.5f;
        float hy = plotH * 0.5f;
        QuadArray quad = new QuadArray(4,
                GeometryArray.COORDINATES | GeometryArray.TEXTURE_COORDINATE_2);
        quad.setCoordinates(0, new float[] {
            -hx, -hy, 0.0f,  hx, -hy, 0.0f,  hx, hy, 0.0f,  -hx, hy, 0.0f });
        quad.setTextureCoordinates(0, 0, new float[] {
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f });
        Shape3D shape = new Shape3D(quad, appearance);
        tog.addChild(Ui3D.at(shape, 0f, -height * 0.05f, 0.005f));

        addChild(tog);

        redraw(null);
        imageComponent.set(canvas);
    }

    public void setModel(EditorModel model) {
        this.model = model;
        if (model != null) {
            model.addListener(this);
            update();
        }
    }

    /** EditorModel.Listener. */
    public void modelChanged(EditorModel m) {
        update();
    }

    /** Recompute the histogram of the current image and refresh the texture. */
    public void update() {
        Histogram h = null;
        if (model != null) {
            BufferedImage img = model.getCurrent();
            if (img != null) {
                try {
                    h = JaiProcessor.histogram(downsample(img));
                } catch (Throwable t) {
                    h = null;
                }
            }
        }
        redraw(h);
        imageComponent.set(canvas);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void redraw(Histogram h) {
        Graphics2D g = canvas.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setColor(BG);
            g.fillRect(0, 0, TW, TH);
            if (h == null) {
                return;
            }
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int numBands = h.getNumBands();
            double logMax = 1.0;
            for (int b = 0; b < numBands; b++) {
                int[] bins = h.getBins(b);
                for (int i = 0; i < bins.length; i++) {
                    double lv = Math.log(1.0 + bins[i]);
                    if (lv > logMax) {
                        logMax = lv;
                    }
                }
            }

            if (numBands == 1) {
                drawBand(g, h.getBins(0), logMax, new Color(210, 220, 235, 150));
            } else {
                for (int k = 0; k < 3; k++) {
                    int band = BAND_FOR_RGB[k];
                    if (band < numBands) {
                        drawBand(g, h.getBins(band), logMax, RGB_COLORS[k]);
                    }
                }
            }
        } finally {
            g.dispose();
        }
    }

    private void drawBand(Graphics2D g, int[] bins, double logMax, Color color) {
        int n = bins.length;
        Polygon poly = new Polygon();
        poly.addPoint(0, TH);
        for (int i = 0; i < n; i++) {
            double lv = Math.log(1.0 + bins[i]) / logMax;
            int x = (int) Math.round(i * (TW / (double) n));
            int y = TH - (int) Math.round(lv * (TH - 2));
            poly.addPoint(x, y);
        }
        poly.addPoint(TW, TH);
        g.setColor(color);
        g.fillPolygon(poly);
    }

    /** Scale large images down so the histogram stays cheap during drags. */
    private static BufferedImage downsample(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int max = Math.max(w, h);
        if (max <= MAX_SAMPLE) {
            return img;
        }
        float f = MAX_SAMPLE / (float) max;
        int sw = Math.max(1, Math.round(w * f));
        int sh = Math.max(1, Math.round(h * f));
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = small.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, sw, sh, null);
        } finally {
            g.dispose();
        }
        return small;
    }
}
