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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;

import org.jdesktop.lg3d.sg.Appearance;
import org.jdesktop.lg3d.sg.GeometryArray;
import org.jdesktop.lg3d.sg.ImageComponent2D;
import org.jdesktop.lg3d.sg.PolygonAttributes;
import org.jdesktop.lg3d.sg.QuadArray;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.sg.Texture2D;
import org.jdesktop.lg3d.sg.TextureAttributes;
import org.jdesktop.lg3d.sg.TransparencyAttributes;
import org.jdesktop.lg3d.utils.action.ActionInt;
import org.jdesktop.lg3d.utils.eventadapter.MouseWheelEventAdapter;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;

/**
 * The central image viewport: the current image rendered on an opaque textured
 * quad, with mouse-wheel zoom and a reset-view control.
 *
 * <p>The texture path deliberately mirrors {@code SwingNode}'s proven
 * configuration for Jogamp Java 3D 1.7.2: a power-of-two {@code TYPE_INT_ARGB}
 * image on an {@code ImageComponent2D(FORMAT_RGBA, ..., yUp=true)} feeding a
 * {@code Texture2D(RGBA)}, sampled with {@code TextureAttributes.REPLACE} and
 * {@code TransparencyAttributes.BLENDED} at 0.0. An opaque backdrop is painted
 * first so no pixel (including the power-of-two padding) is transparent, which
 * is what keeps the quad fully opaque under REPLACE. An alpha-less RGB texture
 * with FASTEST blending would render (almost) invisible.</p>
 *
 * <p>Texture coordinates are cropped to {@code img/pow2} so the padding never
 * shows, and the quad is sized to the image aspect ratio fitted into the
 * viewport box. Large images are downscaled into the texture (max
 * {@value #MAX_TEXTURE}px) purely for display; the model keeps full resolution.</p>
 */
public class ImageCanvas3D extends Component3D implements EditorModel.Listener {

    private static final int MAX_TEXTURE = 2048;
    private static final Color BACKDROP = new Color(0x1E, 0x22, 0x28);

    private final float viewWidth;
    private final float viewHeight;

    private final Appearance appearance;
    private final QuadArray quad;
    private final Shape3D shape;

    private BufferedImage p2Image;
    private ImageComponent2D imageComponent;
    private Texture2D texture;
    private int texW = -1;
    private int texH = -1;

    private EditorModel model;
    private float zoom = 1.0f;

