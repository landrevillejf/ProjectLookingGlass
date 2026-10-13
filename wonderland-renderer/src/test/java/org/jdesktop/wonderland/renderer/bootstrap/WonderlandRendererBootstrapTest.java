/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: the boot installer into the client seam.
 *
 * The whole bootstrap is one static call from the desktop's launcher that
 * fills wonderland-client's AvatarRendererInstallers slot with the live
 * installer. This file does NOT run the no-arg install() -- that would
 * install a supplier whose lambda constructs a real AvatarRenderer (Jogamp
 * classes) -- but it does prove the two things that matter headlessly:
 *
 *   1. install(installer)/uninstall() mutate the shared slot exactly as the
 *      plan promises (idempotent, replaceable, restores the null default).
 *   2. liveInstaller(resolver, factory) composes the plan's chain correctly:
 *      it pulls the URL off the cell's serverState (or defaults when the
 *      state is null / the URL is null), feeds it to the injected resolver,
 *      and hands the resolved result + the requested type to the injected
 *      factory's dispatch. Every branch of that composition is assertable
 *      with a stub resolver and a stub factory.
 */
package org.jdesktop.wonderland.renderer.bootstrap;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.avatars.AvatarPool;
import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.client.cell.view.AvatarCell;
import org.jdesktop.wonderland.client.cell.view.AvatarRendererInstaller;
import org.jdesktop.wonderland.client.cell.view.AvatarRendererInstallers;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.AvatarCellServerState;
import org.jdesktop.wonderland.renderer.avatar.AvatarLoadResolver;
import org.jdesktop.wonderland.renderer.avatar.AvatarLoadResult;
import org.jdesktop.wonderland.renderer.avatar.AvatarRendererFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WonderlandRendererBootstrapTest {

    /** A CellRenderer stand-in: no Jogamp, so headless-safe. */
    private static final class StubRenderer implements CellRenderer {
        @Override public void cellTransformUpdate(CellTransform t) { }
        @Override public void setStatus(CellStatus s, boolean inc) { }
        @Override public CellStatus getStatus() { return CellStatus.DISK; }
    }

    /** A resolver stub that records the URL and returns a canned result. */
    private static final class RecordingResolver extends AvatarLoadResolver {
        final List<String> seen = new ArrayList<>();
        AvatarLoadResult next = AvatarLoadResult.loaded(null, "stub");

        @Override
        public AvatarLoadResult resolve(String url) {
            seen.add(url);
            return next;
        }
    }

    @AfterEach
    void clearSlot() {
        WonderlandRendererBootstrap.uninstall();
    }

    @Test
    void theSlotStartsEmptyAndUninstallPutsItBackAfterEveryTest() {
        WonderlandRendererBootstrap.uninstall();
        assertThat(AvatarRendererInstallers.get()).isNull();
    }

    @Test
    void installWritesTheGivenInstallerToTheSharedSlot() {
        AvatarRendererInstaller stub = (cell, type) -> new StubRenderer();
        WonderlandRendererBootstrap.install(stub);

        assertThat(AvatarRendererInstallers.get()).isSameAs(stub);
    }

    @Test
    void installIsReplaceableAndUninstallReturnsToTheNullPath() {
        AvatarRendererInstaller first = (cell, type) -> new StubRenderer();
        AvatarRendererInstaller second = (cell, type) -> null;

        WonderlandRendererBootstrap.install(first);
        WonderlandRendererBootstrap.install(second);
        assertThat(AvatarRendererInstallers.get()).isSameAs(second);

        WonderlandRendererBootstrap.uninstall();
        assertThat(AvatarRendererInstallers.get()).isNull();
    }

    @Test
    void liveInstallerPullsTheUrlFromTheCellServerStateAndFeedsTheResolver() {
        // The plan's URL-from-state path: a cell with an explicit avatar
        // hint gets that exact string handed to the resolver, and the
        // resolver's result is what reaches the factory's dispatch.
        RecordingResolver resolver = new RecordingResolver();
        resolver.next = AvatarLoadResult.loaded(null, "wla://wonderland-avatars/humanoid-b.dae");
        final AvatarLoadResult[] seenByFactory = new AvatarLoadResult[1];
        AvatarRendererFactory factory = new AvatarRendererFactory((cell, res) -> {
            seenByFactory[0] = res;
            return new StubRenderer();
        });

        AvatarRendererInstaller installer =
                WonderlandRendererBootstrap.liveInstaller(resolver, factory);
        AvatarCell cell = new AvatarCell(new CellID(1L), null);
        cell.setServerState(new AvatarCellServerState("alice"));
        cell.getServerState().setAvatarModelUrl("wla://wonderland-avatars/humanoid-b.dae");

        CellRenderer rend = installer.create(cell, Cell.RendererType.RENDERER_JME);

        assertThat(rend).isNotNull();
        assertThat(resolver.seen).containsExactly("wla://wonderland-avatars/humanoid-b.dae");
        assertThat(seenByFactory[0]).isSameAs(resolver.next);
    }

    @Test
    void liveInstallerFallsBackToANullUrlWhenTheServerStateIsSilent() {
        // The null-default path the plan requires: a wire state that has not
        // set the URL hint sends null into the resolver, which internally
        // substitutes the pool default. The bootstrap never sees the URL,
        // only whether it was set.
        RecordingResolver resolver = new RecordingResolver();
        AvatarRendererFactory factory = new AvatarRendererFactory((cell, res) -> new StubRenderer());

        AvatarRendererInstaller installer =
                WonderlandRendererBootstrap.liveInstaller(resolver, factory);
        AvatarCell cell = new AvatarCell(new CellID(2L), null);
        cell.setServerState(new AvatarCellServerState("bob"));

        installer.create(cell, Cell.RendererType.RENDERER_JME);

        assertThat(resolver.seen).containsExactly((String) null);
    }

    @Test
    void liveInstallerToleratesACellWithNoServerStateAtAll() {
        // A cell constructed by a bare test path (or a pre-M6b session that
        // never delivered a server state) must not NPE -- the bootstrap
        // hands a null URL to the resolver, which is exactly the null path.
        RecordingResolver resolver = new RecordingResolver();
        AvatarRendererFactory factory = new AvatarRendererFactory((cell, res) -> new StubRenderer());

        AvatarRendererInstaller installer =
                WonderlandRendererBootstrap.liveInstaller(resolver, factory);
        AvatarCell cell = new AvatarCell(new CellID(3L), null);

        installer.create(cell, Cell.RendererType.RENDERER_JME);

        assertThat(resolver.seen).containsExactly((String) null);
    }

    @Test
    void liveInstallerForANoneTypeSkipsTheRendererEvenWhenTheResolveLoaded() {
        // The composition rule: whatever the resolver returns, the factory
        // decides per type. NONE and RENDERER_2D yield no renderer -- the
        // installer never constructs the (probe-side) AvatarRenderer even
        // on a loaded pool body.
        RecordingResolver resolver = new RecordingResolver();
        resolver.next = AvatarLoadResult.loaded(null, AvatarPool.urlFor(AvatarPool.defaultName()));
        AvatarRendererFactory factory = new AvatarRendererFactory((cell, res) -> new StubRenderer());

        AvatarRendererInstaller installer =
                WonderlandRendererBootstrap.liveInstaller(resolver, factory);
        AvatarCell cell = new AvatarCell(new CellID(4L), null);

        assertThat(installer.create(cell, Cell.RendererType.NONE)).isNull();
        assertThat(installer.create(cell, Cell.RendererType.RENDERER_2D)).isNull();
    }

    @Test
    void liveInstallerForAJmeTypeWithAnErroredResolveYieldsNoRenderer() {
        // A bad wire hint (missing file) resolves to an error result, the
        // factory dispatches to null, and the cell keeps its "no renderer"
        // behaviour. This is the plan's "warn and continue" chain end-to-end,
        // provable without a Jogamp class.
        RecordingResolver resolver = new RecordingResolver();
        resolver.next = AvatarLoadResult.error("no such classpath resource", "wla://x/missing.dae");
        AvatarRendererFactory factory = new AvatarRendererFactory((cell, res) -> new StubRenderer());

        AvatarRendererInstaller installer =
                WonderlandRendererBootstrap.liveInstaller(resolver, factory);
        AvatarCell cell = new AvatarCell(new CellID(5L), null);
        cell.setServerState(new AvatarCellServerState("carol"));
        cell.getServerState().setAvatarModelUrl("wla://x/missing.dae");

        assertThat(installer.create(cell, Cell.RendererType.RENDERER_JME)).isNull();
        assertThat(resolver.seen).containsExactly("wla://x/missing.dae");
    }

    @Test
    void installingALiveInstallerPopulatesTheSharedSlotWithTheComposedChain() {
        // The end-to-end install path: bootstrap.install(liveInstaller(...))
        // writes the composed installer to the shared client slot, so any
        // subsequent AvatarCell that asks the slot for a renderer gets the
        // resolver + factory chain. Cross-package access to the protected
        // createCellRenderer() is proved in wonderland-client's
        // AvatarRendererInstallersTest -- here we assert the SLOT side.
        RecordingResolver resolver = new RecordingResolver();
        resolver.next = AvatarLoadResult.loaded(null, AvatarPool.urlFor(AvatarPool.defaultName()));
        StubRenderer stub = new StubRenderer();
        AvatarRendererFactory factory = new AvatarRendererFactory((cell, res) -> stub);

        WonderlandRendererBootstrap.install(
                WonderlandRendererBootstrap.liveInstaller(resolver, factory));

        AvatarRendererInstaller installed = AvatarRendererInstallers.get();
        assertThat(installed).isNotNull();
        AvatarCell cell = new AvatarCell(new CellID(6L), null);
        assertThat(installed.create(cell, Cell.RendererType.RENDERER_JME)).isSameAs(stub);
        assertThat(resolver.seen).containsExactly((String) null);
    }
}
