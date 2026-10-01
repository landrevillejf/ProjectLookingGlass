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

    /** Glyph name that draws a built-in vector package box instead of a bundled glyph. */
    private static final String PACKAGE_GLYPH = "PackageBox";

    /** Glyph name that draws a built-in vector database cylinder instead of a bundled glyph. */
    private static final String DATABASE_GLYPH = "DatabaseCylinder";

    /** Glyph name that draws a built-in vector transfer (up/down arrow) pair instead of a bundled glyph. */
    private static final String TRANSFER_GLYPH = "TransferArrows";

    /** Glyph name that draws a built-in vector sun-behind-cloud instead of a bundled glyph. */
    private static final String WEATHER_GLYPH = "WeatherSunCloud";

    /** Glyph name that draws a built-in vector document page instead of a bundled glyph. */
    private static final String DOCUMENT_GLYPH = "DocumentPage";

    /** Glyph name that draws a built-in vector video camera instead of a bundled glyph. */
    private static final String VIDEO_GLYPH = "VideoCamera";

    /** Glyph name that draws a built-in vector chat-bubble pair instead of a bundled glyph. */
    private static final String CHAT_GLYPH = "ChatBubbles";

    /** Glyph name that draws a built-in vector backup mark (down arrow into an archive tray) instead of a bundled glyph. */
    private static final String BACKUP_GLYPH = "BackupArchive";

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
        // LPM Console (standalone Swing package manager captured into the
        // desktop); the bundled glyph set has no package box, so it is drawn
        // in-tool like the keypad, disc and brush.
        {"lpm-console.png", IconColor.GREEN,       IconCategory.GENERAL, PACKAGE_GLYPH},
        // Database Manager (standalone JDBC client captured into the desktop);
        // the bundled glyph set has no database cylinder, so it is drawn in-tool
        // like the keypad, disc, brush and package box.
        {"dbmanager.png",   IconColor.TEAL,        IconCategory.GENERAL, DATABASE_GLYPH},
        // FTP Client (standalone FTP/FTPS/SFTP transfer client captured into the
        // desktop); the bundled glyph set has no upload/download transfer pair,
        // so it is drawn in-tool like the keypad, disc, brush, package box and
        // database cylinder.
        {"ftpclient.png",   IconColor.BLUE,        IconCategory.GENERAL, TRANSFER_GLYPH},
        // Weather (Swing Open-Meteo reader hosted on a SwingNode / 2D MDI frame);
        // the bundled glyph set carries nothing sky shaped, so a sun-behind-cloud
        // mark is drawn in-tool like the keypad, disc, brush, package box,
        // database cylinder and transfer pair.
        {"weather.png",     IconColor.CYAN,        IconCategory.GENERAL, WEATHER_GLYPH},
        // PDF Viewer (Swing reader hosted on a SwingNode / 2D MDI frame); the
        // bundled glyph set has no document page, so it is drawn in-tool like
        // the keypad, disc, brush, package box, database cylinder and transfer.
        {"pdf-viewer.png",  IconColor.RED,         IconCategory.TEXT,    DOCUMENT_GLYPH},
        // Video Conference (Swing Jitsi Meet client hosted on a SwingNode / 2D
        // MDI frame); the bundled glyph set has no video camera, so one is drawn
        // in-tool like the keypad, disc, brush, package box, database cylinder,
        // transfer pair, weather and document marks.
        {"videoconference.png", IconColor.GREEN,   IconCategory.GENERAL, VIDEO_GLYPH},
        // Instant Messenger (Swing multi-protocol chat client hosted on a
        // SwingNode / 2D MDI frame); the bundled glyph set has no chat bubble,
        // so one is drawn in-tool like the keypad, disc, brush, package box,
        // database cylinder, transfer pair, weather, document and video marks.
        {"messenger.png", IconColor.INDIGO,        IconCategory.GENERAL, CHAT_GLYPH},
        // Backup (Swing backup/restore tool hosted on a SwingNode / 2D MDI
        // frame); the bundled glyph set has no archive/save mark, so a
        // down-arrow-into-a-tray glyph is drawn in-tool like the keypad, disc,
        // brush, package box, database cylinder, transfer pair, weather,
        // document, video and chat marks.
        {"backup.png", IconColor.BLUE_GRAY,        IconCategory.GENERAL, BACKUP_GLYPH},
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
            } else if (PACKAGE_GLYPH.equals(glyphName)) {
                glyph = drawPackageGlyph(GLYPH);
            } else if (DATABASE_GLYPH.equals(glyphName)) {
                glyph = drawDatabaseGlyph(GLYPH);
            } else if (TRANSFER_GLYPH.equals(glyphName)) {
                glyph = drawTransferGlyph(GLYPH);
            } else if (WEATHER_GLYPH.equals(glyphName)) {
                glyph = drawWeatherGlyph(GLYPH);
            } else if (DOCUMENT_GLYPH.equals(glyphName)) {
                glyph = drawDocumentGlyph(GLYPH);
            } else if (VIDEO_GLYPH.equals(glyphName)) {
                glyph = drawVideoGlyph(GLYPH);
            } else if (CHAT_GLYPH.equals(glyphName)) {
                glyph = drawChatGlyph(GLYPH);
            } else if (BACKUP_GLYPH.equals(glyphName)) {
                glyph = drawBackupGlyph(GLYPH);
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

    /**
     * Draws the package box glyph: a white crate with a slightly darker lid
     * band across the top and a vertical strip of packing tape down the middle.
     * The bundled {@code toolbarButtonGraphics} set carries nothing box shaped,
     * so it is drawn in-tool like the keypad, disc and brush. Designed in a
     * 32x32 space and scaled to {@code size}.
     */
    private static Icon drawPackageGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        // Box body.
        g.setColor(Color.WHITE);
        g.fillRoundRect(4, 8, 24, 20, 2, 2);
        // Lid band across the top of the box.
        g.setColor(new Color(0xDD, 0xDD, 0xDD));
        g.fillRect(4, 8, 24, 5);
        // Packing tape down the middle.
        g.setColor(new Color(0xC4, 0xC4, 0xC4));
        g.fillRect(14, 8, 4, 20);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the database cylinder glyph: a white drum (top ellipse, body and
     * bottom ellipse) with two faint horizontal bands suggesting stacked platters.
     * The bundled {@code toolbarButtonGraphics} set carries nothing database
     * shaped, so it is drawn in-tool like the keypad, disc, brush and package box.
     * Designed in a 32x32 space and scaled to {@code size}.
     */
    private static Icon drawDatabaseGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        // Drum body + caps.
        g.setColor(Color.WHITE);
        g.fillRect(5, 6, 22, 18);
        g.fillOval(5, 2, 22, 8);
        g.fillOval(5, 18, 22, 8);
        // Two platter bands across the body.
        g.setColor(new Color(0xD5, 0xD5, 0xD5));
        g.setStroke(new BasicStroke(1.4f));
        g.drawArc(5, 7, 22, 8, 180, 180);
        g.drawArc(5, 14, 22, 8, 180, 180);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the transfer glyph: a white upload arrow beside a white download
     * arrow (two opposing vertical shafts, one capped pointing up, the other
     * pointing down), the conventional FileZilla-style transfer mark. The
     * bundled {@code toolbarButtonGraphics} set carries nothing transfer shaped,
     * so it is drawn in-tool like the keypad, disc, brush, package box and
     * database cylinder. Designed in a 32x32 space and scaled to {@code size}.
     */
    private static Icon drawTransferGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        g.setColor(Color.WHITE);
        // Upload arrow (left): shaft topped by an upward-pointing head.
        GeneralPath up = new GeneralPath();
        up.moveTo(10, 3);
        up.lineTo(15, 10);
        up.lineTo(12, 10);
        up.lineTo(12, 29);
        up.lineTo(8, 29);
        up.lineTo(8, 10);
        up.lineTo(5, 10);
        up.closePath();
        g.fill(up);
        // Download arrow (right): shaft bottomed by a downward-pointing head.
        GeneralPath down = new GeneralPath();
        down.moveTo(22, 3);
        down.lineTo(26, 3);
        down.lineTo(26, 22);
        down.lineTo(29, 22);
        down.lineTo(24, 29);
        down.lineTo(19, 22);
        down.lineTo(22, 22);
        down.closePath();
        g.fill(down);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the weather glyph: a white sun peeking out from behind a cloud, the
     * conventional "forecast" mark. The bundled {@code toolbarButtonGraphics}
     * set carries nothing sky shaped, so it is drawn in-tool like the keypad,
     * disc, brush, package box, database cylinder and transfer pair. Designed in
     * a 32x32 space and scaled to {@code size}.
     */
    private static Icon drawWeatherGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        // Sun: a disc with eight short rays, upper-left, partly behind the cloud.
        double cx = 11, cy = 11, r = 4.5;
        g.setColor(new Color(0xFF, 0xE0, 0x66));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            g.drawLine((int) (cx + Math.cos(a) * (r + 1.5)), (int) (cy + Math.sin(a) * (r + 1.5)),
                    (int) (cx + Math.cos(a) * (r + 4.0)), (int) (cy + Math.sin(a) * (r + 4.0)));
        }
        g.fillOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));
        // Cloud: three overlapping lobes over a rounded base, lower-right.
        g.setColor(Color.WHITE);
        g.fillOval(11, 17, 9, 9);
        g.fillOval(16, 14, 11, 11);
        g.fillOval(22, 18, 8, 8);
        g.fillRoundRect(11, 22, 19, 7, 6, 6);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the document page glyph: a white sheet with a folded top-right
     * corner and three faint text lines, the conventional PDF/document mark.
     * The bundled {@code toolbarButtonGraphics} set carries nothing page shaped,
     * so it is drawn in-tool like the keypad, disc, brush, package box, database
     * cylinder and transfer pair. Designed in a 32x32 space and scaled to
     * {@code size}.
     */
    private static Icon drawDocumentGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        // Sheet body with the top-right corner cut for the fold.
        GeneralPath sheet = new GeneralPath();
        sheet.moveTo(7, 2);
        sheet.lineTo(20, 2);
        sheet.lineTo(26, 8);
        sheet.lineTo(26, 30);
        sheet.lineTo(7, 30);
        sheet.closePath();
        g.setColor(Color.WHITE);
        g.fill(sheet);
        // The folded corner flap, slightly darker so it reads as a dog-ear.
        GeneralPath fold = new GeneralPath();
        fold.moveTo(20, 2);
        fold.lineTo(20, 8);
        fold.lineTo(26, 8);
        fold.closePath();
        g.setColor(new Color(0xDD, 0xDD, 0xDD));
        g.fill(fold);
        // Three text lines across the sheet.
        g.setColor(new Color(0xB0, 0xB0, 0xB0));
        g.fillRect(10, 13, 13, 2);
        g.fillRect(10, 18, 13, 2);
        g.fillRect(10, 23, 9, 2);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the video camera glyph: a rounded camera body with a lens hood
     * triangle on the right and a small record dot. The bundled
     * {@code toolbarButtonGraphics} set carries nothing camera shaped, so it is
     * drawn in-tool like the keypad, disc, brush, package box, database
     * cylinder, transfer pair, weather and document marks. Designed in a 32x32
     * space and scaled to {@code size}.
     */
    private static Icon drawVideoGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        // Camera body.
        g.setColor(Color.WHITE);
        g.fillRoundRect(3, 9, 18, 14, 4, 4);
        // Lens hood triangle on the right.
        GeneralPath lens = new GeneralPath();
        lens.moveTo(22, 16);
        lens.lineTo(29, 11);
        lens.lineTo(29, 21);
        lens.closePath();
        g.fill(lens);
        // A record dot on the body, punched out so the tile colour shows through.
        g.setComposite(AlphaComposite.Clear);
        g.fillOval(7, 13, 4, 4);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the chat glyph: two overlapping speech bubbles (a smaller one behind
     * for depth, a larger one in front) with three typing dots punched out of
     * the front bubble so the tile colour shows through. The bundled
     * {@code toolbarButtonGraphics} set carries nothing chat shaped, so it is
     * drawn in-tool like the keypad, disc, brush, package box, database
     * cylinder, transfer pair, weather, document and video marks. Designed in a
     * 32x32 space and scaled to {@code size}.
     */
    private static Icon drawChatGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        // Back bubble, slightly darker and offset up-right for depth.
        g.setColor(new Color(0xDD, 0xDD, 0xDD));
        g.fillRoundRect(10, 4, 18, 13, 6, 6);
        GeneralPath backTail = new GeneralPath();
        backTail.moveTo(23, 16);
        backTail.lineTo(27, 21);
        backTail.lineTo(26, 15);
        backTail.closePath();
        g.fill(backTail);
        // Front bubble with a tail pointing down-left.
        g.setColor(Color.WHITE);
        g.fillRoundRect(3, 10, 18, 13, 6, 6);
        GeneralPath frontTail = new GeneralPath();
        frontTail.moveTo(7, 22);
        frontTail.lineTo(5, 28);
        frontTail.lineTo(12, 23);
        frontTail.closePath();
        g.fill(frontTail);
        // Three typing dots punched out so the tile colour shows through.
        g.setComposite(AlphaComposite.Clear);
        g.fillOval(7, 15, 2, 2);
        g.fillOval(11, 15, 2, 2);
        g.fillOval(15, 15, 2, 2);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the backup glyph: a downward arrow descending into an open archive
     * tray, the conventional "save/back up into an archive" mark. The bundled
     * {@code toolbarButtonGraphics} set carries nothing archive shaped, so it is
     * drawn in-tool like the keypad, disc, brush, package box, database
     * cylinder, transfer pair, weather, document, video and chat marks. Designed
     * in a 32x32 space and scaled to {@code size}.
     */
    private static Icon drawBackupGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        g.setColor(Color.WHITE);
        // Down arrow: a vertical shaft capped by a triangular head.
        g.fillRect(14, 3, 4, 11);
        GeneralPath head = new GeneralPath();
        head.moveTo(9, 13);
        head.lineTo(23, 13);
        head.lineTo(16, 20);
        head.closePath();
        g.fill(head);
        // Open archive tray the arrow descends into: a rounded box with the top
        // punched out so it reads as a container, not a solid block.
        g.fillRoundRect(3, 22, 26, 8, 3, 3);
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(6, 22, 20, 5);
        g.dispose();
        return new ImageIcon(image);
    }
}
