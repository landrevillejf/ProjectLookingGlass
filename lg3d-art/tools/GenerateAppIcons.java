import com.protonmail.landrevillejf.IconColor;
import com.protonmail.landrevillejf.IconManager;
import com.protonmail.landrevillejf.IconManager.CompositeArrangement;
import com.protonmail.landrevillejf.IconManager.IconCategory;
import com.protonmail.landrevillejf.IconManager.IconStyle;

import javax.swing.Icon;
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

    /** app icon file, tile colour, glyph category, glyph name. */
    private static final Object[][] APPS = {
        {"imagestudio.png", IconColor.ORANGE, IconCategory.GENERAL,     "Edit"},
        {"luncher.png",     IconColor.BLUE,   IconCategory.DEVELOPMENT, "Application"},
        {"nlc.png",         IconColor.PURPLE, IconCategory.TEXT,        "Normal"},
        {"chart3d.png",     IconColor.GREEN,  IconCategory.TABLE,       "ColumnInsertAfter"},
        {"contact3d.png",   IconColor.TEAL,   IconCategory.GENERAL,     "ComposeMail"},
        {"agenda3d.png",    IconColor.RED,    IconCategory.GENERAL,     "History"},
        {"mail3d.png",      IconColor.INDIGO, IconCategory.GENERAL,     "SendMail"},
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

            Icon glyph = IconManager.resizeIcon(
                IconManager.loadIconWithFallback(category, glyphName, 24, 24), GLYPH, GLYPH);
            if (glyph.getClass().getSimpleName().contains("Missing")) {
                throw new IllegalStateException("no bundled glyph for " + file
                    + " (" + category + "/" + glyphName + ")");
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
}
