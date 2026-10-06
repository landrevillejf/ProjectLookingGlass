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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.protonmail.landrevillejf.IconManager.IconCategory;
import com.protonmail.landrevillejf.IconManager.IconEffect;
import com.protonmail.landrevillejf.IconManager.IconStyle;
import com.protonmail.landrevillejf.IconManager.PatternType;
import com.protonmail.landrevillejf.IconManager.StatusType;
import com.protonmail.landrevillejf.IconColor;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import org.jdesktop.lg3d.displayserver.desktop2d.IconRecipe.Preset;
import org.jdesktop.lg3d.displayserver.desktop2d.IconRecipe.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link IconRecipe} value/Builder and {@link IconGlyphLibrary}'s
 * render pipeline that drives the full IconManager vocabulary: every
 * {@link Source} (glyph + the generative tiles), each appearance facet (tint,
 * gradient, rounded corners, shadow, effects, brightness/contrast, rotation,
 * flip), the {@link Preset} templates, the never-throw null/degradation paths
 * and the enum-vocabulary/search accessors. Tests that need real IconManager
 * drawing are skipped when the bundled jar is absent; the pure value tests
 * always run. Everything is headless.
 */
class IconRecipeRenderTest {

    private static final int EDGE = 48;

    /** A real bundled glyph name from the GENERAL category, or null when absent. */
    private static String aGlyph() {
        List<String> names = IconGlyphLibrary.glyphNames(IconCategory.GENERAL);
        return names.isEmpty() ? null : names.get(0);
    }

    // --- pure value model (always runs) -----------------------------------

    @Test
    @DisplayName("a bare builder defaults to a plain GLYPH recipe with neutral appearance")
    void builderDefaults() {
        IconRecipe r = IconRecipe.builder().build();
        assertEquals(Source.GLYPH, r.source());
        assertEquals(1f, r.tintAmount());
        assertEquals(0f, r.brightness());
        assertEquals(0f, r.contrast());
        assertEquals(0, r.cornerRadius());
        assertEquals(0d, r.rotation());
        assertFalse(r.shadow());
        assertFalse(r.flipHorizontal());
        assertFalse(r.flipVertical());
        assertTrue(r.effects().isEmpty());
        assertNull(r.primary());
        assertFalse(r.isGenerative());
    }

    @Test
    @DisplayName("negative corner radius is clamped to zero and tint amount to 0..1")
    void builderClamps() {
        IconRecipe r = IconRecipe.builder().cornerRadius(-5).tintAmount(3f).build();
        assertEquals(0, r.cornerRadius());
        assertEquals(1f, r.tintAmount());
        assertEquals(0f, IconRecipe.builder().tintAmount(-2f).build().tintAmount());
    }

    @Test
    @DisplayName("the effects set is defensive: unmodifiable and null-filtered")
    void effectsDefensive() {
        IconRecipe r = IconRecipe.builder().effects(IconEffect.SEPIA, null, IconEffect.GLOW).build();
        assertEquals(2, r.effects().size());
        assertTrue(r.effects().contains(IconEffect.SEPIA));
        assertTrue(r.effects().contains(IconEffect.GLOW));
        assertThrowsUnsupported(() -> r.effects().add(IconEffect.BLUR));
    }

