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
package org.jdesktop.lg3d.scenemanager.utils.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JPanel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.MenuModel;
import org.jdesktop.lg3d.displayserver.desktop2d.RunHistory;
import org.jdesktop.lg3d.displayserver.desktop2d.RunHistoryStore;
import org.jdesktop.lg3d.displayserver.desktop2d.RunResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link RunDialogPanel}: the programmatic key dispatch
 * (typing, backspace, Enter/Escape, history recall), the submit path (app-name
 * match resolves and runs, a non-match leaves the card open with an explanatory
 * status), and the history persistence through an injected fake store. Nothing
 * here touches Java 3D: the {@code SwingNode} host {@link RunDialog3D} and the
 * global key glue in {@link RunDialogPlugin} are probe-verified separately.
 *
 * <p>Resolution itself is delegated to the already-covered {@link RunResolver},
 * so these tests drive it only through a deterministic start-menu app-name
 * match and a guaranteed-missing token rather than re-testing the decision
 * table.</p>
 */
class RunDialogPanelTest {

    /** A real {@code Component} for the synthetic key events' source. */
    private static final Component SRC = new JPanel();

    private static final String CALC_COMMAND =
            "java org.jdesktop.lg3d.apps.calculator.Calculator";

    /** A token that is not an app name and is never on the PATH. */
    private static final String BOGUS = "qqqzzz-not-a-real-command-9999";

    private static MenuModel model() {
        ItemSpec calculator = new ItemSpec("Calculator", CALC_COMMAND,
                "A calculator", "Utilities", null);
        return new MenuModel(List.of(), List.of(calculator));
    }

    /** In-memory {@link RunHistoryStore} that records what was saved. */
    private static final class FakeStore implements RunHistoryStore {
        private RunHistory seed = new RunHistory();
        private RunHistory saved;
        private int saves;

        @Override
        public void save(RunHistory history) {
            saves++;
            saved = history;
        }

        @Override
        public RunHistory load() {
            return seed;
        }

        @Override
        public void clear() {
            seed = new RunHistory();
        }
    }

    /** The panel plus the collaborators a test asserts on. */
    private static final class Fixture {
        final RunHistory history = new RunHistory();
        final FakeStore store = new FakeStore();
        final List<RunResolver.Decision> launched = new ArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final RunDialogPanel panel;

        Fixture(MenuModel model) {
            panel = new RunDialogPanel(model, history, store,
                    launched::add, () -> closed.set(true));
        }
    }

    // ------------------------------------------------------------ key helpers

    private static KeyEvent typed(char c) {
        return new KeyEvent(SRC, KeyEvent.KEY_TYPED, 0L, 0,
                KeyEvent.VK_UNDEFINED, c);
    }

    private static KeyEvent pressed(int code) {
        return new KeyEvent(SRC, KeyEvent.KEY_PRESSED, 0L, 0,
                code, KeyEvent.CHAR_UNDEFINED);
    }

    private static void type(RunDialogPanel panel, String text) {
        for (char c : text.toCharArray()) {
            panel.dispatch(typed(c));
        }
    }

    // ------------------------------------------------------------ initial state

    @Test
    @DisplayName("a fresh panel is empty and shows the hint")
    void startsEmpty() {
        Fixture f = new Fixture(model());
        assertEquals("", f.panel.text());
        assertEquals(RunDialogPanel.HINT, f.panel.statusText());
    }

    @Test
    @DisplayName("a null history is replaced by an empty one")
    void nullHistoryBecomesEmpty() {
        RunDialogPanel panel = new RunDialogPanel(
                model(), null, null, null, null);
        panel.recallOlder();
        assertEquals("", panel.text(), "no history means nothing to recall");
    }

    @Test
    @DisplayName("reset clears the field, the recall cursor and the status")
    void resetClearsEverything() {
        Fixture f = new Fixture(model());
        type(f.panel, "Calculator");
        f.panel.recallOlder();
        f.panel.reset();
        assertEquals("", f.panel.text());
        assertEquals(RunDialogPanel.HINT, f.panel.statusText());
    }

    // ------------------------------------------------------------------ typing

    @Test
    @DisplayName("typed characters append and backspace removes the last one")
    void appendAndBackspace() {
        Fixture f = new Fixture(model());
        type(f.panel, "fire");
        assertEquals("fire", f.panel.text());
        assertTrue(f.panel.dispatch(pressed(KeyEvent.VK_BACK_SPACE)));
        assertEquals("fir", f.panel.text());
    }

    @Test
    @DisplayName("backspace on an empty field is a harmless no-op")
    void backspaceOnEmpty() {
        Fixture f = new Fixture(model());
        f.panel.backspace();
        assertEquals("", f.panel.text());
    }

    @Test
    @DisplayName("typing after a recall abandons the recall cursor")
    void typingAfterRecallResetsCursor() {
        Fixture f = new Fixture(model());
        f.history.add("xterm");
        f.panel.recallOlder();
        assertEquals("xterm", f.panel.text());
        type(f.panel, "-e");
        assertEquals("xterm-e", f.panel.text());
        // A fresh recall starts from the most recent entry again, not from where
        // the abandoned cursor was.
        f.panel.recallOlder();
        assertEquals("xterm", f.panel.text());
    }

    // ---------------------------------------------------------------- dispatch

