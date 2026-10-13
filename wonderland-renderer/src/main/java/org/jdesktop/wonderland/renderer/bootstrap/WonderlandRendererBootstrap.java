/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: install the live avatar renderer into the client
 * seam exactly once.
 *
 * The 2006 desktop reached the avatar renderer through ClientContextJME, a
 * god-object the port never rebuilt. This is the minimal shape that class
 * provided: a single static entry point the launcher calls during boot to
 * register a live installer into wonderland-client's AvatarRendererInstallers
 * slot. Nothing in the check task invokes it -- running it would construct
 * Java 3D objects in a headless JVM -- but its install/replace/uninstall
 * decision tree IS headless-tested by WonderlandRendererBootstrapTest, which
 * passes an injected AvatarRendererInstaller factory function so the boot
 * sequence is provable without touching Jogamp.
 *
 * Idempotency rule: install() called twice replaces the slot with an
 * equivalent live installer; that is legal and cheap (the holder is volatile
 * and every read grabs the latest reference). uninstall() puts back the
 * pre-M6b null path so the "no renderer" quirk the client tests rely on is
 * reachable again.
 */
package org.jdesktop.wonderland.renderer.bootstrap;

import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.view.AvatarCell;
import org.jdesktop.wonderland.client.cell.view.AvatarRendererInstaller;
import org.jdesktop.wonderland.client.cell.view.AvatarRendererInstallers;
import org.jdesktop.wonderland.common.cell.state.AvatarCellServerState;
import org.jdesktop.wonderland.renderer.avatar.AvatarLoadResolver;
import org.jdesktop.wonderland.renderer.avatar.AvatarLoadResult;
import org.jdesktop.wonderland.renderer.avatar.AvatarRendererFactory;

public final class WonderlandRendererBootstrap {

    private WonderlandRendererBootstrap() {
    }

    /**
     * Install the live avatar-renderer installer built from the given
     * resolver and factory. The desktop launcher calls this once with
     * {@link #liveInstaller()} at boot; tests inject a stub pair to prove
     * the sequence without Jogamp.
     */
    public static void install(AvatarRendererInstaller installer) {
        AvatarRendererInstallers.install(installer);
    }

    /** The installer a live desktop uses: reads the pool, resolves, dispatches. */
    public static AvatarRendererInstaller liveInstaller() {
        return liveInstaller(new AvatarLoadResolver(), AvatarRendererFactory.liveFactory());
    }

    /**
     * The installer with injected resolver + factory. The composition rule
     * is exactly what the plan asked for: pull the (optional) URL off the
     * cell's serverState, resolve through the pool, hand the outcome to the
     * factory's per-type dispatch.
     */
    public static AvatarRendererInstaller liveInstaller(AvatarLoadResolver resolver,
                                                        AvatarRendererFactory factory) {
        return (AvatarCell cell, Cell.RendererType type) -> {
            AvatarCellServerState state = cell == null ? null : cell.getServerState();
            String url = state == null ? null : state.getAvatarModelUrl();
            AvatarLoadResult result = resolver.resolve(url);
            return factory.rendererFor(type, cell, result);
        };
    }

    /** Convenience for a fresh default install (uses {@link #liveInstaller()}). */
    public static void install() {
        install(liveInstaller());
    }

    /** Restore the pre-M6b null path. Every cell's createCellRenderer returns null. */
    public static void uninstall() {
        AvatarRendererInstallers.install(null);
    }
}
