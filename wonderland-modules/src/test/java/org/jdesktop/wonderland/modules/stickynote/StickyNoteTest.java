/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (stickynote): the note content model -- null body /
 * author normalised to empty, null colour selecting the default YELLOW, the
 * no-clear setter semantics, and isEmpty.
 */
package org.jdesktop.wonderland.modules.stickynote;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StickyNoteTest {

    @Test
    void nullsAreNormalisedOnConstruction() {
        StickyNote note = new StickyNote(null, null, null);

        assertThat(note.getText()).isEmpty();
        assertThat(note.getAuthor()).isEmpty();
        assertThat(note.getColor()).isEqualTo(StickyNote.Color.YELLOW);
        assertThat(note.isEmpty()).isTrue();
    }

    @Test
    void textAndColourAreEditable() {
        StickyNote note = new StickyNote("buy milk", "alice", StickyNote.Color.GREEN);
        assertThat(note.isEmpty()).isFalse();

        note.setText("buy oat milk");
        note.setColor(StickyNote.Color.BLUE);
        assertThat(note.getText()).isEqualTo("buy oat milk");
        assertThat(note.getColor()).isEqualTo(StickyNote.Color.BLUE);

        note.setText(null);
        assertThat(note.getText()).isEmpty();
        assertThat(note.isEmpty()).isTrue();
    }

    @Test
    void aNullColourKeepsTheCurrentColourRatherThanClearingIt() {
        StickyNote note = new StickyNote("x", "a", StickyNote.Color.PINK);
        note.setColor(null);
        assertThat(note.getColor()).isEqualTo(StickyNote.Color.PINK);
    }

    @Test
    void theAuthorIsImmutable() {
        StickyNote note = new StickyNote("x", "carol", StickyNote.Color.WHITE);
        assertThat(note.getAuthor()).isEqualTo("carol");
    }
}
