/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.tuner;

import java.awt.Color;
import java.awt.SystemColor;
import javax.swing.UIManager;

/**
 * The single place the tuner resolves its colours, so the UI carries <em>no</em>
 * hardcoded palette of its own and instead follows whatever look-and-feel is
 * active.
 *
 * <p>This matters because the one {@link TunerPanel} is shown under different
 * L&amp;Fs: the 2D desktop runs the native system L&amp;F (GTK/Synth) while the
 * 3D desktop hosts the panel in a {@code SwingNode} under the installed hosted
 * (Metal) L&amp;F. Every structural colour here is read live from
 * {@link UIManager} on each call, so the panel and meter re-theme themselves to
 * match their surroundings rather than painting a fixed dark card. When a key is
 * absent the value falls back to a {@link SystemColor} (itself toolkit-derived)
 * or a blend of two resolved colours - never a raw RGB literal.</p>
 *
 * <p>The only non-L&amp;F values are the three <em>semantic</em> status hues of
 * the cent meter (in-tune / near / out of tune). Those follow the universal
 * traffic-light convention and have no L&amp;F key, so they are derived here from
 * a hue fraction on the HSB wheel and lightened or darkened against the resolved
 * background luminance for contrast. They are defined once, in this class, rather
 * than scattered through the painting code.</p>
 */
final class TunerTheme {

    /** Hue fractions (0..1) of the three semantic status colours. */
    private static final float HUE_IN_TUNE = 0.33f;   // green
    private static final float HUE_NEAR = 0.11f;       // amber
    private static final float HUE_OUT = 0.0f;         // red

    private TunerTheme() {
        // Static resolver only.
    }

    // ------------------------------------------------------------------
    // Structural colours (all from the active look-and-feel)
    // ------------------------------------------------------------------

    /** The panel / meter backdrop. */
    static Color background() {
        return ui("Panel.background", SystemColor.control);
    }

    /** The raised note-card background: a subtle lift of the backdrop. */
    static Color cardBackground() {
        return blend(background(), foreground(), 0.07f);
    }

    /** The primary text colour. */
    static Color foreground() {
        return ui("Label.foreground", SystemColor.controlText);
    }

    /** De-emphasised text (captions, status, idle strings). */
    static Color dimForeground() {
        return blend(foreground(), background(), 0.45f);
    }

    /** Hairline / separator colour for borders and the string-strip dividers. */
    static Color border() {
        Color c = ui("controlShadow", null);
        return (c != null) ? c : blend(background(), foreground(), 0.25f);
    }

    /** The selection / highlight accent (active string, focus). */
    static Color accent() {
        Color c = firstNonNull("ComboBox.selectionBackground",
                "Table.selectionBackground", "List.selectionBackground",
                "textHighlight");
        return (c != null) ? c : SystemColor.textHighlight;
    }

    /** The readable foreground on top of {@link #accent()}. */
    static Color accentForeground() {
        Color c = firstNonNull("ComboBox.selectionForeground",
                "Table.selectionForeground", "List.selectionForeground",
                "textHighlightText");
        return (c != null) ? c : SystemColor.textHighlightText;
    }

    // ------------------------------------------------------------------
    // Semantic status colours (traffic-light; the meter's only non-LAF hues)
    // ------------------------------------------------------------------

    /** In-tune status colour (green), contrasted against the backdrop. */
    static Color inTune() {
        return status(HUE_IN_TUNE);
    }

    /** Close-but-not-in-tune status colour (amber). */
    static Color nearTune() {
        return status(HUE_NEAR);
    }

    /** Out-of-tune status colour (red). */
    static Color outTune() {
        return status(HUE_OUT);
    }

    /** The translucent in-tune window painted behind the meter scale. */
    static Color inTuneWindow() {
        return withAlpha(inTune(), 60);
    }

    /** The needle colour when there is no clear pitch (idle). */
    static Color idle() {
        return dimForeground();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Color status(float hue) {
        // Pick a lightness that reads on the current backdrop: bright on dark,
        // deep on light. Derived from the resolved background, never a literal.
        boolean dark = luminance(background()) < 0.5f;
        return dark
                ? Color.getHSBColor(hue, 0.60f, 0.85f)
                : Color.getHSBColor(hue, 0.85f, 0.50f);
    }

    /** Reads a colour from the active L&F, or {@code fallback} when absent. */
    private static Color ui(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return (c != null) ? c : fallback;
    }

    /** The first non-null L&F colour among {@code keys}, else null. */
    private static Color firstNonNull(String... keys) {
        for (String key : keys) {
            Color c = UIManager.getColor(key);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /** Linear blend of two colours; {@code t=0} is {@code a}, {@code t=1} is {@code b}. */
    static Color blend(Color a, Color b, float t) {
        float f = Math.max(0f, Math.min(1f, t));
        int r = Math.round(a.getRed() + (b.getRed() - a.getRed()) * f);
        int g = Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * f);
        int bl = Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * f);
        int al = Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * f);
        return new Color(clamp(r), clamp(g), clamp(bl), clamp(al));
    }

    /** The same colour with an alpha override, preserving its RGB. */
    static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp(alpha));
    }

    /** Perceived luminance (0..1) used to pick a contrasting status lightness. */
    private static float luminance(Color c) {
        return (0.299f * c.getRed() + 0.587f * c.getGreen() + 0.114f * c.getBlue())
                / 255f;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
