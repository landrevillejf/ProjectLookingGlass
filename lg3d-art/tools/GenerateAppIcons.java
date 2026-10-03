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

    /** Glyph name that draws a built-in vector beamed music note instead of a bundled glyph. */
    private static final String AUDIO_GLYPH = "AudioNote";

    /** Glyph name that draws a built-in vector play button on a screen instead of a bundled glyph. */
    private static final String PLAY_GLYPH = "PlayScreen";

    /** Glyph name that draws a built-in vector stack of image layers instead of a bundled glyph. */
    private static final String LAYERS_GLYPH = "ImageLayers";

    /** Glyph name that draws a built-in vector landscape photo (sun over hills) instead of a bundled glyph. */
    private static final String PHOTO_GLYPH = "PhotoLandscape";

    /** Glyph name that draws a built-in vector microphone instead of a bundled glyph. */
    private static final String MIC_GLYPH = "Microphone";

    /** Glyph name that draws a built-in vector security shield instead of a bundled glyph. */
    private static final String SHIELD_GLYPH = "SecurityShield";

    /** Glyph name that draws a built-in vector globe with a padlock badge instead of a bundled glyph. */
    private static final String GLOBE_LOCK_GLYPH = "GlobeLock";
    /** Glyph name that draws a built-in vector padlock instead of a bundled glyph. */
    private static final String PADLOCK_GLYPH = "VaultPadlock";

    /** Glyph name that draws a built-in vector git branch (trunk with a fork) instead of a bundled glyph. */
    private static final String GIT_BRANCH_GLYPH = "GitBranch";

    /** Glyph name that draws a built-in vector browser window (chrome strip over a globe) instead of a bundled glyph. */
    private static final String BROWSER_GLYPH = "BrowserWindow";

    /** Glyph name that draws a built-in vector contract page with curly braces instead of a bundled glyph. */
    private static final String API_DOC_GLYPH = "ApiContractBraces";

    /** Glyph name that draws a built-in vector paper plane (send / dispatch a request) instead of a bundled glyph. */
    private static final String SEND_PLANE_GLYPH = "SendRequestPlane";

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
        // Multimedia suite (Swing panels hosted on a SwingNode / 2D MDI frame).
        // Audio Player: a beamed music note, drawn in-tool like the marks above.
        {"audioplayer.png", IconColor.PURPLE,      IconCategory.GENERAL, AUDIO_GLYPH},
        // Video Player: a play button on a screen, drawn in-tool.
        {"videoplayer.png", IconColor.DEEP_ORANGE, IconCategory.GENERAL, PLAY_GLYPH},
        // Image Editor: a stack of image layers, drawn in-tool.
        {"imageeditor.png", IconColor.TEAL,        IconCategory.GENERAL, LAYERS_GLYPH},
        // Photo Viewer: a landscape photo (sun over hills), drawn in-tool.
        {"photoviewer.png", IconColor.CYAN,        IconCategory.GENERAL, PHOTO_GLYPH},
        // Recorder: a microphone, drawn in-tool.
        {"recorder.png", IconColor.RED,            IconCategory.GENERAL, MIC_GLYPH},
        // Security Center (Swing antivirus / posture panel hosted on a SwingNode /
        // 2D MDI frame); the bundled glyph set has no shield, so a checked shield
        // is drawn in-tool like the microphone and the marks above.
        {"securitycenter.png", IconColor.GREEN,   IconCategory.GENERAL, SHIELD_GLYPH},
        // VPN client (Swing tunnel front-end hosted on a SwingNode / 2D MDI
        // frame); the bundled glyph set has no globe, so a wireframe globe with a
        // padlock badge is drawn in-tool like the shield and padlock above.
        {"vpn.png", IconColor.BLUE,            IconCategory.GENERAL, GLOBE_LOCK_GLYPH},
        // Password Manager (Swing vault hosted on a SwingNode / 2D MDI frame); the
        // bundled glyph set has no padlock, so a locked padlock is drawn in-tool
        // like the shield and the marks above.
        {"passwordmanager.png", IconColor.INDIGO, IconCategory.GENERAL, PADLOCK_GLYPH},
        // Git GUI (Swing GitKraken / GitHub Desktop-style client hosted on a
        // SwingNode / 2D MDI frame); the bundled glyph set has nothing branch
        // shaped, so a git branch (trunk with a fork) is drawn in-tool like the
        // padlock and the marks above.
        {"gitgui.png", IconColor.ORANGE, IconCategory.DEVELOPMENT, GIT_BRANCH_GLYPH},
        // Web Browser (JavaFX WebView / WebKit; a JFXPanel-hosted MDI frame in
        // the 2D desktop and a pure-Swing preview in 3D that spawns the real
        // browser child process); the bundled glyph set has nothing browser
        // shaped, so a browser window with a globe is drawn in-tool like the
        // git branch and the marks above.
        {"webbrowser.png", IconColor.BLUE, IconCategory.GENERAL, BROWSER_GLYPH},
        // OpenAPI Contract Editor (external fat jar forked as a child process by
        // the OpenApiEditor launcher); the bundled glyph set has nothing API /
        // contract shaped, so a document page carrying a pair of curly braces is
        // drawn in-tool like the browser window and the marks above.
        {"openapi-editor.png", IconColor.PURPLE, IconCategory.TEXT, API_DOC_GLYPH},
        // PayloadMan (external tests-suite fat jar forked as a child process by
        // the PayloadMan launcher): a production-grade desktop API testing tool.
        // The bundled glyph set has nothing request shaped, so a paper plane (the
        // universal "send a request" mark) is drawn in-tool like the contract
        // page, browser window and the marks above.
        {"payloadman.png", IconColor.DEEP_ORANGE, IconCategory.GENERAL, SEND_PLANE_GLYPH},
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
            } else if (AUDIO_GLYPH.equals(glyphName)) {
                glyph = drawAudioGlyph(GLYPH);
            } else if (PLAY_GLYPH.equals(glyphName)) {
                glyph = drawPlayGlyph(GLYPH);
            } else if (LAYERS_GLYPH.equals(glyphName)) {
                glyph = drawLayersGlyph(GLYPH);
            } else if (PHOTO_GLYPH.equals(glyphName)) {
                glyph = drawPhotoGlyph(GLYPH);
            } else if (MIC_GLYPH.equals(glyphName)) {
                glyph = drawMicGlyph(GLYPH);
            } else if (SHIELD_GLYPH.equals(glyphName)) {
                glyph = drawShieldGlyph(GLYPH);
            } else if (GLOBE_LOCK_GLYPH.equals(glyphName)) {
                glyph = drawGlobeLockGlyph(GLYPH);
            } else if (PADLOCK_GLYPH.equals(glyphName)) {
                glyph = drawPadlockGlyph(GLYPH);
            } else if (GIT_BRANCH_GLYPH.equals(glyphName)) {
                glyph = drawGitBranchGlyph(GLYPH);
            } else if (BROWSER_GLYPH.equals(glyphName)) {
                glyph = drawBrowserGlyph(GLYPH);
            } else if (API_DOC_GLYPH.equals(glyphName)) {
                glyph = drawApiDocGlyph(GLYPH);
            } else if (SEND_PLANE_GLYPH.equals(glyphName)) {
                glyph = drawSendPlaneGlyph(GLYPH);
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

    /**
     * Draws the audio glyph: a beamed pair of music notes (two stems joined by a
     * slanted beam over two oval note heads), the conventional "music" mark. The
     * bundled {@code toolbarButtonGraphics} set carries nothing note shaped, so it
     * is drawn in-tool like the keypad, disc, brush, package box, database
     * cylinder, transfer pair, weather, document, video, chat and backup marks.
     * Designed in a 32x32 space and scaled to {@code size}.
     */
    private static Icon drawAudioGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        g.setColor(Color.WHITE);
        // Two stems rising from the note heads.
        g.fillRect(11, 6, 2, 17);
        g.fillRect(24, 4, 2, 17);
        // The slanted beam joining the stem tops.
        GeneralPath beam = new GeneralPath();
        beam.moveTo(11, 6);
        beam.lineTo(26, 4);
        beam.lineTo(26, 8);
        beam.lineTo(11, 10);
        beam.closePath();
        g.fill(beam);
        // Two oval note heads.
        g.fillOval(5, 19, 8, 6);
        g.fillOval(18, 17, 8, 6);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the video-player glyph: a white screen with a triangular play button
     * punched out of its centre so the tile colour shows through. The bundled
     * {@code toolbarButtonGraphics} set carries nothing play shaped, so it is
     * drawn in-tool like the marks above. Designed in a 32x32 space and scaled to
     * {@code size}.
     */
    private static Icon drawPlayGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        g.setColor(Color.WHITE);
        g.fillRoundRect(3, 6, 26, 20, 4, 4);
        // Punch the play triangle out of the screen.
        g.setComposite(AlphaComposite.Clear);
        GeneralPath play = new GeneralPath();
        play.moveTo(13, 11);
        play.lineTo(13, 21);
        play.lineTo(22, 16);
        play.closePath();
        g.fill(play);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the image-editor glyph: two overlapping rounded sheets, one behind and
     * offset for depth, the conventional "layers" mark of a raster editor. The
     * bundled {@code toolbarButtonGraphics} set carries nothing layer shaped, so
     * it is drawn in-tool like the marks above. Designed in a 32x32 space and
     * scaled to {@code size}.
     */
    private static Icon drawLayersGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        // Back sheet, slightly darker and offset up-right for depth.
        g.setColor(new Color(0xDD, 0xDD, 0xDD));
        g.fillRoundRect(9, 4, 19, 14, 3, 3);
        // Front sheet.
        g.setColor(Color.WHITE);
        g.fillRoundRect(4, 12, 19, 14, 3, 3);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the photo glyph: a white picture frame enclosing a sun over a range of
     * hills, the conventional "image / photo" mark. The bundled
     * {@code toolbarButtonGraphics} set carries nothing landscape shaped, so it is
     * drawn in-tool like the marks above. Designed in a 32x32 space and scaled to
     * {@code size}.
     */
    private static Icon drawPhotoGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        // Frame, then punch out the inner window so only the border stays solid.
        g.setColor(Color.WHITE);
        g.fillRoundRect(3, 6, 26, 20, 3, 3);
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(6, 9, 20, 14);
        g.setComposite(AlphaComposite.SrcOver);
        // Sun in the upper left of the window.
        g.fillOval(9, 11, 5, 5);
        // Hills across the bottom of the window.
        GeneralPath hill = new GeneralPath();
        hill.moveTo(6, 23);
        hill.lineTo(14, 14);
        hill.lineTo(20, 20);
        hill.lineTo(24, 16);
        hill.lineTo(26, 23);
        hill.closePath();
        g.fill(hill);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the recorder glyph: a microphone capsule over a cradle arc, a stem and
     * a base, the conventional "record audio" mark. The bundled
     * {@code toolbarButtonGraphics} set carries nothing microphone shaped, so it is
     * drawn in-tool like the marks above. Designed in a 32x32 space and scaled to
     * {@code size}.
     */
    private static Icon drawMicGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        g.setColor(Color.WHITE);
        // Capsule.
        g.fillRoundRect(12, 3, 8, 16, 4, 4);
        // Cradle arc beneath the capsule.
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawArc(8, 11, 16, 14, 180, 180);
        // Stem and base.
        g.fillRect(15, 24, 2, 4);
        g.fillRoundRect(10, 27, 12, 3, 2, 2);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the security shield glyph: a filled shield with a checkmark punched
     * out of it, the conventional "protected / verified" mark. The bundled
     * {@code toolbarButtonGraphics} set carries nothing shield shaped, so it is
     * drawn in-tool like the marks above. Designed in a 32x32 space and scaled
     * to {@code size}.
     */
    private static Icon drawShieldGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        g.setColor(Color.WHITE);
        // Shield body: flat top corners sweeping down to a point at the base.
        GeneralPath shield = new GeneralPath();
        shield.moveTo(16, 2);
        shield.lineTo(28, 6);
        shield.lineTo(28, 16);
        shield.curveTo(28, 24, 22, 28, 16, 30);
        shield.curveTo(10, 28, 4, 24, 4, 16);
        shield.lineTo(4, 6);
        shield.closePath();
        g.fill(shield);
        // Punch a checkmark out of the shield so it reads against the tile.
        g.setComposite(AlphaComposite.Clear);
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(10, 16, 14, 21);
        g.drawLine(14, 21, 23, 11);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the padlock glyph: a ring shackle rising out of a rounded body with a
     * keyhole punched through it, the conventional "secured / locked vault" mark.
     * The bundled {@code toolbarButtonGraphics} set carries nothing padlock
     * shaped, so it is drawn in-tool like the shield and the marks above.
     * Designed in a 32x32 space and scaled to {@code size}.
     */
    private static Icon drawPadlockGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        g.setColor(Color.WHITE);
        // Shackle: the top half of a ring whose legs tuck into the body.
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawArc(9, 3, 14, 22, 0, 180);
        // Body.
        g.fillRoundRect(5, 13, 22, 16, 3, 3);
        // Punch a keyhole (a dot over a short slot) out of the body.
        g.setComposite(AlphaComposite.Clear);
        g.fillOval(14, 17, 4, 4);
        g.fillRect(15, 20, 2, 6);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the globe-with-lock glyph: a wireframe globe (meridian, equator, an
     * inner meridian ellipse and two parallels punched out of a disc) with an
     * opaque padlock badge at its lower right, the conventional "private /
     * secured network" mark. The bundled {@code toolbarButtonGraphics} set
     * carries nothing globe shaped, so it is drawn in-tool like the shield and
     * padlock above. Designed in a 32x32 space and scaled to {@code size}.
     */
    private static Icon drawGlobeLockGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        // Globe disc, up-left, leaving room for the lock badge at the lower right.
        g.setColor(Color.WHITE);
        g.fillOval(2, 2, 22, 22);
        // Punch a wireframe out of the disc so it reads as a sphere, not a dot.
        g.setComposite(AlphaComposite.Clear);
        g.setStroke(new BasicStroke(1.2f));
        g.drawLine(13, 2, 13, 24);      // central meridian
        g.drawLine(2, 13, 24, 13);      // equator
        g.drawOval(7, 2, 12, 22);       // inner meridian ellipse
        g.drawLine(3, 8, 23, 8);        // upper parallel
        g.drawLine(3, 18, 23, 18);      // lower parallel
        // Padlock badge, lower right, opaque over the tile.
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawArc(20, 15, 8, 10, 0, 180);        // shackle
        g.fillRoundRect(17, 20, 13, 10, 2, 2);   // body
        g.setComposite(AlphaComposite.Clear);
        g.fillOval(23, 24, 2, 3);                // keyhole
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the git-branch glyph: a vertical trunk with a node at each end and a
     * third node forking off to the right, joined by a sweeping curve - the
     * conventional "branch / version control" mark. The bundled
     * {@code toolbarButtonGraphics} set carries nothing branch shaped, so it is
     * drawn in-tool like the padlock and globe above. Designed in a 32x32 space
     * and scaled to {@code size}.
     */
    private static Icon drawGitBranchGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // Trunk between the two mainline nodes.
        g.drawLine(9, 8, 9, 25);
        // Fork: a curve sweeping from the trunk up to the branch node.
        GeneralPath fork = new GeneralPath();
        fork.moveTo(9, 19);
        fork.curveTo(16, 19, 15, 12, 22, 12);
        g.draw(fork);
        // Nodes: two on the trunk, one at the branch tip.
        g.fillOval(6, 5, 7, 7);     // top trunk node
        g.fillOval(6, 22, 7, 7);    // bottom trunk node
        g.fillOval(19, 8, 7, 7);    // branch node
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the browser glyph: a window with a chrome strip (a punched close dot
     * and an address-bar slot) over a content area holding a wireframe globe, the
     * conventional "web browser" mark. The bundled {@code toolbarButtonGraphics}
     * set carries nothing browser shaped, so it is drawn in-tool like the git
     * branch and the marks above. Designed in a 32x32 space and scaled to
     * {@code size}.
     */
    private static Icon drawBrowserGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        g.setColor(Color.WHITE);
        // Window body.
        g.fillRoundRect(2, 4, 28, 24, 4, 4);
        // Punch out the content area, leaving the top chrome strip solid.
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(5, 12, 22, 13);
        // Punch a close dot and an address-bar slot out of the chrome strip.
        g.fillOval(6, 7, 3, 3);
        g.fillRoundRect(12, 7, 15, 4, 3, 3);
        // Wireframe globe centred in the content area.
        g.setComposite(AlphaComposite.SrcOver);
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(11, 13, 11, 11);
        g.drawLine(16, 13, 16, 24);        // central meridian
        g.drawOval(13, 13, 7, 11);         // inner meridian ellipse
        g.drawLine(11, 18, 22, 18);        // equator
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the API contract glyph: a white sheet with a folded top-right corner
     * carrying a pair of curly braces, the conventional "specification / contract
     * source document" mark. The bundled {@code toolbarButtonGraphics} set carries
     * nothing brace shaped, so it is drawn in-tool like the browser window and the
     * marks above. Designed in a 32x32 space and scaled to {@code size}.
     */
    private static Icon drawApiDocGlyph(int size) {
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
        // A short heading line under the fold.
        g.setColor(new Color(0xB0, 0xB0, 0xB0));
        g.fillRect(10, 10, 8, 2);
        // Curly braces: { on the left, } mirrored on the right, drawn as stroked
        // curves in the same faint grey as the heading line.
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        GeneralPath open = new GeneralPath();
        open.moveTo(14, 15);
        open.curveTo(11.5, 15, 12.5, 18, 12, 19.5);
        open.curveTo(11.7, 20.5, 11, 21, 10, 21);
        open.curveTo(11, 21, 11.7, 21.5, 12, 22.5);
        open.curveTo(12.5, 24, 11.5, 27, 14, 27);
        g.draw(open);
        GeneralPath close = new GeneralPath();
        close.moveTo(19, 15);
        close.curveTo(21.5, 15, 20.5, 18, 21, 19.5);
        close.curveTo(21.3, 20.5, 22, 21, 23, 21);
        close.curveTo(22, 21, 21.3, 21.5, 21, 22.5);
        close.curveTo(20.5, 24, 21.5, 27, 19, 27);
        g.draw(close);
        g.dispose();
        return new ImageIcon(image);
    }

    /**
     * Draws the paper-plane "send" glyph: the classic request-dispatch mark (a
     * plane with an inner notch cut into its trailing edge), pointing right. The
     * bundled {@code toolbarButtonGraphics} set carries nothing request shaped,
     * so it is drawn in-tool like the contract page and the marks above. Designed
     * in a 32x32 space and scaled to {@code size}.
     */
    private static Icon drawSendPlaneGlyph(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 32f, size / 32f);
        GeneralPath plane = new GeneralPath();
        plane.moveTo(3, 27);
        plane.lineTo(30, 16);
        plane.lineTo(3, 5);
        plane.lineTo(3, 13);
        plane.lineTo(22, 16);
        plane.lineTo(3, 19);
        plane.closePath();
        g.setColor(Color.WHITE);
        g.fill(plane);
        g.dispose();
        return new ImageIcon(image);
    }
}
