/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client M6b: the volatile static slot that holds the currently
 * installed AvatarRendererInstaller.
 *
 * The 2006 client had no seam here -- AvatarCell's constructor called
 * ClientContextJME and got an AvatarJME back by class-loading. That holder
 * is unrecoverable (jME 1.x, gone). The port replaces it with a plain
 * static slot so a boot-time installer from wonderland-renderer can wire
 * the scene-graph avatar in without a dependency cycle. Every test can
 * install a stub or leave the slot null (the pre-M6b "no renderer" quirk
 * is the null case) and uninstall in @AfterEach.
 *
 * Volatile rather than a lock: install() is a one-shot at boot and get()
 * is read on every cell-renderer creation; the writer never needs to
 * coordinate with itself, and a reader either sees the last install or
 * sees null (both are valid states).
 */
package org.jdesktop.wonderland.client.cell.view;

public final class AvatarRendererInstallers {

    private static volatile AvatarRendererInstaller installer;

    private AvatarRendererInstallers() {
        // holder: no instances
    }

    /**
     * Install the given {@code installer}, replacing any prior one. Passing
     * null is the documented way to uninstall and fall back to the pre-M6b
     * "no renderer" behaviour.
     */
    public static void install(AvatarRendererInstaller next) {
        installer = next;
    }

    /**
     * The currently installed installer, or null if nothing is wired up.
     * Callers that expect a non-null installer (the desktop's bootstrap) use
     * {@link #require()} to fail loudly; {@code AvatarCell} uses {@code get()}
     * and treats null as the no-renderer quirk.
     */
    public static AvatarRendererInstaller get() {
        return installer;
    }

    /**
     * The installer, or an {@link IllegalStateException} if none is installed.
     * Used by the desktop's live path where a missing installer is a wiring
     * bug, not the documented headless-test case.
     */
    public static AvatarRendererInstaller require() {
        AvatarRendererInstaller i = installer;
        if (i == null) {
            throw new IllegalStateException(
                    "no AvatarRendererInstaller installed; call "
                            + "WonderlandRendererBootstrap.install() before "
                            + "the desktop builds any avatar cell");
        }
        return i;
    }
}
