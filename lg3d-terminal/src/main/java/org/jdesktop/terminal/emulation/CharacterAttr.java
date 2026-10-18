/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file except in
 * compliance with the License. A copy of the License is available at
 * http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.terminal.emulation;

/**
 * Visual attributes for a terminal character: foreground and background colors
 * plus style flags (bold, underline, etc.). Immutable and safe to share.
 *
 * <p>Colors are stored as 8-bit indices into the standard 256-color palette:
 * 0-7 are the basic colors, 8-15 the bright variants, 16-231 the 6x6x6 color
 * cube, and 232-255 the grayscale ramp. {@code -1} means "default" (the
 * terminal's configured foreground or background).</p>
 */
public final class CharacterAttr {
    
    /** Default attributes: default colors, no styles. */
    public static final CharacterAttr DEFAULT = new CharacterAttr(
            -1, -1, false, false, false, false, false, false);
    
    private final int fg;          // foreground color index, -1 = default
    private final int bg;          // background color index, -1 = default
    private final boolean bold;
    private final boolean dim;
    private final boolean italic;
    private final boolean underline;
    private final boolean blink;
    private final boolean inverse;
    
    public CharacterAttr(int fg, int bg, boolean bold, boolean dim,
                         boolean italic, boolean underline, boolean blink,
                         boolean inverse) {
        this.fg = fg;
        this.bg = bg;
        this.bold = bold;
        this.dim = dim;
        this.italic = italic;
        this.underline = underline;
        this.blink = blink;
        this.inverse = inverse;
    }
    
    public int getFg() { return fg; }
    public int getBg() { return bg; }
    public boolean isBold() { return bold; }
    public boolean isDim() { return dim; }
    public boolean isItalic() { return italic; }
    public boolean isUnderline() { return underline; }
    public boolean isBlink() { return blink; }
    public boolean isInverse() { return inverse; }
    
    /** Returns true if this is the default attribute set. */
    public boolean isDefault() {
        return fg == -1 && bg == -1 && !bold && !dim && !italic
                && !underline && !blink && !inverse;
    }
    
    // -- Builders -----------------------------------------------------------
    
    public CharacterAttr withFg(int color) {
        return new CharacterAttr(color, bg, bold, dim, italic, underline, blink, inverse);
    }
    
    public CharacterAttr withBg(int color) {
        return new CharacterAttr(fg, color, bold, dim, italic, underline, blink, inverse);
    }
    
    public CharacterAttr withBold(boolean value) {
        return new CharacterAttr(fg, bg, value, dim, italic, underline, blink, inverse);
    }
    
    public CharacterAttr withDim(boolean value) {
        return new CharacterAttr(fg, bg, bold, value, italic, underline, blink, inverse);
    }
    
    public CharacterAttr withItalic(boolean value) {
        return new CharacterAttr(fg, bg, bold, dim, value, underline, blink, inverse);
    }
    
    public CharacterAttr withUnderline(boolean value) {
        return new CharacterAttr(fg, bg, bold, dim, italic, value, blink, inverse);
    }
    
    public CharacterAttr withBlink(boolean value) {
        return new CharacterAttr(fg, bg, bold, dim, italic, underline, value, inverse);
    }
    
    public CharacterAttr withInverse(boolean value) {
        return new CharacterAttr(fg, bg, bold, dim, italic, underline, blink, value);
    }
    
    /** Resets all attributes to default. */
    public CharacterAttr reset() {
        return DEFAULT;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CharacterAttr other)) return false;
        return fg == other.fg && bg == other.bg
                && bold == other.bold && dim == other.dim
                && italic == other.italic && underline == other.underline
                && blink == other.blink && inverse == other.inverse;
    }
    
    @Override
    public int hashCode() {
        int h = fg;
        h = 31 * h + bg;
        h = 31 * h + (bold ? 1 : 0);
        h = 31 * h + (dim ? 1 : 0);
        h = 31 * h + (italic ? 1 : 0);
        h = 31 * h + (underline ? 1 : 0);
        h = 31 * h + (blink ? 1 : 0);
        h = 31 * h + (inverse ? 1 : 0);
        return h;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Attr[");
        if (fg >= 0) sb.append("fg=").append(fg).append(',');
        if (bg >= 0) sb.append("bg=").append(bg).append(',');
        if (bold) sb.append("bold,");
        if (dim) sb.append("dim,");
        if (italic) sb.append("italic,");
        if (underline) sb.append("underline,");
        if (blink) sb.append("blink,");
        if (inverse) sb.append("inverse,");
        if (sb.length() > 5) sb.setLength(sb.length() - 1); // remove trailing comma
        sb.append(']');
        return sb.toString();
    }
}
