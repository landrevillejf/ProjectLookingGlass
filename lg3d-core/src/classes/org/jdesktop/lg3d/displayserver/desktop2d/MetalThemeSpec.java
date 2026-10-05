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
import java.util.ArrayList;
import java.util.List;
import javax.swing.plaf.ColorUIResource;

/**
 * An immutable, persistence-friendly description of a Swing {@code Metal}
 * theme: a display name plus the six {@code MetalTheme} palette colours
 * (three "primary" shades used for selection/focus chrome and three
 * "secondary" shades used for the control backgrounds).
 *
 * <p>This is the pure seam of the 2D desktop's Metal theme manager. Everything
 * here - the built-in palettes, the compact {@code encode}/{@code decode}
 * serialisation used to persist user-created themes, and the accent-colour
 * derivation behind "New theme" - is display-independent and unit-tested
 * headless. Turning a spec into a live {@code MetalTheme} and pushing it
 * through {@code MetalLookAndFeel} is {@link CustomMetalTheme}'s and
 * {@link MetalThemeManager}'s job.</p>
 *
 * <p>The encoded form is {@code name;#rrggbb;#rrggbb;#rrggbb;#rrggbb;#rrggbb;
 * #rrggbb} (seven {@code ';'}-separated fields); a list of themes encodes one
 * spec per line. A malformed record decodes to {@code null} and is skipped, so
 * a corrupt preference can never take the desktop down.</p>
 */
