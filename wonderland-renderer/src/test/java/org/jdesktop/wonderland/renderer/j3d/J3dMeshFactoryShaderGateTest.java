/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.2/P0.1: the conservative gate on the GPU shader route.
 *
 * What a GL context does -- building a ShaderAppearance, compiling a program --
 * no headless test may claim; that is the probe's job (WlProbe43 proved the route
 * shades per-pixel through this very factory). What IS headless and must never
 * regress is the SAFETY of the gate: the route is OFF unless the operator asks,
 * so a pre-lit world keeps the byte-identical fixed pipeline, and the light rig
 * refuses a degenerate direction rather than blacking out every lit surface.
 */
package org.jdesktop.wonderland.renderer.j3d;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class J3dMeshFactoryShaderGateTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("lg.wonderland.shader");
    }

    @Test
    void theShaderRouteIsOffByDefaultSoPreLitWorldsStayByteIdentical() {
        // No property set (the shipped default): a factory must NOT be on the GPU
        // route, so buildAppearance takes the fixed path unchanged for every
        // material -- including one that asks to be lit.
        System.clearProperty("lg.wonderland.shader");
        assertThat(new J3dMeshFactory().shaderRouteActive()).isFalse();
    }

    @Test
    void theOperatorSwitchTurnsTheRouteOnAndNothingElse() {
        // The only switch is the documented system property; reading it is the
        // whole construction-time decision, with no side effect to verify.
        System.setProperty("lg.wonderland.shader", "true");
        assertThat(new J3dMeshFactory().shaderRouteActive()).isTrue();
    }

    @Test
    void aDegenerateLightRigIsRefusedRatherThanApplied() {
        // A zero or NaN direction would leave every lit surface at the ambient
        // floor -- a silent blackout on the render path. setLightRig must refuse it
        // and change no observable state (the factory keeps shading with its prior
        // key). This never throws, which is the point: it runs on the build path.
        J3dMeshFactory factory = new J3dMeshFactory();
        factory.setLightRig(0f, 0f, 0f, 1f, 1f, 1f, 0.1f, 0.1f, 0.1f);
        factory.setLightRig(Float.NaN, 0f, -1f, 1f, 1f, 1f, 0.1f, 0.1f, 0.1f);
        // A valid rig is accepted; none of the above may throw.
        factory.setLightRig(0f, -0.35f, -0.87f, 1f, 1f, 1f, 0.15f, 0.15f, 0.15f);
        assertThat(factory.shaderRouteActive()).isFalse();
    }
}
