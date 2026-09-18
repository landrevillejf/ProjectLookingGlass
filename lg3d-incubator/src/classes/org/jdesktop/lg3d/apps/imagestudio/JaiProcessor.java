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

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Arrays;

import javax.imageio.ImageIO;
import javax.media.jai.Histogram;
import javax.media.jai.Interpolation;
import javax.media.jai.JAI;
import javax.media.jai.KernelJAI;
import javax.media.jai.LookupTableJAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.TransposeDescriptor;

import java.awt.image.renderable.ParameterBlock;

import com.sun.media.jai.codec.ImageCodec;
import com.sun.media.jai.codec.ImageEncoder;

/**
 * The Java Advanced Imaging bridge for the Image Studio.
 *
 * <p>Every method is a thin, stateless wrapper around a vendored JAI operator
 * (the {@code javax.media.jai} jars under {@code lg3d-incubator/ext}). The
 * operator names and {@link ParameterBlock} argument orders below were verified
 * against the actual 2002-era registry on JDK 21 (see the introspection probe):
 * e.g. {@code rescale} computes {@code dst = src*constants + offsets}, {@code crop}
 * takes four {@code Float}s, {@code transpose} takes a {@code TransposeType}, and
 * {@code border} takes four {@code Integer}s.</p>
 *
 * <p>Two JDK-21 facts drive the design:</p>
 * <ul>
 *   <li>JAI's {@code RasterAccessor} touches {@code sun.awt.image.*}, so the
 *       runtime must export it ({@code --add-exports
 *       java.desktop/sun.awt.image=ALL-UNNAMED}); without that every op throws
 *       {@code IllegalAccessError}. The {@code :lg3d-core:run} task sets it.</li>
 *   <li>JAI's JPEG codec references the removed {@code com.sun.image.codec.jpeg},
 *       so PNG/JPEG always go through {@link ImageIO}; the JAI codec is used only
 *       as a guarded fallback for TIFF/BMP.</li>
 * </ul>
 *
 * <p>The canonical working format is {@link BufferedImage#TYPE_3BYTE_BGR}: a
 * byte, three-band model that every JAI point/filter op handles and that keeps
 * lookup tables valid. Each op result is normalized back to it, so operations
 * compose without sample-model drift. (Alpha, if present on load, is flattened.)</p>
 */
public final class JaiProcessor {

    private JaiProcessor() { }

    // ------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------

    /** Wrap any {@link RenderedImage} as a JAI {@link PlanarImage} (no copy). */
    public static PlanarImage toPlanar(RenderedImage img) {
        if (img instanceof PlanarImage) {
            return (PlanarImage) img;
        }
        return PlanarImage.wrapRenderedImage(img);
    }

