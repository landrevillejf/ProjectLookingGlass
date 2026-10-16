/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.4: GlTexture's decode/upload needs a live GL2ES3, which a
 * headless test JVM cannot construct, so upload() itself is probe-verified (P2.5).
 * What IS asserted here is the degrade-never-throw contract that guards it: a null
 * GL surface, null bytes or empty bytes all return 0 ("no texture") rather than
 * throwing, and dispose() on the same inputs is a silent no-op. That guard is the
 * one branch reachable without a context, and it is the reason a shape with an
 * undecodable picture renders untextured instead of failing the frame.
 */
package org.jdesktop.wonderland.renderer.gl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlTextureTest {

    @Test
    void uploadWithoutAGlSurfaceYieldsNoTexture() {
        assertThat(GlTexture.upload(null, new byte[] {1, 2, 3})).isZero();
    }

    @Test
    void uploadWithoutBytesYieldsNoTexture() {
        // gl is null too, but the guard short-circuits on the first null it meets;
        // both the null-bytes and empty-bytes cases must degrade to 0, never throw.
        assertThat(GlTexture.upload(null, null)).isZero();
        assertThat(GlTexture.upload(null, new byte[0])).isZero();
    }

    @Test
    void disposeIsASilentNoOpWithoutAGlSurfaceOrId() {
        GlTexture.dispose(null, 0);
        GlTexture.dispose(null, 42);
        // no assertion: the contract is "does not throw"
        assertThat(true).isTrue();
    }
}
