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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.apps.uikit.Button3D;
import org.jdesktop.lg3d.apps.uikit.ScrollList3D;
import org.jdesktop.lg3d.apps.uikit.Ui3D;
import org.jdesktop.lg3d.scenemanager.utils.background.SimpleImageBackground;
import org.jdesktop.lg3d.scenemanager.utils.event.BackgroundChangeRequestEvent;
import org.jdesktop.lg3d.sg.Appearance;
import org.jdesktop.lg3d.sg.GeometryArray;
import org.jdesktop.lg3d.sg.ImageComponent2D;
import org.jdesktop.lg3d.sg.PolygonAttributes;
import org.jdesktop.lg3d.sg.QuadArray;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.sg.Texture2D;
import org.jdesktop.lg3d.sg.TextureAttributes;
import org.jdesktop.lg3d.sg.TransparencyAttributes;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.event.LgEventConnector;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Color4f;

/**
 * The Appearance page: enumerates the wallpapers bundled under the runtime
 * {@code resources/images/background} tree, previews the selection on a
 * textured quad, and applies it by posting a {@link BackgroundChangeRequestEvent}
 * with a {@link SimpleImageBackground} - the same mechanism the taskbar's
 * theme icons use, so the live desktop background changes immediately.
 *
 * <p>Decoding and downscaling happen off the scene thread; pixels are pushed
 * into the {@link ImageComponent2D} before the texture is (re)attached to the
 * live appearance, per the Jogamp texture rule.</p>
 */
public class AppearancePage3D implements ControlPanel {

    private static final String BG_DIR = "resources/images/background";
    private static final int MAX_TEXTURE = 512;

    /** Used when the classpath directory cannot be listed (e.g. jar-only). */
    private static final List<String> FALLBACK = List.of(
            "DreamLakeReflections.jpg",
            "GrandCanyon-0.jpg",
            "Leaves_and_Sky-0.jpg",
            "Stanford-0.jpg");

    private Component3D root;
    private ScrollList3D nameList;
    private GlassyText2D statusText;

    // Preview quad + texture (ImageCanvas3D idiom, simplified).
    private final Appearance appearance = new Appearance();
    private final QuadArray quad = new QuadArray(4,
            GeometryArray.COORDINATES | GeometryArray.TEXTURE_COORDINATE_2);
    private ImageComponent2D imageComponent;
    private Texture2D texture;
    private int texW = -1;
    private int texH = -1;
    private float previewW;
    private float previewH;

    private final List<String> names = new ArrayList<>();
    private final List<GlassyPanel> nameBgs = new ArrayList<>();
    private float rowH;
    private float textH;

    private String selectedName;
    private GlassyPanel selectedBg;
    private boolean loadingPreview;

