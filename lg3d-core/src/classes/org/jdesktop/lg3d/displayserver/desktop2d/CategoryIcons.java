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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;
import com.protonmail.landrevillejf.IconManager;
import com.protonmail.landrevillejf.IconManager.IconCategory;
import com.protonmail.landrevillejf.MissingIcon;

/**
 * Resolves the icon for a 2D start-menu <em>category</em> (a menu group such as
 * "Internet", "Utilities", "Games", ...) through the bundled IconManager
 * library, so the category sub-menus carry a recognisable glyph the same way the
 * application entries inside them do (see {@link AppIcons}).
 *
 * <p>Resolution is by category name: the first keyword hit in the lower-cased
 * group name selects a curated IconManager glyph. A group whose name matches no
 * keyword is delegated to {@link AppIcons#iconFor}, which yields a semantic
 * application glyph or a generated initials tile - so every category is iconned,
 * never blank. Because the fallback runs through {@code AppIcons}, a category
 * with no curated glyph still honours the user's active icon pack.</p>
 *
 * <p>IconManager is a compile-only dependency placed on the desktop run
 * classpath by the build; if it is ever absent at runtime the glyph lookup
 * throws {@link NoClassDefFoundError}, which is caught and degraded to the
 * {@code AppIcons} fallback (then to {@code null}) so the menu still builds.</p>
 */
final class CategoryIcons {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /**
     * Category families: the first keyword hit in the (lower-cased) group name
     * selects the IconManager glyph. Ordered specific-before-generic so a name
     * such as "Early Prototypes" resolves on "prototype" rather than a shorter
     * keyword. Base glyph names carry no size suffix - IconManager appends the
     * requested edge itself.
     */
    private static final Object[][] CATEGORY_FAMILIES = {
        {"internet", IconCategory.DEVELOPMENT, "WebComponent"},
        {"web", IconCategory.DEVELOPMENT, "WebComponent"},
        {"utilit", IconCategory.GENERAL, "Preferences"},
        {"accessor", IconCategory.GENERAL, "Preferences"},
        {"tool", IconCategory.GENERAL, "Preferences"},
        {"game", IconCategory.MEDIA, "Play"},
        {"media", IconCategory.MEDIA, "Movie"},
        {"multimedia", IconCategory.MEDIA, "Movie"},
        {"office", IconCategory.TEXT, "Normal"},
        {"document", IconCategory.TEXT, "Normal"},
        {"educat", IconCategory.GENERAL, "Information"},
        {"reference", IconCategory.GENERAL, "Information"},
        {"develop", IconCategory.DEVELOPMENT, "Application"},
        {"system", IconCategory.DEVELOPMENT, "Host"},
        {"test", IconCategory.GENERAL, "Find"},
        {"demo", IconCategory.GENERAL, "TipOfTheDay"},
        {"sample", IconCategory.GENERAL, "TipOfTheDay"},
        {"prototype", IconCategory.DEVELOPMENT, "Applet"},
        {"early", IconCategory.DEVELOPMENT, "Applet"},
        {"application", IconCategory.DEVELOPMENT, "Application"},
        {"main", IconCategory.GENERAL, "Open"},
        {"graphic", IconCategory.GENERAL, "Edit"},
        {"sound", IconCategory.MEDIA, "Volume"},
        {"mail", IconCategory.GENERAL, "ComposeMail"},
        {"help", IconCategory.GENERAL, "Help"},
    };

    /**
     * The only square edges the bundled toolbarButtonGraphics glyphs ship at;
     * any other requested edge must be resized from one of these (mirrors
     * {@link AppIcons}).
     */
    private static final int GLYPH_SMALL_EDGE = 16;
    private static final int GLYPH_LARGE_EDGE = 24;

    /** Cache: the same handful of categories is rendered on every menu open. */
    private static final Map<String, Icon> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, Icon>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Icon> e) {
                    return size() > 64;
                }
            });

    private CategoryIcons() {
        // no instances
    }

    /**
     * The icon for one start-menu category, or {@code null} when none can be
     * built (IconManager absent and no fallback artwork).
     *
     * @param groupName the category's display name (drives the choice)
     * @param size      the requested square edge in pixels
     */
    static Icon iconFor(String groupName, int size) {
        String name = (groupName == null) ? "" : groupName.trim();
        String key = name.toLowerCase() + "@" + size;
        Icon cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Icon icon = categoryGlyph(name, size);
        if (icon == null) {
            // No curated glyph for this category: reuse the application seam so
            // the entry is still iconned (a semantic glyph, else an initials
            // tile) and still honours the user's active icon pack.
            icon = AppIcons.iconFor(name, null, size);
        }
        if (icon != null) {
            CACHE.put(key, icon);
        }
        return icon;
    }

    /**
     * Drops every cached icon, so the next {@link #iconFor} re-resolves against
     * the currently active icon pack. Called alongside {@link AppIcons#clearCache}
     * when the user switches packs.
     */
    static void clearCache() {
        CACHE.clear();
    }

    /** The curated glyph matching the category name, or null when none. */
    private static Icon categoryGlyph(String name, int size) {
        if (name.isEmpty()) {
            return null;
        }
        try {
            String lower = name.toLowerCase();
            for (Object[] family : CATEGORY_FAMILIES) {
                if (!lower.contains((String) family[0])) {
                    continue;
                }
                IconCategory category = (IconCategory) family[1];
                String glyph = (String) family[2];
                // The bundled glyphs only ship at 16 and 24 px, so load a
                // bundled edge and resize to the request; treat a MissingIcon
                // (unknown glyph name) as "no curated glyph" and keep scanning.
                int edge = (size == GLYPH_SMALL_EDGE)
                        ? GLYPH_SMALL_EDGE : GLYPH_LARGE_EDGE;
                Icon icon = IconManager.loadIcon(category, glyph, edge, edge);
                if (icon == null || icon instanceof MissingIcon) {
                    continue;
                }
                return (size == edge) ? icon : IconManager.resizeIcon(icon, size, size);
            }
        } catch (Throwable t) {
            // NoClassDefFoundError when the bundled jar is off the run
            // classpath, or any rendering fault: degrade, never throw on EDT.
            logger.log(Level.FINE,
                    "IconManager could not render a category icon for " + name, t);
            return null;
        }
        return null;
    }
}
