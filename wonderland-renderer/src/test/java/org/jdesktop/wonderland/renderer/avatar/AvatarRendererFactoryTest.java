/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: the avatar factory's pure dispatch decision.
 *
 * Mirrors ModelRendererFactoryTest -- the whole point of the seam is that
 * the "which CellRenderer for which (RendererType, load result)" logic is
 * assertable without a Jogamp class ever being constructed. The supplier
 * here is a lambda returning a stub, so the tests exercise every dispatch
 * branch headless; the live supplier is only reached by the probe.
 */
package org.jdesktop.wonderland.renderer.avatar;

import org.jdesktop.wonderland.avatars.AvatarPool;
import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AvatarRendererFactoryTest {

    /** A CellRenderer stand-in: never constructs Java 3D, so it headless-runs. */
    private static final class StubRenderer implements CellRenderer {
        @Override public void cellTransformUpdate(CellTransform t) { }
        @Override public void setStatus(CellStatus s, boolean inc) { }
        @Override public CellStatus getStatus() { return CellStatus.DISK; }
    }

    /** A load result wrapping a null document -- the factory only sees isLoaded(). */
    private static AvatarLoadResult loadedStub() {
        return AvatarLoadResult.loaded((ColladaDocument) null,
                AvatarPool.urlFor(AvatarPool.defaultName()));
    }

    private static AvatarLoadResult erroredStub() {
        return AvatarLoadResult.error("no such classpath resource",
                "wla://wonderland-avatars/missing.dae");
    }

    @Test
    void aNullTypeResolvesToNoRenderer() {
        AvatarRendererFactory factory = new AvatarRendererFactory(
                (cell, res) -> new StubRenderer());

        assertThat(factory.rendererFor(null, null, loadedStub())).isNull();
    }

    @Test
    void theExplicitNoneTypeResolvesToNoRenderer() {
        AvatarRendererFactory factory = new AvatarRendererFactory(
                (cell, res) -> new StubRenderer());

        assertThat(factory.rendererFor(Cell.RendererType.NONE, null, loadedStub()))
                .isNull();
    }

    @Test
    void the2dTypeResolvesToNoRendererSinceM6bShipsOnlyTheJogampOne() {
        AvatarRendererFactory factory = new AvatarRendererFactory(
                (cell, res) -> new StubRenderer());

        assertThat(factory.rendererFor(Cell.RendererType.RENDERER_2D, null, loadedStub()))
                .isNull();
    }

    @Test
    void aNullLoadResultYieldsNoRendererEvenForJme() {
        AvatarRendererFactory factory = new AvatarRendererFactory(
                (cell, res) -> new StubRenderer());

        assertThat(factory.rendererFor(Cell.RendererType.RENDERER_JME, null, null))
                .isNull();
    }

    @Test
    void anErroredResolveYieldsNoRenderer() {
        // The plan's "null / errored load" case: the resolver degrades to an
        // error result and the factory turns that into a null renderer, so
        // the cell keeps the pre-M6b "no renderer" behaviour on a bad hint
        // instead of crashing on a missing pool entry.
        AvatarRendererFactory factory = new AvatarRendererFactory(
                (cell, res) -> new StubRenderer());

        assertThat(factory.rendererFor(Cell.RendererType.RENDERER_JME, null, erroredStub()))
                .isNull();
    }

    @Test
    void aLoadedResultUnderJmeDelegatesToTheSupplier() {
        StubRenderer stub = new StubRenderer();
        AvatarRendererFactory factory = new AvatarRendererFactory((cell, res) -> stub);

        CellRenderer rend = factory.rendererFor(
                Cell.RendererType.RENDERER_JME, null, loadedStub());

        assertThat(rend).isSameAs(stub);
    }

    @Test
    void theLoadedResultIsPassedThroughToTheSupplierForTheSceneBuild() {
        // The live AvatarRenderer constructor takes the load result directly;
        // the supplier must see the exact same reference so the probe side
        // does not have to re-resolve the URL.
        AvatarLoadResult loaded = loadedStub();
        final AvatarLoadResult[] seen = new AvatarLoadResult[1];
        AvatarRendererFactory factory = new AvatarRendererFactory((cell, res) -> {
            seen[0] = res;
            return new StubRenderer();
        });

        factory.rendererFor(Cell.RendererType.RENDERER_JME, null, loaded);

        assertThat(seen[0]).isSameAs(loaded);
    }

    @Test
    void theCellIsPassedThroughToTheSupplierForItsServerStateAndIdentity() {
        // A supplier that needs to know which cell it is rendering (for
        // logging, for pick attachment at M6c) gets the exact cell that
        // asked. Nothing in M6b uses it, but the plan asks the seam carry
        // it so the probe side never has to re-look it up.
        StubRenderer stub = new StubRenderer();
        Cell marker = new Cell(new org.jdesktop.wonderland.common.cell.CellID(42L), null) { };
        final Cell[] seen = new Cell[1];
        AvatarRendererFactory factory = new AvatarRendererFactory((cell, res) -> {
            seen[0] = cell;
            return stub;
        });

        factory.rendererFor(Cell.RendererType.RENDERER_JME, marker, loadedStub());

        assertThat(seen[0]).isSameAs(marker);
    }

    @Test
    void aNullSupplierNeverThrowsAndAlwaysYieldsNoRenderer() {
        // A wiring mistake (factory constructed without a supplier) degrades
        // to no-renderer rather than a NullPointerException, mirroring M6a's
        // ModelRendererFactory contract.
        AvatarRendererFactory factory = new AvatarRendererFactory(null);

        assertThat(factory.rendererFor(Cell.RendererType.RENDERER_JME, null, loadedStub()))
                .isNull();
    }

    @Test
    void liveFactoryWiresTheLiveSupplierWithoutTouchingJogampUntilCalled() {
        // AvatarRendererFactory.liveFactory() must be safe to call in a
        // headless JVM -- the supplier is a lambda; only firing it at the
        // probe would touch the AvatarRenderer constructor. Nothing here
        // fires it, so this stays green under CI.
        AvatarRendererFactory factory = AvatarRendererFactory.liveFactory();

        assertThat(factory).isNotNull();
    }
}
