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

import java.awt.event.KeyEvent;
import org.jdesktop.lg3d.displayserver.desktop2d.WorkspaceModel;

/**
 * The workspace keystrokes of the native 3D desktop, resolved from raw modifier
 * and key-code state so the mapping is pure and headless-testable.
 *
 * <p>The bindings deliberately mirror the 2D/Swing desktop's
 * {@code ShortcutMap.defaultBindings()} — <em>Alt+Shift+Page&nbsp;Down</em> /
 * <em>Page&nbsp;Up</em> to page between workspaces and <em>Alt+Shift+1..9</em> to
 * move the front window — so the same muscle memory works on either desktop. The
 * plan's original <em>Ctrl+Alt+arrow</em> is not used, for the reason already
 * recorded in {@code ShortcutMap}: in development mode lg3d is an ordinary window
 * under the host window manager, and GNOME/Mutter grabs Ctrl+Alt+arrow and
 * Super+digit for its <em>own</em> workspace switching, so those strokes never
 * reach the JVM. Direct switching to a workspace by number is offered by clicking
 * the HUD pager cell instead.</p>
 */
public final class WorkspaceKeys {

    /** What a keystroke asks the workspace plugin to do. */
    public enum Kind {
        /** Not a workspace keystroke; ignore it. */
        NONE,
        /** Step to the next workspace, wrapping. */
        NEXT,
        /** Step to the previous workspace, wrapping. */
        PREVIOUS,
        /** Move the front window to {@link KeyCommand#workspace()}. */
        MOVE
    }

    /**
     * A resolved keystroke. For {@link Kind#MOVE} the {@code workspace} is the
     * 0-based workspace index; for every other kind it is -1.
     */
    public record KeyCommand(Kind kind, int workspace) {
        /** The shared "not a workspace key" result. */
        public static final KeyCommand NONE = new KeyCommand(Kind.NONE, -1);
    }

    private WorkspaceKeys() {
        // Pure static helper; not instantiable.
    }

    /**
     * Resolves a key press to a workspace command. Only a press with exactly
     * Alt+Shift held (no Ctrl, no Meta) and a non-modifier key counts, so an
     * application's own Alt+Shift combos and plain typing are left alone.
     *
     * @param altDown   whether Alt is held
     * @param shiftDown whether Shift is held
     * @param ctrlDown  whether Ctrl or Meta is held
     * @param keyCode   the AWT key code of the press
     */
    public static KeyCommand resolve(boolean altDown, boolean shiftDown,
            boolean ctrlDown, int keyCode) {
        if (!altDown || !shiftDown || ctrlDown || isModifierKey(keyCode)) {
            return KeyCommand.NONE;
        }
        if (keyCode == KeyEvent.VK_PAGE_DOWN) {
            return new KeyCommand(Kind.NEXT, -1);
        }
        if (keyCode == KeyEvent.VK_PAGE_UP) {
            return new KeyCommand(Kind.PREVIOUS, -1);
        }
        int digit = digitWorkspace(keyCode);
        if (digit >= 0) {
            return new KeyCommand(Kind.MOVE, digit);
        }
        return KeyCommand.NONE;
    }

    /**
     * The 0-based workspace a digit key moves a window to, or -1 when the key is
     * not a digit or names a workspace beyond {@link WorkspaceModel#MAX_COUNT}.
     * The keys are 1-based (Alt+Shift+1 → workspace 0), matching the 2D map.
     */
    static int digitWorkspace(int keyCode) {
        int n = -1;
        if (keyCode >= KeyEvent.VK_1 && keyCode <= KeyEvent.VK_9) {
            n = keyCode - KeyEvent.VK_1;
        } else if (keyCode >= KeyEvent.VK_NUMPAD1 && keyCode <= KeyEvent.VK_NUMPAD9) {
            n = keyCode - KeyEvent.VK_NUMPAD1;
        }
        return (n >= 0 && n < WorkspaceModel.MAX_COUNT) ? n : -1;
    }

    /** True for the modifier keys themselves, which never trigger a command. */
    static boolean isModifierKey(int keyCode) {
        return keyCode == KeyEvent.VK_ALT
                || keyCode == KeyEvent.VK_ALT_GRAPH
                || keyCode == KeyEvent.VK_CONTROL
                || keyCode == KeyEvent.VK_META
                || keyCode == KeyEvent.VK_SHIFT;
    }
}
