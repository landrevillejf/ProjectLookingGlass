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

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The pure model of a wallpaper slideshow on the 2D/Swing desktop: an ordered
 * list of image locations plus a current position, advanced and retreated with
 * wraparound.
 *
 * <p>No timers and no I/O live here. {@link Desktop2D} owns the
 * {@code javax.swing.Timer} that periodically calls {@link #next()} and hands
 * the returned {@link URL} to {@code setWallpaper}, and the folder scan that
 * builds the list, so the cycling arithmetic stays deterministic and
 * unit-testable headless. The model is defensive throughout: an empty list
 * yields {@code null} from every accessor and never throws, and a single image
 * simply repeats.</p>
 */
final class WallpaperSlideshow {

    /** The image locations, in cycling order; never contains nulls. */
    private final List<URL> images = new ArrayList<>();

    /** Index into {@link #images} of the image currently shown. */
    private int index;

    /**
     * Builds a slideshow cycling {@code images} in order, starting at the first.
     * Null entries are dropped and a null list is treated as empty.
     */
    WallpaperSlideshow(List<URL> images) {
        setImages(images);
    }

    /**
     * Replaces the cycled images and rewinds to the first. As in the
     * constructor, nulls are dropped and a null list becomes empty.
     */
    void setImages(List<URL> newImages) {
        images.clear();
        if (newImages != null) {
            for (URL url : newImages) {
                if (url != null) {
                    images.add(url);
                }
            }
        }
        index = 0;
    }

    /** How many images are cycled; 0 for an empty slideshow. */
    int size() {
        return images.size();
    }

    /** True when there is nothing to show. */
    boolean isEmpty() {
        return images.isEmpty();
    }

    /** The index of the image currently shown. */
    int index() {
        return index;
    }

    /** The image currently shown, or null when the slideshow is empty. */
    URL current() {
        return images.isEmpty() ? null : images.get(index);
    }

    /**
     * Advances to the next image (wrapping to the first after the last) and
     * returns it. Returns null and leaves the position untouched when empty; a
     * single image returns itself.
     */
    URL next() {
        if (images.isEmpty()) {
            return null;
        }
        index = (index + 1) % images.size();
        return images.get(index);
    }

    /**
     * Steps back to the previous image (wrapping to the last before the first)
     * and returns it. Returns null and leaves the position untouched when empty;
     * a single image returns itself.
     */
    URL previous() {
        if (images.isEmpty()) {
            return null;
        }
        index = Math.floorMod(index - 1, images.size());
        return images.get(index);
    }

    /**
     * Jumps to {@code target} and returns that image. The index wraps into
     * range, so {@code at(size)} lands on the first and {@code at(-1)} on the
     * last. Returns null and leaves the position untouched when empty.
     */
    URL at(int target) {
        if (images.isEmpty()) {
            return null;
        }
        index = Math.floorMod(target, images.size());
        return images.get(index);
    }

    /** An unmodifiable view of the cycled images, for diagnostics and tests. */
    List<URL> images() {
        return Collections.unmodifiableList(images);
    }
}
