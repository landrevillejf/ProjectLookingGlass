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
package org.jdesktop.lg3d.apps.imagestudio;

import java.awt.image.BufferedImage;

/**
 * The single source of truth for Image Studio's operation catalog: the four
 * category tabs, their operation buttons (label, code, optional slider range)
 * and the {@link EditorModel.Op} factory that maps a code plus a parameter
 * value onto a {@link JaiProcessor} call.
 *
 * <p>Both UIs share this catalog: {@link Toolbar3D} renders it as scene-graph
 * buttons on the 3D desktop and {@link ImageStudioPanel} renders it as Swing
 * buttons on the 2D/Swing desktop, so the two surfaces always offer exactly
 * the same edits with the same ranges and defaults.</p>
 */
final class OpCatalog {

    // Operation codes.
    static final int
        FLIP_H = 1, FLIP_V = 2, ROT90 = 3, ROTATE = 4, SCALE = 5, PIXELATE = 6, BORDER = 7,
        BRIGHT = 10, CONTRAST = 11, GAMMA = 12, GRAY = 13, SEPIA = 14, INVERT = 15,
        POSTERIZE = 16, THRESHOLD = 17,
        BLUR = 20, SHARPEN = 21, EMBOSS = 22, EDGE = 23,
        ADD = 30, SUB = 31, MUL = 32, ABS = 33, AND = 34, OR = 35, XOR = 36, NOISE = 37;

    /** One catalogued operation. */
    static final class OpDef {
        final String label;
        final int code;
        final boolean param;
        final float min;
        final float max;
        final float init;
        final String fmt;
        final boolean intFmt;

        OpDef(String label, int code) {
            this.label = label; this.code = code; this.param = false;
            this.min = 0f; this.max = 1f; this.init = 0f; this.fmt = "%.0f"; this.intFmt = true;
        }

        OpDef(String label, int code, float min, float max, float init,
                String fmt, boolean intFmt) {
            this.label = label; this.code = code; this.param = true;
            this.min = min; this.max = max; this.init = init; this.fmt = fmt; this.intFmt = intFmt;
        }
    }

    static final String[] CATEGORIES = { "Geometry", "Color", "Filter", "Math" };

    static final OpDef[][] CATALOG = {
        {
            new OpDef("Flip H", FLIP_H),
            new OpDef("Flip V", FLIP_V),
            new OpDef("Rot 90", ROT90),
            new OpDef("Rotate", ROTATE, -180f, 180f, 0f, "%.0f\u00B0", true),
            new OpDef("Scale", SCALE, 0.1f, 3.0f, 1.0f, "x%.2f", false),
            new OpDef("Pixelate", PIXELATE, 2f, 40f, 6f, "%.0f", true),
            new OpDef("Border", BORDER, 0f, 40f, 8f, "%.0f", true),
        },
        {
            new OpDef("Brighter", BRIGHT, -128f, 128f, 0f, "%+.0f", true),
            new OpDef("Contrast", CONTRAST, 0f, 3f, 1f, "x%.2f", false),
            new OpDef("Gamma", GAMMA, 0.2f, 3f, 1f, "%.2f", false),
            new OpDef("Gray", GRAY),
            new OpDef("Sepia", SEPIA),
            new OpDef("Invert", INVERT),
            new OpDef("Posterize", POSTERIZE, 2f, 32f, 4f, "%.0f", true),
            new OpDef("Threshold", THRESHOLD, 0f, 255f, 128f, "%.0f", true),
        },
        {
            new OpDef("Blur", BLUR, 1f, 20f, 3f, "%.0f", true),
            new OpDef("Sharpen", SHARPEN, 0.05f, 2f, 0.6f, "%.2f", false),
            new OpDef("Emboss", EMBOSS),
            new OpDef("Edges", EDGE),
        },
        {
            new OpDef("Add", ADD, -128f, 128f, 20f, "%+.0f", true),
            new OpDef("Subtract", SUB, -128f, 128f, 20f, "%+.0f", true),
            new OpDef("Multiply", MUL, 0.2f, 3f, 1.1f, "x%.2f", false),
            new OpDef("Abs", ABS),
            new OpDef("AND", AND, 0f, 255f, 240f, "0x%02X", true),
            new OpDef("OR", OR, 0f, 255f, 15f, "0x%02X", true),
            new OpDef("XOR", XOR, 0f, 255f, 255f, "0x%02X", true),
            new OpDef("Noise", NOISE, 0f, 64f, 20f, "%.0f", true),
        },
    };