    public ImageCanvas3D(float viewWidth, float viewHeight) {
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;

        appearance = new Appearance();
        TextureAttributes texAttr = new TextureAttributes();
        texAttr.setTextureMode(TextureAttributes.REPLACE);
        appearance.setTextureAttributes(texAttr);
        appearance.setCapability(Appearance.ALLOW_TEXTURE_WRITE);
        appearance.setPolygonAttributes(new PolygonAttributes(
                PolygonAttributes.POLYGON_FILL, PolygonAttributes.CULL_NONE,
                0.0f, false, 0.0f));
        TransparencyAttributes trans = new TransparencyAttributes(
                TransparencyAttributes.BLENDED, 0.0f,
                TransparencyAttributes.BLEND_SRC_ALPHA,
                TransparencyAttributes.BLEND_ONE_MINUS_SRC_ALPHA);
        trans.setCapability(TransparencyAttributes.ALLOW_VALUE_WRITE);
        appearance.setTransparencyAttributes(trans);

        quad = new QuadArray(4,
                GeometryArray.COORDINATES | GeometryArray.TEXTURE_COORDINATE_2);
        quad.setCapability(GeometryArray.ALLOW_COORDINATE_WRITE);
        quad.setCapability(GeometryArray.ALLOW_TEXCOORD_WRITE);
        // Start collapsed until an image arrives.
        quad.setCoordinates(0, new float[] {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
        quad.setTextureCoordinates(0, 0, new float[] {
            0, 0, 0, 0, 0, 0, 0, 0 });

        shape = new Shape3D(quad, appearance);
        addChild(shape);

        setCursor(Cursor3D.SMALL_CURSOR);
        // Mouse-wheel zoom about the canvas centre.
        addListener(new MouseWheelEventAdapter(new ActionInt() {
            public void performAction(LgEventSource source, int rotation) {
                float factor = (rotation < 0) ? 1.1f : (1.0f / 1.1f);
                setZoom(zoom * factor);
            }
        }));
    }

    public void setModel(EditorModel model) {
        this.model = model;
        if (model != null) {
            model.addListener(this);
            setImage(model.getCurrent());
        }
    }

    public EditorModel getModel() {
        return model;
    }

    /** EditorModel.Listener: refresh whenever the model's image changes. */
    public void modelChanged(EditorModel m) {
        setImage(m.getCurrent());
    }

    /** Restore the default zoom/fit. */
    public void resetView() {
        setZoom(1.0f);
    }

    public float getZoom() {
        return zoom;
    }

    private void setZoom(float z) {
        zoom = Math.max(0.1f, Math.min(8.0f, z));
        setScale(zoom);
    }

    /** Paint the given image into the live texture and re-fit the quad. */
    public void setImage(RenderedImage img) {
        if (img == null) {
            quad.setCoordinates(0, new float[] {
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
            return;
        }
        int imgW = img.getWidth();
        int imgH = img.getHeight();
        if (imgW < 1 || imgH < 1) {
            return;
        }

        // Downscale very large images for display only.
        float fit = Math.min(1.0f, MAX_TEXTURE / (float) Math.max(imgW, imgH));
        int dispW = Math.max(1, Math.round(imgW * fit));
        int dispH = Math.max(1, Math.round(imgH * fit));

        int p2w = powerOfTwo(dispW);
        int p2h = powerOfTwo(dispH);

        // The ImageComponent2D must hold pixel data before the texture that
        // references it is attached to the (live) appearance: under Jogamp,
        // Appearance.setTexture on a live graph calls TextureRetained.setLive,
        // which dereferences the image data and NPEs on an empty component.
        // So paint + upload first, then build/attach the texture.
        boolean newTexture = (texture == null || p2w != texW || p2h != texH);
        if (newTexture) {
            p2Image = new BufferedImage(p2w, p2h, BufferedImage.TYPE_INT_ARGB);
            imageComponent = new ImageComponent2D(
                    ImageComponent2D.FORMAT_RGBA, p2w, p2h, false, true);
            imageComponent.setCapability(ImageComponent2D.ALLOW_IMAGE_WRITE);
        }

        Graphics2D g = p2Image.createGraphics();
        try {
            g.setComposite(java.awt.AlphaComposite.Src);
            g.setColor(BACKDROP);
            g.fillRect(0, 0, p2w, p2h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (img instanceof BufferedImage) {
                g.drawImage((BufferedImage) img, 0, 0, dispW, dispH, null);
            } else {
                g.drawImage(JaiProcessor.normalize(img), 0, 0, dispW, dispH, null);
            }
        } finally {
            g.dispose();
        }
        imageComponent.set(p2Image);

        if (newTexture) {
            texture = new Texture2D(Texture2D.BASE_LEVEL, Texture2D.RGBA, p2w, p2h);
            texture.setMinFilter(Texture2D.BASE_LEVEL_LINEAR);
            texture.setMagFilter(Texture2D.BASE_LEVEL_LINEAR);
            texture.setBoundaryModeS(Texture2D.CLAMP);
            texture.setBoundaryModeT(Texture2D.CLAMP);
            texture.setImage(0, imageComponent);
            appearance.setTexture(texture);
            texW = p2w;
            texH = p2h;
        }

        // Fit the quad into the viewport box, preserving the image aspect ratio.
        float scale = Math.min(viewWidth / dispW, viewHeight / dispH);
        float qw = dispW * scale;
        float qh = dispH * scale;
        float hx = qw / 2.0f;
        float hy = qh / 2.0f;
        quad.setCoordinates(0, new float[] {
            -hx, -hy, 0.0f,
             hx, -hy, 0.0f,
             hx,  hy, 0.0f,
            -hx,  hy, 0.0f,
        });
        // Crop to the real image region of the pow2 texture. Orientation matches
        // ImagePanel/FuzzyEdgePanel (quad top -> v=0) for a yUp image component.
        float sMax = dispW / (float) p2w;
        float tMax = dispH / (float) p2h;
        quad.setTextureCoordinates(0, 0, new float[] {
            0.0f, tMax,   // bottom-left
            sMax, tMax,   // bottom-right
            sMax, 0.0f,   // top-right
            0.0f, 0.0f,   // top-left
        });
    }

    private static int powerOfTwo(int value) {
        if (value < 1) {
            return 1;
        }
        int pow = 1;
        while (pow < value) {
            pow <<= 1;
        }
        return pow;
    }
}
