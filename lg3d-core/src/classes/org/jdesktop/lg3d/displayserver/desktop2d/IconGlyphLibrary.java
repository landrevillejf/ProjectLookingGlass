/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.displayserver.desktop2d;

import com.protonmail.landrevillejf.IconManager;
import com.protonmail.landrevillejf.IconManager.IconCategory;
import com.protonmail.landrevillejf.MissingIcon;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;

/**
 * A never-throw bridge over the bundled IconManager glyph library
 * ({@code libs/IconManager-1.6.0.jar}) for the icon-pack <em>builder</em>: it
 * enumerates the glyph catalogue by category and renders one glyph to a
 * {@link BufferedImage} so {@link IconPackManager#saveUserPack} can persist it as
 * a PNG in a user pack.
 *
 * <p>IconManager ships its toolbar glyphs only at 16 and 24&nbsp;px and
 * enumerates them as {@code "<Name><edge>.gif"} file names, so this class
 * reduces those to distinct base glyph names (the vocabulary
 * {@code IconManager.loadIcon} expects) and loads at a bundled edge before
 * resizing to the requested size. A {@link MissingIcon} - the red-X placeholder
 * IconManager returns for an unknown name - is treated as "no glyph" and mapped
 * to {@code null}.</p>
 *
 * <p>IconManager is a compile-only dependency placed on the desktop run
 * classpath by the build. Every entry point catches {@link Throwable} (a
 * {@link NoClassDefFoundError} when the jar is absent) and degrades to an empty
 * catalogue / {@code null}, so the control center still opens and the pack list
 * keeps working when the library is missing.</p>
 */
public final class IconGlyphLibrary {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** The larger of the two square edges the bundled glyphs ship at. */
    private static final int GLYPH_EDGE = 24;

    /** The smaller bundled edge, used when a small preview is requested. */
    private static final int GLYPH_SMALL_EDGE = 16;

    /** Friendly, stable labels for each IconManager category. */
    private static final Map<IconCategory, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(IconCategory.GENERAL, "General");
        LABELS.put(IconCategory.DEVELOPMENT, "Development");
        LABELS.put(IconCategory.TEXT, "Text");
        LABELS.put(IconCategory.MEDIA, "Media");
        LABELS.put(IconCategory.NAVIGATION, "Navigation");
        LABELS.put(IconCategory.TABLE, "Tables");
    }

    private IconGlyphLibrary() {
        // no instances
    }

    /** True when the bundled IconManager library is present and enumerable. */
    public static boolean isAvailable() {
        try {
            return IconManager.fetchAllIcons(IconCategory.GENERAL) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The catalogue categories in a stable, human-friendly order. */
    public static List<IconCategory> categories() {
        return new ArrayList<>(LABELS.keySet());
    }

    /** The display label for a category (its name if unknown). */
    public static String label(IconCategory category) {
        String label = LABELS.get(category);
        return (label != null) ? label : String.valueOf(category);
    }

    /**
     * The distinct base glyph names in a category, sorted. The bundled file
     * names carry a size suffix ({@code "Play16.gif"}); stripping it yields the
     * base name {@code IconManager.loadIcon} expects. Empty when the library is
     * absent.
     */
    public static List<String> glyphNames(IconCategory category) {
        try {
            TreeSet<String> bases = new TreeSet<>();
            for (String file : IconManager.fetchAllIcons(category)) {
                String base = stripSize(file);
                if (!base.isEmpty()) {
                    bases.add(base);
                }
            }
            return new ArrayList<>(bases);
        } catch (Throwable t) {
            logger.log(Level.FINE, "cannot enumerate glyphs for " + category, t);
            return List.of();
        }
    }

    /**
     * Loads one glyph at {@code size}, or {@code null} when it cannot be
     * resolved. A {@link MissingIcon} (unknown name) is treated as "no glyph".
     */
    public static Icon loadIcon(IconCategory category, String glyph, int size) {
        if (glyph == null || glyph.isBlank()) {
            return null;
        }
        try {
            int edge = (size <= GLYPH_SMALL_EDGE) ? GLYPH_SMALL_EDGE : GLYPH_EDGE;
            Icon icon = IconManager.loadIcon(category, glyph.trim(), edge, edge);
            if (icon == null || icon instanceof MissingIcon) {
                return null;
            }
            return (size == edge) ? icon : IconManager.resizeIcon(icon, size, size);
        } catch (Throwable t) {
            logger.log(Level.FINE, "cannot load glyph " + category + "/" + glyph, t);
            return null;
        }
    }

    /**
     * Renders one glyph into a square, alpha-carrying {@link BufferedImage} of
     * {@code size}, or {@code null} when the glyph cannot be loaded or painted.
     * This is the image {@link IconPackManager#saveUserPack} writes as a PNG.
     */
    public static BufferedImage loadImage(IconCategory category, String glyph, int size) {
        Icon icon = loadIcon(category, glyph, size);
        return toImage(icon, size);
    }

    /**
     * Paints {@code icon} into a square {@link BufferedImage} of {@code size}, or
     * {@code null} when there is nothing to paint.
     */
    public static BufferedImage toImage(Icon icon, int size) {
        if (icon == null) {
            return null;
        }
        int edge = Math.max(1, size);
        BufferedImage img = new BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            icon.paintIcon(null, g, 0, 0);
        } catch (Throwable t) {
            logger.log(Level.FINE, "cannot paint icon at " + edge, t);
            return null;
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Strips a trailing {@code "<edge>.gif"} size suffix from a glyph file name. */
    static String stripSize(String fileName) {
        if (fileName == null) {
            return "";
        }
        return fileName.trim().replaceAll("\\d+\\.gif$", "");
    }
}
