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
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;

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
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.action.ScaleActionBoolean;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MouseEnteredEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;

/**
 * The bottom strip: a filmstrip of {@code ~/Pictures} thumbnails plus
 * Open/Save/Save As controls.
 *
 * <p>Clicking a thumbnail loads that file into the model. The three buttons pop
 * native Swing {@link JFileChooser}s (the approved pragmatic exception to the
 * all-3D UI) so arbitrary paths can be opened and written. {@code Save} writes
 * back to the current path, or -- if the image was never loaded from disk -- to
 * {@code ~/Pictures/lg3d-imagestudio/<name>-<timestamp>.png}.</p>
 *
 * <p>Thumbnails are decoded subsampled (via {@link ImageReader}) so scanning the
 * folder never loads full-resolution images, then cover-cropped into a
 * power-of-two cell and shown on an opaque textured quad.</p>
 */
public class FileStrip3D extends Component3D {

    private static final int MAX_THUMBS = 12;
    private static final int CELL = 64;
    private static final Color CELL_BG = new Color(0x1A, 0x1E, 0x26);
    private static final List<String> EXTS = Arrays.asList(
            "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff");

    private static final FileFilter IMAGE_FILTER = new FileFilter() {
        public boolean accept(File f) {
            if (f.isDirectory()) {
                return true;
            }
            return isImageName(f.getName());
        }
        public String getDescription() {
            return "Images (png, jpeg, gif, bmp, tiff)";
        }
    };

    private final float width;
    private final float height;
    private final EditorModel model;

    /** X (local) where the filmstrip cells begin, set while building buttons. */
    private float filmstripLeft;

