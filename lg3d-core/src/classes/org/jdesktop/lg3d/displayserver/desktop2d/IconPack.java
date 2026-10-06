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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;

/**
 * One icon pack the 2D desktop can theme its application icons with: a named
 * set of PNGs, each keyed by the icon file's base name (for example
 * {@code mail3d.png}), that {@link AppIcons} consults before it falls back to
 * the procedurally generated IconManager glyph or initials tile.
 *
 * <p>A pack comes from one of three {@linkplain Source sources}. A
 * {@link Source#BUNDLED} pack is a directory under the runtime
 * {@code resources/images/icon-packs/<id>} tree assembled onto the classpath by
 * the build (see {@code IconPackManager}); a {@link Source#USER} pack is one the
 * user composed in the control center from IconManager glyphs, persisted as a
 * named folder under {@code ~/.config/lg3d/icon-packs/<id>}; an
 * {@link Source#IMPORTED} pack is a path on the local filesystem the user chose
 * in the control center, either a folder of PNGs or a {@code .zip} whose entries
 * are those same file names. The {@linkplain #defaultPack() default} pack
 * carries no location and resolves nothing, so {@link AppIcons} keeps today's
 * behaviour.</p>
 *
 * <p>Resolution reads the PNG and scales it to the requested edge, so one pack
 * serves the start menu (16&nbsp;px), the quick-launch strip (22&nbsp;px) and
 * the window frame icons alike. Every read is best-effort: a missing file, an
 * unreadable image or an absent classpath resource yields {@code null}, letting
 * the caller fall back rather than throwing on the EDT.</p>
 */
public final class IconPack {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** Where a pack's PNGs live. */
    public enum Source {
        /** A directory under the runtime {@code resources/images/icon-packs} tree. */
        BUNDLED,
        /** A user-composed pack persisted under {@code ~/.config/lg3d/icon-packs/<id>}. */
        USER,
        /** A user-chosen folder or {@code .zip} on the local filesystem. */
        IMPORTED
    }

    /**
     * The id of the pack that overrides nothing (the procedurally generated
     * IconManager icons are used). Persisted as the empty {@code icon.pack}.
     */
    public static final String DEFAULT_ID = "";

    /** The id given to the single user-imported pack (its path is stored separately). */
    public static final String IMPORTED_ID = "imported";

    private final String id;
    private final String displayName;
    private final Source source;
    /** Classpath directory (bundled) or filesystem folder/zip path (imported); null for default. */
    private final String location;

    private IconPack(String id, String displayName, Source source, String location) {
        this.id = id;
        this.displayName = displayName;
        this.source = source;
        this.location = location;
    }

    /**
     * The pack that overrides nothing, so {@link AppIcons} keeps its generated
     * icons. Its id is the empty {@link #DEFAULT_ID}.
     */
    public static IconPack defaultPack() {
        return new IconPack(DEFAULT_ID, "Default", null, null);
    }

    /**
     * A pack bundled under {@code resources/images/icon-packs/<id>}. The
     * display name is the id with dashes/underscores turned into spaces and the
     * first letter capitalised (e.g. {@code "mono"} to {@code "Mono"}).
     */
    public static IconPack bundled(String id) {
        return new IconPack(id, prettyName(id), Source.BUNDLED,
                IconPackManager.BUNDLED_ROOT + "/" + id);
    }

    /**
     * A user-composed pack: a named folder of PNGs under
     * {@code ~/.config/lg3d/icon-packs/<id>} written by the control center's
     * icon-pack builder. Its id is the (sanitised) folder name, so several user
     * packs coexist and are discovered by scanning that root; the display name is
     * the prettified id.
     */
    public static IconPack user(String id, String path) {
        return new IconPack(id, prettyName(id), Source.USER, path);
    }

    /**
     * The user-imported pack backed by {@code path} (a folder of PNGs or a
     * {@code .zip}). Its id is the fixed {@link #IMPORTED_ID}; the display name
     * is the file/folder name.
     */
    public static IconPack imported(String path) {
        String name = (path == null || path.isBlank()) ? "Imported" : new File(path).getName();
        if (name.isBlank()) {
            name = "Imported";
        }
        return new IconPack(IMPORTED_ID, name, Source.IMPORTED, path);
    }

    /** The pack id persisted on {@code DesktopConfig} ({@code icon.pack}). */
    public String id() {
        return id;
    }

    /** The human-readable pack name shown in the control center. */
    public String displayName() {
        return displayName;
    }

    /** Where this pack's PNGs come from, or {@code null} for the default pack. */
    public Source source() {
        return source;
    }

    /** The classpath directory or filesystem path, or {@code null} for the default pack. */
    public String location() {
        return location;
    }

    /** True when this pack overrides nothing (the generated icons are kept). */
    public boolean isDefault() {
        return DEFAULT_ID.equals(id) && source == null;
    }

    /**
     * The icon for {@code basename} (a file name such as {@code mail3d.png})
     * scaled to {@code size}, or {@code null} when this pack has no such icon
     * or it cannot be read. The default pack always returns {@code null}.
     */
    public Icon resolve(String basename, int size) {
        if (isDefault() || basename == null || basename.isBlank()) {
            return null;
        }
        BufferedImage image = read(basename.trim());
        if (image == null) {
            return null;
        }
        return toIcon(image, size);
    }

    /** Reads the PNG named {@code basename} from this pack's location, or null. */
    private BufferedImage read(String basename) {
        try {
            if (source == Source.BUNDLED) {
                URL url = getClass().getClassLoader().getResource(location + "/" + basename);
                if (url == null) {
                    return null;
                }
                try (InputStream in = url.openStream()) {
                    return ImageIO.read(in);
                }
            }
            File file = new File(location);
            if (file.isDirectory()) {
                File png = new File(file, basename);
                return png.isFile() ? ImageIO.read(png) : null;
            }
            if (file.isFile()) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
                    java.util.zip.ZipEntry entry = zip.getEntry(basename);
                    if (entry == null) {
                        return null;
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        return ImageIO.read(in);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not read icon " + basename + " from pack " + id, e);
        }
        return null;
    }

    /**
     * Wraps {@code image} in an {@link Icon} at a {@code size} square. Scaling is
     * done synchronously into a {@link BufferedImage} (bilinear) rather than via
     * {@code getScaledInstance}, whose result scales asynchronously: an async
     * image reports a {@code -1} width until it is ready and can pop in a frame
     * late on the EDT, so a ready image keeps {@code getIconWidth}/paint exact.
     */
    private static Icon toIcon(BufferedImage image, int size) {
        int edge = Math.max(1, size);
        if (image.getWidth() == edge && image.getHeight() == edge) {
            return new ImageIcon(image);
        }
        BufferedImage scaled = new BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, edge, edge, null);
        g.dispose();
        return new ImageIcon(scaled);
    }

    /** {@code "mono-dark"} to {@code "Mono Dark"}; blank stays blank. */
    private static String prettyName(String id) {
        if (id == null || id.isBlank()) {
            return id;
        }
        String[] words = id.trim().split("[-_ ]+");
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

    @Override
    public String toString() {
        return displayName;
    }
}
