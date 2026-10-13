/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: the model cell the desktop's cell factory hands out.
 *
 * The 2006 model-jme module had ModelCell extend BaseCell, hold a
 * ModelCellClientState carrying a wla:// reference, and build a ModelJME at
 * createCellRenderer(). Rebuilt here as the concrete Cell subclass that knows
 * how to hand a loaded model to the renderer seam: it carries the deployed
 * model URL (which the M5c ModelCellServerState / ModelCellClientState pair
 * names, and which WorldLoader resolves via ModelLoader into a
 * {@link ModelLoadResult}), consults an injectable {@link ModelRendererFactory}
 * on createCellRenderer(), and returns whatever the seam decides -- which is
 * null for every non-JME type and null for a null / errored load.
 *
 * The seam is what keeps the headless suite meaningful: the tests install a
 * stub supplier and assert the *dispatch* (which type yields which decision)
 * without ever touching a Java 3D object; the live desktop installs the
 * default factory whose supplier constructs a real ModelRenderer, verified by
 * the in-JVM probe. Same split the plan asked for, same split lg3d-core uses
 * for its own scene-graph half.
 */
package org.jdesktop.wonderland.renderer.cell;

import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellCache;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.modules.asset.ModelLoadResult;
import org.jdesktop.wonderland.renderer.seam.ModelRendererFactory;

/** A {@link Cell} that renders a loaded Collada model through the seam. */
public class WonderlandModelCell extends Cell {

    private final String depUrl;
    private ModelLoadResult modelResult;
    private ModelRendererFactory rendererFactory = ModelRendererFactory.liveFactory();

    /**
     * @param cellID the cell's identity, on the 2006 canonical constructor
     * @param depUrl the {@code wla://} reference of the deployed model this
     *               cell renders; the loader already resolved it upstream
     */
    public WonderlandModelCell(CellID cellID, CellCache cellCache, String depUrl) {
        super(cellID, cellCache);
        this.depUrl = depUrl;
    }

    /** Convenience for tests and standalone cells: cache-less constructor. */
    public WonderlandModelCell(CellID cellID, String depUrl) {
        this(cellID, null, depUrl);
    }

    /** The reference this cell was created with, so callers can trace it. */
    public String getDepUrl() {
        return depUrl;
    }

    /**
     * Install the resolved load result. The WorldLoader / session calls this
     * once the model has been fetched; before then {@link #createCellRenderer}
     * returns null (no renderer for an unloaded model), matching the 2006
     * BaseCell contract that a cell off the load queue renders nothing.
     */
    public void setModelLoadResult(ModelLoadResult modelResult) {
        this.modelResult = modelResult;
    }

    public ModelLoadResult getModelLoadResult() {
        return modelResult;
    }

    /**
     * Override the seam's factory. The default is {@link
     * ModelRendererFactory#liveFactory()} -- its supplier constructs a real
     * {@code ModelRenderer} and needs a live Java 3D canvas. Tests install a
     * factory whose supplier returns a stub, so the dispatch decision is
     * headless-assertable; the desktop never calls this.
     */
    public void setModelRendererFactory(ModelRendererFactory rendererFactory) {
        this.rendererFactory = rendererFactory;
    }

    @Override
    protected CellRenderer createCellRenderer(RendererType rendererType) {
        if (rendererFactory == null) {
            return null;
        }
        return rendererFactory.rendererFor(rendererType, this, modelResult);
    }
}
