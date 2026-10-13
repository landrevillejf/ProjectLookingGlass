/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7a: headless tests for the Playlist cursor walk.
 */
package org.jdesktop.wonderland.modules.videoplayer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaylistTest {

    private Playlist playlist;

    @BeforeEach
    void setUp() {
        playlist = new Playlist();
    }

    @Test
    void emptyPlaylistHasNoCurrent() {
        assertThat(playlist.isEmpty()).isTrue();
        assertThat(playlist.size()).isZero();
        assertThat(playlist.cursor()).isEqualTo(-1);
        assertThat(playlist.current()).isNull();
    }

    @Test
    void firstAddBecomesCurrent() {
        int index = playlist.add("a.mp4", 1000);
        assertThat(index).isZero();
        assertThat(playlist.cursor()).isZero();
        assertThat(playlist.current().uri()).isEqualTo("a.mp4");
        assertThat(playlist.current().durationMs()).isEqualTo(1000);
    }

    @Test
    void addRefusesNullOrEmptyUri() {
        assertThat(playlist.add(null, 10)).isEqualTo(-1);
        assertThat(playlist.add("", 10)).isEqualTo(-1);
        assertThat(playlist.isEmpty()).isTrue();
    }

    @Test
    void negativeDurationIsFlooredToZero() {
        playlist.add("a.mp4", -50);
        assertThat(playlist.current().durationMs()).isZero();
    }

    @Test
    void nextWrapsAtTheEnd() {
        playlist.add("a.mp4", 1);
        playlist.add("b.mp4", 2);
        playlist.add("c.mp4", 3);
        assertThat(playlist.next().uri()).isEqualTo("b.mp4");
        assertThat(playlist.next().uri()).isEqualTo("c.mp4");
        assertThat(playlist.next().uri()).isEqualTo("a.mp4"); // wrapped
    }

    @Test
    void previousWrapsAtTheFront() {
        playlist.add("a.mp4", 1);
        playlist.add("b.mp4", 2);
        assertThat(playlist.previous().uri()).isEqualTo("b.mp4"); // wrapped to last
        assertThat(playlist.previous().uri()).isEqualTo("a.mp4");
    }

    @Test
    void navigationOnEmptyIsRefused() {
        assertThat(playlist.next()).isNull();
        assertThat(playlist.previous()).isNull();
        assertThat(playlist.moveTo(3)).isEqualTo(-1);
    }

    @Test
    void moveToClampsIntoRange() {
        playlist.add("a.mp4", 1);
        playlist.add("b.mp4", 2);
        assertThat(playlist.moveTo(9)).isEqualTo(1);
        assertThat(playlist.current().uri()).isEqualTo("b.mp4");
        assertThat(playlist.moveTo(-4)).isZero();
        assertThat(playlist.current().uri()).isEqualTo("a.mp4");
    }

    @Test
    void removeCurrentLeavesTheCursorOnTheSuccessor() {
        playlist.add("a.mp4", 1);
        playlist.add("b.mp4", 2);
        playlist.add("c.mp4", 3);
        playlist.moveTo(1);                 // on b
        assertThat(playlist.removeAt(1)).isTrue(); // remove b
        assertThat(playlist.current().uri()).isEqualTo("c.mp4");
        assertThat(playlist.cursor()).isEqualTo(1);
    }

    @Test
    void removeTailWhileOnItClampsToTheNewLast() {
        playlist.add("a.mp4", 1);
        playlist.add("b.mp4", 2);
        playlist.moveTo(1);                 // on b (the tail)
        playlist.removeAt(1);
        assertThat(playlist.current().uri()).isEqualTo("a.mp4");
        assertThat(playlist.cursor()).isZero();
    }

    @Test
    void removeBeforeTheCursorShiftsItLeft() {
        playlist.add("a.mp4", 1);
        playlist.add("b.mp4", 2);
        playlist.moveTo(1);                 // on b
        playlist.removeAt(0);               // remove a
        assertThat(playlist.cursor()).isZero();
        assertThat(playlist.current().uri()).isEqualTo("b.mp4");
    }

    @Test
    void removeLastItemResetsTheCursor() {
        playlist.add("a.mp4", 1);
        playlist.removeAt(0);
        assertThat(playlist.cursor()).isEqualTo(-1);
        assertThat(playlist.current()).isNull();
    }

    @Test
    void removeAtOutOfRangeIsRefused() {
        playlist.add("a.mp4", 1);
        assertThat(playlist.removeAt(5)).isFalse();
        assertThat(playlist.removeAt(-1)).isFalse();
    }

    @Test
    void removePastTheCursorLeavesItUntouched() {
        playlist.add("a.mp4", 1);
        playlist.add("b.mp4", 2);
        playlist.add("c.mp4", 3);
        playlist.removeAt(2); // the cursor is on a (0); the tail removal is inert
        assertThat(playlist.cursor()).isZero();
        assertThat(playlist.current().uri()).isEqualTo("a.mp4");
    }

    @Test
    void itemsSnapshotIsUnmodifiable() {
        playlist.add("a.mp4", 1);
        // An unmodifiable view throws on any mutation before touching content.
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> playlist.items().clear());
    }

    @Test
    void clearResetsEverything() {
        playlist.add("a.mp4", 1);
        playlist.clear();
        assertThat(playlist.isEmpty()).isTrue();
        assertThat(playlist.cursor()).isEqualTo(-1);
    }
}
