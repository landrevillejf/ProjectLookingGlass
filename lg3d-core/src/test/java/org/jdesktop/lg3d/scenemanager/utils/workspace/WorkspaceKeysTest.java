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
package org.jdesktop.lg3d.scenemanager.utils.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import org.jdesktop.lg3d.displayserver.desktop2d.WorkspaceModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link WorkspaceKeys}: the keystroke mapping the 3D
 * desktop shares with the 2D desktop's {@code ShortcutMap} (Alt+Shift+Page
 * Down/Page Up to page, Alt+Shift+1..9 to move a window), and the guard rails
 * that keep an application's own keystrokes and plain typing out of it. Pure
 * static resolution, so no AWT event dispatch and no Java 3D are involved.
 */
class WorkspaceKeysTest {

    private static WorkspaceKeys.KeyCommand resolve(int keyCode) {
        return WorkspaceKeys.resolve(true, true, false, keyCode);
    }

    @Test
    @DisplayName("Alt+Shift+Page Down pages forward, Page Up pages back")
    void pageKeysPage() {
        assertEquals(WorkspaceKeys.Kind.NEXT, resolve(KeyEvent.VK_PAGE_DOWN).kind());
        assertEquals(WorkspaceKeys.Kind.PREVIOUS, resolve(KeyEvent.VK_PAGE_UP).kind());
        // A paging command carries no workspace index.
        assertEquals(-1, resolve(KeyEvent.VK_PAGE_DOWN).workspace());
        assertEquals(-1, resolve(KeyEvent.VK_PAGE_UP).workspace());
    }

    @Test
    @DisplayName("Alt+Shift+1..9 moves to workspace 0..8, one-based on the key")
    void digitKeysAreOneBased() {
        for (int i = 0; i < WorkspaceModel.MAX_COUNT; i++) {
            WorkspaceKeys.KeyCommand command = resolve(KeyEvent.VK_1 + i);
            assertEquals(WorkspaceKeys.Kind.MOVE, command.kind(), "key " + (i + 1));
            assertEquals(i, command.workspace(), "key " + (i + 1));
        }
    }

    @Test
    @DisplayName("the numeric keypad digits work like the number row")
    void numpadDigitsMatchTheNumberRow() {
        for (int i = 0; i < WorkspaceModel.MAX_COUNT; i++) {
            assertEquals(WorkspaceKeys.resolve(true, true, false, KeyEvent.VK_1 + i),
                    WorkspaceKeys.resolve(true, true, false, KeyEvent.VK_NUMPAD1 + i),
                    "keypad " + (i + 1));
        }
    }

    @Test
    @DisplayName("the arrow keys the host window manager grabs are not bound")
    void hostOwnedKeysAreLeftAlone() {
        assertEquals(WorkspaceKeys.Kind.NONE, resolve(KeyEvent.VK_LEFT).kind());
        assertEquals(WorkspaceKeys.Kind.NONE, resolve(KeyEvent.VK_RIGHT).kind());
        assertEquals(WorkspaceKeys.Kind.NONE, resolve(KeyEvent.VK_UP).kind());
        assertEquals(WorkspaceKeys.Kind.NONE, resolve(KeyEvent.VK_DOWN).kind());
        assertEquals(WorkspaceKeys.Kind.NONE, resolve(KeyEvent.VK_TAB).kind());
    }

    @Test
    @DisplayName("Alt or Shift alone is not enough: both must be held")
    void bothModifiersAreRequired() {
        assertEquals(WorkspaceKeys.Kind.NONE,
                WorkspaceKeys.resolve(false, true, false, KeyEvent.VK_PAGE_DOWN).kind(),
                "Shift without Alt");
        assertEquals(WorkspaceKeys.Kind.NONE,
                WorkspaceKeys.resolve(true, false, false, KeyEvent.VK_PAGE_DOWN).kind(),
                "Alt without Shift");
        assertEquals(WorkspaceKeys.Kind.NONE,
                WorkspaceKeys.resolve(false, false, false, KeyEvent.VK_1).kind(),
                "a bare digit is ordinary typing");
    }

    @Test
    @DisplayName("Ctrl or Meta alongside Alt+Shift belongs to the application")
    void extraModifiersCancelTheBinding() {
        assertEquals(WorkspaceKeys.Kind.NONE,
                WorkspaceKeys.resolve(true, true, true, KeyEvent.VK_PAGE_DOWN).kind());
        assertEquals(WorkspaceKeys.Kind.NONE,
                WorkspaceKeys.resolve(true, true, true, KeyEvent.VK_1).kind());
    }

    @Test
    @DisplayName("pressing the modifier keys themselves never fires a command")
    void modifierKeysAreNotCommands() {
        for (int keyCode : new int[] { KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH,
                KeyEvent.VK_CONTROL, KeyEvent.VK_META, KeyEvent.VK_SHIFT }) {
            assertSame(WorkspaceKeys.KeyCommand.NONE, resolve(keyCode),
                    "modifier " + keyCode);
            assertTrue(WorkspaceKeys.isModifierKey(keyCode));
        }
        // The modifiers held down for the combo are not commands either.
        assertFalse(WorkspaceKeys.isModifierKey(KeyEvent.VK_PAGE_DOWN));
        assertFalse(WorkspaceKeys.isModifierKey(KeyEvent.VK_1));
    }

    @Test
    @DisplayName("zero and the keys outside 1..9 name no workspace")
    void digitWorkspaceRejectsOutOfRangeKeys() {
        assertEquals(-1, WorkspaceKeys.digitWorkspace(KeyEvent.VK_0));
        assertEquals(-1, WorkspaceKeys.digitWorkspace(KeyEvent.VK_NUMPAD0));
        assertEquals(-1, WorkspaceKeys.digitWorkspace(KeyEvent.VK_A));
        assertEquals(-1, WorkspaceKeys.digitWorkspace(KeyEvent.VK_PAGE_DOWN));
        assertEquals(0, WorkspaceKeys.digitWorkspace(KeyEvent.VK_1));
        assertEquals(WorkspaceModel.MAX_COUNT - 1,
                WorkspaceKeys.digitWorkspace(KeyEvent.VK_1 + WorkspaceModel.MAX_COUNT - 1));
        assertEquals(WorkspaceKeys.Kind.NONE, resolve(KeyEvent.VK_0).kind());
    }

    @Test
    @DisplayName("a non-command result is the shared NONE instance, not a new one")
    void noneIsShared() {
        assertSame(WorkspaceKeys.KeyCommand.NONE, resolve(KeyEvent.VK_A));
        assertSame(WorkspaceKeys.KeyCommand.NONE,
                WorkspaceKeys.resolve(false, false, false, KeyEvent.VK_PAGE_DOWN));
        assertEquals(WorkspaceKeys.Kind.NONE, WorkspaceKeys.KeyCommand.NONE.kind());
        assertEquals(-1, WorkspaceKeys.KeyCommand.NONE.workspace());
    }
}
