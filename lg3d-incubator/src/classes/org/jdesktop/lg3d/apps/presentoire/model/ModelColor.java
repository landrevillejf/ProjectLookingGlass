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

/**
 * A pure colour parser for the Presentoire document format.
 *
 * <p>This is the single, AWT-free and Java 3D-free authority on what a
 * {@code color="..."} attribute means, shared by the native-3D scene-graph
 * renderer and the plain-Swing 2D panel so the two desktops can never disagree
 * about a slide's colours. A colour is either an HTML-like hex string
 * ({@code #RRGGBB} or {@code #RRGGBBAA}, alpha last) or one of a small set of
 * named colours; anything unrecognised degrades to opaque black rather than
 * throwing, matching the documented behaviour of the original 2006 parser.</p>
 *
 * <p>Colours are represented as a packed {@code 0xAARRGGBB} {@code int} - the
 * same layout {@link java.awt.Color} uses - which keeps this class free of any
 * AWT or {@code org.jogamp.vecmath} import. The 3D renderer converts an
 * {@code int} to a {@code Color4f} with the {@code *Float} accessors; the 2D
 * panel hands it straight to {@code new Color(argb, true)}.</p>
 *
 * <p>The original parser divided each 8-bit channel by {@code 254}, which made
 * a fully-saturated channel reach only ~1.004 and a mid channel drift; this port
 * divides by {@code 255} so {@code #FFFFFF} is exactly white and {@code #808080}
 * is exactly mid-grey.</p>
 */
public final class ModelColor {

    /** Opaque black - the fallback for a null, blank or unrecognised spec. */
    public static final int BLACK = 0xFF000000;

    /** Opaque white. */
    public static final int WHITE = 0xFFFFFFFF;

    /** Opaque red. */
    public static final int RED = 0xFFFF0000;

    /** Opaque green. */
    public static final int GREEN = 0xFF00FF00;

    /** Opaque blue. */
    public static final int BLUE = 0xFF0000FF;

    private ModelColor() {
        // no instances
    }

    /**
     * Parses a Presentoire colour spec into a packed {@code 0xAARRGGBB} int.
     *
     * <p>Accepts {@code #RRGGBB} (opaque), {@code #RRGGBBAA} (alpha last, as the
     * original format documented it) and a case-insensitive colour name. A null
     * or blank spec, a malformed hex string or an unknown name all degrade to
     * {@link #BLACK} rather than throwing, so a single bad attribute can never
     * abort the loading of an otherwise-valid deck.</p>
     *
     * @param spec the colour attribute value, may be null
     * @return the packed ARGB colour, never null
     */
    public static int parse(String spec) {
        if (spec == null) {
            return BLACK;
        }
        String value = spec.trim();
        if (value.isEmpty()) {
            return BLACK;
        }
        if (value.charAt(0) == '#') {
            return parseHex(value);
        }
        return parseName(value);
    }

    /** Parses a {@code #RRGGBB} or {@code #RRGGBBAA} hex colour. */
    private static int parseHex(String value) {
        try {
            if (value.length() == 7) {
                int r = Integer.parseInt(value.substring(1, 3), 16);
                int g = Integer.parseInt(value.substring(3, 5), 16);
                int b = Integer.parseInt(value.substring(5, 7), 16);
                return argb(255, r, g, b);
            }
            if (value.length() == 9) {
                int r = Integer.parseInt(value.substring(1, 3), 16);
                int g = Integer.parseInt(value.substring(3, 5), 16);
                int b = Integer.parseInt(value.substring(5, 7), 16);
                int a = Integer.parseInt(value.substring(7, 9), 16);
                return argb(a, r, g, b);
            }
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            // fall through to the default below
        }
        return BLACK;
    }

    /** Resolves a case-insensitive colour name; unknown names are black. */
    private static int parseName(String value) {
        switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "black":     return BLACK;
            case "white":     return WHITE;
            case "red":       return RED;
            case "green":     return GREEN;
            case "blue":      return BLUE;
            case "yellow":    return 0xFFFFFF00;
            case "cyan":      return 0xFF00FFFF;
            case "magenta":   return 0xFFFF00FF;
            case "gray":
            case "grey":      return 0xFF808080;
            case "darkgray":
            case "darkgrey":  return 0xFF404040;
            case "lightgray":
            case "lightgrey": return 0xFFC0C0C0;
            case "orange":    return 0xFFFFA500;
            case "pink":      return 0xFFFFC0CB;
            default:          return BLACK;
        }
    }

    /** Packs four 0..255 channels into a {@code 0xAARRGGBB} int. */
    public static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /**
     * Formats a packed {@code 0xAARRGGBB} colour back into a Presentoire
     * {@code color="..."} spec - the inverse of {@link #parse(String)}, used by
     * the deck writers (the native {@code content.xml} and the LibreOffice
     * {@code .odp} export) so a colour survives a round trip.
     *
     * <p>A fully opaque colour renders as {@code #RRGGBB}; a translucent one
     * keeps the alpha-last {@code #RRGGBBAA} form the original format documented.
     * Hex digits are upper-case, matching the built-in demo deck.</p>
     *
     * @param argb the packed colour
     * @return a {@code #RRGGBB} or {@code #RRGGBBAA} string, never null
     */
    public static String toHex(int argb) {
        String rgb = String.format(java.util.Locale.ROOT, "#%02X%02X%02X",
                red(argb), green(argb), blue(argb));
        int a = alpha(argb);
        return (a == 255)
                ? rgb
                : rgb + String.format(java.util.Locale.ROOT, "%02X", a);
    }

    /** The 0..255 alpha channel of a packed ARGB colour. */
    public static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    /** The 0..255 red channel of a packed ARGB colour. */
    public static int red(int argb) {
        return (argb >>> 16) & 0xFF;
    }

    /** The 0..255 green channel of a packed ARGB colour. */
    public static int green(int argb) {
        return (argb >>> 8) & 0xFF;
    }

    /** The 0..255 blue channel of a packed ARGB colour. */
    public static int blue(int argb) {
        return argb & 0xFF;
    }

    /** The red channel as a 0..1 float, for the {@code Color4f} 3D path. */
    public static float redFloat(int argb) {
        return red(argb) / 255f;
    }

    /** The green channel as a 0..1 float, for the {@code Color4f} 3D path. */
    public static float greenFloat(int argb) {
        return green(argb) / 255f;
    }

    /** The blue channel as a 0..1 float, for the {@code Color4f} 3D path. */
    public static float blueFloat(int argb) {
        return blue(argb) / 255f;
    }

    /** The alpha channel as a 0..1 float, for the {@code Color4f} 3D path. */
    public static float alphaFloat(int argb) {
        return alpha(argb) / 255f;
    }
}
