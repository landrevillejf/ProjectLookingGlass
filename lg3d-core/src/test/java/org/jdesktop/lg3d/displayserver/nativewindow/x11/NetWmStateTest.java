/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.jdesktop.lg3d.displayserver.nativewindow.x11.NetWmState.Action;
import org.jdesktop.lg3d.displayserver.nativewindow.x11.NetWmState.ActiveDecision;
import org.jdesktop.lg3d.displayserver.nativewindow.x11.NetWmState.ActiveSource;
import org.jdesktop.lg3d.displayserver.nativewindow.x11.NetWmState.ChangeAction;
import org.jdesktop.lg3d.displayserver.nativewindow.x11.NetWmState.State;
import org.jdesktop.lg3d.displayserver.nativewindow.x11.NetWmState.WindowType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link NetWmState} — the pure EWMH decision layer
 * (Phase D). It is a static function over enums and {@link EnumSet}s with no
 * {@code gnu.x11.Display} or atom ids, so every state-change, default-state,
 * allowed-action and focus-stealing decision is pinned directly.
 */
class NetWmStateTest {

    // ---- atom-name mapping -------------------------------------------

    @Test
    @DisplayName("state atom names follow the _NET_WM_STATE_ convention")
    void stateAtomNames() {
        assertEquals("_NET_WM_STATE_MAXIMIZED_VERT",
            NetWmState.stateAtomName(State.MAXIMIZED_VERT));
        assertEquals("_NET_WM_STATE_SKIP_TASKBAR",
            NetWmState.stateAtomName(State.SKIP_TASKBAR));
        assertEquals("_NET_WM_STATE_DEMANDS_ATTENTION",
            NetWmState.stateAtomName(State.DEMANDS_ATTENTION));
    }

    @Test
    @DisplayName("action atom names follow the _NET_WM_ACTION_ convention")
    void actionAtomNames() {
        assertEquals("_NET_WM_ACTION_CLOSE", NetWmState.actionAtomName(Action.CLOSE));
        assertEquals("_NET_WM_ACTION_MAXIMIZE_HORZ",
            NetWmState.actionAtomName(Action.MAXIMIZE_HORZ));
    }

    // ---- ChangeAction / ActiveSource wire codes -----------------------

    @Test
    @DisplayName("ChangeAction codes match EWMH and round-trip through fromCode")
    void changeActionCodes() {
        assertEquals(0, ChangeAction.REMOVE.code());
        assertEquals(1, ChangeAction.ADD.code());
        assertEquals(2, ChangeAction.TOGGLE.code());
        assertEquals(ChangeAction.REMOVE, ChangeAction.fromCode(0));
        assertEquals(ChangeAction.ADD, ChangeAction.fromCode(1));
        assertEquals(ChangeAction.TOGGLE, ChangeAction.fromCode(2));
        assertNull(ChangeAction.fromCode(3));
        assertNull(ChangeAction.fromCode(-1));
    }

    @Test
    @DisplayName("ActiveSource.fromCode maps 1->APPLICATION, 2->PAGER, else NONE")
    void activeSourceCodes() {
        assertEquals(ActiveSource.APPLICATION, ActiveSource.fromCode(1));
        assertEquals(ActiveSource.PAGER, ActiveSource.fromCode(2));
        assertEquals(ActiveSource.NONE, ActiveSource.fromCode(0));
        assertEquals(ActiveSource.NONE, ActiveSource.fromCode(99));
    }

    // ---- applyChange --------------------------------------------------

