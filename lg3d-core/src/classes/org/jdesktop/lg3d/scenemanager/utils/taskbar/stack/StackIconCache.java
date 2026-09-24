/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.scenemanager.utils.taskbar.stack;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.filechooser.FileSystemView;

/**
 * Turns the desktop's own file-type icons into image URLs the 3D scene can
 * texture a row icon with ({@code Pseudo3DIcon} only loads textures from URLs).
 *
 * <p>The Swing system icon of an entry is painted once into a small PNG under
 * {@code ~/.cache/lg3d/stack-icons/}, keyed by entry kind (directories share
 * one "folder" image, files share one image per lower-cased extension), and
 * the cached file's URL is returned. A cache miss that cannot be painted (no
 * icon, unwritable cache) yields {@code null}, and the row then falls back to
 * the menu model's default glyph - a stack never fails because of an icon.</p>
 */
final class StackIconCache {

    private static final Logger logger = Logger.getLogger("lg.scenemanager");

    /** Edge of the square the system icon is fitted into. */
    private static final int SIZE = 48;

    /**
     * Cap on the number of cached icon PNGs. The directory holds one small file
     * per file-type extension ever seen, so without a bound it grows forever;
     * once a write takes it past this many entries the least-recently-used icons
     * are evicted. Overridable for tests via {@link #evictLru(Path, int)}.
     */
    private static final int MAX_ENTRIES = 128;

    private static Path cacheDir;

    private StackIconCache() {
    }

    /** The cached icon URL for a folder stack entry. */
    static URL iconUrlFor(FolderStackModel.StackItem item) {
        return iconUrl(item.getPath(), item.getIcon(), cacheKey(item));
    }

    /** The cached icon URL for a directory (the trailing action row). */
    static URL folderIconUrl(Path dir) {
        return iconUrl(dir, systemIcon(dir), "folder");
    }

    private static String cacheKey(FolderStackModel.StackItem item) {
        if (item.isDirectory()) {
            return "folder";
        }
        String name = item.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return "file";
    }

    private static synchronized URL iconUrl(Path path, Icon icon, String key) {
        try {
            Path dir = cacheDir();
            if (dir == null) {
                return null;
            }
            Path png = dir.resolve(key + ".png");
            boolean created = false;
            if (!Files.exists(png)) {
                if (icon == null) {
                    icon = systemIcon(path);
                }
                BufferedImage img = paint(icon);
                if (img == null) {
                    return null;
                }
                ImageIO.write(img, "png", png.toFile());
                created = true;
            } else {
                // Record the use so LRU eviction keeps the hot icons.
                touch(png);
            }
            if (created) {
                // A new entry is the only thing that grows the cache, so this is
                // the natural (and cheapest) point to bound it.
                evictLru(dir, MAX_ENTRIES);
            }
            return png.toUri().toURL();
        } catch (Exception e) {
            logger.log(Level.FINE, "No cached icon for " + path, e);
            return null;
        }
    }

    /**
     * Evicts the least-recently-used {@code *.png} entries in {@code dir} so at
     * most {@code maxEntries} remain, and returns how many were deleted. Files
     * are ranked by last-modified time (bumped by {@link #touch} on every cache
     * hit), so the oldest-used icons go first; an unreadable timestamp ranks as
     * oldest. The directory is tiny (one small file per seen extension) so a
     * plain list-and-sort is ample. Package-private and pure with respect to any
     * directory so it can be unit-tested against a temp dir. Never throws: a
     * stack must not break because of cache housekeeping.
     */
    static int evictLru(Path dir, int maxEntries) {
        if (dir == null || maxEntries < 0) {
            return 0;
        }
        List<Path> pngs = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".png"))
                    .forEach(pngs::add);
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not list stack icon cache " + dir, e);
            return 0;
        }
        int excess = pngs.size() - maxEntries;
        if (excess <= 0) {
            return 0;
        }
        pngs.sort(Comparator.comparingLong(StackIconCache::lastModified));
        int evicted = 0;
        for (int i = 0; i < excess; i++) {
            try {
                if (Files.deleteIfExists(pngs.get(i))) {
                    evicted++;
                }
            } catch (Exception e) {
                logger.log(Level.FINE, "Could not evict " + pngs.get(i), e);
            }
        }
        return evicted;
    }

    /** Bumps a cache file's modified time so LRU eviction treats it as used. */
    private static void touch(Path png) {
        try {
            Files.setLastModifiedTime(png,
                    FileTime.fromMillis(System.currentTimeMillis()));
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not touch " + png, e);
        }
    }

    /** Last-modified millis of {@code p}, or 0 (oldest) when unreadable. */
    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Paints a Swing icon into a {@value #SIZE}px square, fitted with its
     * aspect ratio preserved; null when the icon cannot be painted.
     */
    private static BufferedImage paint(Icon icon) {
        if (icon == null) {
            return null;
        }
        int w = icon.getIconWidth();
        int h = icon.getIconHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        BufferedImage base = new BufferedImage(w, h,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D gb = base.createGraphics();
        icon.paintIcon(null, gb, 0, 0);
        gb.dispose();

        BufferedImage out = new BufferedImage(SIZE, SIZE,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        double s = Math.min((double) SIZE / w, (double) SIZE / h);
        int dw = Math.max(1, (int) Math.round(w * s));
        int dh = Math.max(1, (int) Math.round(h * s));
        g.drawImage(base, (SIZE - dw) / 2, (SIZE - dh) / 2, dw, dh, null);
        g.dispose();
        return out;
    }

    private static Icon systemIcon(Path path) {
        try {
            return FileSystemView.getFileSystemView()
                    .getSystemIcon(path.toFile());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Path cacheDir() {
        if (cacheDir == null) {
            try {
                cacheDir = Paths.get(System.getProperty("user.home"),
                        ".cache", "lg3d", "stack-icons");
                Files.createDirectories(cacheDir);
            } catch (Exception e) {
                logger.log(Level.FINE, "No stack icon cache dir", e);
                cacheDir = null;
                return null;
            }
        }
        return Files.isDirectory(cacheDir) ? cacheDir : null;
    }
}
