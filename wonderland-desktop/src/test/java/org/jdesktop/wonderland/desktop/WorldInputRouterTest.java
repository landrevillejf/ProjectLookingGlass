/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c2: headless tests for the desktop-event -> intent
 * mapping.
 *
 * The listener's other half -- the picked node list -- belongs to the live
 * PickEngine and is probe-only, but the vocabulary it translates is pure, and
 * the invariant that matters most is assertable without a display: every event
 * class the listener registers for must be a class it can actually read, or the
 * world layer hears nothing and no test would notice.
 */
package org.jdesktop.wonderland.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jdesktop.lg3d.wg.event.MouseButtonEvent3D;
import org.jdesktop.lg3d.wg.event.MouseDraggedEvent3D;
import org.jdesktop.lg3d.wg.event.MouseEnteredEvent3D;
import org.jdesktop.lg3d.wg.event.MouseEvent3D;
import org.jdesktop.lg3d.wg.event.MouseMotionEvent3D;
import org.jdesktop.lg3d.wg.event.MouseMovedEvent3D;
import org.jdesktop.lg3d.wg.event.MouseWheelEvent3D;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link WorldInputRouter}: the gesture vocabulary, the
 * declared event classes and the constructor guards.
 */
class WorldInputRouterTest {

    @Test
    void aClickSelectsAndADragDrags() {
        assertThat(WorldInputRouter.intentFor(MouseButtonEvent3D.class))
                .isEqualTo(PickIntent.SELECT);
        assertThat(WorldInputRouter.intentFor(MouseDraggedEvent3D.class))
                .isEqualTo(PickIntent.DRAG);
    }

    @Test
    void motionAndEnterBothReadAsHover() {
        assertThat(WorldInputRouter.intentFor(MouseMovedEvent3D.class))
                .isEqualTo(PickIntent.HOVER);
        assertThat(WorldInputRouter.intentFor(MouseEnteredEvent3D.class))
                .isEqualTo(PickIntent.HOVER);
        // The abstract parent, in case the connector hands over the class the
        // listener registered rather than the concrete event's own.
        assertThat(WorldInputRouter.intentFor(MouseMotionEvent3D.class))
                .isEqualTo(PickIntent.HOVER);
    }

    @Test
    void aDragIsNotReadAsPlainMotion() {
        // MouseDraggedEvent3D is also a MouseMotionEvent3D, so the specific
        // class has to win -- or every drag would arrive as a hover.
        assertThat(WorldInputRouter.intentFor(MouseDraggedEvent3D.class))
                .isEqualTo(PickIntent.DRAG);
    }

    @Test
    void gesturesTheWorldDoesNotClaimStayWithTheDesktop() {
        // Zooming the universe is the scene manager's job: a cell that claimed
        // the wheel would steal it from every empty pixel behind it.
        assertThat(WorldInputRouter.intentFor(MouseWheelEvent3D.class)).isNull();
        assertThat(WorldInputRouter.intentFor(MouseEvent3D.class)).isNull();
        assertThat(WorldInputRouter.intentFor(String.class)).isNull();
        assertThat(WorldInputRouter.intentFor(null)).isNull();
    }

    @Test
    void everyDeclaredEventClassMapsToAnIntent() {
        Class<?>[] declared = WorldInputRouter.routedEventClasses();

        assertThat(declared).isNotEmpty();
        for (Class<?> eventClass : declared) {
            assertThat(WorldInputRouter.intentFor(eventClass))
                    .as("%s is registered but unmappable", eventClass.getName())
                    .isNotNull();
        }
        assertThat(declared).doesNotContain(MouseWheelEvent3D.class);
    }

    @Test
    void theDeclarationIsACopySoACallerCannotRewriteTheRouter() {
        Class<?>[] first = WorldInputRouter.routedEventClasses();
        first[0] = MouseWheelEvent3D.class;

        assertThat(WorldInputRouter.routedEventClasses()[0])
                .isEqualTo(MouseButtonEvent3D.class);
    }

    @Test
    void theContainerAndTheRouterAreBothRequired() {
        PickRouter router = new PickRouter(
                new CellFrameBridge(new NoOpMounter()));

        assertThatThrownBy(() -> new WorldInputRouter(null, router, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRouterIsRequiredAndTheSinkIsNot() {
        // sink == null is the probe's "prove the path, consume nothing" install,
        // so it must not be rejected the way a missing router is.
        assertThatThrownBy(() -> new WorldInputRouter(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A router needs a bridge, and a bridge needs a mounter that does nothing. */
    private static final class NoOpMounter implements FrameMounter {
        @Override
        public void mount(String cellId,
                org.jdesktop.wonderland.client.cell.CellRenderer renderer) {
        }

        @Override
        public void unmount(String cellId) {
        }

        @Override
        public void applyTransform(String cellId, double[] rowMajorFeed) {
        }

        @Override
        public void setVisible(String cellId, boolean visible) {
        }
    }
}
