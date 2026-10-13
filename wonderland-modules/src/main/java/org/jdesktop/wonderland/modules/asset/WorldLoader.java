/*
 * wonderland-modules M5c (assetmgr): loads a world module end to end (headless).
 *
 * Discovers a module's world-cell files ({@code *-wlc.xml}), decodes each into
 * its {@link CellServerState} through the JAXB path wonderland-common already
 * owns, and resolves every model cell's deployed asset through {@link ModelLoader}.
 * The permissive unmarshaller event handler reproduces the 2006 tolerant decode:
 * an element with no registered type here (a {@code <placemark-component>} from
 * a module not yet ported) is a recoverable warning, not a load abort. Seeding
 * the live cell cache / scene graph from the returned {@link WorldContents} is
 * the deferred M6 desktop integration.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.Unmarshaller;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.cell.state.CellServerStateFactory;
import org.jdesktop.wonderland.common.cell.state.ModelCellComponentServerState;

/** Loads a world module's cells and their deployed models from a {@link WorldStore}. */
public final class WorldLoader {

    private final ModelLoader modelLoader = new ModelLoader();
    private final Unmarshaller unmarshaller;

    public WorldLoader() {
        this.unmarshaller = tolerantUnmarshaller();
    }

    /** The system unmarshaller, tuned to skip (rather than abort on) unknown elements. */
    private static Unmarshaller tolerantUnmarshaller() {
        Unmarshaller unmarshaller = CellServerStateFactory.getUnmarshaller(null);
        try {
            unmarshaller.setEventHandler(event -> true); // skip unknown elements
        } catch (javax.xml.bind.JAXBException ex) {
            throw new IllegalStateException("cannot install the tolerant handler", ex);
        }
        return unmarshaller;
    }

    /**
     * Load every cell in {@code module}.
     *
     * @return the world's cells; a cell that fails to decode is skipped, not fatal
     */
    public WorldContents load(WorldStore store, String module) {
        List<WorldContents.LoadedCell> cells = new ArrayList<>();
        for (String wlc : store.listAssets(module, "-wlc.xml")) {
            CellServerState state = store.open(module, wlc)
                    .map(stream -> decode(stream))
                    .orElse(null);
            if (state == null) {
                continue;
            }
            cells.add(new WorldContents.LoadedCell(state, resolveModel(store, state)));
        }
        return new WorldContents(module, cells);
    }

    private ModelLoadResult resolveModel(WorldStore store, CellServerState state) {
        Object component = state.getComponentServerState(ModelCellComponentServerState.class);
        if (component instanceof ModelCellComponentServerState modelComponent) {
            return modelLoader.load(store, modelComponent.deployedModelURL);
        }
        return null;
    }

    private CellServerState decode(InputStream in) {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return CellServerState.decode(reader, unmarshaller);
        } catch (Exception ex) {
            return null; // a corrupt cell file must not abort the world load
        }
    }
}
