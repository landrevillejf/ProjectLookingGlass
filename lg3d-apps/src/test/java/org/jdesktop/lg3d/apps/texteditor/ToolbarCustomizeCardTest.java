/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.apps.texteditor.ToolbarButtonConfig.DisplayMode;
import org.jdesktop.lg3d.apps.texteditor.ToolbarButtonConfig.Entry;
import org.jdesktop.lg3d.apps.texteditor.ToolbarCustomizeCard.AvailableCommand;
import org.jdesktop.lg3d.apps.texteditor.ToolbarCustomizeCard.Host;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link ToolbarCustomizeCard}: driving the two-list editor
 * through its test seams — add / remove move commands between the halves, reorder
 * the active draft, flip a display mode, and Apply hands the ordered selection to
 * a capturing {@link Host}. Built on the EDT only; no dialog, combo or layered
 * surface is used, exactly as the card must be offscreen-safe.
 */
class ToolbarCustomizeCardTest {

    /** Records what the card commits; the list host is a plain in-panel card. */
    private static final class FakeHost implements Host {
        final List<List<Entry>> applied = new ArrayList<>();
        int closes;

        @Override
        public List<AvailableCommand> availableCommands() {
            return List.of();
        }

        @Override
        public List<Entry> currentToolbar() {
            return List.of();
        }

        @Override
        public void applyToolbar(List<Entry> entries) {
            applied.add(new ArrayList<>(entries));
        }

        @Override
        public void closeCard() {
            closes++;
        }
    }

    private static List<AvailableCommand> commands() {
        return List.of(
                new AvailableCommand("p/a", "Alpha", "Text", "Do A"),
                new AvailableCommand("p/b", "Beta", "Insert", "Do B"),
                new AvailableCommand("p/c", "Gamma", "Project", "Do C"));
    }

    @Test
    @DisplayName("add moves a command from available to active, in order")
    void addMoves() {
        ToolbarCustomizeCard card = new ToolbarCustomizeCard(new FakeHost());
        card.show(commands(), List.of());
        assertEquals(3, card.availableCount());
        assertEquals(0, card.activeCount());

        card.selectAvailable(0);
        card.clickAdd();
        assertEquals(2, card.availableCount());
        assertEquals(1, card.activeCount());
        assertEquals("p/a", card.draftEntries().get(0).id());

        card.selectAvailable(0); // now "p/b"
        card.clickAdd();
        assertEquals(List.of("p/a", "p/b"), ids(card.draftEntries()));
    }

    @Test
    @DisplayName("remove returns the selected active entry to the available half")
    void removeMoves() {
        ToolbarCustomizeCard card = new ToolbarCustomizeCard(new FakeHost());
        card.show(commands(), List.of(new Entry("p/a", DisplayMode.ICON_TEXT)));
        assertEquals(2, card.availableCount());
        assertEquals(1, card.activeCount());

        card.selectActive(0);
        card.clickRemove();
        assertEquals(0, card.activeCount());
        assertEquals(3, card.availableCount());
    }

    @Test
    @DisplayName("reorder and display-mode edits mutate the draft")
    void reorderAndMode() {
        ToolbarCustomizeCard card = new ToolbarCustomizeCard(new FakeHost());
        card.show(commands(), List.of(
                new Entry("p/a", DisplayMode.ICON_TEXT),
                new Entry("p/b", DisplayMode.ICON_TEXT)));

        card.selectActive(0);
        card.chooseMode(DisplayMode.ICON);
        assertEquals(DisplayMode.ICON, card.draftEntries().get(0).mode());

        card.clickMove(+1);
        assertEquals(List.of("p/b", "p/a"), ids(card.draftEntries()));
        card.clickMove(-1);
        assertEquals(List.of("p/a", "p/b"), ids(card.draftEntries()));
    }

    @Test
    @DisplayName("Apply commits the draft and closes; the host sees the order")
    void applyCommits() {
        FakeHost host = new FakeHost();
        ToolbarCustomizeCard card = new ToolbarCustomizeCard(host);
        card.show(commands(), List.of());
        card.selectAvailable(2); // "p/c"
        card.clickAdd();
        card.selectAvailable(0); // "p/a"
        card.clickAdd();
        card.clickApply();

        assertEquals(1, host.applied.size());
        assertEquals(List.of("p/c", "p/a"), ids(host.applied.get(0)));
        assertEquals(1, host.closes);
    }

    @Test
    @DisplayName("an already-chosen command is not offered again")
    void chosenHiddenFromAvailable() {
        ToolbarCustomizeCard card = new ToolbarCustomizeCard(new FakeHost());
        card.show(commands(), List.of(new Entry("p/b", DisplayMode.ICON)));
        assertEquals(2, card.availableCount());
        assertTrue(card.draftEntries().stream().anyMatch(e -> e.id().equals("p/b")));
    }

    @Test
    @DisplayName("selecting an available command enables Add through the live listener")
    void selectionEnablesAdd() {
        ToolbarCustomizeCard card = new ToolbarCustomizeCard(new FakeHost());
        card.show(commands(), List.of(new Entry("p/a", DisplayMode.ICON_TEXT)));
        // One command is already chosen, so nothing is pre-selected and Add is off.
        assertFalse(card.isAddEnabled());

        // A plain click on a row (modeled by moving the list selection, which is
        // what the mouse does) must re-enable Add without any seam help — this is
        // the path that used to leave the card looking dead.
        card.selectAvailable(0);
        assertTrue(card.isAddEnabled());
        assertTrue(card.isRemoveEnabled(), "the active row stays selectable");

        card.selectAvailable(-1);
        assertFalse(card.isAddEnabled(), "deselecting greys Add back out");
    }

    @Test
    @DisplayName("opening with an empty toolbar pre-selects the first command")
    void emptyToolbarOffersOneClickAdd() {
        ToolbarCustomizeCard card = new ToolbarCustomizeCard(new FakeHost());
        card.show(commands(), List.of());
        assertTrue(card.isAddEnabled(),
                "the first available command is selected on open");
        card.clickAdd();
        assertEquals(List.of("p/a"), ids(card.draftEntries()));
    }

    private static List<String> ids(List<Entry> entries) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) {
            out.add(e.id());
        }
        return out;
    }
}