public record MetalThemeSpec(
        String name,
        Color primary1,
        Color primary2,
        Color primary3,
        Color secondary1,
        Color secondary2,
        Color secondary3) {

    /** Field separator inside one encoded spec. */
    private static final char FIELD_SEP = ';';
    /** Line separator between encoded specs in a list. */
    private static final char LINE_SEP = '\n';
    /** Number of fields in an encoded spec (name + six colours). */
    private static final int FIELD_COUNT = 7;

    // Literal Steel fallbacks. Declared before STEEL/OCEAN so they are already
    // initialised when those built-ins run the compact constructor below (which
    // reads them); referencing STEEL there instead would NPE mid-initialisation.
    private static final ColorUIResource FB_PRIMARY1 = new ColorUIResource(0x66, 0x66, 0x99);
    private static final ColorUIResource FB_PRIMARY2 = new ColorUIResource(0x99, 0x99, 0xCC);
    private static final ColorUIResource FB_PRIMARY3 = new ColorUIResource(0xCC, 0xCC, 0xFF);
    private static final ColorUIResource FB_SECONDARY1 = new ColorUIResource(0x66, 0x66, 0x66);
    private static final ColorUIResource FB_SECONDARY2 = new ColorUIResource(0x99, 0x99, 0x99);
    private static final ColorUIResource FB_SECONDARY3 = new ColorUIResource(0xCC, 0xCC, 0xCC);

    /**
     * The stock 2006 "Steel" palette - the exact six shades
     * {@code DefaultMetalTheme} reports (captured as literals because
     * {@code MetalTheme}'s getters are {@code protected}).
     */
    public static final MetalThemeSpec STEEL = new MetalThemeSpec("Steel",
            FB_PRIMARY1, FB_PRIMARY2, FB_PRIMARY3,
            FB_SECONDARY1, FB_SECONDARY2, FB_SECONDARY3);
    /**
     * The modern "Ocean" palette - the exact six shades the JDK's
     * package-private {@code OceanTheme} (Metal's default theme) reports.
     */
    public static final MetalThemeSpec OCEAN = new MetalThemeSpec("Ocean",
            new ColorUIResource(0x63, 0x82, 0xBF), new ColorUIResource(0xA3, 0xB8, 0xCC),
            new ColorUIResource(0xB8, 0xCF, 0xE5), new ColorUIResource(0x7A, 0x8A, 0x99),
            new ColorUIResource(0xB8, 0xCF, 0xE5), new ColorUIResource(0xEE, 0xEE, 0xEE));

    /**
     * "Graphite" - a cool, understated slate-grey palette for a neutral,
     * professional desktop with no colour accent.
     */
    public static final MetalThemeSpec GRAPHITE = new MetalThemeSpec("Graphite",
            new ColorUIResource(0x4A, 0x54, 0x5E), new ColorUIResource(0x70, 0x7C, 0x88),
            new ColorUIResource(0xB0, 0xBA, 0xC4), new ColorUIResource(0x66, 0x66, 0x66),
            new ColorUIResource(0x99, 0x99, 0x99), new ColorUIResource(0xE0, 0xE2, 0xE5));

    /**
     * "Emerald" - a forest-green accent palette with a pale minty background.
     */
    public static final MetalThemeSpec EMERALD = new MetalThemeSpec("Emerald",
            new ColorUIResource(0x1B, 0x5E, 0x42), new ColorUIResource(0x3D, 0x91, 0x6B),
            new ColorUIResource(0xB2, 0xE0, 0xC7), new ColorUIResource(0x5A, 0x66, 0x60),
            new ColorUIResource(0x96, 0xA3, 0x9C), new ColorUIResource(0xE3, 0xEC, 0xE6));

    /**
     * "Sunset" - a warm amber/coral accent palette with a pale cream background.
     */
    public static final MetalThemeSpec SUNSET = new MetalThemeSpec("Sunset",
            new ColorUIResource(0x8C, 0x43, 0x1F), new ColorUIResource(0xD6, 0x76, 0x3F),
            new ColorUIResource(0xFA, 0xCD, 0xA0), new ColorUIResource(0x78, 0x67, 0x5C),
            new ColorUIResource(0xB0, 0xA0, 0x94), new ColorUIResource(0xF5, 0xE8, 0xDC));

    /**
     * "Glassy" - reproduces, as faithfully as a six-colour Metal palette
     * allows, the translucent ice-blue "glass" look of the native 3D desktop's
     * {@code Frame3D} window chrome. Every shade below is lifted directly from
     * the 3D rendering code rather than picked freehand, so the 2D desktop
     * reads as the same material:
     * <ul>
     *   <li>{@code primary1}/{@code primary2} are the exact edge/title accent
     *       blues {@code org.jdesktop.lg3d.widgets.builtin.WidgetPanel}'s
     *       {@code BORDER} (0x6094D6) and {@code TITLE_COLOR} (0x8CB9F5) paint
     *       on every glassy HUD card and window decoration;</li>
     *   <li>{@code primary3} is {@code org.jdesktop.lg3d.utils.shape.
     *       FrostedGlassPanel}'s {@code DEFAULT_TINT} - the cool, lightly-blue
     *       frosted-white tint (RGB 0.85, 0.90, 1.00) every {@code Frame3D}
     *       glass panel is washed with - rounded to 0xD9E6FF;</li>
     *   <li>the {@code secondary} ramp is a neutral extension of that same
     *       tint (a muted slate-blue shadow down to a near-white glass
     *       background) so controls and panels stay legible while keeping the
     *       icy, translucent character.</li>
     * </ul>
     */
    public static final MetalThemeSpec GLASSY = new MetalThemeSpec("Glassy",
            new ColorUIResource(0x60, 0x94, 0xD6), new ColorUIResource(0x8C, 0xB9, 0xF5),
            new ColorUIResource(0xD9, 0xE6, 0xFF), new ColorUIResource(0x3A, 0x48, 0x5E),
            new ColorUIResource(0xBE, 0xCD, 0xE4), new ColorUIResource(0xE8, 0xF0, 0xFC));

    /**
     * Canonicalises the record: a blank name falls back to {@code "Custom"} and
     * any null colour falls back to the matching Steel shade, so a partially
     * specified theme still renders rather than NPE-ing inside the Metal UI.
     */
    public MetalThemeSpec {
        name = (name == null || name.isBlank()) ? "Custom" : name.trim();
        primary1 = orDefault(primary1, FB_PRIMARY1);
        primary2 = orDefault(primary2, FB_PRIMARY2);
        primary3 = orDefault(primary3, FB_PRIMARY3);
        secondary1 = orDefault(secondary1, FB_SECONDARY1);
        secondary2 = orDefault(secondary2, FB_SECONDARY2);
        secondary3 = orDefault(secondary3, FB_SECONDARY3);
    }

    private static Color orDefault(Color c, Color fallback) {
        return (c == null) ? fallback : c;
    }

    /**
     * Derives a full theme from a single accent colour: the accent becomes
     * {@code primary2}, a darkened shade becomes {@code primary1} and a
     * lightened shade {@code primary3}, while the neutral secondary ramp is
     * borrowed from Steel. This backs the control center's "New theme" action,
     * so a user picks one colour rather than six.
     */
    public static MetalThemeSpec fromAccent(String name, Color accent) {
        Color base = (accent == null) ? STEEL.primary2 : accent;
        return new MetalThemeSpec(name,
                darken(base, 0.55f), base, lighten(base, 0.70f),
                STEEL.secondary1, STEEL.secondary2, STEEL.secondary3);
    }

    private static Color darken(Color c, float keep) {
        return new Color(scale(c.getRed(), keep), scale(c.getGreen(), keep),
                scale(c.getBlue(), keep));
    }

    private static Color lighten(Color c, float toward) {
        return new Color(
                blend(c.getRed(), toward), blend(c.getGreen(), toward),
                blend(c.getBlue(), toward));
    }

    private static int scale(int channel, float keep) {
        return clamp(Math.round(channel * keep));
    }

    /** Blends a channel toward white by {@code t} (0 = unchanged, 1 = white). */
    private static int blend(int channel, float t) {
        return clamp(Math.round(channel + (255 - channel) * t));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ------------------------------------------------------------------
    // Serialisation (pure)
    // ------------------------------------------------------------------

    /** Encodes this spec into the compact {@code name;#rgb;...} form. */
    public String encode() {
        StringBuilder sb = new StringBuilder(name);
        appendColor(sb, primary1);
        appendColor(sb, primary2);
        appendColor(sb, primary3);
        appendColor(sb, secondary1);
        appendColor(sb, secondary2);
        appendColor(sb, secondary3);
        return sb.toString();
    }

    private static void appendColor(StringBuilder sb, Color c) {
        sb.append(FIELD_SEP).append('#')
                .append(String.format("%06x", c.getRGB() & 0xFFFFFF));
    }

    /**
     * Decodes one encoded spec, or returns {@code null} when {@code s} is
     * null/blank or malformed (wrong field count, non-hex colour, empty name).
     */
    public static MetalThemeSpec decode(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split(String.valueOf(FIELD_SEP), -1);
        if (parts.length != FIELD_COUNT) {
            return null;
        }
        String name = parts[0].trim();
        if (name.isEmpty()) {
            return null;
        }
        try {
            return new MetalThemeSpec(name,
                    parseColor(parts[1]), parseColor(parts[2]), parseColor(parts[3]),
                    parseColor(parts[4]), parseColor(parts[5]), parseColor(parts[6]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Color parseColor(String field) {
        String f = field.trim();
        if (f.length() != 7 || f.charAt(0) != '#') {
            throw new IllegalArgumentException("bad colour: " + field);
        }
        return new Color(Integer.parseInt(f.substring(1), 16));
    }

    /** Encodes a list of specs, one per line (skipping nulls). */
    public static String encodeAll(List<MetalThemeSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MetalThemeSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(LINE_SEP);
            }
            sb.append(spec.encode());
        }
        return sb.toString();
    }

    /** Decodes a newline-separated list, skipping blank/malformed records. */
    public static List<MetalThemeSpec> decodeAll(String s) {
        List<MetalThemeSpec> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String line : s.split(String.valueOf(LINE_SEP))) {
            MetalThemeSpec spec = decode(line);
            if (spec != null) {
                out.add(spec);
            }
        }
        return out;
    }
}
