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
package org.jdesktop.lg3d.apps.presentoire.model;

import java.util.Collections;
import java.util.List;

/**
 * One parsed element of a Presentoire slide: a text block, an image or a
 * bullet list.
 *
 * <p>This is the AWT-free, Java 3D-free content model that both renderers read.
 * The original 2006 code parsed the XML attributes twice over - once inside each
 * {@code drawElement()} that built a {@code Component3D} - so the parsing and the
 * scene-graph construction were welded together and could not be exercised
 * headlessly or reused by a 2D viewer. This port lifts the parsed result into an
 * immutable value object: {@link PresentoireModel} produces these, the native-3D
 * {@code Slide}/{@code Element} classes turn them into {@code Component3D} nodes,
 * and the plain-Swing {@code PresentoirePanel} paints them with Java 2D. Neither
 * renderer re-reads the XML.</p>
 *
 * <p>Geometry is carried in the document's own normalised slide units (the
 * original Java 3D coordinates, roughly {@code -0.1 .. 0.1} across a slide), so
 * each renderer maps them to its own space: the 3D path uses them directly as a
 * translation, the 2D path scales them into pixels. {@code z} defaults to
 * {@code 0.01} exactly as the original did, to keep stacked coplanar quads from
 * z-fighting in 3D.</p>
 */
public abstract class SlideElement {

    /** The element kind, for a renderer that prefers to switch over {@code instanceof}. */
    public enum Kind {
        /** A text block ({@code <text>}). */
        TEXT,
        /** An image ({@code <img>}). */
        IMAGE,
        /** A bullet list ({@code <ul>}). */
        LIST
    }

    /** The default z offset, matching the original anti-flicker value. */
    public static final float DEFAULT_Z = 0.01f;

    private final float x;
    private final float y;
    private final float z;
    private final float width;
    private final float height;

    protected SlideElement(float x, float y, float z, float width, float height) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.width = width;
        this.height = height;
    }

    /** The kind of this element. */
    public abstract Kind kind();

    /** The horizontal offset in slide units. */
    public float x() {
        return x;
    }

    /** The vertical offset in slide units. */
    public float y() {
        return y;
    }

    /** The depth offset in slide units (defaults to {@value #DEFAULT_Z}). */
    public float z() {
        return z;
    }

    /** The element width in slide units. */
    public float width() {
        return width;
    }

    /** The element height in slide units. */
    public float height() {
        return height;
    }

    /**
     * A text block: a colour and the raw text, which may span several lines.
     * The original split the content on {@code \n} and stacked one 3D text quad
     * per line; {@link #lines()} exposes the same split so both renderers agree.
     */
    public static final class Text extends SlideElement {

        private final int argb;
        private final String text;

        public Text(float x, float y, float z, float width, float height,
                int argb, String text) {
            super(x, y, z, width, height);
            this.argb = argb;
            this.text = (text == null) ? "" : text;
        }

        @Override
        public Kind kind() {
            return Kind.TEXT;
        }

        /** The packed {@code 0xAARRGGBB} text colour. */
        public int argb() {
            return argb;
        }

        /** The raw text content, never null. */
        public String text() {
            return text;
        }

        /** The text split into lines on {@code \n}; a single line yields one entry. */
        public String[] lines() {
            // -1 keeps trailing empty lines so the line count is stable.
            return text.split("\n", -1);
        }
    }

    /**
     * An image: the ZIP-relative {@code src} path. The pixels are resolved
     * lazily through {@link PresentoireModel#loadImage(String)} so a deck is
     * parsed without decoding every picture up front.
     */
    public static final class Image extends SlideElement {

        private final String src;

        public Image(float x, float y, float z, float width, float height, String src) {
            super(x, y, z, width, height);
            this.src = (src == null) ? "" : src;
        }

        @Override
        public Kind kind() {
            return Kind.IMAGE;
        }

        /** The ZIP-relative image path, never null. */
        public String src() {
            return src;
        }
    }

    /** A bullet list: a colour and the text of each {@code <li>} item. */
    public static final class ListElement extends SlideElement {

        private final int argb;
        private final List<String> items;

        public ListElement(float x, float y, float z, float width, float height,
                int argb, List<String> items) {
            super(x, y, z, width, height);
            this.argb = argb;
            this.items = Collections.unmodifiableList(items);
        }

        @Override
        public Kind kind() {
            return Kind.LIST;
        }

        /** The packed {@code 0xAARRGGBB} colour of the bullets and their text. */
        public int argb() {
            return argb;
        }

        /** The immutable list of bullet texts. */
        public List<String> items() {
            return items;
        }
    }
}
