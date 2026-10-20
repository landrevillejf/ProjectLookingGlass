/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5c (assetmgr): loads a world module end to end (headless).
 *
 * Discovers a module's world-cell files ({@code *-wlc.xml}), decodes each into
 * its {@link CellServerState} through the JAXB path wonderland-common already
 * owns, and resolves every model cell's deployed asset through {@link ModelLoader}.
 * The permissive unmarshaller event handler reproduces the 2006 tolerant decode:
 * an element with no registered type here (a {@code <placemark-component>} from
 * a module not yet ported) is a recoverable warning, not a load abort.
 *
 * <p>A cell whose ROOT is not ported is a different failure, and used to be a
 * silent one: JAXB has no class to hand the document to, throws, and the loop
 * dropped the cell. That is how the combined world lost nine of its forty-one
 * objects -- every poster, the PDF viewers, the whiteboard, the remote window --
 * because none of those modules were ported and a world browser that cannot draw
 * a thing was deleting it from the world instead. Such a cell now goes to
 * {@link ForeignCellReader}, which keeps its name, its placement and the asset it
 * displays, so it is placed and, where the renderer knows the type, drawn. The
 * rule the whole method follows: a cell this loader cannot understand is a cell
 * it lists poorly, never a cell that does not exist.</p>
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.bind.Unmarshaller;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.cell.state.CellServerStateFactory;
import org.jdesktop.wonderland.common.cell.state.ModelCellComponentServerState;

/** Loads a world module's cells and their deployed models from a {@link WorldStore}. */
public final class WorldLoader {

    private final ModelLoader modelLoader = new ModelLoader();
    private final Unmarshaller unmarshaller;

    public WorldLoader() {
        this(CellServerStateFactory.getUnmarshaller(null));
    }

    /**
     * Test seam: build the loader around a supplied unmarshaller, so the tolerant
     * handler install below can be driven with one whose {@code setEventHandler}
     * refuses. Production callers use the no-arg constructor.
     */
    WorldLoader(Unmarshaller unmarshaller) {
        this.unmarshaller = tolerant(unmarshaller);
    }

    /** Tune the given unmarshaller to skip (rather than abort on) unknown elements. */
    private static Unmarshaller tolerant(Unmarshaller unmarshaller) {
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
            Optional<InputStream> file = store.open(module, wlc);
            if (file.isEmpty()) {
                continue;
            }
            String xml = read(file.get());
            CellServerState state = decode(xml);
            if (state == null) {
                state = ForeignCellReader.read(xml);
            }
            if (state == null) {
                continue;
            }
            cells.add(new WorldContents.LoadedCell(state, resolveModel(store, state),
                    wlc, parentOf(wlc)));
        }
        return new WorldContents(module, cells);
    }

    /** The directory suffix that means "the cells inside me live here". */
    private static final String CHILDREN_SUFFIX = "-wld";

    /** The file suffix a cell carries, which its parent's name carries too. */
    private static final String CELL_SUFFIX = "-wlc.xml";

    /**
     * The cell that contains {@code path}, or null when it is a world root.
     *
     * <p>A world module stores parentage in its directory layout rather than in
     * the cell file: {@code OrientationWorld-18-wlc.xml} owns the directory
     * {@code OrientationWorld-18-wld/}, and everything below it is placed relative
     * to it. Deriving the parent from the path is therefore the only way to learn
     * the tree -- the cell's own XML never names it -- and it is why flattening a
     * stock world onto the origin moved the meeting complex ninety metres away
     * from itself (see {@link WorldPlacement}).</p>
     *
     * <p>Returns the parent path whether or not that file is in this load: a cell
     * whose parent was not shipped is still a cell, and {@link WorldPlacement}
     * places it by its own numbers.</p>
     */
    static String parentOf(String path) {
        if (path == null) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        if (slash <= 0 || !path.endsWith(CELL_SUFFIX)) {
            return null;
        }
        String directory = path.substring(0, slash);
        int nameStart = directory.lastIndexOf('/') + 1;
        String leaf = directory.substring(nameStart);
        if (!leaf.endsWith(CHILDREN_SUFFIX)) {
            return null; // a root cell, filed straight under the project directory
        }
        return directory.substring(0, nameStart)
                + leaf.substring(0, leaf.length() - CHILDREN_SUFFIX.length())
                + CELL_SUFFIX;
    }

    /** One cell file's text; empty when it cannot be read, which loses only that cell. */
    private static String read(InputStream in) {
        try (InputStream stream = in; Reader reader =
                new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) > 0) {
                text.append(buffer, 0, count);
            }
            return text.toString();
        } catch (IOException ex) {
            return "";
        }
    }

    private ModelLoadResult resolveModel(WorldStore store, CellServerState state) {
        Object component = state.getComponentServerState(ModelCellComponentServerState.class);
        if (component instanceof ModelCellComponentServerState modelComponent) {
            return modelLoader.load(store, modelComponent.deployedModelURL);
        }
        return null;
    }

    /**
     * Decode one cell through the registered types. A document JAXB will not open
     * returns null rather than throwing, because the caller has a second reader.
     */
    private CellServerState decode(String xml) {
        if (xml.isEmpty()) {
            return null;
        }
        try (Reader reader = new StringReader(xml)) {
            return CellServerState.decode(reader, unmarshaller);
        } catch (Exception | LinkageError ex) {
            return null; // a corrupt or unregistered cell must not abort the world load
        }
    }
}
