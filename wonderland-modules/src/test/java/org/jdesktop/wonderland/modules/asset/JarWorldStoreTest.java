/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (world library): JarWorldStore behaviour.
 *
 * The store is what gives the port access to the STOCK Open Wonderland worlds,
 * which ship as packaged module jars (their cells under wfs/, their art under an
 * art/ directory) rather than unpacked directories. These tests build a small
 * synthetic world jar in a temp directory -- module.xml, one -wlc.xml cell, and a
 * deployed model stored under art/ -- and prove the three things the world loader
 * depends on: a jar IS enumerable (listAssets walks the entries, the very thing
 * the classpath store refused), a module-root wla:// reference finds the bytes the
 * jar packs under art/ (the read-side translation, so no vendored binary is ever
 * rewritten), and an absent asset degrades to "not present" without throwing.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class JarWorldStoreTest {

    private static final String MODULE = "wonderland-town-world";

    @TempDir
    static Path dir;

    private static Path worldJar;

    @BeforeAll
    static void buildAWorldJar() throws IOException {
        worldJar = dir.resolve(MODULE + ".jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(worldJar))) {
            put(jar, "module.xml",
                    "<wonderland-module><name>" + MODULE
                    + "</name><description>A town</description></wonderland-module>");
            put(jar, "wfs/town-wfs/room-wlc.xml",
                    "<model-cell><name>Room</name></model-cell>");
            put(jar, "wfs/town-wfs/plaza-wlc.xml",
                    "<model-cell><name>Plaza</name></model-cell>");
            // the deployed model is packed under art/, exactly like a stock world jar,
            // while its wla:// reference names it at the module root
            put(jar, "art/room.kmz/room.kmz.dep", "dep bytes");
            put(jar, "art/room.kmz/models/room.dae.gz", "collada bytes");
        }
    }

    private static void put(JarOutputStream jar, String name, String body) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(body.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    @Test
    void listAssetsEnumeratesTheCellsFromTheJarItself() throws IOException {
        try (JarWorldStore store = new JarWorldStore(worldJar)) {
            assertThat(store.listAssets(MODULE, "-wlc.xml")).containsExactly(
                    "wfs/town-wfs/plaza-wlc.xml",
                    "wfs/town-wfs/room-wlc.xml");
        }
    }

    @Test
    void listAssetsWithANullSuffixListsEveryFileEntry() throws IOException {
        try (JarWorldStore store = new JarWorldStore(worldJar)) {
            assertThat(store.listAssets(MODULE, null))
                    .contains("module.xml", "art/room.kmz/room.kmz.dep")
                    .doesNotContain("wfs/", "wfs/town-wfs/"); // directory entries skipped
        }
    }

    @Test
    void aModuleRootReferenceFindsTheBytesPackedUnderArt() throws IOException {
        try (JarWorldStore store = new JarWorldStore(worldJar)) {
            // the exact spelling the wla:// reference uses: module-root, no art/
            assertThat(store.present(MODULE, "room.kmz/room.kmz.dep")).isTrue();
            try (InputStream in = store.open(MODULE, "room.kmz/room.kmz.dep")
                    .orElseThrow()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo("dep bytes");
            }
            assertThat(store.present(MODULE, "room.kmz/models/room.dae.gz")).isTrue();
        }
    }

    @Test
    void anExactEntryMatchIsPreferredOverTheArtFallback() throws IOException {
        try (JarWorldStore store = new JarWorldStore(worldJar)) {
            // module.xml sits at the jar root, not under art/; the direct hit wins
            try (InputStream in = store.open(MODULE, "module.xml").orElseThrow()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("<name>" + MODULE + "</name>");
            }
        }
    }

    @Test
    void anAbsentAssetDegradesToEmptyWithoutThrowing() throws IOException {
        try (JarWorldStore store = new JarWorldStore(worldJar)) {
            assertThat(store.open(MODULE, "no-such-asset.dep")).isEmpty();
            assertThat(store.present(MODULE, "no-such-asset.dep")).isFalse();
            assertThat(store.open(MODULE, null)).isEmpty();
            assertThat(store.open(MODULE, "")).isEmpty();
        }
    }
}
