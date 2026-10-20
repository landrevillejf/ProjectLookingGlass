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
 * jar packs under art/ -- and the plain {@code .dae} a {@code .dep} names finds the
 * {@code .dae.gz} the packager actually wrote, which is the difference between a
 * stock world loading its buildings and loading nothing at all -- and an absent
 * asset degrades to "not present" without throwing.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JarWorldStoreTest {

    private static final String MODULE = "wonderland-town-world";

    @TempDir
    static Path dir;

    private static Path worldJar;

    @BeforeAll
    static void buildAWorldJar() throws IOException {
        worldJar = dir.resolve(MODULE + ".jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(worldJar))) {
            // explicit directory entries, exactly as a packaged world jar carries
            // them: a store that never met one would not know to skip it.
            putDir(jar, "wfs/");
            putDir(jar, "wfs/town-wfs/");
            putDir(jar, "art/room.kmz/");
            putDir(jar, "art/room.kmz/models/");
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

    private static void putDir(JarOutputStream jar, String name) throws IOException {
        jar.putNextEntry(new JarEntry(name));
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
            // present() guards the same null/empty spellings open() does
            assertThat(store.present(MODULE, null)).isFalse();
            assertThat(store.present(MODULE, "")).isFalse();
            // a candidate that resolves to a directory entry is not an asset
            assertThat(store.present(MODULE, "room.kmz/models")).isFalse();
        }
    }

    @Test
    void thePlainNameADepRecordsFindsTheGzippedBytesThePackagerWrote() throws IOException {
        // The one spelling rule that decides whether a stock world arrives as a
        // building or as a coloured box. Every deployed model in every one of the
        // seven shipped worlds is stored as art/<pkg>/models/<name>.dae.gz while its
        // .dep names it plain at the module root -- so a store that takes the
        // reference literally finds nothing, loads nothing, and says nothing about it.
        // Measured: 23 of 23 distinct models, 0 of them readable by exact name.
        try (JarWorldStore store = new JarWorldStore(worldJar)) {
            assertThat(store.present(MODULE, "room.kmz/models/room.dae"))
                    .as("the reference the author typed, not the name the jar carries")
                    .isTrue();
            try (InputStream in = store.open(MODULE, "room.kmz/models/room.dae")
                    .orElseThrow()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo("collada bytes");
            }
        }
    }

    @Test
    void bothConventionsApplyTogetherAndNeitherImpliesTheOther() throws IOException {
        // art/ and .gz are independent dimensions: a texture may sit uncompressed
        // under art/, and a model may sit compressed at the root. Asserting the pair
        // together is what keeps a future "just add art/" fix from loading the seven
        // worlds and still missing every one of their COLLADA documents.
        try (JarWorldStore store = new JarWorldStore(worldJar)) {
            assertThat(store.present(MODULE, "room.kmz/models/room.dae")).isTrue();
            assertThat(store.present(MODULE, "room.kmz/models/room.dae.gz")).isTrue();
            assertThat(store.present(MODULE, "art/room.kmz/models/room.dae.gz")).isTrue();
            // the .dep is packed under art/ and uncompressed, so only one of the two
            // conventions applies to it -- and it still resolves
            assertThat(store.present(MODULE, "room.kmz/room.kmz.dep")).isTrue();
            assertThat(store.present(MODULE, "room.kmz/room.kmz.dep.gz")).isFalse();
        }
    }

    @Test
    void aReadFaultSurfacesAsUncheckedIoNotASilentEmpty() throws IOException {
        // The seam raises the IOException JarFile.getInputStream only produces on a
        // corrupt or closed jar: an entry that IS present but cannot be read must be
        // reported, not mistaken for an absent one (an empty Optional).
        JarWorldStore.JarIo faultRead = new JarWorldStore.JarIo() {
            @Override
            public InputStream inputStream(JarFile jar, ZipEntry entry) throws IOException {
                throw new IOException("entry unreadable");
            }

            @Override
            public void close(JarFile jar) throws IOException {
                jar.close();
            }
        };
        try (JarWorldStore store = new JarWorldStore(worldJar, faultRead)) {
            assertThatThrownBy(() -> store.open(MODULE, "module.xml"))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("cannot read");
        }
    }

    @Test
    void aCloseFaultIsSwallowedBecauseThereIsNothingToRecover() throws IOException {
        // close() is the one place the store deliberately ignores an IOException: a
        // read-only jar that refuses to close is not something the caller can act
        // on, and a failure on the cleanup path must not mask the load's own result.
        JarWorldStore.JarIo faultClose = new JarWorldStore.JarIo() {
            @Override
            public InputStream inputStream(JarFile jar, ZipEntry entry) throws IOException {
                return jar.getInputStream(entry);
            }

            @Override
            public void close(JarFile jar) throws IOException {
                throw new IOException("close refused");
            }
        };
        JarWorldStore store = new JarWorldStore(worldJar, faultClose);
        assertThatCode(store::close).doesNotThrowAnyException();
    }
}
