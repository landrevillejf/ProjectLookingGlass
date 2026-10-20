/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5c (assetmgr): the end-to-end world load over REAL bytes.
 * Loads the vendored almost-empty world (real .wlc.xml -> JAXB ModelCellServerState,
 * real .dep/.ldr and gzip'd .dae bytes) through a directory store and asserts the
 * present/missing texture inventory the two shipped models actually produce --
 * grass resolves its one texture, skyline references two that are absent. The
 * tolerant decode is proved too: a corrupt cell and an unreadable cell are skipped,
 * never fatal, and a cell without a model component loads with a null model.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldLoaderTest {

    /** The module directory as it sits on the test classpath (unpacked resources). */
    private static Path worldModuleDir() {
        try {
            return Path.of(WorldLoaderTest.class.getResource("/wonderland-almostempty-world")
                    .toURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void loadsTheShippedWorldAndItsTwoModelCells() {
        Path moduleDir = worldModuleDir();
        DirectoryWorldStore store = new DirectoryWorldStore(moduleDir.getParent());
        WorldContents contents = new WorldLoader().load(store, moduleDir.getFileName().toString());

        assertThat(contents.cellCount()).isEqualTo(2);
        assertThat(contents.loadedModelCount()).isEqualTo(2);
        assertThat(contents.cells()).allSatisfy(cell -> assertThat(cell.modelLoaded()).isTrue());
        // grass references untitled/texture0.png (present); skyline references
        // texture0.png + texture1.png (neither vendored) -> two missing across the world
        assertThat(contents.missingTextureCount()).isEqualTo(2);
    }

    @Test
    void theGrassCellResolvesItsPresentTextureAndTheSkylineCellItsMissingOnes() {
        Path moduleDir = worldModuleDir();
        DirectoryWorldStore store = new DirectoryWorldStore(moduleDir.getParent());
        WorldContents contents = new WorldLoader().load(store, moduleDir.getFileName().toString());

        ModelLoadResult grass = contents.cells().stream()
                .filter(c -> "TreeHillGrassHex-120m-perged.kmz".equals(c.name()))
                .findFirst().orElseThrow().model();
        assertThat(grass.isLoaded()).isTrue();
        assertThat(grass.presentTextures())
                .containsExactly("TreeHillGrassHex-120m-perged.kmz/models/untitled/texture0.png");
        assertThat(grass.missingTextures()).isEmpty();

        ModelLoadResult skyline = contents.cells().stream()
                .filter(c -> "TreeHillSkylineHex-110m-perged.kmz".equals(c.name()))
                .findFirst().orElseThrow().model();
        assertThat(skyline.isLoaded()).isTrue();
        assertThat(skyline.presentTextures()).isEmpty();
        assertThat(skyline.missingTextures()).hasSize(2);
    }

    @Test
    void aCorruptOrUnreadableCellIsSkippedAndANonModelCellLoadsNull() {
        WorldStore stub = new WorldStore() {
            @Override
            public List<String> listAssets(String module, String suffix) {
                return List.of("plain-wlc.xml", "corrupt-wlc.xml", "missing-wlc.xml");
            }

            @Override
            public Optional<InputStream> open(String module, String path) {
                if (path.startsWith("missing")) {
                    return Optional.empty();
                }
                String xml = path.startsWith("corrupt")
                        ? "this is not xml at all <<<"
                        : "<model-cell><name>Plain</name></model-cell>";
                return Optional.of(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            }

            @Override
            public boolean present(String module, String path) {
                return false;
            }
        };

        WorldContents contents = new WorldLoader().load(stub, "mod");

        // the corrupt and the missing cell are skipped; only the plain one loads,
        // and it has no model component, so its model is null
        assertThat(contents.cellCount()).isEqualTo(1);
        assertThat(contents.cells().get(0).name()).isEqualTo("Plain");
        assertThat(contents.cells().get(0).model()).isNull();
        assertThat(contents.loadedModelCount()).isZero();
    }

    @Test
    void anEmptyOrUnreadableCellIsSkippedRatherThanDecoded() {
        // read() degrades a stream IOException to "" and decode() maps "" to null,
        // so a cell whose bytes are empty or whose stream fails mid-read is skipped
        // -- the same tolerant outcome a corrupt cell gets, proved here for the two
        // I/O-shaped failures rather than the malformed-text one above.
        WorldStore stub = new WorldStore() {
            @Override
            public List<String> listAssets(String module, String suffix) {
                return List.of("empty-wlc.xml", "faulty-wlc.xml");
            }

            @Override
            public Optional<InputStream> open(String module, String path) {
                if (path.startsWith("empty")) {
                    return Optional.of(new ByteArrayInputStream(new byte[0]));
                }
                return Optional.of(new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("stream on fire");
                    }
                });
            }

            @Override
            public boolean present(String module, String path) {
                return false;
            }
        };

        WorldContents contents = new WorldLoader().load(stub, "mod");
        assertThat(contents.cellCount()).isZero();
    }

    @Test
    void anUnmarshallerThatRefusesTheTolerantHandlerFailsFast() {
        // The seam hands the loader an unmarshaller whose setEventHandler throws.
        // The tolerant handler is what lets an unknown element degrade to a warning;
        // without it every load would abort on the first unported component, so the
        // loader refuses to be constructed rather than limp into a strict decode.
        Unmarshaller refusing = (Unmarshaller) Proxy.newProxyInstance(
                WorldLoaderTest.class.getClassLoader(),
                new Class<?>[] {Unmarshaller.class},
                (proxy, method, args) -> {
                    if ("setEventHandler".equals(method.getName())) {
                        throw new JAXBException("handler refused");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        assertThatThrownBy(() -> new WorldLoader(refusing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot install the tolerant handler");
    }

    @Test
    void parentageIsDerivedFromTheDirectoryLayout() {
        // The cell that owns a -wld directory is the parent of everything inside it;
        // this is the only place the world tree is recorded, and it is what lets
        // WorldPlacement fold a child's parent-relative numbers into world space.
        assertThat(WorldLoader.parentOf(null)).isNull();
        // a child filed under its parent's -wld directory names the parent file
        assertThat(WorldLoader.parentOf(
                "proj/OrientationWorld-18-wld/Field-2-wlc.xml"))
                .isEqualTo("proj/OrientationWorld-18-wlc.xml");
        // a root cell filed straight under the project directory has no parent
        assertThat(WorldLoader.parentOf("proj/RootCell-wlc.xml")).isNull();
        // a file that is not a cell at all is not placed in the tree
        assertThat(WorldLoader.parentOf("proj/module.xml")).isNull();
    }
}
