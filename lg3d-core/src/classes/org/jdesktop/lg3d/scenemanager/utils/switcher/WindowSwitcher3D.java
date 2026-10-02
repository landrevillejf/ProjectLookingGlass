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

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The window switcher as a scene-graph node: the {@code CDViewer}-style
 * {@link WindowCarousel3D} that {@link WindowSwitcherPlugin} mounts on the
 * front-most {@code DesktopHudLayer}. This wrapper exists so the plugin keeps a
 * single node to place, show, hide and dispose, while the carousel itself owns
 * the per-window cards and the pointer gestures.
 *
 * <p>Unlike the transient toast cards, the carousel <em>is</em> pickable while
 * shown: the user can spin it with the mouse wheel and click a window's card to
 * commit it, so it forwards those gestures to the plugin via the listener
 * setters below.</p>
 */
public class WindowSwitcher3D extends Component3D {

    private final WindowCarousel3D carousel;

    public WindowSwitcher3D() {
        setName("WindowSwitcher3D");
        this.carousel = new WindowCarousel3D();
        addChild(carousel);
        setVisible(false);
    }

    /** The hosted carousel, exposed for the plugin's gesture wiring. */
    public WindowCarousel3D carousel() {
        return carousel;
    }

    /** Shows the carousel over {@code frames} with {@code selected} at the front. */
    public void show(List<Frame3D> frames, int selected) {
        carousel.show(frames, selected);
        setVisible(true);
    }

    /** Hides the carousel. */
    public void hide() {
        carousel.hide();
        setVisible(false);
    }

    /** Sets the miniature source the carousel cards use. */
    public void setThumbnailSource(WindowThumbnailSource source) {
        carousel.setThumbnailSource(source);
    }

    /** Wheel gesture: signed click count to spin the carousel. */
    public void setRevolveListener(IntConsumer listener) {
        carousel.setRevolveListener(listener);
    }

    /** Click gesture: the window whose card was clicked. */
    public void setSelectListener(Consumer<Frame3D> listener) {
        carousel.setSelectListener(listener);
    }

    /** Pointer enter/exit: whether the carousel is being pointed at. */
    public void setFocusListener(Consumer<Boolean> listener) {
        carousel.setFocusListener(listener);
    }

    /** Frees the carousel's cards and thumbnail listeners. */
    public void dispose() {
        carousel.dispose();
    }
}