    public FileStrip3D(float width, float height, EditorModel model) {
        this.width = width;
        this.height = height;
        this.model = model;

        TransparencyOrderedGroup chrome = new TransparencyOrderedGroup();
        chrome.addChild(Ui3D.at(Ui3D.panel(width, height, 0.004f, Ui3D.PANEL_BG), 0f, 0f, 0f));
        addChild(chrome);

        buildButtons();
        buildFilmstrip();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private void buildButtons() {
        float pad = height * 0.14f;
        float btnH = height * 0.46f;
        float btnW = width * 0.075f;
        float gap = width * 0.008f;
        float textH = btnH * 0.34f;
        float x = -width * 0.5f + pad + btnW * 0.5f;

        Component3D open = Ui3D.button("Open", btnW, btnH, textH,
                Ui3D.ACTION_OFF, Ui3D.ACTION_ON, Ui3D.TEXT_BRIGHT,
                new ActionNoArg() { public void performAction(LgEventSource s) { openDialog(); } });
        open.setTranslation(x, 0f, 0.005f);
        addChild(open);
        x += btnW + gap;

        Component3D save = Ui3D.button("Save", btnW, btnH, textH,
                Ui3D.ACTION_OFF, Ui3D.ACTION_ON, Ui3D.TEXT_BRIGHT,
                new ActionNoArg() { public void performAction(LgEventSource s) { save(); } });
        save.setTranslation(x, 0f, 0.005f);
        addChild(save);
        x += btnW + gap;

        Component3D saveAs = Ui3D.button("Save As", btnW, btnH, textH,
                Ui3D.ACTION_OFF, Ui3D.ACTION_ON, Ui3D.TEXT_BRIGHT,
                new ActionNoArg() { public void performAction(LgEventSource s) { saveAsDialog(); } });
        saveAs.setTranslation(x, 0f, 0.005f);
        addChild(saveAs);

        // Divider + "Pictures" caption before the filmstrip.
        float captionX = x + btnW * 0.5f + gap * 2f;
        float capH = height * 0.22f;
        addChild(Ui3D.label("Pictures \u203A", width * 0.12f, capH, Ui3D.TEXT_DIM,
                GlassyText2D.Alignment.LEFT, captionX, 0f, 0.005f));
        filmstripLeft = captionX + width * 0.075f;
    }

    private void buildFilmstrip() {
        List<Path> files = scanPictures();
        float pad = height * 0.14f;
        float availW = width * 0.5f - pad - filmstripLeft;
        if (files.isEmpty()) {
            float hintH = height * 0.24f;
            addChild(Ui3D.label("No images found in ~/Pictures", availW, hintH,
                    Ui3D.TEXT_DIM, GlassyText2D.Alignment.LEFT,
                    filmstripLeft, 0f, 0.005f));
            return;
        }
        int n = Math.min(files.size(), MAX_THUMBS);
        float gap = height * 0.10f;
        float cell = Math.min(height * 0.68f, (availW - (n - 1) * gap) / n);
        float x = filmstripLeft + cell * 0.5f;
        for (int i = 0; i < n; i++) {
            final Path p = files.get(i);
            BufferedImage thumb = readThumb(p.toFile(), CELL);
            if (thumb == null) {
                continue;
            }
            Component3D t = makeThumb(thumb, cell, p);
            t.setTranslation(x, 0f, 0.005f);
            addChild(t);
            x += cell + gap;
        }
    }

    private Component3D makeThumb(BufferedImage img, float size, final Path path) {
        BufferedImage cell = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = cell.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setColor(CELL_BG);
            g.fillRect(0, 0, CELL, CELL);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int sw = Math.max(1, img.getWidth());
            int sh = Math.max(1, img.getHeight());
            double s = Math.max(CELL / (double) sw, CELL / (double) sh);
            int dw = (int) Math.round(sw * s);
            int dh = (int) Math.round(sh * s);
            g.drawImage(img, (CELL - dw) / 2, (CELL - dh) / 2, dw, dh, null);
        } finally {
            g.dispose();
        }

        ImageComponent2D ic = new ImageComponent2D(
                ImageComponent2D.FORMAT_RGBA, CELL, CELL, false, true);
        ic.setCapability(ImageComponent2D.ALLOW_IMAGE_WRITE);
        ic.set(cell);
        Texture2D tex = new Texture2D(Texture2D.BASE_LEVEL, Texture2D.RGBA, CELL, CELL);
        tex.setMinFilter(Texture2D.BASE_LEVEL_LINEAR);
        tex.setMagFilter(Texture2D.BASE_LEVEL_LINEAR);
        tex.setBoundaryModeS(Texture2D.CLAMP);
        tex.setBoundaryModeT(Texture2D.CLAMP);
        tex.setImage(0, ic);

        Appearance app = new Appearance();
        TextureAttributes ta = new TextureAttributes();
        ta.setTextureMode(TextureAttributes.REPLACE);
        app.setTextureAttributes(ta);
        app.setTexture(tex);
        app.setPolygonAttributes(new PolygonAttributes(
                PolygonAttributes.POLYGON_FILL, PolygonAttributes.CULL_NONE,
                0.0f, false, 0.0f));
        app.setTransparencyAttributes(new TransparencyAttributes(
                TransparencyAttributes.BLENDED, 0.0f,
                TransparencyAttributes.BLEND_SRC_ALPHA,
                TransparencyAttributes.BLEND_ONE_MINUS_SRC_ALPHA));

        float hs = size * 0.5f;
        QuadArray quad = new QuadArray(4,
                GeometryArray.COORDINATES | GeometryArray.TEXTURE_COORDINATE_2);
        quad.setCoordinates(0, new float[] {
            -hs, -hs, 0.0f,  hs, -hs, 0.0f,  hs, hs, 0.0f,  -hs, hs, 0.0f });
        quad.setTextureCoordinates(0, 0, new float[] {
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f });

        Component3D c = new Component3D();
        c.addChild(new Shape3D(quad, app));
        c.setCursor(Cursor3D.SMALL_CURSOR);
        c.addListener(new MouseEnteredEventAdapter(new ScaleActionBoolean(c, 1.14f, 120)));
        c.addListener(new MouseClickedEventAdapter(new ActionNoArg() {
            public void performAction(LgEventSource s) {
                loadPath(path);
            }
        }));
        return c;
    }

    // ------------------------------------------------------------------
    // Model operations
    // ------------------------------------------------------------------

    private void loadPath(Path path) {
        try {
            BufferedImage img = JaiProcessor.load(path);
            model.setImage(img, path);
        } catch (Throwable t) {
            model.setStatus("Could not open " + path.getFileName()
                    + ": " + t.getClass().getSimpleName());
        }
    }

    private void save() {
        Path p = model.getPath();
        if (p == null) {
            p = exportPath();
        }
        doSave(p);
    }

