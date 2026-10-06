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

import com.protonmail.landrevillejf.IconColor;
import com.protonmail.landrevillejf.IconManager;
import com.protonmail.landrevillejf.IconManager.IconCategory;
import com.protonmail.landrevillejf.IconManager.IconEffect;
import com.protonmail.landrevillejf.IconManager.IconStyle;
import com.protonmail.landrevillejf.IconManager.PatternType;
import com.protonmail.landrevillejf.IconManager.StatusType;
import com.protonmail.landrevillejf.MissingIcon;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    // ---------------------------------------------------------------------
    // IconManager vocabulary the studio exposes (never throws when absent)
    // ---------------------------------------------------------------------

    /** The named tint colours IconManager ships, or empty when it is absent. */
    public static List<IconColor> colors() {
        try {
            return Arrays.asList(IconColor.values());
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** The generative/gradient icon styles, or empty when IconManager is absent. */
    public static List<IconStyle> styles() {
        try {
            return Arrays.asList(IconStyle.values());
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** The post-processing effects, or empty when IconManager is absent. */
    public static List<IconEffect> effects() {
        try {
            return Arrays.asList(IconEffect.values());
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** The pattern fills, or empty when IconManager is absent. */
    public static List<PatternType> patterns() {
        try {
            return Arrays.asList(PatternType.values());
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** The presence/status kinds, or empty when IconManager is absent. */
    public static List<StatusType> statuses() {
        try {
            return Arrays.asList(StatusType.values());
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * The {@link Color} an {@link IconColor} name paints with, or {@code null}
     * when it cannot be resolved (library absent).
     */
    public static Color color(IconColor named) {
        if (named == null) {
            return null;
        }
        try {
            return named.getColor();
        } catch (Throwable t) {
            return null;
        }
    }

    /** A human-readable label for any IconManager enum constant. */
    public static String label(Enum<?> constant) {
        if (constant == null) {
            return "";
        }
        String[] words = constant.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /**
     * Searches the whole catalogue for glyph base names matching {@code query},
     * grouped by category (in {@link #categories()} order). Reduces IconManager's
     * {@code "<Name><edge>.gif"} hits to the distinct base names
     * {@link #loadIcon} expects. Empty when the query is blank, the library is
     * absent, or nothing matches.
     */
    public static Map<IconCategory, List<String>> searchGlyphs(String query) {
        if (query == null || query.isBlank()) {
            return Map.of();
        }
        try {
            Map<IconCategory, List<String>> raw = IconManager.searchIcons(query.trim());
            if (raw == null || raw.isEmpty()) {
                return Map.of();
            }
            Map<IconCategory, List<String>> out = new LinkedHashMap<>();
            for (IconCategory category : categories()) {
                List<String> files = raw.get(category);
                if (files == null || files.isEmpty()) {
                    continue;
                }
                TreeSet<String> bases = new TreeSet<>();
                for (String file : files) {
                    String base = stripSize(file);
                    if (!base.isEmpty()) {
                        bases.add(base);
                    }
                }
                if (!bases.isEmpty()) {
                    out.put(category, new ArrayList<>(bases));
                }
            }
            return out;
        } catch (Throwable t) {
            logger.log(Level.FINE, "cannot search glyphs for " + query, t);
            return Map.of();
        }
    }

    // ---------------------------------------------------------------------
    // The render pipeline: an IconRecipe -> a styled IconManager icon
    // ---------------------------------------------------------------------

    /**
     * Renders {@code recipe} into a square {@link BufferedImage} of {@code size},
     * or {@code null} when nothing could be drawn. This is the studio's single
     * entry point: it builds the {@linkplain IconRecipe.Source source} (a glyph
     * or a generative tile) then runs the appearance chain - tint, rounded
     * corners, drop shadow, {@link IconEffect}s, brightness/contrast, rotation
     * and flip - degrading gracefully (never throwing) if IconManager is absent
     * or any one step fails.
     */
    public static BufferedImage render(IconRecipe recipe, int size) {
        return toImage(renderIcon(recipe, size), size);
    }

    /**
     * Renders {@code recipe} to a styled {@link Icon} at {@code size}, or
     * {@code null} when the source cannot be resolved. Each appearance step is
     * individually guarded so a single unsupported effect cannot blank the icon.
     */
    public static Icon renderIcon(IconRecipe recipe, int size) {
        if (recipe == null) {
            return null;
        }
        int edge = Math.max(1, size);
        try {
            Icon icon = baseIcon(recipe, edge);
            return (icon == null) ? null : applyAppearance(recipe, icon, edge);
        } catch (Throwable t) {
            logger.log(Level.FINE, "cannot render recipe " + recipe, t);
            return null;
        }
    }

    /** Builds the unstyled source icon (glyph or generative tile), or null. */
    private static Icon baseIcon(IconRecipe recipe, int edge) {
        Color c1 = (recipe.primary() != null) ? recipe.primary() : IconRecipe.DEFAULT_ACCENT;
        Color c2 = (recipe.secondary() != null) ? recipe.secondary() : IconRecipe.DEFAULT_ACCENT_2;
        String label = (recipe.label() != null) ? recipe.label() : "";
        switch (recipe.source()) {
            case GLYPH:
                return glyphBase(recipe, edge, c1, c2);
            case GLASS:
                return orNull(IconManager.createGlassIcon(c1, label, edge, edge));
            case COLOR:
                return orNull(IconManager.createColorIcon(c1, label, edge));
            case GRADIENT:
                return orNull(IconManager.createGradientIcon(c1, c2, label, edge,
                        (recipe.style() != null) ? recipe.style() : IconStyle.GRADIENT));
            case CIRCULAR:
                return orNull(IconManager.createCircularColorIcon(c1, label, edge, c2));
            case NEUMORPHISM:
                return orNull(IconManager.createNeumorphismIcon(c1, label, edge, false));
            case TEXT:
                return orNull(IconManager.createTextIcon(label,
                        new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, edge / 2)), c2, c1, edge));
            case PATTERN:
                return orNull(IconManager.createPatternIcon(c1, c2, label, edge,
                        (recipe.pattern() != null) ? recipe.pattern() : PatternType.DOTS));
            case STATUS:
                return orNull(IconManager.createStatusIcon(
                        (recipe.status() != null) ? recipe.status() : StatusType.ONLINE, edge));
            default:
                return null;
        }
    }

    /** The glyph source: a gradient-tinted glyph when two colours are set, else plain. */
    private static Icon glyphBase(IconRecipe recipe, int edge, Color c1, Color c2) {
        if (recipe.glyph() == null || recipe.glyph().isBlank()) {
            return null;
        }
        if (recipe.secondary() != null && recipe.primary() != null) {
            int ge = (edge <= GLYPH_SMALL_EDGE) ? GLYPH_SMALL_EDGE : GLYPH_EDGE;
            Icon icon = orNull(IconManager.loadIconWithGradient(recipe.category(),
                    recipe.glyph().trim(), ge, ge, c1, c2));
            if (icon == null) {
                return null;
            }
            return (ge == edge) ? icon : IconManager.resizeIcon(icon, edge, edge);
        }
        return loadIcon(recipe.category(), recipe.glyph(), edge);
    }

    /** Runs the recipe's appearance chain over {@code icon}, each step guarded. */
    private static Icon applyAppearance(IconRecipe recipe, Icon icon, int edge) {
        Icon out = icon;
        if (recipe.source() == IconRecipe.Source.GLYPH && recipe.primary() != null
                && recipe.secondary() == null && recipe.tintAmount() > 0f) {
            final Color tint = recipe.primary();
            final float amount = recipe.tintAmount();
            out = step(out, in -> IconManager.applyTint(in, tint, amount));
        }
        if (recipe.cornerRadius() > 0) {
            final int radius = Math.min(recipe.cornerRadius(), edge / 2);
            out = step(out, in -> IconManager.createRoundedIcon(in, radius));
        }
        if (recipe.shadow()) {
            final int size = Math.max(2, edge / 10);
            final int offset = Math.max(1, edge / 16);
            out = step(out, in -> IconManager.createIconWithShadow(in, Color.BLACK, size,
                    0.4f, 0, offset));
        }
        if (!recipe.effects().isEmpty()) {
            final IconEffect[] fx = recipe.effects().toArray(new IconEffect[0]);
            out = step(out, in -> IconManager.applyEffects(in, fx));
        }
        if (recipe.brightness() != 0f || recipe.contrast() != 0f) {
            final float b = recipe.brightness();
            final float c = recipe.contrast();
            out = step(out, in -> IconManager.adjustBrightnessContrast(in, b, c));
        }
        if (recipe.rotation() != 0d) {
            final double angle = recipe.rotation();
            out = step(out, in -> IconManager.rotateIcon(in, angle));
        }
        if (recipe.flipHorizontal() || recipe.flipVertical()) {
            final boolean h = recipe.flipHorizontal();
            final boolean v = recipe.flipVertical();
            out = step(out, in -> IconManager.flipIcon(in, h, v));
        }
        return out;
    }

    /** One guarded appearance step: a failure (or null) keeps the incoming icon. */
    private static Icon step(Icon icon, IconOp op) {
        if (icon == null) {
            return null;
        }
        try {
            Icon out = op.apply(icon);
            return (out != null) ? out : icon;
        } catch (Throwable t) {
            logger.log(Level.FINE, "appearance step failed; keeping previous icon", t);
            return icon;
        }
    }

    /** A single IconManager transform, so {@link #step} can guard it. */
    private interface IconOp {
        Icon apply(Icon in);
    }

    /** Maps a null or {@link MissingIcon} result to null; anything else passes through. */
    private static Icon orNull(Icon icon) {
        return (icon instanceof MissingIcon) ? null : icon;
    }

    /** Strips a trailing {@code "<edge>.gif"} size suffix from a glyph file name. */
    static String stripSize(String fileName) {
        if (fileName == null) {
            return "";
        }
        return fileName.trim().replaceAll("\\d+\\.gif$", "");
    }
}
