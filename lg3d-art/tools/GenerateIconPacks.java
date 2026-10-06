import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * One-shot asset generator for the 2D desktop's <em>bundled</em> icon packs.
 *
 * <p>It reads every app icon already committed under
 * {@code lg3d-core/src/resources/images/icon/} and writes a re-styled copy of
 * the whole set into {@code lg3d-core/src/resources/images/icon-packs/<pack>/},
 * keeping each file's base name so {@code IconPackManager} can override an app
 * by matching the descriptor icon's base name. Two colour variants ship:
 * <b>mono</b> (a neutral greyscale rendition) and <b>vivid</b> (a
 * saturation/contrast boost). Because the file names are unchanged, a pack
 * overrides exactly the icons the desktop already resolves and nothing else.</p>
 *
 * <p>The transforms are pure Java 2D pixel ops over the committed PNGs - no
 * external library, fully deterministic and headless - so re-running regenerates
 * the packs byte-for-byte. Like {@code GenerateAppIcons}, this tool lives in
 * {@code lg3d-art} (excluded from the Gradle build) and is run manually from the
 * repository root:</p>
 * <pre>
 *   export JAVA_HOME=&lt;jdk21&gt;
 *   javac -d build-gradle/scratch lg3d-art/tools/GenerateIconPacks.java
 *   java  -Djava.awt.headless=true -cp build-gradle/scratch GenerateIconPacks
 * </pre>
 * The generated PNGs are committed; the {@code :lg3d-core:runtimeResources} task
 * then assembles {@code lg3d-core/src/resources} onto the run classpath, so the
 * packs land at {@code resources/images/icon-packs/<pack>/}.
 */
public class GenerateIconPacks {

    /** The committed app-icon tree the variants are derived from. */
    private static final String SRC_DIR = "lg3d-core/src/resources/images/icon";

    /** Output root, assembled onto the run classpath as {@code resources/images/icon-packs}. */
    private static final String OUT_ROOT = "lg3d-core/src/resources/images/icon-packs";

    /** The bundled pack ids (directory names) this tool renders. */
    private static final String[] PACKS = {"mono", "vivid"};

    public static void main(String[] args) throws Exception {
        File src = new File(SRC_DIR);
        File[] pngs = src.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (pngs == null || pngs.length == 0) {
            throw new IllegalStateException("no source icons found in " + src.getAbsolutePath());
        }
        Arrays.sort(pngs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        for (String pack : PACKS) {
            File outDir = new File(new File(OUT_ROOT), pack);
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                throw new IllegalStateException("could not create " + outDir.getAbsolutePath());
            }
            int written = 0;
            for (File png : pngs) {
                BufferedImage image = ImageIO.read(png);
                if (image == null) {
                    System.out.println("skip (unreadable): " + png.getName());
                    continue;
                }
                BufferedImage variant = transform(image, pack);
                File out = new File(outDir, png.getName());
                ImageIO.write(variant, "png", out);
                written++;
            }
            System.out.println("pack " + pack + ": wrote " + written + " icons into " + outDir.getPath());
        }
    }

    /** Applies the named pack's colour transform, preserving the alpha channel. */
    private static BufferedImage transform(BufferedImage in, String pack) {
        int w = in.getWidth();
        int h = in.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = in.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a == 0) {
                    out.setRGB(x, y, 0x00000000);
                    continue;
                }
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int[] rgb = "mono".equals(pack) ? mono(r, g, b) : vivid(r, g, b);
                out.setRGB(x, y, (a << 24) | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]);
            }
        }
        return out;
    }

    /** A neutral greyscale rendition (Rec. 601 luma), alpha untouched. */
    private static int[] mono(int r, int g, int b) {
        int lum = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
        lum = clamp(lum);
        return new int[] {lum, lum, lum};
    }

    /** Boosts saturation and contrast for a punchier, more colourful tile. */
    private static int[] vivid(int r, int g, int b) {
        float[] hsb = Color.RGBtoHSB(r, g, b, null);
        float s = clamp01(hsb[1] * 1.35f + 0.10f);
        float v = clamp01(hsb[2] * 1.05f);
        int rgb = Color.HSBtoRGB(hsb[0], s, v);
        return new int[] {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
