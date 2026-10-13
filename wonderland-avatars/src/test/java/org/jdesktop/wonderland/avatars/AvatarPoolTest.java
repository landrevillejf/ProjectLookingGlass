/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-avatars M6b: the pool catalog invariants.
 *
 * AvatarPool is the code contract that binds a name to a .dae resource; the
 * renderer's AvatarLoadResolver relies on names() being complete, on
 * defaultName() always being present and on every urlFor() producing a
 * reference that resolves to bytes this jar actually ships. This test proves
 * those invariants directly against the class loader -- no wonderland-modules
 * dependency needed, because resolving a `wla://<module>/<path>` reference
 * through the classpath store reduces to the same `getResource(...)` call.
 */
package org.jdesktop.wonderland.avatars;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AvatarPoolTest {

    @Test
    void namesAreUniqueAndInDeclarationOrder() {
        assertThat(AvatarPool.names())
                .containsExactly(
                        AvatarPool.HUMANOID_A,
                        AvatarPool.HUMANOID_B,
                        AvatarPool.HUMANOID_C);
        assertThat(AvatarPool.names()).doesNotHaveDuplicates();
    }

    @Test
    void defaultNameIsAlwaysPartOfTheCatalog() {
        // The client picks defaultName() whenever a wire state is silent about
        // the URL; a default that is not in the catalog would resolve to a
        // null InputStream and turn every un-decorated avatar into an error.
        assertThat(AvatarPool.names()).contains(AvatarPool.defaultName());
    }

    @Test
    void urlForProducesTheStableWlaPrefixAndSuffix() {
        assertThat(AvatarPool.urlFor(AvatarPool.HUMANOID_A))
                .isEqualTo("wla://wonderland-avatars/humanoid-a.dae");
        assertThat(AvatarPool.urlFor("any-name"))
                .isEqualTo("wla://wonderland-avatars/any-name.dae");
    }

    @Test
    void urlForFallsBackToTheDefaultForANullOrEmptyName() {
        // The pool never hands back a null URL, so callers can pass a wire
        // value through directly without a null guard.
        assertThat(AvatarPool.urlFor(null)).isEqualTo(AvatarPool.urlFor(AvatarPool.defaultName()));
        assertThat(AvatarPool.urlFor("")).isEqualTo(AvatarPool.urlFor(AvatarPool.defaultName()));
    }

    @Test
    void everyNameInTheCatalogResolvesToARealDaeResource() {
        for (String name : AvatarPool.names()) {
            String url = AvatarPool.urlFor(name);
            String resource = toResourcePath(url);
            assertThat(resource).isEqualTo("wonderland-avatars/" + name + ".dae");
            // The pool's code contract: if a name is in the catalog, its
            // bytes exist. This catches a rename that missed a .dae, and
            // catches a .dae moved out of the resource folder.
            try (InputStream in = AvatarPool.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                assertThat(in)
                        .as("avatar body for %s missing on the classpath at %s", name, resource)
                        .isNotNull();
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(body)
                        .as("avatar body for %s is not a plausible COLLADA file", name)
                        .contains("<COLLADA")
                        .contains("version=\"1.4.1\"")
                        .contains("<triangles")
                        .contains("<instance_geometry");
            } catch (Exception ex) {
                throw new AssertionError("reading " + resource + " failed", ex);
            }
        }
    }

    @Test
    void theCatalogIsImmutableSoACallerCannotPoisonItForOthers() {
        // AvatarPool.names() returns an unmodifiable view; a caller that
        // tried to mutate it would corrupt every subsequent resolver call.
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> AvatarPool.names().add("not-a-real-avatar"));
    }

    /**
     * Reduce a {@code wla://<module>/<path>} reference to the resource path a
     * ClassLoader would take -- the exact same transformation
     * wonderland-modules' ClasspathWorldStore applies at runtime, inlined so
     * the pool's unit test has no cross-module dependency.
     */
    private static String toResourcePath(String wlaUrl) {
        assertThat(wlaUrl).startsWith("wla://");
        String rest = wlaUrl.substring("wla://".length());
        return rest;
    }
}