    private void doSave(Path path) {
        if (model.getCurrent() == null) {
            model.setStatus("Nothing to save");
            return;
        }
        try {
            String fmt = JaiProcessor.formatForPath(path);
            boolean ok = JaiProcessor.save(model.getCurrent(), path, fmt);
            if (ok) {
                model.setPath(path);
                model.setStatus("Saved " + path.getFileName());
            } else {
                model.setStatus("No writer available for " + fmt);
            }
        } catch (Throwable t) {
            model.setStatus("Save failed: " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // Native dialogs (Swing)
    // ------------------------------------------------------------------

    private void openDialog() {
        final File dir = dirFile(lastDir());
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    JFileChooser fc = new JFileChooser(dir);
                    fc.setDialogTitle("Open Image");
                    fc.setFileFilter(IMAGE_FILTER);
                    if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        File f = fc.getSelectedFile();
                        if (f != null) {
                            loadPath(f.toPath());
                        }
                    }
                } catch (Throwable t) {
                    model.setStatus("Open dialog unavailable: "
                            + t.getClass().getSimpleName());
                }
            }
        });
    }

    private void saveAsDialog() {
        final File dir = dirFile(lastDir());
        final File suggested = new File(dir, suggestName());
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    JFileChooser fc = new JFileChooser(dir);
                    fc.setDialogTitle("Save Image As");
                    fc.setSelectedFile(suggested);
                    fc.setFileFilter(IMAGE_FILTER);
                    if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                        File f = fc.getSelectedFile();
                        if (f != null) {
                            doSave(f.toPath());
                        }
                    }
                } catch (Throwable t) {
                    model.setStatus("Save dialog unavailable: "
                            + t.getClass().getSimpleName());
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // File helpers
    // ------------------------------------------------------------------

    private List<Path> scanPictures() {
        List<Path> out = new ArrayList<Path>();
        Path dir = picturesDir();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        DirectoryStream<Path> stream = null;
        try {
            stream = Files.newDirectoryStream(dir);
            for (Path p : stream) {
                if (Files.isRegularFile(p) && isImageName(p.getFileName().toString())) {
                    out.add(p);
                }
            }
        } catch (Throwable t) {
            // ignore scan failures; an empty strip is acceptable
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Exception ignore) { }
            }
        }
        Collections.sort(out);
        return out;
    }

    private static Path picturesDir() {
        Path p = Paths.get(System.getProperty("user.home"), "Pictures");
        return Files.isDirectory(p) ? p : null;
    }

    private Path lastDir() {
        Path cur = model.getPath();
        if (cur != null && cur.getParent() != null) {
            return cur.getParent();
        }
        Path pics = picturesDir();
        if (pics != null) {
            return pics;
        }
        return Paths.get(System.getProperty("user.home"));
    }

    private Path exportPath() {
        Path dir = Paths.get(System.getProperty("user.home"), "Pictures", "lg3d-imagestudio");
        String base = (model.getPath() != null)
                ? stripExt(model.getPath().getFileName().toString())
                : "image";
        return dir.resolve(base + "-" + System.currentTimeMillis() + ".png");
    }

    private String suggestName() {
        if (model.getPath() != null) {
            return model.getPath().getFileName().toString();
        }
        return "image-" + System.currentTimeMillis() + ".png";
    }

    private static File dirFile(Path dir) {
        File f = (dir != null) ? dir.toFile() : null;
        return (f != null && f.isDirectory()) ? f : new File(System.getProperty("user.home"));
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    private static boolean isImageName(String name) {
        String lower = name.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return false;
        }
        return EXTS.contains(lower.substring(dot + 1));
    }

    /** Decode a subsampled thumbnail so full-resolution data is never loaded. */
    private static BufferedImage readThumb(File f, int target) {
        ImageInputStream iis = null;
        ImageReader reader = null;
        try {
            iis = ImageIO.createImageInputStream(f);
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            reader = readers.next();
            reader.setInput(iis, true, true);
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            int sub = Math.max(1, Math.max(w, h) / target);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(sub, sub, 0, 0);
            return reader.read(0, param);
        } catch (Throwable t) {
            return null;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
            if (iis != null) {
                try { iis.close(); } catch (Exception ignore) { }
            }
        }
    }
}
