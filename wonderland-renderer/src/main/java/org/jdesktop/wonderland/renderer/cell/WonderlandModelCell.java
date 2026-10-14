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
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.cell.state.ModelCellClientState;
import org.jdesktop.wonderland.modules.asset.ModelLoadResult;
import org.jdesktop.wonderland.renderer.seam.ModelRendererFactory;

/** A {@link Cell} that renders a loaded Collada model through the seam. */
public class WonderlandModelCell extends Cell {

    private String depUrl;
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

    /**
     * The constructor the client cell cache reflects open by class name
     * ({@code getConstructor(CellID, CellCache)}); the deployed model URL is
     * not known at construction time, so it arrives through
     * {@link #setClientState} a moment later when the cache applies the
     * server's ModelCellClientState. This is the wire-fed path a walked-in
     * cell takes, as opposed to the loader-fed 3-arg constructor above.
     */
    public WonderlandModelCell(CellID cellID, CellCache cellCache) {
        this(cellID, cellCache, null);
    }

    /** Convenience for tests and standalone cells: cache-less constructor. */
    public WonderlandModelCell(CellID cellID, String depUrl) {
        this(cellID, null, depUrl);
    }

    /**
     * The {@code wla://} reference this cell renders. It is set either at
     * construction (loader path) or when the wire's ModelCellClientState is
     * applied (client-cache path); null until one of those happens.
     */
    public String getDepUrl() {
        return depUrl;
    }

    /**
     * Apply the server's client state. Beyond the base wiring (name, cell
     * components), a model cell pulls its deployed-model URL out of a
     * {@link ModelCellClientState} so the URL crosses the shared wire format
     * rather than being hard-coded at construction. A null URL leaves any
     * loader-supplied value in place.
     */
    @Override
    public void setClientState(CellClientState configData) {
        super.setClientState(configData);
        if (configData instanceof ModelCellClientState model) {
            String url = model.getDeployedModelURL();
            if (url != null) {
                this.depUrl = url;
            }
        }
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