    /**
     * Copy any rendered image into the canonical {@code TYPE_3BYTE_BGR} working
     * format. Always produces an independent image (safe to dispose the source).
     */
    public static BufferedImage normalize(RenderedImage img) {
        BufferedImage src;
        if (img instanceof BufferedImage) {
            src = (BufferedImage) img;
        } else if (img instanceof PlanarImage) {
            src = ((PlanarImage) img).getAsBufferedImage();
        } else {
            src = PlanarImage.wrapRenderedImage(img).getAsBufferedImage();
        }
        int w = Math.max(1, src.getWidth());
        int h = Math.max(1, src.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Run a single-source JAI operator with the given trailing parameters and
     * return the normalized {@code TYPE_3BYTE_BGR} result.
     */
    private static BufferedImage run(RenderedImage src, String opName, Object... params) {
        ParameterBlock pb = new ParameterBlock();
        pb.addSource(toPlanar(src));
        for (Object p : params) {
            pb.add(p);
        }
        RenderedOp op = JAI.create(opName, pb);
        BufferedImage bi = normalize(op);
        op.dispose();
        return bi;
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /** Uniform scale by {@code factor} (bilinear). */
    public static BufferedImage scale(RenderedImage src, float factor) {
        return scale(src, factor, factor);
    }

    /** Scale by independent x/y factors (bilinear). */
    public static BufferedImage scale(RenderedImage src, float xs, float ys) {
        float x = Math.max(0.01f, xs);
        float y = Math.max(0.01f, ys);
        return run(src, "scale",
                Float.valueOf(x), Float.valueOf(y), Float.valueOf(0f), Float.valueOf(0f),
                Interpolation.getInstance(Interpolation.INTERP_BILINEAR));
    }

    /** Rotate about the image centre by {@code angleDegrees} (bilinear). */
    public static BufferedImage rotate(RenderedImage src, float angleDegrees) {
        PlanarImage p = toPlanar(src);
        float cx = p.getWidth() / 2.0f;
        float cy = p.getHeight() / 2.0f;
        float radians = (float) Math.toRadians(angleDegrees);
        return run(src, "rotate",
                Float.valueOf(cx), Float.valueOf(cy), Float.valueOf(radians),
                Interpolation.getInstance(Interpolation.INTERP_BILINEAR));
    }

    public static BufferedImage flipHorizontal(RenderedImage src) {
        return run(src, "transpose", TransposeDescriptor.FLIP_HORIZONTAL);
    }

    public static BufferedImage flipVertical(RenderedImage src) {
        return run(src, "transpose", TransposeDescriptor.FLIP_VERTICAL);
    }

    public static BufferedImage rotate90(RenderedImage src) {
        return run(src, "transpose", TransposeDescriptor.ROTATE_90);
    }

    public static BufferedImage rotate180(RenderedImage src) {
        return run(src, "transpose", TransposeDescriptor.ROTATE_180);
    }

    public static BufferedImage rotate270(RenderedImage src) {
        return run(src, "transpose", TransposeDescriptor.ROTATE_270);
    }

    /** Crop to the given region, clamped to the image bounds. */
    public static BufferedImage crop(RenderedImage src, int x, int y, int w, int h) {
        PlanarImage p = toPlanar(src);
        int cx = clamp(x, 0, Math.max(0, p.getWidth() - 1));
        int cy = clamp(y, 0, Math.max(0, p.getHeight() - 1));
        int cw = clamp(w, 1, p.getWidth() - cx);
        int ch = clamp(h, 1, p.getHeight() - cy);
        return run(src, "crop",
                Float.valueOf(cx), Float.valueOf(cy), Float.valueOf(cw), Float.valueOf(ch));
    }

    /**
     * Symmetric border/frame of {@code pad} pixels filled with {@code rgb}
     * (JAI's {@code border} uses a {@code BorderExtender}; we extend with a
     * constant by compositing over a solid canvas of the padded size).
     */
    public static BufferedImage border(RenderedImage src, int pad, int rgb) {
        PlanarImage p = toPlanar(src);
        BufferedImage base = normalize(src);
        int w = base.getWidth() + 2 * pad;
        int h = base.getHeight() + 2 * pad;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(new java.awt.Color(rgb));
            g.fillRect(0, 0, w, h);
            g.drawImage(base, pad, pad, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Pixelate by downscaling to blocks of {@code block} px then back up (nearest). */
    public static BufferedImage pixelate(RenderedImage src, int block) {
        PlanarImage p = toPlanar(src);
        int b = Math.max(2, block);
        float factor = 1.0f / b;
        BufferedImage small = run(src, "scale",
                Float.valueOf(factor), Float.valueOf(factor), Float.valueOf(0f), Float.valueOf(0f),
                Interpolation.getInstance(Interpolation.INTERP_NEAREST));
        return run(small, "scale",
                Float.valueOf(p.getWidth() / (float) small.getWidth()),
                Float.valueOf(p.getHeight() / (float) small.getHeight()),
                Float.valueOf(0f), Float.valueOf(0f),
                Interpolation.getInstance(Interpolation.INTERP_NEAREST));
    }

    // ------------------------------------------------------------------
    // Point / colour
    // ------------------------------------------------------------------

    /** Brightness: {@code dst = src + amount} (amount in -255..255). */
    public static BufferedImage brightness(RenderedImage src, double amount) {
        return run(src, "rescale", new double[] { 1.0 }, new double[] { amount });
    }

    /** Contrast about mid-grey: {@code dst = src*gain + (1-gain)*128}. */
    public static BufferedImage contrast(RenderedImage src, double gain) {
        double g = Math.max(0.0, gain);
        return run(src, "rescale", new double[] { g }, new double[] { (1.0 - g) * 128.0 });
    }

    /** Combined {@code dst = src*gain + offset}. */
    public static BufferedImage rescale(RenderedImage src, double gain, double offset) {
        return run(src, "rescale", new double[] { gain }, new double[] { offset });
    }

    /** Gamma correction via a byte lookup table ({@code gamma} > 0). */
    public static BufferedImage gamma(RenderedImage src, double gamma) {
        double g = (gamma <= 0.0) ? 0.01 : gamma;
        byte[] curve = new byte[256];
        for (int i = 0; i < 256; i++) {
            double v = 255.0 * Math.pow(i / 255.0, 1.0 / g);
            curve[i] = (byte) clamp((int) Math.round(v), 0, 255);
        }
        return run(src, "lookup", new LookupTableJAI(replicate(curve)));
    }

    /** Luminance grayscale (kept as three identical bands). */
    public static BufferedImage grayscale(RenderedImage src) {
        // Working format is BGR, so weight the B,G,R samples accordingly. Each
        // bandcombine row carries numBands weights + 1 trailing constant column.
        double[] lum = { 0.114, 0.587, 0.299, 0.0 };
        double[][] m = { lum, lum, lum };
        // Cast to Object so the double[][] is passed as ONE ParameterBlock arg;
        // otherwise varargs spreads it (a double[][] is an Object[]).
        return run(src, "bandcombine", (Object) m);
    }

    /** Sepia tone via a 3x3 band-combine matrix (BGR working order). */
    public static BufferedImage sepia(RenderedImage src) {
        // Rows are the destination B',G',R' bands; columns the source B,G,R plus
        // the trailing constant column bandcombine requires.
        double[][] m = {
            { 0.131, 0.534, 0.272, 0.0 },
            { 0.168, 0.686, 0.349, 0.0 },
            { 0.189, 0.769, 0.393, 0.0 },
        };
        return run(src, "bandcombine", (Object) m);
    }

    /** Photographic negative. */
    public static BufferedImage invert(RenderedImage src) {
        return run(src, "invert");
    }

    /** Posterize to {@code levels} tonal steps per channel (2..256). */
    public static BufferedImage posterize(RenderedImage src, int levels) {
        int n = clamp(levels, 2, 256);
        double step = 255.0 / (n - 1);
        byte[] curve = new byte[256];
        for (int i = 0; i < 256; i++) {
            int q = (int) Math.round(Math.round(i / step) * step);
            curve[i] = (byte) clamp(q, 0, 255);
        }
        return run(src, "lookup", new LookupTableJAI(replicate(curve)));
    }

    /** Hard black/white threshold at {@code t} (0..255). */
    public static BufferedImage threshold(RenderedImage src, int t) {
        int tt = clamp(t, 0, 255);
        byte[] curve = new byte[256];
        for (int i = 0; i < 256; i++) {
            curve[i] = (byte) ((i < tt) ? 0 : 255);
        }
        return run(src, "lookup", new LookupTableJAI(replicate(curve)));
    }

    // ------------------------------------------------------------------
    // Filters (convolution)
    // ------------------------------------------------------------------

    /** Box/gaussian blur of the given radius (>=1). */
    public static BufferedImage blur(RenderedImage src, int radius) {
        int r = Math.max(1, radius);
        return run(src, "convolve", gaussianKernel(r));
    }

    /** Sharpen of the given strength (0..1 scaled into the kernel centre). */
    public static BufferedImage sharpen(RenderedImage src, float amount) {
        float a = Math.max(0.05f, amount);
        float[] k = {
            0, -a, 0,
            -a, 1 + 4 * a, -a,
            0, -a, 0,
        };
        return run(src, "convolve", new KernelJAI(3, 3, k));
    }

    /** Emboss effect. */
    public static BufferedImage emboss(RenderedImage src) {
        float[] k = {
            -2, -1, 0,
            -1, 1, 1,
            0, 1, 2,
        };
        return run(src, "convolve", new KernelJAI(3, 3, k));
    }

    /** Laplacian edge detection. */
    public static BufferedImage edge(RenderedImage src) {
        float[] k = {
            -1, -1, -1,
            -1, 8, -1,
            -1, -1, -1,
        };
        return run(src, "convolve", new KernelJAI(3, 3, k));
    }

    /** Convolve with a caller-supplied square kernel (side = sqrt(len)). */
    public static BufferedImage convolve(RenderedImage src, float[] kernel, int side) {
        return run(src, "convolve", new KernelJAI(side, side, kernel));
    }

    // ------------------------------------------------------------------
    // Math / logic
    // ------------------------------------------------------------------

    public static BufferedImage addConst(RenderedImage src, double c) {
        return run(src, "addconst", new double[] { c });
    }

    public static BufferedImage subtractConst(RenderedImage src, double c) {
        return run(src, "subtractconst", new double[] { c });
    }

    public static BufferedImage multiplyConst(RenderedImage src, double c) {
        return run(src, "multiplyconst", new double[] { c });
    }

    public static BufferedImage absolute(RenderedImage src) {
        return run(src, "absolute");
    }

    public static BufferedImage andConst(RenderedImage src, int c) {
        return run(src, "andconst", new int[] { c });
    }

    public static BufferedImage orConst(RenderedImage src, int c) {
        return run(src, "orconst", new int[] { c });
    }

    public static BufferedImage xorConst(RenderedImage src, int c) {
        return run(src, "xorconst", new int[] { c });
    }

    /**
     * Add uniform random noise of the given amplitude. JAI in this build has no
     * {@code addnoise} operator, so this is done directly on the pixels (the one
     * non-JAI op, kept here so the editor's operation surface stays complete).
     */
    public static BufferedImage noise(RenderedImage src, int amplitude) {
        BufferedImage base = normalize(src);
        int amp = Math.max(0, amplitude);
        int w = base.getWidth();
        int h = base.getHeight();
        java.util.Random rnd = new java.util.Random();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = base.getRGB(x, y);
                int n = rnd.nextInt(2 * amp + 1) - amp;
                int r = clamp(((rgb >> 16) & 0xFF) + n, 0, 255);
                int gg = clamp(((rgb >> 8) & 0xFF) + n, 0, 255);
                int b = clamp((rgb & 0xFF) + n, 0, 255);
                base.setRGB(x, y, 0xFF000000 | (r << 16) | (gg << 8) | b);
            }
        }
        return base;
    }

    // ------------------------------------------------------------------
    // Analysis
    // ------------------------------------------------------------------

    /**
     * Compute a 256-bin-per-band histogram over every pixel. Returns the JAI
     * {@link Histogram} (independent of the op, which is disposed here).
     */
    public static Histogram histogram(RenderedImage src) {
        RenderedOp op = JAI.create("histogram", toPlanar(src));
        Object prop = op.getProperty("histogram");
        op.dispose();
        if (prop instanceof Histogram) {
            return (Histogram) prop;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // I/O
    // ------------------------------------------------------------------

    /**
     * Load an image: {@link ImageIO} first (PNG/JPEG/GIF/BMP/TIFF on JDK 21),
     * then JAI's {@code fileload} (its own codecs) as a fallback for anything
     * ImageIO cannot decode. Returns a normalized working image, or null.
     */
    public static BufferedImage load(Path file) throws IOException {
        File f = file.toFile();
        BufferedImage bi = ImageIO.read(f);
        if (bi != null) {
            return normalize(bi);
        }
        // JAI codec fallback (old TIFF/BMP variants, etc.).
        try {
            RenderedOp op = JAI.create("fileload", f.getAbsolutePath());
            if (op != null) {
                BufferedImage loaded = normalize(op);
                op.dispose();
                return loaded;
            }
        } catch (Throwable t) {
            // fall through to the IOException below
        }
        throw new IOException("Unsupported or unreadable image: " + f);
    }

    /**
     * Save in the given format ("png", "jpeg", "bmp", "tiff", "gif"). PNG/JPEG
     * always use {@link ImageIO}; if no ImageIO writer exists (or the format is
     * TIFF/BMP) a guarded JAI-codec encoder is tried. Returns true on success.
     */
    public static boolean save(BufferedImage image, Path file, String format) throws IOException {
        String fmt = (format == null) ? "png" : format.toLowerCase();
        BufferedImage toWrite = image;
        if (fmt.equals("jpeg") || fmt.equals("jpg")) {
            fmt = "jpeg";
            // JPEG has no alpha: flatten onto white.
            if (image.getColorModel().hasAlpha()) {
                BufferedImage rgb = new BufferedImage(
                        image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                try {
                    g.setColor(java.awt.Color.WHITE);
                    g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                    g.drawImage(image, 0, 0, null);
                } finally {
                    g.dispose();
                }
                toWrite = rgb;
            }
        }
        File f = file.toFile();
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (ImageIO.write(toWrite, fmt, f)) {
            return true;
        }
        // Guarded JAI-codec fallback (TIFF/BMP). Never used for JPEG: JAI's JPEG
        // encoder references the removed com.sun.image.codec.jpeg.
        if (fmt.equals("tiff") || fmt.equals("bmp")) {
            OutputStream os = null;
            try {
                os = new FileOutputStream(f);
                ImageEncoder enc = ImageCodec.createImageEncoder(fmt, os, null);
                if (enc != null) {
                    enc.encode(toPlanar(toWrite));
                    return true;
                }
            } finally {
                if (os != null) {
                    try { os.close(); } catch (IOException ignore) { }
                }
            }
        }
        return false;
    }

    /** Best-effort format name from a file extension (defaults to "png"). */
    public static String formatForPath(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        String ext = (dot >= 0) ? name.substring(dot + 1) : "";
        if (ext.equals("jpg")) {
            return "jpeg";
        }
        if (Arrays.asList("png", "jpeg", "bmp", "tiff", "tif", "gif").contains(ext)) {
            return ext.equals("tif") ? "tiff" : ext;
        }
        return "png";
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static KernelJAI gaussianKernel(int radius) {
        int n = 2 * radius + 1;
        float[] k = new float[n * n];
        float sigma = Math.max(0.5f, radius / 2.0f);
        float sum = 0f;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double dx = x - radius;
                double dy = y - radius;
                float v = (float) Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
                k[y * n + x] = v;
                sum += v;
            }
        }
        for (int i = 0; i < k.length; i++) {
            k[i] /= sum;
        }
        return new KernelJAI(n, n, k);
    }

    /** Replicate a single-band byte curve across three bands (BGR working image). */
    private static byte[][] replicate(byte[] curve) {
        byte[][] data = new byte[3][];
        for (int b = 0; b < 3; b++) {
            data[b] = curve.clone();
        }
        return data;
    }

    private static int clamp(int v, int lo, int hi) {
        return (v < lo) ? lo : ((v > hi) ? hi : v);
    }
}
