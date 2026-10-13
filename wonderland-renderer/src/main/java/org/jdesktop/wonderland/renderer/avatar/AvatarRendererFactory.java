/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: the seam an AvatarCell uses to pick its renderer.
 *
 * Same shape as M6a's ModelRendererFactory: a pure decision (given the
 * requested RendererType and a resolved AvatarLoadResult, which
 * CellRenderer?) separated from the expensive Java 3D scene-graph build.
 * The headless suite drives the decision through an injected
 * {@link RendererSupplier} that returns a stub, so the dispatch cases
 * (null type / NONE / RENDERER_2D / null result / errored result /
 * loaded result) are all exercised without a Jogamp class ever being
 * constructed. The desktop installs {@link #liveSupplier()} which calls
 * the {@code AvatarRenderer} constructor -- that half is probe-only.
 *
 * The pool's default resolution -- "when the wire URL is null, use the
 * pool default" -- is not this class's job; that lives in {@link
 * AvatarLoadResolver}. The factory only sees the outcome, so the plan's
 * "rendererFor returns null for null / errored load" case is exactly a
 * null-or-not-loaded {@link AvatarLoadResult} handed in by the caller.
 */
package org.jdesktop.wonderland.renderer.avatar;

import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellRenderer;

/** Chooses a {@link CellRenderer} for one avatar cell + one resolved load. */
public final class AvatarRendererFactory {

    /**
     * Produces the live renderer for one cell + one resolved avatar load.
     * Injected so a headless test hands back a lightweight stub; the live
     * desktop installs {@link #liveSupplier()}.
     */
    @FunctionalInterface
    public interface RendererSupplier {
        CellRenderer create(Cell cell, AvatarLoadResult result);
    }

    private final RendererSupplier supplier;

    public AvatarRendererFactory(RendererSupplier supplier) {
        this.supplier = supplier;
    }

    /**
     * The supplier the real desktop installs: build a live {@link
     * AvatarRenderer} over the resolved Collada document. Requires the Java
     * 3D native stack; never invoke from a headless JVM.
     */
    public static RendererSupplier liveSupplier() {
        return (cell, result) -> new AvatarRenderer(result);
    }

    /** The factory wired to {@link #liveSupplier()}. */
    public static AvatarRendererFactory liveFactory() {
        return new AvatarRendererFactory(liveSupplier());
    }

    /**
     * Decide the renderer for one cell's requested type. Returns null when
     * <ul>
     *   <li>{@code type} is null (no renderer kind picked yet),</li>
     *   <li>{@code type} is {@code NONE} (explicitly "no renderer"),</li>
     *   <li>{@code type} is {@code RENDERER_2D} (M6b ships only the 3D one),</li>
     *   <li>the load result is null or is not {@link AvatarLoadResult#isLoaded()}
     *       -- a failed pool resolve yields no renderer, mirroring M6a's
     *       2006 "warn and continue" behaviour,</li>
     *   <li>no supplier is installed (a wiring mistake degrades to no
     *       renderer rather than a crash).</li>
     * </ul>
     * Otherwise delegates to the injected supplier.
     */
    public CellRenderer rendererFor(Cell.RendererType type, Cell cell, AvatarLoadResult result) {
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
