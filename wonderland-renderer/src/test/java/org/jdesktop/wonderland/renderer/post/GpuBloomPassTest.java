/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.2: the GPU bloom pass, the part a headless suite may
 * honestly assert.
 *
 * GpuBloomPass drives a live GLContext, and a GLContext cannot be constructed in a
 * headless test JVM (the repo-wide rule for Jogamp objects), so the GL chain --
 * render()'s blit/bright-pass/blur/composite and dispose()'s deletes -- is verified
 * by the in-JVM probe (WlProbe55 measured 71 fps and read the visible halo off the
 * swapped buffer), NOT asserted here. What IS pure and pinned below is the contract
 * a host depends on before a display is ever in the room: the Settings defaults are
 * the exact constants the probe proved, negative magnitudes are refused, a pass
 * constructs without touching GL and starts healthy, and -- the reason this suite
 * can exist at all -- render()/dispose() treat a null context as the no-op it is on
 * a headless box rather than latching a fault or throwing into the caller. The
 * never-throws latch is the whole safety story of a cosmetic pass on someone else's
 * render thread, and its first guard is reachable without a driver.
 */
package org.jdesktop.wonderland.renderer.post;

import org.jdesktop.wonderland.renderer.post.GpuBloomPass.Settings;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GpuBloomPassTest {

    @Test
    void theDefaultSettingsAreTheExactConstantsTheProbeProved() {
        Settings d = Settings.DEFAULT;
        assertThat(d.threshold()).isEqualTo(0.5f);
        assertThat(d.knee()).isEqualTo(0.25f);
        assertThat(d.strength()).isEqualTo(0.7f);
        assertThat(d.iterations()).isEqualTo(2);
    }

    @Test
    void settingsRefuseEveryNegativeMagnitude() {
        // Each operand of the guard is a branch a caller could trip; each is proved
        // to throw, so no negative sneaks through one unchecked field.
        assertThatThrownBy(() -> new Settings(-1f, 0f, 0f, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0f, -1f, 0f, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0f, 0f, -1f, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0f, 0f, 0f, -1))
                .isInstanceOf(IllegalArgumentException.class);
        // And the all-zero corner -- a bloom that adds nothing -- is legal, because
        // strength 0 is the exact identity, not an error.
        assertThat(new Settings(0f, 0f, 0f, 0)).isNotNull();
    }

    @Test
    void bothConstructorsDefaultToTheProvedSettingsInstance() {
        assertThat(new GpuBloomPass().settings()).isSameAs(Settings.DEFAULT);
        assertThat(new GpuBloomPass(null).settings())
                .as("a null settings is the default, never a null the pass must guard")
                .isSameAs(Settings.DEFAULT);
    }

    @Test
    void anExplicitSettingsIsKeptVerbatim() {
        Settings custom = new Settings(0.4f, 0.2f, 0.5f, 1);
        assertThat(new GpuBloomPass(custom).settings()).isSameAs(custom);
    }

    @Test
    void constructingAPassTouchesNoGlAndStartsHealthy() {
        // The whole point of a pure constructor: a host may build the pass long
        // before a canvas exists, and a headless suite may build one to inspect it.
        GpuBloomPass pass = new GpuBloomPass(Settings.DEFAULT);
        assertThat(pass.isFailed()).isFalse();
        assertThat(pass.failureReason()).isNull();
    }

    @Test
    void renderWithoutAContextIsANoOpNotAFault() {
        GpuBloomPass pass = new GpuBloomPass(Settings.DEFAULT);
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
        GpuBloomPass pass = new GpuBloomPass(Settings.DEFAULT);
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
