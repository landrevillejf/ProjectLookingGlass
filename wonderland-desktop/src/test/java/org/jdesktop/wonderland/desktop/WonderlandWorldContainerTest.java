/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c: headless tests for WonderlandWorldContainer's pure
 * scene-extraction branch.
 *
 * The container extends lg3d's Component3D, so it CANNOT be instantiated in a
 * headless JVM -- its constructor reaches through AppConnectorPrivate. But the
 * scene-extraction step is a static method that only inspects the renderer's
 * runtime type. The negative branch (a plain CellRenderer that carries no
 * Jogamp scene) is fully assertable without constructing anything; the positive
 * branches (ModelRenderer / AvatarRenderer) need live Jogamp renderers and are
 * left to the in-JVM desktop probe.
 */
package org.jdesktop.wonderland.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import org.jogamp.java3d.BranchGroup;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.junit.jupiter.api.Test;

/**
 * Headless assertions on {@link WonderlandWorldContainer}'s static, type-only
 * scene extraction.
 */
class WonderlandWorldContainerTest {

    /** A renderer that is neither a ModelRenderer nor an AvatarRenderer. */
    private static final CellRenderer PLAIN_RENDERER = new CellRenderer() {
        @Override
        public void cellTransformUpdate(CellTransform transform) {
        }

        @Override
        public void setStatus(CellStatus status, boolean increasing) {
        }

        @Override
        public CellStatus getStatus() {
            return null;
        }
    };

    @Test
    void extractSceneReturnsNullForPlainRenderer() {
        BranchGroup scene = WonderlandWorldContainer.extractScene(PLAIN_RENDERER);
        assertThat(scene).isNull();
    }

    @Test
    void extractSceneReturnsNullForNullRenderer() {
        assertThat(WonderlandWorldContainer.extractScene(null)).isNull();
    }

    @Test
    void containerImplementsFrameMounterContract() {
        // The container's constructor needs a live AppConnector, so we assert
        // the type relationship (that CellFrameBridge can drive it) statically.
        assertThat(FrameMounter.class)
                .isAssignableFrom(WonderlandWorldContainer.class);
    }
}