    private static void assertThrowsUnsupported(Runnable run) {
        try {
            run.run();
            throw new AssertionError("expected the effects set to be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // correct
        }
    }

    @Test
    @DisplayName("forApp keeps appearance but stamps category/glyph/label; toBuilder round-trips")
    void forAppAndToBuilder() {
        IconRecipe base = IconRecipe.builder().primary(Color.RED).cornerRadius(4)
                .shadow(true).build();
        IconRecipe app = base.forApp(IconCategory.MEDIA, "Play", "PL");
        assertEquals(IconCategory.MEDIA, app.category());
        assertEquals("Play", app.glyph());
        assertEquals("PL", app.label());
        assertSame(Color.RED, app.primary());
        assertEquals(4, app.cornerRadius());
        assertTrue(app.shadow());
        // the original is untouched (immutability)
        assertNull(base.category());
        assertNull(base.glyph());
        assertEquals(app.cornerRadius(), app.toBuilder().build().cornerRadius());
    }

    @Test
    @DisplayName("every preset has a label and a non-null template")
    void presetsWellFormed() {
        for (Preset preset : Preset.values()) {
            assertNotNull(preset.label());
            assertFalse(preset.label().isBlank());
            assertNotNull(preset.template());
            assertEquals(preset.label(), preset.toString());
        }
    }

    @Test
    @DisplayName("the enum vocabularies are enumerated and humanised")
    void vocabularies() {
        assertFalse(IconGlyphLibrary.styles().isEmpty());
        assertFalse(IconGlyphLibrary.effects().isEmpty());
        assertFalse(IconGlyphLibrary.patterns().isEmpty());
        assertFalse(IconGlyphLibrary.statuses().isEmpty());
        assertEquals("Do Not Disturb", IconGlyphLibrary.label(StatusType.DO_NOT_DISTURB));
        assertEquals("Blue Gray", IconGlyphLibrary.label(IconColor.BLUE_GRAY));
        assertEquals("", IconGlyphLibrary.label((Enum<?>) null));
        assertTrue(IconGlyphLibrary.styles().contains(IconStyle.GLASS));
        assertTrue(IconGlyphLibrary.patterns().contains(PatternType.CHECKER));
    }

    @Test
    @DisplayName("a blank search query returns an empty result without touching IconManager")
    void blankSearchIsEmpty() {
        assertTrue(IconGlyphLibrary.searchGlyphs(null).isEmpty());
        assertTrue(IconGlyphLibrary.searchGlyphs("   ").isEmpty());
    }

    // --- render degradation (always runs) ---------------------------------

    @Test
    @DisplayName("render(null) and a glyph recipe with no glyph both yield null")
    void renderNullSafe() {
        assertNull(IconGlyphLibrary.render(null, EDGE));
        assertNull(IconGlyphLibrary.renderIcon(null, EDGE));
        assertNull(IconGlyphLibrary.render(IconRecipe.builder().glyph("  ").build(), EDGE));
    }

    @Test
    @DisplayName("a named IconColor resolves to a Color; null is null")
    void colorLookup() {
        assertNull(IconGlyphLibrary.color(null));
        if (IconGlyphLibrary.isAvailable()) {
            assertNotNull(IconGlyphLibrary.color(IconColor.BLUE));
        }
    }

    // --- render pipeline (needs the bundled IconManager jar) --------------

    @Test
    @DisplayName("every generative source renders to a square alpha image")
    void generativeSourcesRender() {
        assumeTrue(IconGlyphLibrary.isAvailable(), "IconManager jar absent");
        Source[] generative = {
            Source.GLASS, Source.COLOR, Source.GRADIENT, Source.CIRCULAR,
            Source.NEUMORPHISM, Source.TEXT, Source.PATTERN, Source.STATUS
        };
        for (Source source : generative) {
            IconRecipe r = IconRecipe.builder().source(source).label("LG")
                    .primary(new Color(0x3D7EFF)).secondary(new Color(0x9B4DFF))
                    .style(IconStyle.GRADIENT).pattern(PatternType.DOTS)
                    .status(StatusType.ONLINE).build();
            BufferedImage img = IconGlyphLibrary.render(r, EDGE);
            assertNotNull(img, "generative source renders: " + source);
            assertEquals(EDGE, img.getWidth());
            assertEquals(EDGE, img.getHeight());
            assertEquals(BufferedImage.TYPE_INT_ARGB, img.getType());
        }
    }

    @Test
    @DisplayName("a real glyph renders plain, tinted and gradient-tinted")
    void glyphSourceRenders() {
        assumeTrue(IconGlyphLibrary.isAvailable(), "IconManager jar absent");
        String glyph = aGlyph();
        assumeTrue(glyph != null, "GENERAL category ships no glyphs");

        assertNotNull(IconGlyphLibrary.render(
                IconRecipe.glyph(IconCategory.GENERAL, glyph), EDGE));
        assertNotNull(IconGlyphLibrary.render(IconRecipe.builder()
                .category(IconCategory.GENERAL).glyph(glyph)
                .primary(Color.RED).tintAmount(0.8f).build(), EDGE));
        assertNotNull(IconGlyphLibrary.render(IconRecipe.builder()
                .category(IconCategory.GENERAL).glyph(glyph)
                .primary(Color.BLUE).secondary(Color.CYAN).build(), EDGE));
    }

    @Test
    @DisplayName("the full appearance chain renders without throwing at the requested edge")
    void appearanceChainRenders() {
        assumeTrue(IconGlyphLibrary.isAvailable(), "IconManager jar absent");
        IconRecipe r = IconRecipe.builder()
                .source(Source.GLASS).label("X").primary(Color.GREEN)
                .cornerRadius(10).shadow(true)
                .effects(IconEffect.GLOW, IconEffect.SHADOW)
                .brightness(0.2f).contrast(0.1f)
                .rotation(15d).flipHorizontal(true).flipVertical(true)
                .build();
        BufferedImage img = IconGlyphLibrary.render(r, EDGE);
        assertNotNull(img, "the whole chain degrades gracefully to a drawn tile");
        assertEquals(EDGE, img.getWidth());
        assertEquals(EDGE, img.getHeight());
        assertNotNull(IconGlyphLibrary.renderIcon(r, EDGE));
    }

    @Test
    @DisplayName("every preset template renders when stamped with a real glyph/label")
    void presetsRender() {
        assumeTrue(IconGlyphLibrary.isAvailable(), "IconManager jar absent");
        String glyph = aGlyph();
        assumeTrue(glyph != null, "GENERAL category ships no glyphs");
        for (Preset preset : Preset.values()) {
            IconRecipe r = preset.template().forApp(IconCategory.GENERAL, glyph, "LG");
            BufferedImage img = IconGlyphLibrary.render(r, EDGE);
            assertNotNull(img, "preset renders: " + preset);
            assertEquals(EDGE, img.getWidth());
        }
    }

    @Test
    @DisplayName("a matching search query returns base glyph names grouped by category")
    void searchFindsGlyphs() {
        assumeTrue(IconGlyphLibrary.isAvailable(), "IconManager jar absent");
        String glyph = aGlyph();
        assumeTrue(glyph != null && glyph.length() >= 2, "no glyph to search for");
        Map<IconCategory, List<String>> hits = IconGlyphLibrary.searchGlyphs(glyph.substring(0, 2));
        // Best-effort: the catalogue may or may not match a two-letter prefix,
        // but the call must never throw and must return base (suffix-free) names.
        assertNotNull(hits);
        for (List<String> names : hits.values()) {
            for (String name : names) {
                assertFalse(name.matches(".*\\d+\\.gif$"), "search reduces to base names");
            }
        }
    }
}
