/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.4: the GPU FXAA pass, the part a headless suite may honestly
 * assert.
 *
 * GpuFxaaPass drives a live GLContext, and a GLContext cannot be constructed in a
 * headless test JVM (the repo-wide rule for Jogamp objects), so the GL chain is verified
 * by the in-JVM probe under DISPLAY, NOT asserted here. The subpixel maths the shader
 * runs is proven separately by FxaaKernelTest against FxaaKernel. What IS pure and
 * pinned below is the contract a host depends on before a display is ever in the room:
 * the default settings are the FXAA quality preset, a pass constructs without touching
 * GL and starts healthy, an explicit settings instance is kept verbatim, and
 * render()/dispose() treat a null context as the no-op it is on a headless box rather
 * than latching a fault or throwing into Java 3D's renderer.
 */
package org.jdesktop.wonderland.renderer.post;

import org.jdesktop.wonderland.renderer.post.FxaaKernel.Settings;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GpuFxaaPassTest {

    @Test
    void bothConstructorsDefaultToTheQualityPreset() {
        assertThat(new GpuFxaaPass().settings()).isSameAs(Settings.DEFAULT);
        assertThat(new GpuFxaaPass(null).settings())
                .as("a null settings is the default, never a null the pass must guard")
                .isSameAs(Settings.DEFAULT);
    }

    @Test
    void anExplicitSettingsIsKeptVerbatim() {
        Settings custom = new Settings(0.2f, 0.05f, 0.5f);
        assertThat(new GpuFxaaPass(custom).settings()).isSameAs(custom);
    }

    @Test
    void constructingAPassTouchesNoGlAndStartsHealthy() {
        GpuFxaaPass pass = new GpuFxaaPass(Settings.DEFAULT);
        assertThat(pass.isFailed()).isFalse();
        assertThat(pass.failureReason()).isNull();
    }

    @Test
    void renderWithoutAContextIsANoOpNotAFault() {
        GpuFxaaPass pass = new GpuFxaaPass(Settings.DEFAULT);
        assertThatCode(() -> pass.render(null, 1280, 763)).doesNotThrowAnyException();
        assertThatCode(() -> pass.render(null, 0, 0)).doesNotThrowAnyException();
        assertThat(pass.isFailed())
                .as("declining to run for want of a context is not a GL fault")
                .isFalse();
        assertThat(pass.failureReason()).isNull();
    }

    @Test
    void disposeWithoutAContextIsAnIdempotentNoOp() {
        GpuFxaaPass pass = new GpuFxaaPass(Settings.DEFAULT);
        assertThatCode(() -> {
            pass.dispose(null);
            pass.dispose(null);
        }).doesNotThrowAnyException();
        assertThat(pass.isFailed()).isFalse();
    }
}