    @Override
    public String displayName() {
        return "Appearance";
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
        float listBottom = -h * 0.5f + pad + h * 0.05f;
        float listH = listTop - listBottom;
        rowH = listH / 10.5f;
        textH = rowH * 0.46f;

        // ---- wallpaper name list (left) ----
        float listW = w * 0.34f;
        float listX = -w * 0.5f + pad;
        root.addChild(Ui3D.component(Ui3D.label("Wallpapers", listW, h * 0.04f,
                Ui3D.TEXT_DIM, GlassyText2D.Alignment.LEFT,
                listX, listTop + h * 0.03f, 0.001f)));
        nameList = new ScrollList3D(listW, listH, rowH, Ui3D.PANEL_BG);
        nameList.setTranslation(listX + listW * 0.5f, (listTop + listBottom) * 0.5f, 0.001f);
        root.addChild(nameList);

        // ---- preview (right) ----
        previewW = w * 0.5f;
        previewH = listH - btnH * 1.6f;
        float previewCX = listX + listW + pad + previewW * 0.5f;
        float previewCY = listBottom + btnH * 1.2f + previewH * 0.5f;

        // Frame panel behind the quad.
        root.addChild(Ui3D.component(Ui3D.at(
                Ui3D.panel(previewW + pad, previewH + pad, 0.002f, Ui3D.ROW_OFF),
                previewCX, previewCY, -0.002f)));

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

        quad.setCapability(GeometryArray.ALLOW_COORDINATE_WRITE);
        quad.setCapability(GeometryArray.ALLOW_TEXCOORD_WRITE);
        quad.setCoordinates(0, new float[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
        quad.setTextureCoordinates(0, 0, new float[] { 0, 0, 0, 0, 0, 0, 0, 0 });
        Component3D preview = new Component3D();
        preview.addChild(new Shape3D(quad, appearance));
        preview.setTranslation(previewCX, previewCY, 0.001f);
        root.addChild(preview);

        // ---- apply button + status ----
        float btnW = w * 0.14f;
        Button3D applyBtn = new Button3D("Apply", btnW, btnH, btnH * 0.4f,
                Ui3D.BUTTON_OFF, Ui3D.BUTTON_ON, Ui3D.TEXT_BRIGHT,
                new ActionNoArg() {
                    public void performAction(LgEventSource s) {
                        apply();
                    }
                });
        applyBtn.setTranslation(w * 0.5f - pad - btnW * 0.5f,
                listBottom - btnH * 0.2f, 0.001f);
        root.addChild(applyBtn);

        statusText = Ui3D.makeText("", w * 0.96f, h * 0.035f, Ui3D.TEXT_DIM,
                GlassyText2D.Alignment.LEFT);
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

    private void reload() {
        if (root == null) {
            return;
        }
        List<String> found = enumerate();
        boolean same = found.equals(names);
        names.clear();
        names.addAll(found);
        if (same && nameList.getRowCount() == names.size()) {
            return;
        }
        List<Component3D> rows = new ArrayList<>();
        nameBgs.clear();
        for (String n : names) {
            GlassyPanel bg = Ui3D.panel(nameList.getWidth(), rowH * 0.9f, 0.002f,
                    Ui3D.ROW_OFF);
            bg.setCapability(Shape3D.ALLOW_APPEARANCE_READ);
            bg.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
            nameBgs.add(bg);
            Component3D row = new Component3D();
            row.addChild(Ui3D.component(Ui3D.at(bg, 0f, 0f, -0.001f)));
            row.addChild(Ui3D.component(Ui3D.label(n,
                    nameList.getWidth() * 0.94f, textH, Ui3D.TEXT_BRIGHT,
                    GlassyText2D.Alignment.LEFT,
                    -nameList.getWidth() * 0.5f + rowH * 0.35f, 0f, 0.001f)));
            final String name = n;
            final GlassyPanel fbg = bg;
            row.addListener(new MouseClickedEventAdapter(new ActionNoArg() {
                public void performAction(LgEventSource s) {
                    select(name, fbg);
                }
            }));
            rows.add(row);
        }
        nameList.setRows(rows);
        selectedName = null;
        selectedBg = null;
        if (!names.isEmpty()) {
            select(names.get(0), nameBgs.get(0));
        }
    }

    private List<String> enumerate() {
        List<String> result = new ArrayList<>();
        URL dirUrl = getClass().getClassLoader().getResource(BG_DIR);
        if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
            try {
                File dir = new File(dirUrl.toURI());
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String lower = f.getName().toLowerCase();
                        if (f.isFile() && (lower.endsWith(".jpg")
                                || lower.endsWith(".jpeg") || lower.endsWith(".png"))) {
                            result.add(f.getName());
                        }
                    }
                }
            } catch (Exception e) {
                result.clear();
            }
        }
        Collections.sort(result);
        if (result.isEmpty()) {
            result.addAll(FALLBACK);
        }
        return result;
    }

    private void select(String name, GlassyPanel bg) {
        if (selectedBg != null && selectedBg != bg) {
            selectedBg.setAppearance(Ui3D.appearance(Ui3D.ROW_OFF));
        }
        selectedName = name;
        selectedBg = bg;
        bg.setAppearance(Ui3D.appearance(Ui3D.ROW_ON));
        loadPreview(name);
    }