    @Test
    @DisplayName("dispatch consumes control keys and printable characters")
    void dispatchConsumes() {
        Fixture f = new Fixture(model());
        assertTrue(f.panel.dispatch(typed('a')));
        assertEquals("a", f.panel.text());
        assertTrue(f.panel.dispatch(pressed(KeyEvent.VK_ENTER)), "Enter is consumed");
        assertTrue(f.panel.dispatch(pressed(KeyEvent.VK_ESCAPE)), "Escape is consumed");
        assertTrue(f.panel.dispatch(pressed(KeyEvent.VK_UP)), "Up is consumed");
        assertTrue(f.panel.dispatch(pressed(KeyEvent.VK_DOWN)), "Down is consumed");
        assertTrue(f.panel.dispatch(pressed(KeyEvent.VK_BACK_SPACE)), "Backspace is consumed");
        assertFalse(f.panel.dispatch(pressed(KeyEvent.VK_SHIFT)),
                "an unbound control key passes through");
    }

    @Test
    @DisplayName("dispatch ignores null, releases and ISO control characters")
    void dispatchIgnores() {
        Fixture f = new Fixture(model());
        assertFalse(f.panel.dispatch(null));
        assertFalse(f.panel.dispatch(new KeyEvent(SRC, KeyEvent.KEY_RELEASED,
                0L, 0, KeyEvent.VK_A, KeyEvent.CHAR_UNDEFINED)));
        assertFalse(f.panel.dispatch(typed('\n')), "ISO control characters pass through");
        assertEquals("", f.panel.text());
    }

    @Test
    @DisplayName("Escape invokes the close callback")
    void escapeCloses() {
        Fixture f = new Fixture(model());
        assertTrue(f.panel.dispatch(pressed(KeyEvent.VK_ESCAPE)));
        assertTrue(f.closed.get());
    }

    // ------------------------------------------------------------------ submit

    @Test
    @DisplayName("submitting an app name runs it, records history and closes")
    void submitAppName() {
        Fixture f = new Fixture(model());
        type(f.panel, "Calculator");
        assertTrue(f.panel.submit());

        assertEquals(1, f.launched.size());
        RunResolver.Decision decision = f.launched.get(0);
        assertTrue(decision.isApp());
        assertEquals(CALC_COMMAND, decision.command());

        assertEquals(List.of("Calculator"), f.history.entries());
        assertSame(f.history, f.store.saved, "the live history is persisted");
        assertEquals(1, f.store.saves);
        assertTrue(f.closed.get());
    }

    @Test
    @DisplayName("Enter drives the same submit path")
    void enterSubmits() {
        Fixture f = new Fixture(model());
        type(f.panel, "Calculator");
        assertTrue(f.panel.dispatch(pressed(KeyEvent.VK_ENTER)));
        assertEquals(1, f.launched.size());
        assertTrue(f.closed.get());
    }

    @Test
    @DisplayName("a non-match leaves the card open with an explanatory status")
    void submitNotFound() {
        Fixture f = new Fixture(model());
        type(f.panel, BOGUS);
        assertFalse(f.panel.submit());
        assertEquals("Not found: " + BOGUS, f.panel.statusText());
        assertTrue(f.launched.isEmpty());
        assertTrue(f.history.isEmpty(), "a non-match is not recorded");
        assertEquals(0, f.store.saves);
        assertFalse(f.closed.get());
    }

    @Test
    @DisplayName("submitting blank input restores the hint and runs nothing")
    void submitBlank() {
        Fixture f = new Fixture(model());
        assertFalse(f.panel.submit());
        assertEquals(RunDialogPanel.HINT, f.panel.statusText());
        assertTrue(f.launched.isEmpty());
        assertFalse(f.closed.get());
    }

    @Test
    @DisplayName("submit tolerates a null store and a null runner")
    void submitWithoutStoreOrRunner() {
        RunDialogPanel panel = new RunDialogPanel(
                model(), new RunHistory(), null, null, null);
        panel.append('C');
        // Type the rest of the app name so it resolves, with no store/runner to
        // persist or launch through; it must not throw.
        for (char c : "alculator".toCharArray()) {
            panel.append(c);
        }
        assertTrue(panel.submit());
        assertEquals("Calculator", panel.text());
    }

    // ------------------------------------------------------------------ recall

    @Test
    @DisplayName("up and down walk the history most-recent-first and back to empty")
    void recallWalksHistory() {
        Fixture f = new Fixture(model());
        f.history.add("one");
        f.history.add("two");
        f.history.add("three");   // entries(): three, two, one

        f.panel.recallOlder();
        assertEquals("three", f.panel.text());
        f.panel.recallOlder();
        assertEquals("two", f.panel.text());
        f.panel.recallOlder();
        assertEquals("one", f.panel.text());
        f.panel.recallOlder();     // clamps at the oldest
        assertEquals("one", f.panel.text());

        f.panel.recallNewer();
        assertEquals("two", f.panel.text());
        f.panel.recallNewer();
        assertEquals("three", f.panel.text());
        f.panel.recallNewer();     // past the newest clears the field
        assertEquals("", f.panel.text());
        f.panel.recallNewer();     // already clear: no-op
        assertEquals("", f.panel.text());
    }

    @Test
    @DisplayName("dispatch routes Up and Down to the recall cursor")
    void recallViaDispatch() {
        Fixture f = new Fixture(model());
        f.history.add("xterm");
        assertTrue(f.panel.dispatch(pressed(KeyEvent.VK_UP)));
        assertEquals("xterm", f.panel.text());
        assertTrue(f.panel.dispatch(pressed(KeyEvent.VK_DOWN)));
        assertEquals("", f.panel.text());
    }

    @Test
    @DisplayName("recall on an empty history changes nothing")
    void recallOnEmptyHistory() {
        Fixture f = new Fixture(model());
        f.panel.recallOlder();
        f.panel.recallNewer();
        assertEquals("", f.panel.text());
    }
}
