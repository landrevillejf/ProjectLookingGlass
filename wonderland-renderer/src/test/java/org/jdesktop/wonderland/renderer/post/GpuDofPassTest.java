/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.5: the GPU depth-of-field pass, the part a headless suite may
 * honestly assert.
 *
 * GpuDofPass drives a live GLContext -- and, unlike the bloom/tone-map/anti-alias before
 * it, also blits the frame's DEPTH -- and a GLContext cannot be constructed in a headless
 * test JVM (the repo-wide rule for Jogamp objects), so the GL chain (render()'s
 * colour+depth blit / defocus / composite and dispose()'s deletes) is verified by the
 * in-JVM probe under DISPLAY, NOT asserted here. The gather the shader runs is proven
 * separately by DofKernelTest against DofKernel. What IS pure and pinned below is the
 * contract a host depends on before a display is ever in the room: the default settings
 * are DofKernel's neutral defocus, a pass constructs without touching GL and starts
 * healthy, an explicit settings instance is kept verbatim, and -- the reason this suite
 * can exist at all -- render()/dispose() treat a null context as the no-op it is on a
 * headless box rather than latching a fault or throwing into the caller. The never-throws
 * latch is the whole safety story of a cosmetic pass on someone else's render thread, and
 * its first guard is reachable without a driver.
 */
package org.jdesktop.wonderland.renderer.post;

import org.jdesktop.wonderland.renderer.post.DofKernel.Settings;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GpuDofPassTest {

    @Test
    void bothConstructorsDefaultToTheNeutralDefocus() {
        assertThat(new GpuDofPass().settings()).isSameAs(Settings.DEFAULT);
        assertThat(new GpuDofPass(null).settings())
                .as("a null settings is the default, never a null the pass must guard")
                .isSameAs(Settings.DEFAULT);
    }

    @Test
    void anExplicitSettingsIsKeptVerbatim() {
        Settings custom = new Settings(0.25f, 0.1f, 12f);
        assertThat(new GpuDofPass(custom).settings()).isSameAs(custom);
    }

    @Test
    void constructingAPassTouchesNoGlAndStartsHealthy() {
        // The whole point of a pure constructor: a host may build the pass long
        // before a canvas exists, and a headless suite may build one to inspect it.
        GpuDofPass pass = new GpuDofPass(Settings.DEFAULT);
        assertThat(pass.isFailed()).isFalse();
        assertThat(pass.failureReason()).isNull();
    }

    @Test
    void renderWithoutAContextIsANoOpNotAFault() {
        GpuDofPass pass = new GpuDofPass(Settings.DEFAULT);
        // A null context is the headless reality and a canvas not yet realised; the
        // guard must short-circuit before any GL call, leave the pass healthy and
        // never throw into Java 3D's renderer thread.
        assertThatCode(() -> pass.render(null, 1280, 763)).doesNotThrowAnyException();
        assertThatCode(() -> pass.render(null, 0, 0)).doesNotThrowAnyException();
        assertThat(pass.isFailed())
                .as("declining to run for want of a context is not a GL fault")
                .isFalse();
        assertThat(pass.failureReason()).isNull();
    }

    @Test
    void disposeWithoutAContextIsAnIdempotentNoOp() {
        GpuDofPass pass = new GpuDofPass(Settings.DEFAULT);
        // Teardown on a headless box (or before a first render) has nothing to free
        // and must not throw; a pass is disposed once when its canvas goes away, and
        // a second call -- or one before any render -- is tolerated.
        assertThatCode(() -> {
            pass.dispose(null);
            pass.dispose(null);
        }).doesNotThrowAnyException();
        assertThat(pass.isFailed()).isFalse();
    }
}
