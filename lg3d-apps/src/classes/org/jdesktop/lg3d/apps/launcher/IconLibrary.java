/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.launcher;

import com.protonmail.landrevillejf.IconManager;
import com.protonmail.landrevillejf.IconManager.IconCategory;
import com.protonmail.landrevillejf.MissingIcon;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;

/**
 * Bridge between the {@link LauncherFrame} icon picker and the bundled
 * IconManager glyph library ({@code libs/IconManager-1.6.0.jar}), so a
 * user-created launcher can use one of the desktop's own icons in addition to a
 * custom image file.
 *
 * <p>IconManager ships its toolbar glyphs only at 16 and 24 px and enumerates
 * them as {@code "<Name><edge>.gif"} file names, so this class reduces those to
 * distinct base glyph names (the vocabulary {@code IconManager.loadIcon} expects)
 * and loads previews at a bundled edge. A chosen glyph is exported to a PNG under
 * {@code ~/.config/lg3d/launchers/icons/} and handed back as an absolute path, so
 * it flows through {@code LauncherSaver} and the drag-to-quick-launch payload
 * exactly like a custom icon file does.</p>
 *
 * <p>IconManager is a compile-only dependency placed on the desktop run
 * classpath by the build. Every entry point here catches {@link Throwable} (a
 * {@link NoClassDefFoundError} when the jar is absent) and degrades to an empty
 * catalogue / {@code null} so the launcher frame still opens and the custom-file
 * path keeps working.</p>
 */
final class IconLibrary {

    private static final Logger logger = Logger.getLogger("lg.launcher");

    /** The only square edges the bundled glyphs ship at (see class doc). */
    static final int GLYPH_EDGE = 24;

    /** Directory (under the launcher store) exported glyph PNGs are written to. */
    private static final String ICON_DIR = ".config/lg3d/launchers/icons";

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

    private IconLibrary() {
        // no instances
    }

    /** True when the bundled IconManager library is present at runtime. */
    static boolean isAvailable() {
        try {
            return !categories().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The catalogue categories in a stable, human-friendly order. */
    static List<IconCategory> categories() {
        try {
            return new ArrayList<>(LABELS.keySet());
        } catch (Throwable t) {
            logger.log(Level.FINE, "IconManager catalogue unavailable", t);
            return List.of();
        }
    }

    /** The display label for a category (its name if unknown). */
    static String label(final IconCategory category) {
        String label = LABELS.get(category);
        return (label != null) ? label : String.valueOf(category);
    }

    /**
     * The distinct base glyph names in a category, sorted. The bundled file
     * names carry a size suffix ({@code "Play16.gif"}); stripping it yields the
     * base name {@code IconManager.loadIcon} expects.
     */
    static List<String> glyphNames(final IconCategory category) {
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
     * Loads a preview of one glyph at the bundled edge, or {@code null} when it
     * cannot be resolved. A {@link MissingIcon} (the red-X placeholder IconManager
     * returns for an unknown name) is treated as "no glyph".
     */
    static Icon load(final IconCategory category, final String glyph) {
        if (glyph == null || glyph.isBlank()) {
            return null;
        }
        try {
            Icon icon = IconManager.loadIcon(category, glyph.trim(), GLYPH_EDGE, GLYPH_EDGE);
            return (icon instanceof MissingIcon) ? null : icon;
        } catch (Throwable t) {
            logger.log(Level.FINE, "cannot load glyph " + category + "/" + glyph, t);
            return null;
        }
    }

    /**
     * Exports one glyph to a PNG under {@code dir} and returns its absolute path,
     * or {@code null} when the glyph cannot be loaded or written. The returned
     * path is what the launcher stores as its icon, exactly like a custom file.
     */
    static Path export(final IconCategory category, final String glyph, final Path dir) {
        Icon icon = load(category, glyph);
        if (icon == null) {
            return null;
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(sanitize(glyph) + ".png").toAbsolutePath();
            IconManager.exportIcon(icon, file.toString(), "png");
            return file;
        } catch (Throwable t) {
            logger.log(Level.WARNING, "cannot export glyph " + category + "/" + glyph, t);
            return null;
        }
    }

    /** Exports one glyph into the default launcher icon store. */
    static Path export(final IconCategory category, final String glyph) {
        return export(category, glyph, defaultIconDir());
    }

    /** The default directory exported glyph PNGs are written to. */
    static Path defaultIconDir() {
        return Paths.get(System.getProperty("user.home")).resolve(ICON_DIR);
    }

    /** Strips a trailing {@code "<edge>.gif"} size suffix from a glyph file name. */
    static String stripSize(final String fileName) {
        if (fileName == null) {
            return "";
        }
        return fileName.trim().replaceAll("\\d+\\.gif$", "");
    }

    /** Reduces a glyph name to a safe PNG file stem. */
    static String sanitize(final String glyph) {
        String safe = (glyph == null) ? "icon" : glyph.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        return safe.isEmpty() ? "icon" : safe;
    }
}
