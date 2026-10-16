/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
package org.jdesktop.wonderland.renderer.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jdesktop.wonderland.renderer.post.GpuBloomPass;
import org.jdesktop.wonderland.renderer.post.GpuDofPass;
import org.jdesktop.wonderland.renderer.post.GpuFxaaPass;
import org.jdesktop.wonderland.renderer.post.GpuHdrPass;
import org.jdesktop.wonderland.renderer.post.GpuPostEffect;
import org.jdesktop.wonderland.renderer.post.GpuSsaoPass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The headless contract of {@link GlView}: the post-process chain assembly
 * (order + gating), the constructor guards and the accessors. The GL callbacks
 * (init/display/reshape/dispose/capturePreview) are probe-only -- a GLContext
 * cannot be constructed headless -- and P2.5's probe verifies them; nothing here
 * touches GL, so building passes (which is documented GL-free) is safe.
 */
class GlViewTest {

    /** A camera 5 units down +z looking at the origin -- Camera's public pose+lens form. */
    private static Camera newCamera() {
        return new Camera(0f, 0f, 5f, 0f, 0f, 0f, 0f, 1f, 0f,
                (float) Math.toRadians(60), 1.6f, 0.1f, 100f);
    }

    @Test
    @DisplayName("buildChain(all on) returns SSAO, bloom, HDR, DOF, FXAA in composition order")
    void buildChainAllOnIsOrdered() {
        List<GpuPostEffect> chain = GlView.buildChain(true, true, true, true, true);
        assertEquals(5, chain.size());
        assertTrue(chain.get(0) instanceof GpuSsaoPass);
        assertTrue(chain.get(1) instanceof GpuBloomPass);
        assertTrue(chain.get(2) instanceof GpuHdrPass);
        assertTrue(chain.get(3) instanceof GpuDofPass);
        assertTrue(chain.get(4) instanceof GpuFxaaPass);
    }

    @Test
    @DisplayName("buildChain gates each link on its own flag, preserving order")
    void buildChainIsSelective() {
        List<GpuPostEffect> chain = GlView.buildChain(false, true, false, false, true);
        assertEquals(2, chain.size());
        assertTrue(chain.get(0) instanceof GpuBloomPass);
        assertTrue(chain.get(1) instanceof GpuFxaaPass);
    }

    @Test
    @DisplayName("buildChain(all off) is empty, never null")
    void buildChainAllOffIsEmpty() {
        List<GpuPostEffect> chain = GlView.buildChain(false, false, false, false, false);
        assertNotNull(chain);
        assertTrue(chain.isEmpty());
    }

    @Test
    @DisplayName("defaultChain runs all five: every WorldFeatures switch is on by default")
    void defaultChainIsFullByDefault() {
        // The test JVM sets none of the lg.wonderland.* switches, and WorldFeatures
        // defaults ON, so the live desktop's chain is the full five.
        assertEquals(5, GlView.defaultChain().size());
    }

    @Test
    @DisplayName("constructor rejects a null scene or camera")
    void constructorGuards() {
        assertThrows(IllegalArgumentException.class, () -> new GlView(null, newCamera()));
        assertThrows(IllegalArgumentException.class, () -> new GlView(new GlScene(), null));
    }

    @Test
    @DisplayName("accessors expose the scene, camera and an unmodifiable chain")
    void accessors() {
        GlScene scene = new GlScene();
        Camera cam = newCamera();
        List<GpuPostEffect> chain = List.of(new GpuBloomPass());
        GlView view = new GlView(scene, cam, chain);
        assertSame(scene, view.scene());
        assertSame(cam, view.camera());
        assertEquals(1, view.passes().size());
        assertThrows(UnsupportedOperationException.class,
                () -> view.passes().add(new GpuFxaaPass()));
    }

    @Test
    @DisplayName("a null chain becomes empty; size is zero before the first reshape")
    void emptyChainAndInitialSize() {
        GlView bare = new GlView(new GlScene(), newCamera(), null);
        assertTrue(bare.passes().isEmpty());
        assertEquals(0, bare.width());
        assertEquals(0, bare.height());
        // The WorldFeatures-driven constructor still yields a live, non-null chain.
        assertNotNull(new GlView(new GlScene(), newCamera()).passes());
    }

    @Test
    @DisplayName("capturePreview rejects a null GL by short-circuit, before any glReadPixels")
    void capturePreviewGuardsNullGl() {
        // The null-GL guard is pure and returns without issuing GL, so it is the
        // headless-reachable half of capturePreview; the readback itself is probe-only.
        GlView view = new GlView(new GlScene(), newCamera(), null);
        assertNull(view.capturePreview(null, 64, 64));
    }
}
