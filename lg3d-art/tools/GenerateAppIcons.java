import com.protonmail.landrevillejf.IconColor;
import com.protonmail.landrevillejf.IconManager;
import com.protonmail.landrevillejf.IconManager.CompositeArrangement;
import com.protonmail.landrevillejf.IconManager.IconCategory;
import com.protonmail.landrevillejf.IconManager.IconStyle;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * One-shot asset generator: renders the start-menu icons for the LG3D apps that
 * ship without one (they would otherwise fall back to the generic
 * {@code defaultapp.png}) and writes them into the lg3d-core icon resource tree.
 *
 * <p>Each icon is produced entirely by the bundled {@code IconManager} library
 * ({@code libs/IconManager-1.6.0.jar}): a vivid gradient/glass tile in a
 * per-app colour with the most semantically fitting {@code toolbarButtonGraphics}
 * glyph overlaid on top, exported at 48x48 to match {@code defaultapp.png}.
 *
 * <p>The bundled glyphs only exist at 16px and 24px, and {@code loadIcon} builds
 * its filename as {@code <name><height>.gif}, so we load at 24 and
 * {@code resizeIcon} up; asking for 48 directly silently returns the
 * {@code MissingIcon} fallback.
 *
 * <p>Not part of any Gradle source set (lg3d-art is excluded from the build);
 * run it manually from the repository root:
 * <pre>
 *   export JAVA_HOME=&lt;jdk21&gt;
 *   javac -cp libs/IconManager-1.6.0.jar -d build-gradle/scratch \
 *       lg3d-art/tools/GenerateAppIcons.java
 *   java  -Djava.awt.headless=true \
 *       -cp libs/IconManager-1.6.0.jar:build-gradle/scratch GenerateAppIcons
 * </pre>
 * The generated PNGs are committed; re-running is only needed to regenerate them.
 */
public class GenerateAppIcons {

    /** Output tree assembled onto the run classpath as {@code resources/images/icon/}. */
    private static final String OUT_DIR = "lg3d-core/src/resources/images/icon";

    /** Icon size, matching defaultapp.png. */
    private static final int SIZE = 48;

    /** Glyph overlay size on top of the tile. */
    private static final int GLYPH = 32;

    /** Glyph name that draws a built-in vector keypad instead of a bundled glyph. */
    private static final String KEYPAD_GLYPH = "CalculatorKeypad";

    /** Glyph name that draws a built-in vector optical disc instead of a bundled glyph. */
    private static final String DISC_GLYPH = "MediaDisc";

    /** Glyph name that draws a built-in vector paint brush instead of a bundled glyph. */
    private static final String BRUSH_GLYPH = "PaintBrush";

    /** app icon file, tile colour, glyph category, glyph name. */
    private static final Object[][] APPS = {
        {"imagestudio.png", IconColor.ORANGE, IconCategory.GENERAL,     "Edit"},
        {"luncher.png",     IconColor.BLUE,   IconCategory.DEVELOPMENT, "Application"},
        {"nlc.png",         IconColor.PURPLE, IconCategory.TEXT,        "Normal"},
        {"chart3d.png",     IconColor.GREEN,  IconCategory.TABLE,       "ColumnInsertAfter"},
        {"contact3d.png",   IconColor.TEAL,   IconCategory.GENERAL,     "ComposeMail"},
        {"agenda3d.png",    IconColor.RED,    IconCategory.GENERAL,     "History"},
        {"mail3d.png",      IconColor.INDIGO, IconCategory.GENERAL,     "SendMail"},
        // Native 3D games (grid glyphs for the board games, a magnifier for the
        // chess engine's search, overlapping pages for the solitaire card fan).
        {"tictactoe.png",   IconColor.DEEP_ORANGE, IconCategory.TABLE,  "RowInsertAfter"},
        {"sudoku.png",      IconColor.INDIGO,      IconCategory.TABLE,  "ColumnInsertBefore"},
        {"chess.png",       IconColor.BLUE_GRAY,   IconCategory.GENERAL, "Find"},
        {"solitaire.png",   IconColor.GREEN,       IconCategory.GENERAL, "Copy"},
        // Advanced calculator (Swing panel hosted on a SwingNode); the bundled
        // glyph set has no calculator, so the keypad glyph is drawn in-tool.
        {"calculator.png",  IconColor.TEAL,        IconCategory.GENERAL, KEYPAD_GLYPH},
        // Media Writer (Swing panel hosted on a SwingNode); the bundled glyph
        // set has no optical disc, so the disc glyph is drawn in-tool.
        {"mediawriter.png", IconColor.BLUE,        IconCategory.GENERAL, DISC_GLYPH},
        // Paint (conventional Swing JFrame captured into the desktop); the
        // bundled glyph set has no paint brush, so it is drawn in-tool.
        {"paint.png",       IconColor.PINK,        IconCategory.GENERAL, BRUSH_GLYPH},
    };

