/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: the concrete model cell's wiring to the seam.
 *
 * WonderlandModelCell is the Cell subclass the desktop hands out for a
 * deployed model; its createCellRenderer() override is a thin delegate to
 * ModelRendererFactory. Asserted here is that wiring -- that the cell passes
 * through the type and its installed load result to the seam, and that no
 * Java 3D ever touches the headless JVM before the seam decides to build a
 * real one. The desktop's install path (liveFactory) is what the probe
 * verifies; here we install a stub supplier so the *decision* is exercised.
 */
package org.jdesktop.wonderland.renderer.cell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellCache;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.cell.state.ModelCellClientState;
import org.jdesktop.wonderland.modules.asset.ModelLoadResult;
import org.jdesktop.wonderland.modules.asset.ModelLoader;
import org.jdesktop.wonderland.modules.asset.WorldStore;
import org.jdesktop.wonderland.renderer.seam.ModelRendererFactory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WonderlandModelCellTest {

    private static final class MapStore implements WorldStore {
        private final Map<String, byte[]> files = new HashMap<>();

        MapStore put(String path, byte[] content) {
            files.put("M/" + path, content);
            return this;
        }

        @Override
        public Optional<InputStream> open(String module, String path) {
            byte[] content = files.get(module + "/" + path);
            return content == null ? Optional.empty()
                    : Optional.of(new ByteArrayInputStream(content));
        }

        @Override
        public boolean present(String module, String path) {
            return files.containsKey(module + "/" + path);
        }

        @Override
        public List<String> listAssets(String module, String suffix) {
            return List.of();
        }
    }

    private static final String DEP = "<deployed-model>"
            + "<modelURL>wla://M/dir/u.dae</modelURL></deployed-model>";
    private static final String DAE = "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\""
            + " version=\"1.4.1\"></COLLADA>";

    private static ModelLoadResult loadedResult() {
        MapStore store = new MapStore()
                .put("dir/m.dep", DEP.getBytes(StandardCharsets.UTF_8))
                .put("dir/u.dae", gzip(DAE.getBytes(StandardCharsets.UTF_8)));
        return new ModelLoader().load(store, "wla://M/dir/m.dep");
    }

    private static byte[] gzip(byte[] raw) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(raw);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class StubRenderer implements CellRenderer {
        @Override
        public void cellTransformUpdate(CellTransform t) {
        }

        @Override
        public void setStatus(CellStatus s, boolean inc) {
        }

        @Override
        public CellStatus getStatus() {
            return CellStatus.DISK;
        }
    }

    @Test
    void aFreshCellCarriesItsDepUrlAndHasNoLoadResultYet() {
        WonderlandModelCell cell = new WonderlandModelCell(new CellID(7L), "wla://M/dir/m.dep");

        assertThat(cell.getDepUrl()).isEqualTo("wla://M/dir/m.dep");
        assertThat(cell.getModelLoadResult()).isNull();
    }

    @Test
    void createCellRendererReturnsNullForATypeTheSeamRejectsEvenWithALoadedModel() {
        WonderlandModelCell cell = new WonderlandModelCell(new CellID(1L), "wla://M/x.dep");
        cell.setModelLoadResult(loadedResult());
        cell.setModelRendererFactory(new ModelRendererFactory((c, r) -> new StubRenderer()));

        // RENDERER_2D resolves to null in the seam (M6a only ships the JME one);
        // this proves the cell delegates rather than hard-coding the choice
        assertThat(cell.createCellRenderer(Cell.RendererType.RENDERER_2D)).isNull();
        assertThat(cell.createCellRenderer(Cell.RendererType.NONE)).isNull();
    }

    @Test
    void createCellRendererReturnsNullForAJmeRequestWhenNoLoadResultIsInstalled() {
        WonderlandModelCell cell = new WonderlandModelCell(new CellID(2L), "wla://M/x.dep");
        cell.setModelRendererFactory(new ModelRendererFactory((c, r) -> new StubRenderer()));

        // the load hasn't happened yet -- the cell must not build a renderer
        // for a model that has no Collada document behind it
        assertThat(cell.createCellRenderer(Cell.RendererType.RENDERER_JME)).isNull();
    }

    @Test
    void createCellRendererDelegatesToTheSeamForAJmeRequestAndLoadedModel() {
        WonderlandModelCell cell = new WonderlandModelCell(new CellID(3L), "wla://M/x.dep");
        cell.setModelLoadResult(loadedResult());
        StubRenderer stub = new StubRenderer();
        cell.setModelRendererFactory(new ModelRendererFactory((c, r) -> {
            // the seam sees the cell's own identity so a future factory can
            // key its renderer map by cell
            assertThat(c).isSameAs(cell);
            return stub;
        }));

        assertThat(cell.createCellRenderer(Cell.RendererType.RENDERER_JME)).isSameAs(stub);
    }

    @Test
    void aNullFactoryIsSafelyNoRenderer() {
        // an operator wiring mistake should degrade the cell to invisible
        // rather than crash the desktop's status walk
        WonderlandModelCell cell = new WonderlandModelCell(new CellID(4L), "wla://M/x.dep");
        cell.setModelLoadResult(loadedResult());
        cell.setModelRendererFactory(null);

        assertThat(cell.createCellRenderer(Cell.RendererType.RENDERER_JME)).isNull();
    }

    @Test
    void theDefaultLiveFactoryIsInstalledButNothingIsConstructedUntilTheSeamDecidesTo() {
        // a fresh cell + a JME type + no load result: the default
        // liveFactory's decision path stops before invoking the supplier, so
        // the headless test JVM never touches a Jogamp class. That is what
        // keeps the M6a suite runnable under java.awt.headless=true.
        WonderlandModelCell cell = new WonderlandModelCell(new CellID(5L), "wla://M/x.dep");

        assertThat(cell.createCellRenderer(Cell.RendererType.RENDERER_JME)).isNull();
    }

    // ---- Stage 2: the deployed-model URL now arrives over the shared wire
    // ---- format, not just the loader constructor. The client cache reflects a
    // ---- Cell open with (CellID, CellCache) and then calls setClientState; a
    // ---- model cell must pick the wla:// reference up from that state.

    @Test
    void theCacheConstructorOpensWithoutAUrlAndSetClientStateReadsItOffTheWire() {
        // exactly the shape CellCacheBasicImpl.instantiateCell reflects open
        WonderlandModelCell cell = new WonderlandModelCell(new CellID(9L), (CellCache) null);
        assertThat(cell.getDepUrl()).as("not known at construction").isNull();

        ModelCellClientState wire = new ModelCellClientState();
        wire.setName("Exam Room 1");
        wire.setDeployedModelURL("wla://wonderland-outpatient-clinic/exam-room.kmz/exam-room.dep");
        cell.setClientState(wire);

        assertThat(cell.getDepUrl())
                .isEqualTo("wla://wonderland-outpatient-clinic/exam-room.kmz/exam-room.dep");
        assertThat(cell.getName()).isEqualTo("Exam Room 1");
    }

    @Test
    void aPlainClientStateLeavesTheLoaderUrlInPlace() {
        // a non-model client state carries no URL -- the loader-supplied value
        // must not be blanked out by the base setClientState call
        WonderlandModelCell cell = new WonderlandModelCell(new CellID(10L), "wla://M/pre.dep");
        CellClientState plain = new CellClientState();
        plain.setName("some-cell");

        cell.setClientState(plain);

        assertThat(cell.getDepUrl()).isEqualTo("wla://M/pre.dep");
    }

    @Test
    void aModelClientStateWithoutAUrlKeepsTheLoaderValue() {
        // the null-guard branch: a model state whose URL is unresolved must not
        // overwrite an already-known reference
        WonderlandModelCell cell = new WonderlandModelCell(new CellID(11L), "wla://M/pre.dep");
        ModelCellClientState wire = new ModelCellClientState();
        wire.setName("y");

        cell.setClientState(wire);

        assertThat(cell.getDepUrl()).isEqualTo("wla://M/pre.dep");
    }
}
