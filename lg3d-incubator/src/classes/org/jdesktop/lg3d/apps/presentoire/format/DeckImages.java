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
package org.jdesktop.lg3d.apps.presentoire.format;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;

/**
 * Small shared helpers for the picture bytes a deck's {@code <img>} elements
 * name, used by every export codec so they resolve images the same way.
 *
 * <p>A model carries its pictures either in a ZIP (a native deck) or in an
 * in-memory {@code src -> bytes} map (an imported or synthesized deck);
 * {@link PresentoireModel#openEntry(String)} already papers over the difference,
 * so these helpers work for any model and never throw - a missing or unreadable
 * picture yields null and the codec simply omits it, exactly as the renderers
 * skip an image they cannot decode.</p>
 */
final class DeckImages {

    /** The extension assumed when a picture's {@code src} has none. */
    private static final String DEFAULT_EXT = "png";

    private DeckImages() {
        // no instances
    }

    /**
     * Reads the raw bytes of the picture named {@code src}, or null when the
     * model cannot resolve it.
     *
     * @param model the deck owning the picture
     * @param src   the {@code <img>} source path
     * @return the picture bytes, or null
     */
    static byte[] bytes(PresentoireModel model, String src) {
        if (model == null || src == null || src.isEmpty()) {
            return null;
        }
        try (InputStream in = model.openEntry(src)) {
            return (in == null) ? null : in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The lower-case file extension of a picture's {@code src}, defaulting to
     * {@code png} when it has none, so a codec can name a stored image or pick a
     * picture type.
     *
     * @param src the {@code <img>} source path
     * @return a non-empty lower-case extension, never null
     */
    static String extension(String src) {
        if (src == null) {
            return DEFAULT_EXT;
        }
        int dot = src.lastIndexOf('.');
        if (dot < 0 || dot == src.length() - 1) {
            return DEFAULT_EXT;
        }
        String ext = src.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.isEmpty() ? DEFAULT_EXT : ext;
    }
}