    /** Finds a catalogued operation by its button label, or null. */
    static OpDef forLabel(String label) {
        if (label == null) {
            return null;
        }
        for (OpDef[] category : CATALOG) {
            for (OpDef op : category) {
                if (label.equals(op.label)) {
                    return op;
                }
            }
        }
        return null;
    }

    /**
     * Formats an op parameter for display: hexadecimal patterns take an
     * {@code Integer}, integer-style {@code %f} patterns take a rounded
     * {@code Double} and the rest take the raw {@code Float}. Passing an
     * {@code Integer} to a {@code %f} pattern throws
     * {@code IllegalFormatConversionException}, which silently broke the 3D
     * slider's value label for every int-formatted op.
     */
    static String formatValue(String fmt, boolean intFmt, float v) {
        Object arg;
        if (fmt.indexOf('X') >= 0) {
            arg = Integer.valueOf(Math.round(v));
        } else if (intFmt) {
            arg = Double.valueOf(Math.round(v));
        } else {
            arg = Float.valueOf(v);
        }
        return String.format(fmt, arg);
    }

    private OpCatalog() {
    }

    /** Build the {@link EditorModel.Op} for an operation code at a given value. */
    static EditorModel.Op opFor(final int code, final float v) {
        return new EditorModel.Op() {
            public BufferedImage apply(BufferedImage src) {
                switch (code) {
                    // Geometry
                    case FLIP_H:    return JaiProcessor.flipHorizontal(src);
                    case FLIP_V:    return JaiProcessor.flipVertical(src);
                    case ROT90:     return JaiProcessor.rotate90(src);
                    case ROTATE:    return JaiProcessor.rotate(src, v);
                    case SCALE:     return JaiProcessor.scale(src, v);
                    case PIXELATE:  return JaiProcessor.pixelate(src, Math.round(v));
                    case BORDER:    return JaiProcessor.border(src, Math.round(v), 0xF0F0F0);
                    // Colour
                    case BRIGHT:    return JaiProcessor.brightness(src, v);
                    case CONTRAST:  return JaiProcessor.contrast(src, v);
                    case GAMMA:     return JaiProcessor.gamma(src, v);
                    case GRAY:      return JaiProcessor.grayscale(src);
                    case SEPIA:     return JaiProcessor.sepia(src);
                    case INVERT:    return JaiProcessor.invert(src);
                    case POSTERIZE: return JaiProcessor.posterize(src, Math.round(v));
                    case THRESHOLD: return JaiProcessor.threshold(src, Math.round(v));
                    // Filters
                    case BLUR:      return JaiProcessor.blur(src, Math.round(v));
                    case SHARPEN:   return JaiProcessor.sharpen(src, v);
                    case EMBOSS:    return JaiProcessor.emboss(src);
                    case EDGE:      return JaiProcessor.edge(src);
                    // Math / logic
                    case ADD:       return JaiProcessor.addConst(src, v);
                    case SUB:       return JaiProcessor.subtractConst(src, v);
                    case MUL:       return JaiProcessor.multiplyConst(src, v);
                    case ABS:       return JaiProcessor.absolute(src);
                    case AND:       return JaiProcessor.andConst(src, Math.round(v));
                    case OR:        return JaiProcessor.orConst(src, Math.round(v));
                    case XOR:       return JaiProcessor.xorConst(src, Math.round(v));
                    case NOISE:     return JaiProcessor.noise(src, Math.round(v));
                    default:        return src;
                }
            }
        };
    }
}
