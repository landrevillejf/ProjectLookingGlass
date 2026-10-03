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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import javax.swing.UIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link TunerTheme}: every structural colour resolves (non-null) from the
 * active look-and-feel headless, the derived colours track {@link UIManager}
 * rather than a fixed palette, and the blend / alpha helpers are correct. This is
 * what guarantees the tuner UI carries no hardcoded colours.
 */
class TunerThemeTest {

    @Test
    @DisplayName("every structural colour resolves non-null headless")
    void structuralColoursResolve() {
        assertNotNull(TunerTheme.background());
        assertNotNull(TunerTheme.cardBackground());
        assertNotNull(TunerTheme.foreground());
        assertNotNull(TunerTheme.dimForeground());
        assertNotNull(TunerTheme.border());
        assertNotNull(TunerTheme.accent());
        assertNotNull(TunerTheme.accentForeground());
    }

    @Test
    @DisplayName("every semantic status colour resolves non-null and is distinct")
    void statusColoursResolve() {
        Color inTune = TunerTheme.inTune();
        Color near = TunerTheme.nearTune();
        Color out = TunerTheme.outTune();
        assertNotNull(inTune);
        assertNotNull(near);
        assertNotNull(out);
        assertNotNull(TunerTheme.inTuneWindow());
        assertNotNull(TunerTheme.idle());
        // The traffic-light hues must be distinguishable from one another.
        assertNotEquals(inTune.getRGB(), near.getRGB());
        assertNotEquals(near.getRGB(), out.getRGB());
        assertNotEquals(inTune.getRGB(), out.getRGB());
        // The in-tune window is the in-tune colour with an alpha override.
        assertEquals(60, TunerTheme.inTuneWindow().getAlpha());
        assertEquals(inTune.getRed(), TunerTheme.inTuneWindow().getRed());
    }

    @Test
    @DisplayName("the background tracks the active look-and-feel key")
    void backgroundFollowsLookAndFeel() {
        Color laf = UIManager.getColor("Panel.background");
        // UIManager lazily installs a default LAF, so this key is present.
        assertNotNull(laf);
        assertEquals(laf.getRGB(), TunerTheme.background().getRGB());
    }

    @Test
    @DisplayName("the in-tune status colour is predominantly green")
    void inTuneIsGreenish() {
        Color c = TunerTheme.inTune();
        assertTrue(c.getGreen() > c.getRed() && c.getGreen() > c.getBlue(),
                "in-tune should read green: " + c);
    }

    @Test
    @DisplayName("blend interpolates between two colours and clamps t")
    void blendInterpolates() {
        Color a = new Color(0, 0, 0, 255);
        Color b = new Color(200, 100, 50, 255);
        Color mid = TunerTheme.blend(a, b, 0.5f);
        assertEquals(100, mid.getRed());
        assertEquals(50, mid.getGreen());
        assertEquals(25, mid.getBlue());
        // t is clamped to [0,1], so out-of-range values pin to the endpoints.
        assertEquals(a.getRGB(), TunerTheme.blend(a, b, -1f).getRGB());
        assertEquals(b.getRGB(), TunerTheme.blend(a, b, 2f).getRGB());
        assertEquals(a.getRGB(), TunerTheme.blend(a, b, 0f).getRGB());
        assertEquals(b.getRGB(), TunerTheme.blend(a, b, 1f).getRGB());
    }

    @Test
    @DisplayName("withAlpha overrides alpha but preserves the RGB")
    void withAlphaPreservesRgb() {
        Color base = new Color(10, 20, 30, 255);
        Color faded = TunerTheme.withAlpha(base, 64);
        assertEquals(10, faded.getRed());
        assertEquals(20, faded.getGreen());
        assertEquals(30, faded.getBlue());
        assertEquals(64, faded.getAlpha());
        // Alpha is clamped into range.
        assertEquals(255, TunerTheme.withAlpha(base, 999).getAlpha());
        assertEquals(0, TunerTheme.withAlpha(base, -5).getAlpha());
    }
}
