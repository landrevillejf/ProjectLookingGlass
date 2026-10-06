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

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;
import com.protonmail.landrevillejf.IconManager;
import com.protonmail.landrevillejf.IconManager.IconCategory;
import com.protonmail.landrevillejf.MissingIcon;

/**
 * Resolves the 2D desktop's application icons through the bundled IconManager
 * library ({@code libs/IconManager-1.6.0.jar}), replacing the legacy per-app
 * classpath PNGs.
 *
 * <p>Resolution is deterministic and per application name: an application whose
 * name clearly matches a known family (mail, help, media, database, browser,
 * preferences, ...) gets the matching IconManager toolbar glyph; every other
 * application gets a distinctive generated glass tile carrying its initials in
 * a stable colour derived from its name, so two different apps never share an
 * icon. Because the same name always resolves to the same icon, the start menu,
 * the window frame icon and the taskbar button of one application all agree.</p>
 *
 * <p>IconManager is a compile-only dependency placed on the desktop run
 * classpath by the build; if it is ever absent at runtime every call here
 * throws {@link NoClassDefFoundError}, which is caught and degraded to the
 * legacy classpath PNG (then to {@code null}) so the desktop still starts.</p>
 */
final class AppIcons {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /**
     * Semantic families: the first keyword hit in the (lower-cased) application
     * name selects the IconManager toolbar glyph. Base names carry no size
     * suffix - IconManager appends the requested edge itself.
     */
    private static final Object[][] FAMILIES = {
        {"mail", IconCategory.GENERAL, "ComposeMail"},
        {"help", IconCategory.GENERAL, "Help"},
        {"about", IconCategory.GENERAL, "About"},
        {"preference", IconCategory.GENERAL, "Preferences"},
        {"control", IconCategory.GENERAL, "Preferences"},
        {"setting", IconCategory.GENERAL, "Preferences"},
        {"search", IconCategory.GENERAL, "Search"},
        {"print", IconCategory.GENERAL, "Print"},
        {"music", IconCategory.MEDIA, "Volume"},
        {"audio", IconCategory.MEDIA, "Volume"},
        {"volume", IconCategory.MEDIA, "Volume"},
        {"media", IconCategory.MEDIA, "Movie"},
        {"movie", IconCategory.MEDIA, "Movie"},
        {"video", IconCategory.MEDIA, "Movie"},
        {"player", IconCategory.MEDIA, "Play"},
        {"database", IconCategory.DEVELOPMENT, "Server"},
        {"db", IconCategory.DEVELOPMENT, "Server"},
        {"sql", IconCategory.DEVELOPMENT, "Server"},
        {"ide", IconCategory.DEVELOPMENT, "Application"},
        {"develop", IconCategory.DEVELOPMENT, "Application"},
        {"code", IconCategory.DEVELOPMENT, "Application"},
        {"console", IconCategory.DEVELOPMENT, "Application"},
        {"terminal", IconCategory.DEVELOPMENT, "Application"},
        {"browser", IconCategory.NAVIGATION, "Home"},
        {"web", IconCategory.NAVIGATION, "Home"},
        {"internet", IconCategory.NAVIGATION, "Home"},
        {"editor", IconCategory.TEXT, "Normal"},
        {"text", IconCategory.TEXT, "Normal"},
        {"write", IconCategory.TEXT, "Normal"},
    };

    /**
     * The only square edges the bundled toolbarButtonGraphics glyphs ship at;
     * any other requested edge must be resized from one of these.
     */
    private static final int GLYPH_SMALL_EDGE = 16;
    private static final int GLYPH_LARGE_EDGE = 24;

    /**
     * Descriptor icon resources that are genuine per-application artwork and
     * must be shown verbatim in the 2D desktop so the entry matches the 3D
     * desktop exactly, instead of being replaced by a name-derived IconManager
     * glyph or initials tile. The Application Launcher's rocket is the canonical
     * case: it is real artwork whose name matches no semantic family, so the
     * generated "AL" tile would not match the 3D start menu's rocket. Matched
     * against the classpath icon resource with the {@code resource:///} scheme
     * already stripped (see {@code Desktop2DMenuConfig.stripResourceScheme}).
     */
    private static final Set<String> DESCRIPTOR_ICON_PREFERRED = Set.of(
            "resources/images/icon/launcher.png");

    /** Stable tile palette; the index is a hash of the application name. */
    private static final Color[] PALETTE = {
        new Color(0x2E, 0x86, 0xC1), new Color(0xC0, 0x39, 0x2B),
        new Color(0x1E, 0x8E, 0x3E), new Color(0x8E, 0x44, 0xAD),
        new Color(0xD6, 0x89, 0x10), new Color(0x16, 0xA0, 0x85),
        new Color(0x2C, 0x3E, 0x50), new Color(0xB0, 0x3A, 0x76),
    };

