/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (world library): the store that spans many world modules.
 *
 * The combined world places cells whose deployed models live in OTHER installed
 * jars (auditorium, town, gallery, meeting complex...), so a read keyed by the
 * referenced module -- not by the jar currently open -- is what makes it whole.
 * A single JarWorldStore ignores that key and reads only its own bytes, which is
 * exactly how the combined world loaded with most of its objects missing. These
 * tests pin the routing contract hermetically (synthetic jars in a temp dir, no
 * stock-world extraction, matching the rest of the suite):
 *   - a reference to another module is answered from THAT module's jar;
 *   - a module no jar carries falls back to the shared store;
 *   - a module with neither answer is simply absent, never a throw;
 *   - present / listAssets / hasModule follow the same route.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class MultiWorldStoreTest {

    @TempDir
    Path dir;

    private Path jar(String name, String... paths) throws IOException {
        Path file = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (String p : paths) {
                out.putNextEntry(new JarEntry(p));
                out.write(p.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    /** A store that answers every module with a fixed marker, to prove the fallback route. */
    private static final class MarkerStore implements WorldStore {
        @Override
        public Optional<InputStream> open(String module, String path) {
            return Optional.of(new java.io.ByteArrayInputStream(
                    ("fallback:" + module + "/" + path).getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public boolean present(String module, String path) {
            return path.startsWith("shared/");
        }

        @Override
        public List<String> listAssets(String module, String suffix) {
            return List.of("shared/fell-back" + suffix);
        }
    }

    @Test
    void aReferenceToAnotherModuleReadsFromThatModulesJar() throws IOException {
        // The combined-world case: the requesting module names an asset whose bytes
        // live in a DIFFERENT installed jar. The composite must route to that jar,
        // not to the caller's own -- which is the single-store bug that emptied it.
        Path a = jar("world-a.jar", "art/own.txt");
        Path b = jar("world-b.jar", "art/model.dae");
        try (MultiWorldStore store = new MultiWorldStore()) {
            store.add("world-a", a);
            store.add("world-b", b);

            // Requesting art/model.dae under world-a routes to world-a's own jar,
            // which does not hold it: routing is by module, so it is absent rather
            // than served from world-b -- the very mixing a single shared store got wrong.
            assertThat(store.open("world-a", "art/model.dae")).isEmpty();
            // The same path under the module that owns it resolves to that jar's bytes.
            assertThat(new String(store.open("world-b", "art/model.dae").orElseThrow()
                    .readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("art/model.dae");
            assertThat(store.open("world-b", "art/own.txt")).isEmpty();
        }
    }

    @Test
    void anUnregisteredModuleFallsBackToTheSharedStore() throws IOException {
        Path a = jar("world-a.jar", "art/own.txt");
        try (MultiWorldStore store = new MultiWorldStore()) {
            store.add("world-a", a);
            store.fallback(new MarkerStore());

            // world-b has no jar, so its read is answered by the classpath fallback.
            assertThat(new String(store.open("world-b", "x/y.txt").orElseThrow()
                    .readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("fallback:world-b/x/y.txt");
            assertThat(store.present("world-b", "shared/a")).isTrue();
            assertThat(store.present("world-b", "private/a")).isFalse();
            assertThat(store.listAssets("world-b", ".wlc.xml")).containsExactly("shared/fell-back.wlc.xml");
        }
    }

    @Test
    void aModuleWithNeitherJarNorFallbackIsSimplyAbsent() throws IOException {
        Path a = jar("world-a.jar", "art/own.txt");
        try (MultiWorldStore store = new MultiWorldStore()) {
            store.add("world-a", a);
            // No fallback configured: an unknown module is nothing, and nothing throws.
            assertThat(store.open("ghost", "art/x")).isEmpty();
            assertThat(store.present("ghost", "art/x")).isFalse();
            assertThat(store.listAssets("ghost", ".dae")).isEmpty();
        }
    }

    @Test
    void hasModuleCountsOnlyRegisteredJarsNotTheFallback() throws IOException {
        Path a = jar("world-a.jar", "art/own.txt");
        try (MultiWorldStore store = new MultiWorldStore()) {
            store.add("world-a", a);
            store.fallback(new MarkerStore());
            assertThat(store.hasModule("world-a")).isTrue();
            assertThat(store.hasModule("world-b"))
                    .as("answered by the fallback, but not a registered jar")
                    .isFalse();
            assertThat(store.hasModule(null)).isFalse();
        }
    }
}
