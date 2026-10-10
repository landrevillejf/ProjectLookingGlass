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
package org.jdesktop.lg3d.apps.texteditor.ext;

/**
 * A capability an extension declares in its {@link TextEditorManifest} and that
 * the user must grant (through the extension manager) before the matching hook
 * or {@link EditorContext} capability becomes live.
 *
 * <p>Permissions are the text editor's consent boundary: an enabled extension only
 * receives the hooks and context methods its granted permissions cover. This is
 * a user-consent / UX boundary, not a JVM sandbox &mdash; in-process extension
 * code is trusted code, and the manager makes that explicit when approving.</p>
 */
public enum TextEditorPermission {

    /** Read document text and selection via {@link EditorContext#getDocumentText()} and {@link EditorContext#getSelectedText()}. */
    READ,

    /** Write document text and selection via {@link EditorContext#setDocumentText(String)} and {@link EditorContext#replaceSelection(String)}. */
    WRITE,

    /** Open and save files via {@link EditorContext#openFile()} and {@link EditorContext#saveFile()}; also drives the output console via {@link EditorContext#showOutput} and {@link EditorContext#clearOutput}. */
    FILE_IO,

    /** Read or change editor settings. */
    SETTINGS,

    /** Contribute toolbar buttons via {@link TextEditorExtension#toolbarContributions()}. */
    TOOLBAR,

    /**
     * Report per-line diagnostics ({@link Diagnostic}) that the editor paints as
     * gutter markers and underlines and lists in the Problems panel. Reporting a
     * diagnostic never mutates the document, so it is gated separately from
     * {@link #WRITE}.
     */
    DIAGNOSE,

    /**
     * Drive the debugger surface of the editor (breakpoints, call stack, local
     * variables, debug output) through the {@link EditorContext} debug hooks.
     * Grants the extension control of the Debug panel chrome; the editor itself
     * never launches or attaches a VM on the extension's behalf.
     */
    DEBUG
}
