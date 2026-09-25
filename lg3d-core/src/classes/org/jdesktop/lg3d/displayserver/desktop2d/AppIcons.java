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
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;
import com.protonmail.landrevillejf.IconManager;
import com.protonmail.landrevillejf.IconManager.IconCategory;

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
        Icon icon = build(name, size);
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
                Icon icon = IconManager.loadIcon(
                        (IconCategory) family[1], (String) family[2], size, size);
                if (icon != null) {
                    return icon;
                }
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
