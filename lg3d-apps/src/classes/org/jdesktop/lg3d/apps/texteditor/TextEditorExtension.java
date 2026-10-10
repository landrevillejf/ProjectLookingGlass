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

import java.util.List;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The Text Editor extension SPI. Developers implement this interface and
 * register the implementation with {@code META-INF/services} (either on the
 * application classpath for a built-in, or inside a jar dropped into
 * {@code ~/.lg3d/texteditor/extensions} for a third-party extension); the
 * editor discovers it with {@link java.util.ServiceLoader}.
 *
 * <p>Every hook except {@link #manifest()} has a sensible default, so an
 * extension implements only the behaviour it needs. Hooks are invoked by the
 * {@code ExtensionBroker} on the EDT and are individually guarded: an extension
 * that throws is logged and skipped without disturbing the editor or the other
 * extensions.</p>
 *
 * <p>Capabilities are gated on the {@link org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission
 * permissions} the user granted in the extension manager; see {@link EditorContext}.</p>
 */
public interface TextEditorExtension {

    /**
     * @return this extension's identity, blurb and required permissions; never null
     */
    TextEditorManifest manifest();

    /**
     * The human-readable group this extension's toolbar actions are filed under
     * in the extension manager (for example {@code "Text"}, {@code "Code"},
     * {@code "Java/Kotlin"}, {@code "Web"}). The editor uses it only to group the
     * contributed actions; it never gates behaviour. Blank or {@code null}
     * values are normalised to {@code "General"}.
     *
     * @return the category label; {@code "General"} by default
     */
    default String category() {
        return "General";
    }

    /**
     * Called once on the EDT after the editor is up and this extension is
     * enabled, with a capability facade scoped to the granted permissions.
     *
     * @param ctx the editor capability facade
     */
    default void onEditorStarted(EditorContext ctx) { }

    /**
     * Called on the EDT when the editor is shutting down, so the extension can
     * release resources.
     *
     * @param ctx the editor capability facade
     */
    default void onEditorStopping(EditorContext ctx) { }

    /**
     * Called after a document is opened or a new tab is created. Requires
     * {@link org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission#READ}; write operations
     * through {@link DocumentContext} require {@link org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission#WRITE}.
     *
     * @param doc the loaded document
     */
    default void onDocumentOpened(DocumentContext doc) { }

    /**
     * Called on the EDT after the current document's text has changed, debounced
     * to the editor's idle timer (so it does not fire on every keystroke) and
     * re-fired once the user switches tabs. Requires
     * {@link org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission#READ}. Live
     * analysis extensions (diagnostics, structure) refresh here.
     *
     * @param doc the changed document, with the current caret/selection geometry
     */
    default void onDocumentChanged(DocumentContext doc) { }

    /**
     * Called after a document is saved. Requires
     * {@link org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission#READ}.
     *
     * @param doc the saved document
     */
    default void onDocumentSaved(DocumentContext doc) { }

    /**
     * Toolbar buttons this extension contributes. Requires
     * {@link org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission#TOOLBAR}; rendered on the EDT.
     *
     * @return the contributions, empty by default
     */
    default List<ToolbarContribution> toolbarContributions() {
        return List.of();
    }
}
