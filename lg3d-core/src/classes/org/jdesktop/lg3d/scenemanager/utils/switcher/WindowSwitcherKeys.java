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
package org.jdesktop.lg3d.scenemanager.utils.switcher;

import java.awt.event.KeyEvent;

/**
 * The keystroke vocabulary of the native 3D desktop's window switcher, reduced
 * to a pure function so it is headless-testable.
 *
 * <p>The trigger is <em>Ctrl+Alt+Tab</em> to step forward and
 * <em>Ctrl+Alt+Shift+Tab</em> to step backward. Plain Alt+Tab is deliberately not
 * used: in development mode the desktop is an ordinary window under the host
 * window manager, which grabs Alt+Tab (and Super+Tab) before it ever reaches this
 * JVM &mdash; the same reason the 2D desktop's switcher settles for Alt+grave.
 * The Ctrl+Alt prefix is the plan's binding for the 3D switcher and is not taken
 * by the common host window managers.</p>
 *
 * <p>Committing the selection is <em>not</em> a keystroke: detecting the release
 * of the Ctrl/Alt modifiers is unreliable across platforms, so (exactly like the
 * 2D overlay) the selection commits itself on a short idle timer once the user
 * stops pressing the trigger. No binding is placed on Escape or Enter, which
 * would hijack dialogs and text fields.</p>
 */
public final class WindowSwitcherKeys {

    /** What a switcher keystroke means. */
    public enum Kind {
        /** Not a switcher keystroke; leave it to the application. */
        NONE,
        /** Step the highlight to the next (less recently used) window. */
        NEXT,
        /** Step the highlight to the previous (more recently used) window. */
        PREVIOUS
    }

    private WindowSwitcherKeys() {
        // Pure static resolution; not instantiable.
    }

    /**
     * Resolves one key press to a switcher command. Only Ctrl+Alt+Tab (forward)
     * and Ctrl+Alt+Shift+Tab (backward) are switcher keys; everything else is
     * {@link Kind#NONE}.
     *
     * @param control whether Ctrl (or Meta) is held
     * @param alt     whether Alt is held
     * @param shift   whether Shift is held
     * @param keyCode the AWT key code of the press
     */
    public static Kind resolve(boolean control, boolean alt, boolean shift,
            int keyCode) {
        if (keyCode != KeyEvent.VK_TAB || !control || !alt) {
            return Kind.NONE;
        }
        return shift ? Kind.PREVIOUS : Kind.NEXT;
    }
}
