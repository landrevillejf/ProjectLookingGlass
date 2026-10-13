/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: the seam's dispatch decision.
 *
 * The 2006 ClientContextJME was a giant "if the desktop asked for the jme
 * renderer, build one" gate. Rebuilt here as a pure dispatch: which
 * CellRenderer for which RendererType. The expensive scene-graph build sits
 * behind an injected {@code RendererSupplier} -- the tests hand in a stub and
 * assert only which branch fires, never touching a Jogamp class; the live
 * desktop installs the supplier that constructs a real ModelRenderer. That
 * split is exactly what the plan asked for, and this file pins it: null / NONE
 * / RENDERER_2D / an errored load all resolve to "no renderer", while a
 * loaded model under RENDERER_JME delegates to the supplier.
 */
package org.jdesktop.wonderland.renderer.seam;

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
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.modules.asset.ModelLoadResult;
import org.jdesktop.wonderland.modules.asset.ModelLoader;
import org.jdesktop.wonderland.modules.asset.WorldStore;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ModelRendererFactoryTest {

    /** A store whose modules are maps of path -> bytes; matches M5c's pattern. */
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
            + "<modelURL>wla://M/dir/untitled.dae</modelURL>"
            + "</deployed-model>";

    private static final String DAE = "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\""
            + " version=\"1.4.1\"><library_images><image id=\"img0\">"
            + "<init_from>untitled/texture0.png</init_from></image></library_images></COLLADA>";

    private static ModelLoadResult loadedResult() {
        MapStore store = new MapStore()
                .put("dir/m.dep", DEP.getBytes(StandardCharsets.UTF_8))
                .put("dir/untitled.dae", gzip(
                        DAE.getBytes(StandardCharsets.UTF_8)));
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

    /** A CellRenderer stand-in: never constructs Java 3D, so it headless-runs. */
    private static final class StubRenderer implements CellRenderer {
        @Override
        public void cellTransformUpdate(org.jdesktop.wonderland.common.cell.CellTransform t) {
        }

        @Override
        public void setStatus(org.jdesktop.wonderland.common.cell.CellStatus s, boolean inc) {
        }

        @Override
        public org.jdesktop.wonderland.common.cell.CellStatus getStatus() {
            return org.jdesktop.wonderland.common.cell.CellStatus.DISK;
        }
    }

    @Test
    void anullTypeResolvesToNoRenderer() {
        ModelRendererFactory factory = new ModelRendererFactory(
                (cell, res) -> new StubRenderer());

        assertThat(factory.rendererFor(null, null, loadedResult())).isNull();
    }

    @Test
    void theExplicitNoneTypeResolvesToNoRenderer() {
        ModelRendererFactory factory = new ModelRendererFactory(
                (cell, res) -> new StubRenderer());

        assertThat(factory.rendererFor(Cell.RendererType.NONE, null, loadedResult()))
                .isNull();
    }

    @Test
    void the2dTypeResolvesToNoRendererSinceM6aShipsOnlyTheJogampOne() {
        ModelRendererFactory factory = new ModelRendererFactory(
                (cell, res) -> new StubRenderer());

        assertThat(factory.rendererFor(Cell.RendererType.RENDERER_2D, null, loadedResult()))
                .isNull();
    }

    @Test
    void aNullModelResultYieldsNoRendererEvenForJme() {
        ModelRendererFactory factory = new ModelRendererFactory(
                (cell, res) -> new StubRenderer());

        assertThat(factory.rendererFor(Cell.RendererType.RENDERER_JME, null, null))
                .isNull();
    }

    @Test
    void anErroredModelLoadYieldsNoRenderer() {
        ModelRendererFactory factory = new ModelRendererFactory(
                (cell, res) -> new StubRenderer());

        // a store with no bytes at all -> load() degrades to an ERROR result
        ModelLoadResult errored = new ModelLoader().load(new MapStore(), "wla://M/x.dep");
        assertThat(errored.isLoaded()).isFalse();

        assertThat(factory.rendererFor(Cell.RendererType.RENDERER_JME, null, errored))
                .isNull();
    }

    @Test
    void aLoadedModelUnderJmeDelegatesToTheSupplier() {
        StubRenderer stub = new StubRenderer();
        ModelRendererFactory factory = new ModelRendererFactory((cell, res) -> stub);

        CellRenderer rend = factory.rendererFor(
                Cell.RendererType.RENDERER_JME, null, loadedResult());

        assertThat(rend).isSameAs(stub);
    }

    @Test
    void aLoadedModelPassesItselfThroughToTheSupplierForTheSceneBuild() {
        ModelLoadResult loaded = loadedResult();
        final ModelLoadResult[] seen = new ModelLoadResult[1];
        ModelRendererFactory factory = new ModelRendererFactory((cell, res) -> {
            seen[0] = res;
            return new StubRenderer();
        });

        factory.rendererFor(Cell.RendererType.RENDERER_JME, null, loaded);

        // the supplier sees the exact same result -- so the probe side can
        // pull document() and presentTextures() from it without a re-load
        assertThat(seen[0]).isSameAs(loaded);
    }

    @Test
    void aNullSupplierNeverThrowsAndAlwaysYieldsNoRenderer() {
        // the desktop is expected to install a supplier via liveFactory(); an
        // instance constructed without one is legal and safely no-ops, so a
        // test wiring mistake degrades to a hidden model rather than a crash
        ModelRendererFactory factory = new ModelRendererFactory(null);

        assertThat(factory.rendererFor(Cell.RendererType.RENDERER_JME, null, loadedResult()))
                .isNull();
    }
}