    /** Decodes + downscales off the scene thread, uploads on the EDT. */
    private void loadPreview(String name) {
        if (loadingPreview) {
            return;
        }
        URL url = getClass().getClassLoader().getResource(BG_DIR + "/" + name);
        if (url == null) {
            statusText.setText("Wallpaper not found on the classpath: " + name);
            return;
        }
        loadingPreview = true;
        statusText.setText("Loading preview...");
        Thread loader = new Thread(() -> {
            Scaled scaled = null;
            try (InputStream in = url.openStream()) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) {
                    scaled = downscale(img);
                }
            } catch (Exception e) {
                // fall through: scaled stays null
            }
            final Scaled ready = scaled;
            SwingUtilities.invokeLater(() -> {
                loadingPreview = false;
                if (ready != null && name.equals(selectedName)) {
                    setPreview(ready);
                    statusText.setText("Selected: " + name);
                } else if (ready == null) {
                    statusText.setText("Preview unavailable: " + name);
                }
            });
        }, "AppearancePage3D:preview");
        loader.setDaemon(true);
        loader.start();
    }

    /** A decoded wallpaper: pow2 texture image plus its real (unpadded) size. */
    private static final class Scaled {
        final BufferedImage image;
        final int dispW;
        final int dispH;

        Scaled(BufferedImage image, int dispW, int dispH) {
            this.image = image;
            this.dispW = dispW;
            this.dispH = dispH;
        }
    }

    /** Fits the image into MAX_TEXTURE, padded to power-of-two dimensions. */
    private static Scaled downscale(BufferedImage img) {
        int imgW = img.getWidth();
        int imgH = img.getHeight();
        float fit = Math.min(1.0f, MAX_TEXTURE / (float) Math.max(imgW, imgH));
        int dispW = Math.max(1, Math.round(imgW * fit));
        int dispH = Math.max(1, Math.round(imgH * fit));
        int p2w = powerOfTwo(dispW);
        int p2h = powerOfTwo(dispH);
        BufferedImage out = new BufferedImage(p2w, p2h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(new java.awt.Color(0x1E, 0x22, 0x28));
            g.fillRect(0, 0, p2w, p2h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, dispW, dispH, null);
        } finally {
            g.dispose();
        }
        return new Scaled(out, dispW, dispH);
    }

    /**
     * Uploads the (already power-of-two, already scaled) image. Pixels go into
     * the ImageComponent2D before the texture is attached to the live
     * appearance - Appearance.setTexture on a live graph dereferences the
     * image data and NPEs on an empty component.
     */
    private void setPreview(Scaled s) {
        BufferedImage p2Image = s.image;
        int p2w = p2Image.getWidth();
        int p2h = p2Image.getHeight();
        if (imageComponent == null || p2w != texW || p2h != texH) {
            imageComponent = new ImageComponent2D(
                    ImageComponent2D.FORMAT_RGBA, p2w, p2h, false, true);
            imageComponent.setCapability(ImageComponent2D.ALLOW_IMAGE_WRITE);
        }
        imageComponent.set(p2Image);

        if (texture == null || p2w != texW || p2h != texH) {
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

        // Fit the quad into the preview box, preserving the image aspect, and
        // crop the texture to the real (unpadded) image region.
        float scale = Math.min(previewW / s.dispW, previewH / s.dispH);
        float qw = s.dispW * scale;
        float qh = s.dispH * scale;
        float hx = qw / 2.0f;
        float hy = qh / 2.0f;
        quad.setCoordinates(0, new float[] {
            -hx, -hy, 0.0f,
             hx, -hy, 0.0f,
             hx,  hy, 0.0f,
            -hx,  hy, 0.0f,
        });
        float sMax = s.dispW / (float) p2w;
        float tMax = s.dispH / (float) p2h;
        quad.setTextureCoordinates(0, 0, new float[] {
            0.0f, tMax,   // bottom-left
            sMax, tMax,   // bottom-right
            sMax, 0.0f,   // top-right
            0.0f, 0.0f,   // top-left
        });
    }

    private void apply() {
        if (selectedName == null) {
            statusText.setText("Select a wallpaper first.");
            return;
        }
        URL url = getClass().getClassLoader().getResource(BG_DIR + "/" + selectedName);
        if (url == null) {
            statusText.setText("Wallpaper not found on the classpath: " + selectedName);
            return;
        }
        try {
            SimpleImageBackground background = new SimpleImageBackground(url);
            LgEventConnector.getLgEventConnector().postEvent(
                    new BackgroundChangeRequestEvent(background), null);
            statusText.setText("Background applied: " + selectedName);
        } catch (RuntimeException e) {
            statusText.setText("Could not apply the background: " + e.getMessage());
        }
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
