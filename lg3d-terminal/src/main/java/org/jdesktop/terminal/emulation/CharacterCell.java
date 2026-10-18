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
 * A single cell in the terminal grid: one character plus its visual attributes
 * (colors, bold, underline, etc.). Immutable so the grid can be read
 * concurrently with the emulator writing to other cells.
 */
public final class CharacterCell {
    
    /** A space cell with default attributes — the fill value for empty cells. */
    public static final CharacterCell SPACE = new CharacterCell(' ', CharacterAttr.DEFAULT);
    
    private final char ch;
    private final CharacterAttr attr;
    
    public CharacterCell(char ch, CharacterAttr attr) {
        this.ch = ch;
        this.attr = attr != null ? attr : CharacterAttr.DEFAULT;
    }
    
    public char getChar() { return ch; }
    public CharacterAttr getAttr() { return attr; }
    
    /** Returns a copy of this cell with a different character. */
    public CharacterCell withChar(char newCh) {
        return new CharacterCell(newCh, attr);
    }
    
    /** Returns a copy of this cell with different attributes. */
    public CharacterCell withAttr(CharacterAttr newAttr) {
        return new CharacterCell(ch, newAttr);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CharacterCell other)) return false;
        return ch == other.ch && attr.equals(other.attr);
    }
    
    @Override
    public int hashCode() {
        return 31 * ch + attr.hashCode();
    }
    
    @Override
    public String toString() {
        return "Cell[" + (ch == ' ' ? "SP" : String.valueOf(ch)) + ", " + attr + "]";
    }
}
