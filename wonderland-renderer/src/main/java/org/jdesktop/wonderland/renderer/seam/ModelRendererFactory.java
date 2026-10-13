/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: the seam a Cell uses to pick its renderer, by type.
 *
 * The 2006 ClientContextJME / ViewManager pair was the entry point every cell
 * subclass called from createCellRenderer(); the renderer it handed back was
 * always an AvatarJME / ModelJME / etc. built directly against the com.jme
 * scene stack. That entry point is rebuilt here -- but with the decision
 * (which CellRenderer for which RendererType) separated from the expensive
 * act of constructing the Java 3D scene. The decision is pure, unit-tested
 * headlessly; the actual ModelRenderer construction is delegated to a
 * pluggable {@link RendererSupplier} the desktop installs once, and the tests
 * install a stub. That is the split the plan asked for: no Java 3D object ever
 * touches the headless JVM, but the cell seam still returns the correct
 * reference, so a real (non-null) renderer flows back into the Cell machinery
 * end-to-end when the probe runs on a live 3D canvas.
 */
package org.jdesktop.wonderland.renderer.seam;

import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.modules.asset.ModelLoadResult;
import org.jdesktop.wonderland.renderer.j3d.ModelRenderer;

/**
 * Chooses a {@link CellRenderer} for one {@link Cell.RendererType} given a
 * loaded model. M6a only knows one renderer kind -- the Collada-on-Jogamp
 * {@link ModelRenderer} -- so {@code RENDERER_2D} and {@code NONE} both
 * resolve to {@code null}, matching the 2006 contract that a cell without a
 * registered renderer is legal (ViewCell's base {@code createCellRenderer}
 * returned null too).
 */
public final class ModelRendererFactory {

    /**
     * Produces the actual renderer for one cell + one loaded model. Injected
     * so a headless test can hand back a lightweight stub instead of a
     * scene-graph object; the live desktop installs {@link #liveSupplier()}.
     */
    @FunctionalInterface
    public interface RendererSupplier {
        CellRenderer create(Cell cell, ModelLoadResult result);
    }

    private final RendererSupplier supplier;

    public ModelRendererFactory(RendererSupplier supplier) {
        this.supplier = supplier;
    }

    /**
     * The supplier the real desktop installs: build a {@link ModelRenderer}
     * over the loaded Collada document plus its present-texture inventory.
     * Requires a live Java 3D canvas; never invoke from a headless JVM.
     */
    public static RendererSupplier liveSupplier() {
        return (cell, result) -> new ModelRenderer(result.document(), result.presentTextures());
    }

    /** The factory wired to {@link #liveSupplier()}. */
    public static ModelRendererFactory liveFactory() {
        return new ModelRendererFactory(liveSupplier());
    }

    /**
     * Decide the renderer for one cell's requested type. Returns null when:
     * <ul>
     *   <li>{@code type} is null (the desktop has not picked a renderer kind),</li>
     *   <li>{@code type} is {@code NONE} (explicitly "no renderer"),</li>
     *   <li>{@code type} is {@code RENDERER_2D} (M6a ships only the 3D one),</li>
     *   <li>the model result is null or is not {@link ModelLoadResult#isLoaded()}
     *       -- a failed load yields no renderer, matching the 2006 loader's
     *       "warn and continue" behaviour.</li>
     * </ul>
     * Otherwise delegates to the injected supplier.
     */
    public CellRenderer rendererFor(Cell.RendererType type, Cell cell, ModelLoadResult result) {
        if (type == null || type == Cell.RendererType.NONE) {
            return null;
        }
        if (type != Cell.RendererType.RENDERER_JME) {
            return null;
        }
        if (result == null || !result.isLoaded()) {
            return null;
        }
        if (supplier == null) {
            return null;
        }
        return supplier.create(cell, result);
    }
}
