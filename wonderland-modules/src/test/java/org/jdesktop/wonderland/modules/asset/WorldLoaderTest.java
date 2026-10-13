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
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

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
}
