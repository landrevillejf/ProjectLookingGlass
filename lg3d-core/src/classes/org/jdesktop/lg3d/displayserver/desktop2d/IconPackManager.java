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

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.swing.Icon;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

/**
 * Chooses the icon pack the 2D desktop themes its application icons with, and
 * resolves a single override for one application icon. It is the seam
 * {@link AppIcons} consults <em>before</em> generating an IconManager glyph or
 * initials tile, so a pack replaces only the icons it actually carries and
 * everything else keeps its generated art.
 *
 * <p>{@link #available()} lists the {@linkplain IconPack#defaultPack() default}
 * pack, every bundled pack discovered under the runtime
 * {@code resources/images/icon-packs} tree, and the single user-imported pack
 * when {@code DesktopConfig} holds a valid folder/zip path. {@link #active()}
 * picks the one named by the persisted {@code icon.pack}, falling back to the
 * default when that pack is no longer present (a bundled pack removed, an
 * imported folder deleted).</p>
 *
 * <p>Bundled discovery only finds packs on a {@code file:} classpath (the
 * normal run layout the build assembles); from a sealed jar the tree cannot be
 * listed, so discovery degrades to the default pack alone rather than throwing.
 * That also keeps the whole class headless-testable: with no runtime resources
 * on the test classpath, {@link #available()} is just the default pack plus any
 * imported pack built from a temp folder or temp zip.</p>
 */
public final class IconPackManager {

    /** The runtime classpath root the bundled packs live under. */
    static final String BUNDLED_ROOT = "resources/images/icon-packs";

    private IconPackManager() {
        // no instances
    }

    /**
     * Every selectable pack, in display order: the default pack, the bundled
     * packs discovered on the classpath (alphabetical), then the imported pack
     * when a valid one is configured. Never empty (the default is always first).
     */
    public static List<IconPack> available() {
        List<IconPack> packs = new ArrayList<>();
        packs.add(IconPack.defaultPack());
        packs.addAll(bundled());
        IconPack imported = imported();
        if (imported != null) {
            packs.add(imported);
        }
        return packs;
    }

    /**
     * The bundled packs discovered under {@value #BUNDLED_ROOT}, alphabetical by
     * id. Empty when the tree cannot be listed (a sealed jar, or the resources
     * are absent from this classpath - as they are under test).
     */
    static List<IconPack> bundled() {
        List<IconPack> packs = new ArrayList<>();
        try {
            URL root = IconPackManager.class.getClassLoader().getResource(BUNDLED_ROOT);
            if (root != null && "file".equals(root.getProtocol())) {
                File dir = new File(root.toURI());
                File[] kids = dir.listFiles(File::isDirectory);
                if (kids != null) {
                    Arrays.sort(kids, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    for (File kid : kids) {
                        packs.add(IconPack.bundled(kid.getName()));
                    }
                }
            }
        } catch (Exception e) {
            // Discovery is best-effort; fall back to no bundled packs.
        }
        return packs;
    }

    /**
     * The user-imported pack, or {@code null} when no import path is configured
     * or the path no longer exists.
     */
    static IconPack imported() {
        String path = DesktopConfig.get().getIconPackDir();
        if (path == null || path.isBlank()) {
            return null;
        }
        File file = new File(path);
        return file.exists() ? IconPack.imported(path) : null;
    }

    /**
     * The pack the user has selected: the one whose id matches the persisted
     * {@code icon.pack}, or the {@linkplain IconPack#defaultPack() default} when
     * none is set or the named pack is no longer available. Never {@code null}.
     */
    public static IconPack active() {
        String id = DesktopConfig.get().getIconPack();
        if (id == null || id.isBlank()) {
            return IconPack.defaultPack();
        }
        for (IconPack pack : available()) {
            if (pack.id().equals(id)) {
                return pack;
            }
        }
        return IconPack.defaultPack();
    }

    /**
     * The override icon for one application, or {@code null} when the active
     * pack does not carry one (so {@link AppIcons} keeps its generated icon).
     *
     * <p>Two keys are tried in order: the base name of the descriptor's
     * {@code iconResource} (e.g. {@code resources/images/icon/mail3d.png} to
     * {@code mail3d.png}), then a slug of {@code appName} plus {@code .png}
     * (e.g. {@code "Mail 3D"} to {@code mail-3d.png}) so an app with no
     * descriptor art - one whose icon is generated from its name - can still be
     * overridden by a pack that ships that slug.</p>
     *
     * @param iconResource the descriptor classpath icon resource, or null
     * @param appName      the application's display name
     * @param size         the requested square edge in pixels
     */
    public static Icon overrideFor(String iconResource, String appName, int size) {
        IconPack pack = active();
        if (pack.isDefault()) {
            return null;
        }
        String base = basename(iconResource);
        if (base != null) {
            Icon icon = pack.resolve(base, size);
            if (icon != null) {
                return icon;
            }
        }
        String slug = slug(appName);
        if (slug != null) {
            Icon icon = pack.resolve(slug + ".png", size);
            if (icon != null) {
                return icon;
            }
        }
        return null;
    }

    /** The file name at the end of a classpath resource path, or null when blank. */
    static String basename(String resource) {
        if (resource == null) {
            return null;
        }
        String r = resource.trim();
        if (r.isEmpty()) {
            return null;
        }
        int slash = Math.max(r.lastIndexOf('/'), r.lastIndexOf('\\'));
        String base = (slash >= 0) ? r.substring(slash + 1) : r;
        return base.isEmpty() ? null : base;
    }

    /**
     * A lower-cased, dash-separated slug of an application name (e.g.
     * {@code "Mail 3D"} to {@code "mail-3d"}), or null when nothing usable
     * remains.
     */
    static String slug(String appName) {
        if (appName == null) {
            return null;
        }
        String s = appName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        while (s.startsWith("-")) {
            s = s.substring(1);
        }
        while (s.endsWith("-")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? null : s;
    }
}
