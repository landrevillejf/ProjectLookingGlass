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

import com.protonmail.landrevillejf.IconManager.IconCategory;
import com.protonmail.landrevillejf.IconManager.IconEffect;
import com.protonmail.landrevillejf.IconManager.IconStyle;
import com.protonmail.landrevillejf.IconManager.PatternType;
import com.protonmail.landrevillejf.IconManager.StatusType;
import java.awt.Color;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * An immutable description of <em>how</em> to render one application icon, used
 * by the icon-pack studio to drive the bundled IconManager library's full
 * vocabulary - a glyph or a generative tile as the {@linkplain Source source},
 * then an appearance chain (tint, gradient, rounded corners, drop shadow, the
 * {@link IconEffect} post-processors, brightness/contrast, rotation and flip).
 *
 * <p>A recipe is a pure value: {@link IconGlyphLibrary#render} turns it into a
 * {@link java.awt.image.BufferedImage} and nothing else reads or mutates it, so
 * the studio can freely derive per-application copies with
 * {@link #forApp(IconCategory, String, String)} and {@link #toBuilder()}. Every
 * field is optional except the {@linkplain Source source}; a
 * {@linkplain #builder() bare} recipe renders the plain glyph, matching the
 * pre-studio behaviour.</p>
 *
 * <p>{@link Preset} bundles a handful of ready-made appearance templates the
 * studio lists so the user can start from a coherent look (flat, mono tint,
 * glass, gradient, neumorphism, rounded shadow, sepia vintage) and then fine
 * tune it. A preset carries no glyph; the studio stamps each application's
 * category/glyph/label onto it.</p>
 */
public final class IconRecipe {

    /** What the icon is built from before the appearance chain runs. */
    public enum Source {
        /** A bundled IconManager toolbar glyph, resolved by category + name. */
        GLYPH,
        /** {@code IconManager.createGlassIcon} - a translucent glass tile. */
        GLASS,
        /** {@code IconManager.createColorIcon} - a solid colour tile. */
        COLOR,
        /** {@code IconManager.createGradientIcon} - a two-colour gradient tile. */
        GRADIENT,
        /** {@code IconManager.createCircularColorIcon} - a round colour tile. */
        CIRCULAR,
        /** {@code IconManager.createNeumorphismIcon} - a soft embossed tile. */
        NEUMORPHISM,
        /** {@code IconManager.createTextIcon} - a text label tile. */
        TEXT,
        /** {@code IconManager.createPatternIcon} - a patterned tile. */
        PATTERN,
        /** {@code IconManager.createStatusIcon} - a presence/status dot. */
        STATUS
    }

    /** A ready-made appearance template the studio offers as a starting point. */
    public enum Preset {
        /** The plain bundled glyph, no appearance applied. */
        FLAT("Flat glyph"),
        /** The glyph tinted to a single colour. */
        MONO("Mono tint"),
        /** A translucent glass tile carrying the app's initials. */
        GLASS("Glass tile"),
        /** A two-colour gradient tile carrying the app's initials. */
        GRADIENT("Gradient tile"),
        /** A soft neumorphic tile carrying the app's initials. */
        NEUMORPHISM("Neumorphism"),
        /** The glyph on a rounded, drop-shadowed tile. */
        ROUNDED("Rounded shadow"),
        /** The glyph run through the sepia post-processor. */
        VINTAGE("Sepia vintage");

        private final String label;

        Preset(final String label) {
            this.label = label;
        }

        /** The human-readable preset name shown in the studio. */
        public String label() {
            return label;
        }

        /**
         * The appearance template for this preset, with no glyph/label set (the
         * studio stamps those per application via
         * {@link IconRecipe#forApp(IconCategory, String, String)}).
         */
        public IconRecipe template() {
            return switch (this) {
                case FLAT -> builder().build();
                case MONO -> builder().primary(DEFAULT_ACCENT).tintAmount(1f).build();
                case GLASS -> builder().source(Source.GLASS).primary(DEFAULT_ACCENT).build();
                case GRADIENT -> builder().source(Source.GRADIENT).primary(DEFAULT_ACCENT)
                        .secondary(DEFAULT_ACCENT_2).style(IconStyle.GRADIENT).build();
                case NEUMORPHISM -> builder().source(Source.NEUMORPHISM)
                        .primary(DEFAULT_ACCENT).build();
                case ROUNDED -> builder().cornerRadius(9).shadow(true).build();
                case VINTAGE -> builder().effects(IconEffect.SEPIA).build();
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The default accent a coloured preset starts from. */
    static final Color DEFAULT_ACCENT = new Color(0x3D7EFF);

    /** The default secondary (gradient end) a coloured preset starts from. */
    static final Color DEFAULT_ACCENT_2 = new Color(0x9B4DFF);

    private final Source source;
    private final IconCategory category;
    private final String glyph;
    private final String label;
    private final Color primary;
    private final Color secondary;
    private final float tintAmount;
    private final IconStyle style;
    private final PatternType pattern;
    private final StatusType status;
    private final int cornerRadius;
    private final boolean shadow;
    private final Set<IconEffect> effects;
    private final float brightness;
    private final float contrast;
    private final double rotation;
    private final boolean flipHorizontal;
    private final boolean flipVertical;

    private IconRecipe(final Builder b) {
        this.source = b.source;
        this.category = b.category;
        this.glyph = b.glyph;
        this.label = b.label;
        this.primary = b.primary;
        this.secondary = b.secondary;
        this.tintAmount = b.tintAmount;
        this.style = b.style;
        this.pattern = b.pattern;
        this.status = b.status;
        this.cornerRadius = Math.max(0, b.cornerRadius);
        this.shadow = b.shadow;
        this.effects = (b.effects.isEmpty())
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(b.effects));
        this.brightness = b.brightness;
        this.contrast = b.contrast;
        this.rotation = b.rotation;
        this.flipHorizontal = b.flipHorizontal;
        this.flipVertical = b.flipVertical;
    }

    /** A new builder defaulting to a plain {@link Source#GLYPH} recipe. */
    public static Builder builder() {
        return new Builder();
    }

    /** A bare recipe for one bundled glyph, with no appearance applied. */
    public static IconRecipe glyph(final IconCategory category, final String glyph) {
        return builder().source(Source.GLYPH).category(category).glyph(glyph).build();
    }

    /**
     * A copy of this recipe stamped for one application: it keeps every
     * appearance field but takes the given {@code category}/{@code glyph} (used
     * by {@link Source#GLYPH}) and {@code label} (the initials/word the
     * generative sources draw). This is how a {@link Preset} template becomes a
     * concrete per-app icon.
     */
    public IconRecipe forApp(final IconCategory category, final String glyph,
            final String label) {
        return toBuilder().category(category).glyph(glyph).label(label).build();
    }

    /** A builder pre-loaded with this recipe's fields, for deriving variants. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.source = source;
        b.category = category;
        b.glyph = glyph;
        b.label = label;
        b.primary = primary;
        b.secondary = secondary;
        b.tintAmount = tintAmount;
        b.style = style;
        b.pattern = pattern;
        b.status = status;
        b.cornerRadius = cornerRadius;
        b.shadow = shadow;
        b.effects.clear();
        b.effects.addAll(effects);
        b.brightness = brightness;
        b.contrast = contrast;
        b.rotation = rotation;
        b.flipHorizontal = flipHorizontal;
        b.flipVertical = flipVertical;
        return b;
    }

    public Source source() {
        return source;
    }

    public IconCategory category() {
        return category;
    }

    public String glyph() {
        return glyph;
    }

    public String label() {
        return label;
    }

    public Color primary() {
        return primary;
    }

    public Color secondary() {
        return secondary;
    }

    public float tintAmount() {
        return tintAmount;
    }

    public IconStyle style() {
        return style;
    }

    public PatternType pattern() {
        return pattern;
    }

    public StatusType status() {
        return status;
    }

    public int cornerRadius() {
        return cornerRadius;
    }

    public boolean shadow() {
        return shadow;
    }

    /** The post-processing effects, in a stable order; never null, may be empty. */
    public Set<IconEffect> effects() {
        return effects;
    }

    public float brightness() {
        return brightness;
    }

    public float contrast() {
        return contrast;
    }

    public double rotation() {
        return rotation;
    }

    public boolean flipHorizontal() {
        return flipHorizontal;
    }

    public boolean flipVertical() {
        return flipVertical;
    }

    /** True when this recipe draws one of the generative (non-glyph) tiles. */
    public boolean isGenerative() {
        return source != Source.GLYPH;
    }

    @Override
    public String toString() {
        return "IconRecipe[" + source
                + (glyph != null ? " " + glyph : "")
                + (label != null ? " \"" + label + "\"" : "") + "]";
    }

    /**
     * A fluent builder for {@link IconRecipe}. Numeric fields default to "no
     * change" ({@code tintAmount} 1, {@code brightness}/{@code contrast} 0,
     * {@code cornerRadius} 0, {@code rotation} 0) and nullable fields to
     * {@code null}, so an untouched builder renders the plain glyph.
     */
    public static final class Builder {

        private Source source = Source.GLYPH;
        private IconCategory category;
        private String glyph;
        private String label;
        private Color primary;
        private Color secondary;
        private float tintAmount = 1f;
        private IconStyle style;
        private PatternType pattern;
        private StatusType status;
        private int cornerRadius;
        private boolean shadow;
        private final Set<IconEffect> effects = EnumSet.noneOf(IconEffect.class);
        private float brightness;
        private float contrast;
        private double rotation;
        private boolean flipHorizontal;
        private boolean flipVertical;

        private Builder() {
        }

        public Builder source(final Source source) {
            this.source = (source != null) ? source : Source.GLYPH;
            return this;
        }

        public Builder category(final IconCategory category) {
            this.category = category;
            return this;
        }

        public Builder glyph(final String glyph) {
            this.glyph = glyph;
            return this;
        }

        public Builder label(final String label) {
            this.label = label;
            return this;
        }

        public Builder primary(final Color primary) {
            this.primary = primary;
            return this;
        }

        public Builder secondary(final Color secondary) {
            this.secondary = secondary;
            return this;
        }

        /** The tint blend strength, clamped to {@code 0..1}. */
        public Builder tintAmount(final float tintAmount) {
            this.tintAmount = Math.max(0f, Math.min(1f, tintAmount));
            return this;
        }

        public Builder style(final IconStyle style) {
            this.style = style;
            return this;
        }

        public Builder pattern(final PatternType pattern) {
            this.pattern = pattern;
            return this;
        }

        public Builder status(final StatusType status) {
            this.status = status;
            return this;
        }

        public Builder cornerRadius(final int cornerRadius) {
            this.cornerRadius = cornerRadius;
            return this;
        }

        public Builder shadow(final boolean shadow) {
            this.shadow = shadow;
            return this;
        }

        /** Replaces the effect set with the given effects (null clears). */
        public Builder effects(final IconEffect... effects) {
            this.effects.clear();
            if (effects != null) {
                for (IconEffect effect : effects) {
                    if (effect != null) {
                        this.effects.add(effect);
                    }
                }
            }
            return this;
        }

        /** Adds one effect to the set. */
        public Builder addEffect(final IconEffect effect) {
            if (effect != null) {
                this.effects.add(effect);
            }
            return this;
        }

        public Builder brightness(final float brightness) {
            this.brightness = brightness;
            return this;
        }

        public Builder contrast(final float contrast) {
            this.contrast = contrast;
            return this;
        }

        public Builder rotation(final double rotation) {
            this.rotation = rotation;
            return this;
        }

        public Builder flipHorizontal(final boolean flipHorizontal) {
            this.flipHorizontal = flipHorizontal;
            return this;
        }

        public Builder flipVertical(final boolean flipVertical) {
            this.flipVertical = flipVertical;
            return this;
        }

        public IconRecipe build() {
            return new IconRecipe(this);
        }
    }
}