    @Test
    @DisplayName("applyChange ADD/REMOVE/TOGGLE mutate a copy, never the input")
    void applyChangeAddRemoveToggle() {
        EnumSet<State> base = EnumSet.of(State.ABOVE);

        EnumSet<State> added = NetWmState.applyChange(base, ChangeAction.ADD, State.MODAL);
        assertTrue(added.contains(State.MODAL));
        assertTrue(added.contains(State.ABOVE));
        assertEquals(EnumSet.of(State.ABOVE), base); // input untouched

        EnumSet<State> removed = NetWmState.applyChange(base, ChangeAction.REMOVE, State.ABOVE);
        assertFalse(removed.contains(State.ABOVE));

        EnumSet<State> toggledOn = NetWmState.applyChange(base, ChangeAction.TOGGLE, State.MODAL);
        assertTrue(toggledOn.contains(State.MODAL));
        EnumSet<State> toggledOff = NetWmState.applyChange(toggledOn, ChangeAction.TOGGLE, State.MODAL);
        assertFalse(toggledOff.contains(State.MODAL));
    }

    @Test
    @DisplayName("applyChange is null-safe for the current set, action and state")
    void applyChangeNullSafe() {
        EnumSet<State> fromNull = NetWmState.applyChange(null, ChangeAction.ADD, State.STICKY);
        assertEquals(EnumSet.of(State.STICKY), fromNull);

        EnumSet<State> base = EnumSet.of(State.ABOVE);
        assertEquals(base, NetWmState.applyChange(base, null, State.MODAL));
        assertEquals(base, NetWmState.applyChange(base, ChangeAction.ADD, null));
    }

    // ---- defaultStateFor ----------------------------------------------

    @Test
    @DisplayName("defaultStateFor gives dialogs SKIP_TASKBAR + MODAL")
    void defaultStateDialog() {
        assertEquals(EnumSet.of(State.SKIP_TASKBAR, State.MODAL),
            NetWmState.defaultStateFor(WindowType.DIALOG));
    }

    @Test
    @DisplayName("defaultStateFor gives transient types SKIP_TASKBAR only")
    void defaultStateTransient() {
        EnumSet<State> expected = EnumSet.of(State.SKIP_TASKBAR);
        assertEquals(expected, NetWmState.defaultStateFor(WindowType.SPLASH));
        assertEquals(expected, NetWmState.defaultStateFor(WindowType.UTILITY));
        assertEquals(expected, NetWmState.defaultStateFor(WindowType.TOOLBAR));
        assertEquals(expected, NetWmState.defaultStateFor(WindowType.MENU));
    }

    @Test
    @DisplayName("defaultStateFor gives NORMAL/DESKTOP/DOCK (and null) an empty set")
    void defaultStateEmpty() {
        assertTrue(NetWmState.defaultStateFor(WindowType.NORMAL).isEmpty());
        assertTrue(NetWmState.defaultStateFor(WindowType.DESKTOP).isEmpty());
        assertTrue(NetWmState.defaultStateFor(WindowType.DOCK).isEmpty());
        assertTrue(NetWmState.defaultStateFor(null).isEmpty());
    }

    // ---- allowedActions -----------------------------------------------

    @Test
    @DisplayName("a normal, fully-capable window offers the full action set")
    void allowedActionsFull() {
        EnumSet<Action> actions = NetWmState.allowedActions(
            EnumSet.noneOf(State.class), true, true, true, true);
        assertEquals(EnumSet.of(Action.CLOSE, Action.MOVE, Action.RESIZE,
            Action.MAXIMIZE_VERT, Action.MAXIMIZE_HORZ, Action.MINIMIZE,
            Action.FULLSCREEN, Action.ABOVE, Action.BELOW), actions);
    }

    @Test
    @DisplayName("MOVE is always offered; CLOSE only when closable")
    void allowedActionsMoveAlwaysCloseConditional() {
        EnumSet<Action> notClosable = NetWmState.allowedActions(
            EnumSet.noneOf(State.class), false, false, false, false);
        assertTrue(notClosable.contains(Action.MOVE));
        assertFalse(notClosable.contains(Action.CLOSE));
        assertFalse(notClosable.contains(Action.RESIZE));
        assertFalse(notClosable.contains(Action.MAXIMIZE_VERT));
        assertFalse(notClosable.contains(Action.FULLSCREEN));

        EnumSet<Action> closable = NetWmState.allowedActions(
            EnumSet.noneOf(State.class), false, false, true, false);
        assertTrue(closable.contains(Action.CLOSE));
    }

