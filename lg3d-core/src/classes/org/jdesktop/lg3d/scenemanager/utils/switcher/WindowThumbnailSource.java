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

import org.jdesktop.lg3d.sg.Texture;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * Supplies the miniature each carousel card shows for an open window.
 *
 * <p>The switcher cannot reuse {@code Frame3D.getThumbnail()}: that
 * {@code Thumbnail} is a single-parented {@code Component3D} already owned by the
 * taskbar, so reparenting it onto the HUD carousel would steal it from the bar.
 * Instead a source returns a shareable {@link Texture} &mdash; for a
 * Swing-to-node window, the very {@code Texture2D} the window's
 * {@code SwingNode} already paints into, so the card shows the live window
 * content. Windows with no such texture (pure-3D apps) yield {@code null} and the
 * carousel falls back to a titled glass card.</p>
 *
 * <p>Returning {@code null} is the honest "no miniature available" signal; the
 * interface is deliberately Java 3D-return-typed but implementation-light so it
 * can be faked in headless tests of the carousel logic.</p>
 */
public interface WindowThumbnailSource {

    /**
     * The texture to map onto the card for {@code frame}, or {@code null} when no
     * live miniature is available.
     */
    Texture textureFor(Frame3D frame);

    /**
     * Releases any observer this source registered for {@code frame} (e.g. a
     * {@code SwingNode} texture listener). Called when a card is torn down; a
     * no-op for sources that hold nothing per frame.
     */
    default void release(Frame3D frame) {
        // Nothing to release by default.
    }
}
