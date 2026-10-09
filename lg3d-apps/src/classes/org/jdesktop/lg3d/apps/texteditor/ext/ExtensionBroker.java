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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.texteditor.ext.ExtensionRegistry.LoadedExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime that drives {@link org.jdesktop.lg3d.apps.texteditor.TextEditorExtension}
 * hooks on behalf of the editor panel. It is the single place that (a) asks the
 * {@link ExtensionRegistry} which extensions are enabled, (b) enforces the
 * granted-permission gate per hook, and (c) isolates failures so a throwing
 * extension is logged and skipped without disturbing the editor or the other
 * extensions.
 *
 * <p>All hooks are called on the EDT. The broker itself is stateless and
 * thread-agnostic.</p>
 */
public final class ExtensionBroker {

    private static final Logger LOG = LoggerFactory.getLogger(ExtensionBroker.class);

    private final ExtensionRegistry registry;
    private final Consumer<String> showMessage;
    private final Runnable openFile;
    private final Runnable saveFile;

    /**
     * @param registry the enabled/grant source
     * @param showMessage delegate for {@link EditorContext#showMessage}
     * @param openFile  delegate for {@link EditorContext#openFile}
     * @param saveFile  delegate for {@link EditorContext#saveFile}
     */
    public ExtensionBroker(ExtensionRegistry registry, Consumer<String> showMessage,
                          Runnable openFile, Runnable saveFile) {
        this.registry = registry;
        this.showMessage = showMessage;
        this.openFile = openFile;
        this.saveFile = saveFile;
    }

    /** Fires {@link org.jdesktop.lg3d.apps.texteditor.TextEditorExtension#onEditorStarted} for enabled extensions. */
    public void notifyStarted() {
        for (LoadedExtension le : registry.enabled()) {
            try {
                le.getExtension().onEditorStarted(contextFor(le));
            } catch (RuntimeException e) {
                LOG.warn("Extension {} onEditorStarted failed; ignored",
                        le.getManifest().getId(), e);
            }
        }
    }

    /** Fires {@link org.jdesktop.lg3d.apps.texteditor.TextEditorExtension#onEditorStopping} for enabled extensions. */
    public void notifyStopping() {
        for (LoadedExtension le : registry.enabled()) {
            try {
                le.getExtension().onEditorStopping(contextFor(le));
            } catch (RuntimeException e) {
                LOG.warn("Extension {} onEditorStopping failed; ignored",
                        le.getManifest().getId(), e);
            }
        }
    }

    /**
     * Fires {@link org.jdesktop.lg3d.apps.texteditor.TextEditorExtension#onDocumentOpened}
     * for enabled extensions with READ permission.
     *
     * @param filePath         the full file path, or null for an unsaved document
     * @param fileName         the file name, or null for an unsaved document
     * @param fullText         the full document text
     * @param selectedText     the currently selected text
     * @param textMutator      replaces the full document (gated on WRITE)
     * @param selectionMutator replaces the selection (gated on WRITE)
     */
    public void notifyDocumentOpened(String filePath, String fileName, String fullText,
                                      String selectedText, Consumer<String> textMutator,
                                      Consumer<String> selectionMutator) {
        for (LoadedExtension le : registry.enabled()) {
            if (!le.has(TextEditorPermission.READ)) {
                continue;
            }
            Consumer<String> textRunner = le.has(TextEditorPermission.WRITE)
                    ? textMutator : s -> { };
            Consumer<String> selectionRunner = le.has(TextEditorPermission.WRITE)
                    ? selectionMutator : s -> { };
            DocumentContext doc = new DocumentContext(filePath, fileName, fullText,
                    selectedText, textRunner, selectionRunner);
            try {
                le.getExtension().onDocumentOpened(doc);
            } catch (RuntimeException e) {
                LOG.warn("Extension {} onDocumentOpened failed; ignored",
                        le.getManifest().getId(), e);
            }
        }
    }

    /**
     * Fires {@link org.jdesktop.lg3d.apps.texteditor.TextEditorExtension#onDocumentSaved}
     * for enabled extensions with READ permission.
     *
     * @param filePath         the full file path, or null for an unsaved document
     * @param fileName         the file name, or null for an unsaved document
     * @param fullText         the full document text
     * @param selectedText     the currently selected text
     * @param textMutator      replaces the full document (gated on WRITE)
     * @param selectionMutator replaces the selection (gated on WRITE)
     */
    public void notifyDocumentSaved(String filePath, String fileName, String fullText,
                                    String selectedText, Consumer<String> textMutator,
                                    Consumer<String> selectionMutator) {
        for (LoadedExtension le : registry.enabled()) {
            if (!le.has(TextEditorPermission.READ)) {
                continue;
            }
            Consumer<String> textRunner = le.has(TextEditorPermission.WRITE)
                    ? textMutator : s -> { };
            Consumer<String> selectionRunner = le.has(TextEditorPermission.WRITE)
                    ? selectionMutator : s -> { };
            DocumentContext doc = new DocumentContext(filePath, fileName, fullText,
                    selectedText, textRunner, selectionRunner);
            try {
                le.getExtension().onDocumentSaved(doc);
            } catch (RuntimeException e) {
                LOG.warn("Extension {} onDocumentSaved failed; ignored",
                        le.getManifest().getId(), e);
            }
        }
    }

    /**
     * Collects toolbar buttons from enabled {@link TextEditorPermission#TOOLBAR}
     * extensions, de-duplicated by contribution id.
     *
     * @return the contributions to render, possibly empty
     */
    public List<ToolbarContribution> toolbarContributions() {
        List<ToolbarContribution> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LoadedExtension le : registry.enabled()) {
            if (!le.has(TextEditorPermission.TOOLBAR)) {
                continue;
            }
            try {
                List<ToolbarContribution> contributions = le.getExtension().toolbarContributions();
                if (contributions == null) {
                    continue;
                }
                for (ToolbarContribution c : contributions) {
                    if (c != null && seen.add(c.getId())) {
                        out.add(c);
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("Extension {} toolbarContributions failed; ignored",
                        le.getManifest().getId(), e);
            }
        }
        return out;
    }

    private EditorContext contextFor(LoadedExtension le) {
        return new EditorContext(le.getGranted(), showMessage, openFile, saveFile);
    }
}