    public static void main(String[] args) throws Exception {
        File outDir = new File(OUT_DIR);
        if (!outDir.isDirectory()) {
            throw new IllegalStateException("icon resource dir not found: " + outDir.getAbsolutePath());
        }
        for (Object[] app : APPS) {
            String file = (String) app[0];
            IconColor color = (IconColor) app[1];
            IconCategory category = (IconCategory) app[2];
            String glyphName = (String) app[3];

            Icon glyph;
            if (KEYPAD_GLYPH.equals(glyphName)) {
                glyph = drawKeypadGlyph(GLYPH);
            } else if (DISC_GLYPH.equals(glyphName)) {
                glyph = drawDiscGlyph(GLYPH);
            } else if (BRUSH_GLYPH.equals(glyphName)) {
                glyph = drawBrushGlyph(GLYPH);
            } else {
                glyph = IconManager.resizeIcon(
                    IconManager.loadIconWithFallback(category, glyphName, 24, 24), GLYPH, GLYPH);
                if (glyph.getClass().getSimpleName().contains("Missing")) {
                    throw new IllegalStateException("no bundled glyph for " + file
                        + " (" + category + "/" + glyphName + ")");
                }
            }
            Icon background = IconManager.createGradientIcon(
                color.getColor(), color.getColor().darker(), "", SIZE, IconStyle.GLASS);
            Icon icon = IconManager.createCompositeIcon(
                new Icon[]{background, glyph}, CompositeArrangement.OVERLAY);

            File out = new File(outDir, file);
            IconManager.exportIcon(icon, out.getAbsolutePath(), "png");
            System.out.println("wrote " + out.getPath());
        }
    }

    /**
     * Draws the calculator keypad glyph: a display strip above a 4x3 key grid.
     * The bundled {@code toolbarButtonGraphics} set carries nothing calculator
     * shaped, and a misleading glyph (grid arrows, mail) reads worse than a
     * purpose-drawn keypad.
     */
    private static Icon drawKeypadGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRoundRect(2, 2, size - 4, 7, 3, 3);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 4; col++) {
                g.fillRoundRect(2 + col * 7, 12 + row * 6, 6, 5, 2, 2);
            }
        }
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the optical disc glyph: a white disc with a transparent spindle
     * hole and a faint data groove. The bundled {@code toolbarButtonGraphics}
     * set carries nothing disc shaped, so it is drawn in-tool like the keypad.
     */
    private static Icon drawDiscGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        int d = size - 4;
        g.fillOval(2, 2, d, d);
        // punch the spindle hole and a concentric data groove out of the disc
        g.setComposite(AlphaComposite.Clear);
        int hole = Math.max(5, size / 5);
        g.fillOval((size - hole) / 2, (size - hole) / 2, hole, hole);
        g.setStroke(new BasicStroke(Math.max(1f, size / 24f)));
        int ring = d / 2;
        g.drawOval((size - ring) / 2, (size - ring) / 2, ring, ring);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the paint brush glyph: a rounded handle, a ferrule band and a
     * tapered bristle tip, rotated so the brush runs diagonally (handle to the
     * upper right, tip to the lower left). The bundled
     * {@code toolbarButtonGraphics} set carries nothing brush shaped, so it is
     * drawn in-tool like the keypad and disc. Designed in a 32x32 space and
     * scaled to {@code size}.
     */
    private static Icon drawBrushGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        g.rotate(Math.toRadians(45), 16, 16);
        // Handle.
        g.setColor(Color.WHITE);
        g.fillRoundRect(13, 1, 6, 15, 3, 3);
        // Ferrule band.
        g.setColor(new Color(0xDD, 0xDD, 0xDD));
        g.fillRect(12, 15, 8, 3);
        // Tapered bristle tip.
        g.setColor(Color.WHITE);
        GeneralPath tip = new GeneralPath();
        tip.moveTo(12, 18);
        tip.lineTo(20, 18);
        tip.lineTo(17, 28);
        tip.quadTo(16, 31, 15, 28);
        tip.closePath();
        g.fill(tip);
        g.dispose();
        return new ImageIcon(image);
    }
}
