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
package org.jdesktop.lg3d.apps.texteditor;

/**
 * The Advanced Text Editor's extension point: any jar on the desktop
 * classpath that registers an implementation of this interface through
 * {@code META-INF/services} is discovered at startup by
 * {@link ExtensionLoader} and installed once against the live
 * {@link EditorContext}.
 *
 * <p>An extension typically registers one or more toolbar actions (for
 * example "Sort Lines", "Insert Timestamp", "Strip Trailing Whitespace").
 * It runs in the desktop JVM with the editor's own privileges &mdash; this
 * is a developer-facing SPI and a consent boundary, not a sandbox: only
 * install extensions you trust, exactly as with any classpath jar.</p>
 */
public interface TextEditorExtension {

    /** The human-readable extension name shown in the Extensions view. */
    String name();

    /**
     * Called once when the editor starts, on the Swing event dispatch
     * thread. Implementations register their contributions through the
     * context and must not block; any exception they throw is caught and
     * logged by the loader, and the editor continues without them.
     */
    void install(EditorContext context);
}
