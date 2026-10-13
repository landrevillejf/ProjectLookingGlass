/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client M6b: the AvatarRendererInstaller seam holder and the
 * AvatarCell delegation it enables.
 *
 * AvatarCell used to return null from createCellRenderer unconditionally --
 * the M2f "renderer rebuilt at M6, not claimed here" quirk. M6b keeps the
 * null case as the DEFAULT (nothing installed, so a headless test JVM still
 * sees the M2f behaviour) but adds a delegation path when an installer is
 * registered. These cases pin both halves: the holder's install/get/require
 * contract and the cell's decision to null when uninstalled and delegate
 * when installed.
 */
package org.jdesktop.wonderland.client.cell.view;

import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarRendererInstallersTest {

    /** A renderer that does nothing but is distinguishable identity-wise. */
    private static final class StubRenderer implements CellRenderer {
        @Override public void cellTransformUpdate(CellTransform t) { }
        @Override public void setStatus(CellStatus s, boolean inc) { }
        @Override public CellStatus getStatus() { return CellStatus.DISK; }
    }

    @AfterEach
    void clearInstaller() {
        // Never leave a slot filled across tests -- the holder is a static,
        // so a leaked installer would break an adjacent test in the suite.
        AvatarRendererInstallers.install(null);
    }

    @Test
    void theHolderStartsEmptyAndRequireFailsLoudlyUntilBootstrapped() {
        // The pre-M6b desktop state: no installer, so a headless test JVM or
        // a bootstrap-not-yet-run desktop sees the null path.
        AvatarRendererInstallers.install(null);
        assertThat(AvatarRendererInstallers.get()).isNull();
        assertThatThrownBy(AvatarRendererInstallers::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WonderlandRendererBootstrap");
    }

    @Test
    void installIsReplaceableAndUninstallRestoresTheEmptyState() {
        AvatarRendererInstaller first = (cell, type) -> new StubRenderer();
        AvatarRendererInstaller second = (cell, type) -> null;
        AvatarRendererInstallers.install(first);
        assertThat(AvatarRendererInstallers.get()).isSameAs(first);
        AvatarRendererInstallers.install(second);
        assertThat(AvatarRendererInstallers.get()).isSameAs(second);
        AvatarRendererInstallers.install(null);
        assertThat(AvatarRendererInstallers.get()).isNull();
    }

    @Test
    void requireReturnsTheInstalledInstallerOnceBootstrapped() {
        AvatarRendererInstaller installed = (cell, type) -> new StubRenderer();
        AvatarRendererInstallers.install(installed);
        assertThat(AvatarRendererInstallers.require()).isSameAs(installed);
    }

    @Test
    void avatarCellReturnsNullWhenNothingIsInstalled() {
        // The M2f quirk preserved as the null-slot default: an AvatarCell in
        // a bare test JVM still hands back a null renderer for every type.
        AvatarRendererInstallers.install(null);
        AvatarCell cell = new AvatarCell(new CellID(1L), null);

        assertThat(cell.createCellRenderer(Cell.RendererType.RENDERER_JME)).isNull();
        assertThat(cell.createCellRenderer(Cell.RendererType.RENDERER_2D)).isNull();
        assertThat(cell.createCellRenderer(Cell.RendererType.NONE)).isNull();
    }

    @Test
    void avatarCellDelegatesToTheInstalledInstallerForEveryRequestedType() {
        // The installer receives the cell identity and the type verbatim, so
        // it can decide per-type (JME -> live, NONE -> null, ...) without
        // AvatarCell hard-coding any of that.
        StubRenderer jmeRenderer = new StubRenderer();
        AvatarRendererInstaller recording = (cell, type) -> {
            if (type == Cell.RendererType.RENDERER_JME) {
                return jmeRenderer;
            }
            return null;
        };
        AvatarRendererInstallers.install(recording);
        AvatarCell cell = new AvatarCell(new CellID(2L), null);

        assertThat(cell.createCellRenderer(Cell.RendererType.RENDERER_JME))
                .isSameAs(jmeRenderer);
        assertThat(cell.createCellRenderer(Cell.RendererType.RENDERER_2D)).isNull();
        assertThat(cell.createCellRenderer(Cell.RendererType.NONE)).isNull();
    }

    @Test
    void avatarCellPassesItselfAndTheRequestedTypeThroughToTheInstaller() {
        // The seam receives the specific cell that asked, not a generic
        // context, so a live installer can read AvatarCellServerState from
        // it -- which is how AvatarRendererFactory finds the avatarModelUrl
        // in wonderland-renderer's M6b code path.
        AvatarCell cell = new AvatarCell(new CellID(3L), null);
        StubRenderer stub = new StubRenderer();
        AvatarRendererInstaller capture = (c, t) -> {
            assertThat(c).isSameAs(cell);
            assertThat(t).isEqualTo(Cell.RendererType.RENDERER_JME);
            return stub;
        };
        AvatarRendererInstallers.install(capture);

        assertThat(cell.createCellRenderer(Cell.RendererType.RENDERER_JME)).isSameAs(stub);
    }

    @Test
    void anInstallerThrowingPropagatesToTheCellCallerRatherThanSilentlyNulling() {
        // The seam is thin: if the installed factory misbehaves, the exception
        // reaches the Cell base class which logs at SEVERE and leaves the cell
        // without a renderer. This is the wiring-bug case, not a documented
        // behaviour -- we assert the raw pass-through so nobody "improves" the
        // delegation by swallowing here.
        AvatarRendererInstaller bad = (c, t) -> {
            throw new IllegalStateException("installer bug");
        };
        AvatarRendererInstallers.install(bad);
        AvatarCell cell = new AvatarCell(new CellID(4L), null);

        assertThatThrownBy(() -> cell.createCellRenderer(Cell.RendererType.RENDERER_JME))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("installer bug");
    }
}