    @Test
    @DisplayName("RESIZE is dropped when maximized or fullscreen")
    void allowedActionsResizeSuppressed() {
        assertFalse(NetWmState.allowedActions(EnumSet.of(State.MAXIMIZED_VERT),
            true, true, true, false).contains(Action.RESIZE));
        assertFalse(NetWmState.allowedActions(EnumSet.of(State.MAXIMIZED_HORZ),
            true, true, true, false).contains(Action.RESIZE));
        assertFalse(NetWmState.allowedActions(EnumSet.of(State.FULLSCREEN),
            true, true, true, true).contains(Action.RESIZE));
        // ...but is present when resizable and neither maximized nor fullscreen.
        assertTrue(NetWmState.allowedActions(EnumSet.noneOf(State.class),
            true, true, true, false).contains(Action.RESIZE));
    }

    @Test
    @DisplayName("MAXIMIZE is dropped when fullscreen; MINIMIZE dropped for SKIP_TASKBAR")
    void allowedActionsMaximizeAndMinimize() {
        assertFalse(NetWmState.allowedActions(EnumSet.of(State.FULLSCREEN),
            true, true, true, true).contains(Action.MAXIMIZE_VERT));
        assertFalse(NetWmState.allowedActions(EnumSet.of(State.SKIP_TASKBAR),
            true, true, true, false).contains(Action.MINIMIZE));
        assertTrue(NetWmState.allowedActions(EnumSet.noneOf(State.class),
            true, true, true, false).contains(Action.MINIMIZE));
    }

    @Test
    @DisplayName("ABOVE/BELOW are mutually exclusive with the current state")
    void allowedActionsAboveBelowExclusive() {
        EnumSet<Action> above = NetWmState.allowedActions(EnumSet.of(State.ABOVE),
            true, true, true, false);
        assertTrue(above.contains(Action.BELOW));
        assertFalse(above.contains(Action.ABOVE));

        EnumSet<Action> below = NetWmState.allowedActions(EnumSet.of(State.BELOW),
            true, true, true, false);
        assertTrue(below.contains(Action.ABOVE));
        assertFalse(below.contains(Action.BELOW));

        EnumSet<Action> neither = NetWmState.allowedActions(EnumSet.noneOf(State.class),
            true, true, true, false);
        assertTrue(neither.contains(Action.ABOVE));
        assertTrue(neither.contains(Action.BELOW));
    }

    @Test
    @DisplayName("allowedActions tolerates a null state set")
    void allowedActionsNullState() {
        EnumSet<Action> actions = NetWmState.allowedActions(null, true, true, true, false);
        assertTrue(actions.contains(Action.MOVE));
        assertTrue(actions.contains(Action.RESIZE));
    }

    // ---- decideActive -------------------------------------------------

    @Test
    @DisplayName("application-initiated activation is refused (focus-stealing prevention)")
    void decideActiveApplication() {
        assertEquals(ActiveDecision.DEMANDS_ATTENTION,
            NetWmState.decideActive(ActiveSource.APPLICATION));
    }

    @Test
    @DisplayName("pager/none activation is honoured")
    void decideActivePagerAndNone() {
        assertEquals(ActiveDecision.ACTIVATE, NetWmState.decideActive(ActiveSource.PAGER));
        assertEquals(ActiveDecision.ACTIVATE, NetWmState.decideActive(ActiveSource.NONE));
        assertEquals(ActiveDecision.ACTIVATE, NetWmState.decideActive(null));
    }

    // ---- readOnly -----------------------------------------------------

    @Test
    @DisplayName("readOnly returns an unmodifiable view; null becomes empty")
    void readOnlyView() {
        assertTrue(NetWmState.readOnly(null).isEmpty());
        EnumSet<State> base = EnumSet.of(State.ABOVE);
        java.util.Set<State> view = NetWmState.readOnly(base);
        assertEquals(1, view.size());
        assertThrows(UnsupportedOperationException.class, () -> view.add(State.MODAL));
    }
}
