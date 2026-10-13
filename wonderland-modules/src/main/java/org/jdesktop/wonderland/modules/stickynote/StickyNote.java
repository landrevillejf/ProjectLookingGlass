/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (stickynote): the headless note model.
 *
 * The text, author and colour a sticky-note cell carries. The Java 3D note
 * renderer -- the 2006 module's paper quad with the text painted on it -- is
 * deferred to M6; the data the renderer needs is fully specified (and tested)
 * here.
 */
package org.jdesktop.wonderland.modules.stickynote;

/** The content of one sticky note. */
public final class StickyNote {

    /** The fixed note palette the 2006 module offered. */
    public enum Color {
        /** Default note colour. */
        YELLOW,
        /** A green note. */
        GREEN,
        /** A blue note. */
        BLUE,
        /** A pink note. */
        PINK,
        /** A white note. */
        WHITE
    }

    private String text;
    private final String author;
    private Color color;

    /**
     * @param text note body; null becomes empty
     * @param author display name of who wrote it; null becomes empty
     * @param color note colour; null selects the default YELLOW
     */
    public StickyNote(String text, String author, Color color) {
        this.text = text == null ? "" : text;
        this.author = author == null ? "" : author;
        this.color = color == null ? Color.YELLOW : color;
    }

    /** @return the note body, never null */
    public String getText() {
        return text;
    }

    /** @param text new body; null becomes empty */
    public void setText(String text) {
        this.text = text == null ? "" : text;
    }

    /** @return the author display name, never null */
    public String getAuthor() {
        return author;
    }

    /** @return the note colour, never null */
    public Color getColor() {
        return color;
    }

    /** @param color new colour; null keeps the current colour rather than clearing it */
    public void setColor(Color color) {
        if (color != null) {
            this.color = color;
        }
    }

    /** @return whether the note has any visible text */
    public boolean isEmpty() {
        return text.isEmpty();
    }
}
