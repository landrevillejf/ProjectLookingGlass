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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link Shortcuts}' resolve-and-dispatch path with a fake
 * {@link Shortcuts.Target} that records which action fired. Because the dispatch
 * is a pure switch over action ids, the whole path is exercised headless — no
 * {@code KeyEventDispatcher}, focus manager or display.
 */
class ShortcutsTest {

    /** A {@link Shortcuts.Target} that just logs the actions it receives. */
    private static final class RecordingTarget implements Shortcuts.Target {
        final List<String> fired = new ArrayList<>();

        @Override
        public void showDesktop() {
            fired.add(ShortcutMap.SHOW_DESKTOP);
        }

        @Override
        public void snapLeft() {
            fired.add(ShortcutMap.SNAP_LEFT);
        }

        @Override
        public void snapRight() {
            fired.add(ShortcutMap.SNAP_RIGHT);
        }

        @Override
        public void snapMaximize() {
            fired.add(ShortcutMap.SNAP_MAXIMIZE);
        }

        @Override
        public void runDialog() {
            fired.add(ShortcutMap.RUN_DIALOG);
        }

        @Override
        public void openTerminal() {
            fired.add(ShortcutMap.OPEN_TERMINAL);
        }

        @Override
        public void closeWindow() {
            fired.add(ShortcutMap.WINDOW_CLOSE);
        }

        @Override
        public void workspaceNext() {
            fired.add(ShortcutMap.WORKSPACE_NEXT);
        }

        @Override
        public void workspacePrevious() {
            fired.add(ShortcutMap.WORKSPACE_PREVIOUS);
        }

        @Override
        public void moveWindowToWorkspace(int index) {
            fired.add(ShortcutMap.MOVE_TO_WORKSPACE_PREFIX + index);
        }
    }

    @Test
    @DisplayName("handle fires the matching target method for each action id")
    void handleDispatchesEachAction() {
        String[] ids = {
            ShortcutMap.SHOW_DESKTOP, ShortcutMap.SNAP_LEFT, ShortcutMap.SNAP_RIGHT,
            ShortcutMap.SNAP_MAXIMIZE, ShortcutMap.RUN_DIALOG, ShortcutMap.OPEN_TERMINAL,
            ShortcutMap.WINDOW_CLOSE,
        };
        for (String id : ids) {
            RecordingTarget target = new RecordingTarget();
            Shortcuts.handle(id, target);
            assertEquals(List.of(id), target.fired, id + " fires exactly its own action");
        }
    }

    @Test
    @DisplayName("an unknown action id fires nothing")
    void handleUnknown() {
        RecordingTarget target = new RecordingTarget();
        Shortcuts.handle("not-an-action", target);
        assertTrue(target.fired.isEmpty());
    }

    @Test
    @DisplayName("null action id or target is a safe no-op")
    void handleNulls() {
        RecordingTarget target = new RecordingTarget();
        Shortcuts.handle(null, target);
        Shortcuts.handle(ShortcutMap.SHOW_DESKTOP, null);
        assertTrue(target.fired.isEmpty());
    }

    @Test
    @DisplayName("dispatch resolves a bound stroke, fires it and consumes the event")
    void dispatchBoundStroke() {
        RecordingTarget target = new RecordingTarget();
        boolean handled = Shortcuts.dispatch(
                KeyStroke.getKeyStroke("alt F2"), ShortcutMap.defaults(), target);
        assertTrue(handled, "a bound stroke is consumed");
        assertEquals(List.of(ShortcutMap.RUN_DIALOG), target.fired);
    }

    @Test
    @DisplayName("dispatch leaves an unbound stroke alone so it falls through")
    void dispatchUnboundStroke() {
        RecordingTarget target = new RecordingTarget();
        boolean handled = Shortcuts.dispatch(
                KeyStroke.getKeyStroke("alt BACK_QUOTE"), ShortcutMap.defaults(), target);
        assertFalse(handled, "the switcher key is not consumed");
        assertTrue(target.fired.isEmpty());
    }

    @Test
    @DisplayName("dispatch is a safe no-op for a null stroke, map or target")
    void dispatchNulls() {
        RecordingTarget target = new RecordingTarget();
        assertFalse(Shortcuts.dispatch(null, ShortcutMap.defaults(), target));
        assertFalse(Shortcuts.dispatch(KeyStroke.getKeyStroke("alt F2"), null, target));
        assertFalse(Shortcuts.dispatch(KeyStroke.getKeyStroke("alt F2"), ShortcutMap.defaults(), null));
        assertTrue(target.fired.isEmpty());
    }

    @Test
    @DisplayName("every default binding dispatches to its own action")
    void dispatchAllDefaults() {
        for (var entry : ShortcutMap.defaultBindings().entrySet()) {
            RecordingTarget target = new RecordingTarget();
            boolean handled = Shortcuts.dispatch(
                    KeyStroke.getKeyStroke(entry.getKey()), ShortcutMap.defaults(), target);
            assertTrue(handled, entry.getKey() + " is bound");
            assertEquals(List.of(entry.getValue()), target.fired);
        }
    }
}
