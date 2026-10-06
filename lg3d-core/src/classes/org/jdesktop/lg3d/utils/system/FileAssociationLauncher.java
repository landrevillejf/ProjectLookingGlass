/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.utils.system;

import java.nio.file.Path;

/**
 * Handles opening a file with one of the desktop's <em>own</em> applications,
 * the in-JVM counterpart of an external command in
 * {@link org.jdesktop.lg3d.utils.prefs.FileAssociations}.
 *
 * <p>{@link Opener} is a low-level utility that cannot itself host a Swing
 * application inside the desktop, so the 2D desktop registers one of these at
 * start-up ({@link Opener#setFileAssociationLauncher}). When a configured
 * association names an internal application ({@code java ...} / {@code swingapp
 * ...}), {@code Opener} offers it to the launcher; when the launcher is absent
 * (no desktop running) or declines (the command is really an external
 * executable), {@code Opener} runs the command itself as a child process. This
 * keeps {@code utils.system} free of any dependency on the Swing desktop.</p>
 */
@FunctionalInterface
public interface FileAssociationLauncher {

    /**
     * Opens {@code file} with the internal application named by {@code command}.
     *
     * @param command the handler command (a start-menu descriptor command)
     * @param file    the file to open
     * @return true if this launcher handled the command; false to let
     *         {@link Opener} run it as an external process instead
     */
    boolean launch(String command, Path file);
}