    /** Cache: the same application is rendered by menu, frame and taskbar. */
    private static final Map<String, Icon> CACHE =
            new LinkedHashMap<String, Icon>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Icon> e) {
                    return size() > 256;
                }
            };

    private AppIcons() {
        // no instances
    }

    /**
     * The icon for one application, or {@code null} when none can be built.
     *
     * @param appName      the application's display name (drives the choice)
     * @param iconResource legacy classpath PNG, used only if IconManager is absent
     * @param size         the requested square edge in pixels
     */
    static Icon iconFor(String appName, String iconResource, int size) {
        String name = (appName == null || appName.isBlank()) ? "?" : appName.trim();
        String key = name + "@" + size;
        synchronized (CACHE) {
            Icon cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Icon icon = null;
        // A user-selected icon pack wins over everything: if the active pack
        // carries an icon for this app (matched by the descriptor icon's base
        // name, else an app-name slug) use it, so the pack overrides only the
        // icons it ships and everything else keeps its generated art below.
        icon = IconPackManager.overrideFor(iconResource, name, size);
        if (icon == null && prefersDescriptorIcon(iconResource)) {
            // Genuine per-app artwork (e.g. the Application Launcher rocket):
            // load the descriptor PNG first so the 2D icon matches the 3D
            // desktop. If it cannot be resolved (off the classpath) fall
            // through to the name-derived icon below.
            icon = Desktop2DStartMenu.icon(iconResource);
        }
        if (icon == null) {
            icon = build(name, size);
        }
        if (icon == null) {
            // IconManager missing or unable to render: fall back to the legacy
            // descriptor PNG so the desktop never shows a blank entry.
            icon = Desktop2DStartMenu.icon(iconResource);
        }
        if (icon != null) {
            synchronized (CACHE) {
                CACHE.put(key, icon);
            }
        }
        return icon;
    }

    /**
     * Drops every cached icon, so the next {@link #iconFor} re-resolves against
     * the currently active icon pack. Called when the user switches packs (see
     * {@code Desktop2D.applyIconPack}) so the start menu, taskbar and window
     * frames all pick up the new art instead of serving stale cached icons.
     */
    static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /**
     * True when {@code iconResource} is genuine per-app artwork the 2D desktop
     * must show verbatim (matching the 3D desktop) rather than replace with a
     * name-derived glyph or initials tile.
     */
    static boolean prefersDescriptorIcon(String iconResource) {
        return iconResource != null
                && DESCRIPTOR_ICON_PREFERRED.contains(iconResource.trim());
    }

    /** Semantic glyph first, then a generated initials tile; null on failure. */
    private static Icon build(String name, int size) {
        try {
            Icon glyph = semanticGlyph(name, size);
            return (glyph != null) ? glyph : initialsTile(name, size);
        } catch (Throwable t) {
            // NoClassDefFoundError when the bundled jar is off the run
            // classpath, or any rendering fault: degrade, never throw on EDT.
            logger.log(Level.FINE, "IconManager could not render an icon for " + name, t);
            return null;
        }
    }

    /** The family glyph matching the application name, or null when none. */
    private static Icon semanticGlyph(String name, int size) {
        String lower = name.toLowerCase();
        for (Object[] family : FAMILIES) {
            if (lower.contains((String) family[0])) {
                IconCategory category = (IconCategory) family[1];
                String glyph = (String) family[2];
                // The bundled glyphs only ship at 16 and 24 px and loadIcon
                // builds "<name><height>.gif", so asking for any other edge
                // (the quick-launch strip's 22, say) silently returns the red-X
                // MissingIcon. Load a bundled edge and resize to the request,
                // and treat a MissingIcon (unknown glyph name) as "no family
                // glyph" so the caller falls back to an initials tile.
                int edge = (size == GLYPH_SMALL_EDGE)
                        ? GLYPH_SMALL_EDGE : GLYPH_LARGE_EDGE;
                Icon icon = IconManager.loadIcon(category, glyph, edge, edge);
                if (icon == null || icon instanceof MissingIcon) {
                    continue;
                }
                return (size == edge) ? icon : IconManager.resizeIcon(icon, size, size);
            }
        }
        return null;
    }

    /** A coloured glass tile carrying the application's initials. */
    private static Icon initialsTile(String name, int size) {
        return IconManager.createGlassIcon(colorFor(name), initials(name), size, size);
    }

    /** A stable palette colour for an application name. */
    static Color colorFor(String name) {
        return PALETTE[Math.floorMod(name.hashCode(), PALETTE.length)];
    }

    /** Up to two leading letters, e.g. {@code "Mail 3D"} to {@code "M3"}. */
    static String initials(String name) {
        String[] words = name.split("[^A-Za-z0-9]+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (out.length() == 2) {
                break;
            }
        }
        if (out.length() == 0) {
            return "?";
        }
        if (out.length() == 1 && words[0].length() > 1) {
            out.append(Character.toUpperCase(words[0].charAt(1)));
        }
        return out.toString();
    }
}
