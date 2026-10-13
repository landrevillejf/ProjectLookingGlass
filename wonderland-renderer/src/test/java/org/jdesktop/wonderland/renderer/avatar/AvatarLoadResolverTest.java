/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: the pool resolve decision.
 *
 * This test drives the real ClasspathWorldStore against the real bundled
 * .dae bytes -- no stubbing -- because the resolver's job is exactly to prove
 * the pool is well-formed. Any regression in the pool content, in the
 * wla:// URL shape, or in the "null URL uses the pool default" rule fires
 * here, headless. The renderer probe is only the scene-graph assembly on top
 * of a load result already validated by these cases.
 */
package org.jdesktop.wonderland.renderer.avatar;

import org.jdesktop.wonderland.avatars.AvatarPool;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AvatarLoadResolverTest {

    @Test
    void aNullUrlSelectsThePoolDefaultUrl() {
        // The plan's rule: null means "client picks the default". The
        // selection is decoupled from the read so it can be pinned without
        // touching the classpath.
        assertThat(AvatarLoadResolver.selectUrl(null))
                .isEqualTo(AvatarPool.urlFor(AvatarPool.defaultName()));
    }

    @Test
    void anEmptyUrlBehavesLikeANullUrl() {
        assertThat(AvatarLoadResolver.selectUrl(""))
                .isEqualTo(AvatarPool.urlFor(AvatarPool.defaultName()));
    }

    @Test
    void anExplicitUrlWinsOverThePoolDefault() {
        String explicit = "wla://wonderland-avatars/humanoid-b.dae";

        assertThat(AvatarLoadResolver.selectUrl(explicit)).isEqualTo(explicit);
    }

    @Test
    void resolvingNullLoadsTheDefaultBundledBody() {
        AvatarLoadResult res = new AvatarLoadResolver().resolve(null);

        assertThat(res.isLoaded()).isTrue();
        assertThat(res.errorMessage()).isNull();
        assertThat(res.document()).isNotNull();
        assertThat(res.resolvedUrl()).isEqualTo(AvatarPool.urlFor(AvatarPool.defaultName()));
    }

    @Test
    void resolvingEveryPoolNameLoadsARealDocument() {
        // The pool's contract: every name in names() has a matching .dae
        // resource. Any missing file, wrong module directory or malformed
        // COLLADA in the shipped pool fires here, headless, no probe needed.
        AvatarLoadResolver resolver = new AvatarLoadResolver();
        for (String name : AvatarPool.names()) {
            AvatarLoadResult res = resolver.resolve(AvatarPool.urlFor(name));
            assertThat(res.isLoaded())
                    .as("pool body %s resolves through ClasspathWorldStore", name)
                    .isTrue();
            assertThat(res.document())
                    .as("pool body %s parses to a ColladaDocument", name)
                    .isNotNull();
        }
    }

    @Test
    void resolvingAnUnknownPoolNameDegradesToAnErrorResult() {
        // The wire extension lets a rogue server hint any URL; the resolver
        // must not throw -- the plan says "warn and continue", matching M5's
        // ModelLoader. An errored result then makes the factory hand back
        // null, so the cell renders nothing rather than crashing.
        AvatarLoadResult res = new AvatarLoadResolver()
                .resolve("wla://wonderland-avatars/does-not-exist.dae");

        assertThat(res.isLoaded()).isFalse();
        assertThat(res.document()).isNull();
        assertThat(res.errorMessage()).contains("no such classpath resource");
        assertThat(res.resolvedUrl()).isEqualTo("wla://wonderland-avatars/does-not-exist.dae");
    }

    @Test
    void resolvingANonWlaUrlDegradesToAnErrorResult() {
        // A malformed wire hint -- an http:// URL, a file:// path, a stray
        // string -- is refused at the parse step before any classpath read.
        AvatarLoadResult res = new AvatarLoadResolver().resolve("http://example.com/x.dae");

        assertThat(res.isLoaded()).isFalse();
        assertThat(res.errorMessage()).contains("not a wla:// reference");
        assertThat(res.resolvedUrl()).isEqualTo("http://example.com/x.dae");
    }

    @Test
    void resolvingAMissingModuleDegradesToAnErrorResult() {
        // wla:// requires a module AND a path; a bare "wla://module" with no
        // path is rejected by WlaRef.parse and never reaches the store.
        AvatarLoadResult res = new AvatarLoadResolver().resolve("wla://wonderland-avatars/");

        assertThat(res.isLoaded()).isFalse();
        assertThat(res.errorMessage()).contains("not a wla:// reference");
    }

    @Test
    void aPathTryingToEscapeTheModuleDirectoryDegradesToAnErrorResult() {
        // The confinement rule: ClasspathWorldStore refuses a "../" segment
        // so a wire hint cannot read outside the pool jar. The resolver
        // surfaces that as a "no such resource" error, not a crash.
        AvatarLoadResult res = new AvatarLoadResolver()
                .resolve("wla://wonderland-avatars/../secrets/password.dae");

        assertThat(res.isLoaded()).isFalse();
        assertThat(res.errorMessage()).isNotBlank();
    }

    @Test
    void resolvingADocumentFromThePoolExposesItsGeometryThroughTheColladaModel() {
        // Prove the returned ColladaDocument is usable (has the geometry the
        // AvatarRenderer will consume), not just "a parsed XML object". A
        // regression in the pool's geometry id or the visual scene's
        // instance_geometry url fails here headlessly.
        AvatarLoadResult res = new AvatarLoadResolver()
                .resolve(AvatarPool.urlFor(AvatarPool.HUMANOID_A));

        assertThat(res.isLoaded()).isTrue();
        assertThat(res.document().worldMatrices()).isNotEmpty();
        assertThat(res.document().geometry("humanoid-a-geom")).isNotNull();
    }
}
