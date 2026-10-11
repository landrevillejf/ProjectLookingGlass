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
package org.jdesktop.lg3d.displayserver.desktop2d;

/**
 * Marks a component that lives on the 2D desktop's desktop pane and fades with
 * one of the user's {@link TransparencySurface} settings.
 *
 * <p>The desktop pane asks each child it paints whether it opts in, so a surface
 * that must fade declares it here and needs no painting code of its own -
 * {@link DesktopTransparency} renders it into a scratch image and blits it at the
 * configured alpha. That keeps the fade in the shell, where the wallpaper behind
 * the component is known, rather than in the component.</p>
 *
 * <p>Two opt-ins exist today: {@link Desktop2DWindow} (the
 * {@link TransparencySurface#WINDOW window} surface) and the widget cards in
 * {@code lg3d-widgets} (the {@link TransparencySurface#WIDGET widget} surface).
 * Because the fading happens in the 2D desktop pane, a component that implements
 * this interface is still painted solid anywhere else - notably through a
 * {@code SwingNode} on the 3D desktop, where an opaque background is what makes
 * the texture capture reliable.</p>
 */
public interface TranslucentSurface {

    /** Which user setting governs this component's opacity. Never null. */
    TransparencySurface transparencySurface();
}
