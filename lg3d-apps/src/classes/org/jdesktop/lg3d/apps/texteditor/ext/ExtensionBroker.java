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
import java.util.function.BiConsumer;
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
    private final EditorSinks sinks;

    /**
     * Back-compatible constructor: the legacy status/open/save/output delegates,
     * with no diagnostics or debug surface wired.
     */
    public ExtensionBroker(ExtensionRegistry registry, Consumer<String> showMessage,
                          Runnable openFile, Runnable saveFile,
                          BiConsumer<String, String> showOutput, Runnable clearOutput) {
        this(registry, EditorSinks.legacy(showMessage, openFile, saveFile, showOutput, clearOutput));
    }

    /**
     * @param registry the enabled/grant source
     * @param sinks    the editor's capability delegates (may leave any unset)
     */
    public ExtensionBroker(ExtensionRegistry registry, EditorSinks sinks) {
        this.registry = registry;
        this.sinks = (sinks != null) ? sinks : EditorSinks.builder().build();
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
        notifyDocumentOpened(filePath, fileName, fullText, selectedText,
                0, 0, 0, 0, 0, textMutator, selectionMutator);
    }

    /**
     * Like {@link #notifyDocumentOpened(String, String, String, String, Consumer, Consumer)}
     * but also carries the caret/selection geometry for position-aware extensions.
     */
    public void notifyDocumentOpened(String filePath, String fileName, String fullText,
                                      String selectedText, int caretOffset, int caretLine,
                                      int caretColumn, int selectionStart, int selectionEnd,
                                      Consumer<String> textMutator, Consumer<String> selectionMutator) {
        dispatch("onDocumentOpened", filePath, fileName, fullText, selectedText,
                caretOffset, caretLine, caretColumn, selectionStart, selectionEnd,
                textMutator, selectionMutator,
                (ext, doc) -> ext.onDocumentOpened(doc));
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
        notifyDocumentSaved(filePath, fileName, fullText, selectedText,
                0, 0, 0, 0, 0, textMutator, selectionMutator);
    }

    /**
     * Like {@link #notifyDocumentSaved(String, String, String, String, Consumer, Consumer)}
     * but also carries the caret/selection geometry.
     */
    public void notifyDocumentSaved(String filePath, String fileName, String fullText,
                                    String selectedText, int caretOffset, int caretLine,
                                    int caretColumn, int selectionStart, int selectionEnd,
                                    Consumer<String> textMutator, Consumer<String> selectionMutator) {
        dispatch("onDocumentSaved", filePath, fileName, fullText, selectedText,
                caretOffset, caretLine, caretColumn, selectionStart, selectionEnd,
                textMutator, selectionMutator,
                (ext, doc) -> ext.onDocumentSaved(doc));
    }

    /**
     * Fires {@link org.jdesktop.lg3d.apps.texteditor.TextEditorExtension#onDocumentChanged}
     * for enabled extensions with READ permission, on the debounced edit signal.
     */
    public void notifyDocumentChanged(String filePath, String fileName, String fullText,
                                      String selectedText, int caretOffset, int caretLine,
                                      int caretColumn, int selectionStart, int selectionEnd,
                                      Consumer<String> textMutator, Consumer<String> selectionMutator) {
        dispatch("onDocumentChanged", filePath, fileName, fullText, selectedText,
                caretOffset, caretLine, caretColumn, selectionStart, selectionEnd,
                textMutator, selectionMutator,
                (ext, doc) -> ext.onDocumentChanged(doc));
    }

    /**
     * Shared dispatch: for each enabled READ extension, builds a permission-gated
     * {@link DocumentContext} and invokes {@code hook}, isolating failures so one
     * throwing extension never disturbs the editor or the other extensions.
     */
    private void dispatch(String hookName, String filePath, String fileName, String fullText,
                          String selectedText, int caretOffset, int caretLine, int caretColumn,
                          int selectionStart, int selectionEnd,
                          Consumer<String> textMutator, Consumer<String> selectionMutator,
                          BiConsumer<org.jdesktop.lg3d.apps.texteditor.TextEditorExtension,
                                  DocumentContext> hook) {
        for (LoadedExtension le : registry.enabled()) {
            if (!le.has(TextEditorPermission.READ)) {
                continue;
            }
            Consumer<String> textRunner = le.has(TextEditorPermission.WRITE)
                    ? textMutator : s -> { };
            Consumer<String> selectionRunner = le.has(TextEditorPermission.WRITE)
                    ? selectionMutator : s -> { };
            DocumentContext doc = new DocumentContext(filePath, fileName, fullText,
                    selectedText, caretOffset, caretLine, caretColumn,
                    selectionStart, selectionEnd, textRunner, selectionRunner);
            try {
                hook.accept(le.getExtension(), doc);
            } catch (RuntimeException e) {
                LOG.warn("Extension {} {} failed; ignored",
                        le.getManifest().getId(), hookName, e);
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
        for (ContributedAction ca : categorizedActions()) {
            out.add(ca.contribution());
        }
        return out;
    }

    /**
     * One toolbar contribution paired with the category and display name of the
     * extension that contributed it, so the manager can group actions.
     *
     * @param category     the owning extension's {@code category()} (never blank)
     * @param extension    the owning extension's display name
     * @param contribution the contribution itself
     */
    public record ContributedAction(String category, String extension,
                                    ToolbarContribution contribution) { }

    /**
     * Collects toolbar buttons from enabled {@link TextEditorPermission#TOOLBAR}
     * extensions in registration order, de-duplicated by contribution id, each
     * tagged with the owning extension's {@code category()} and display name so
     * the editor can group them. A null/blank category normalises to
     * {@code "General"}.
     *
     * @return the categorized contributions to render, possibly empty
     */
    public List<ContributedAction> categorizedActions() {
        List<ContributedAction> out = new ArrayList<>();
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
                String category = normalizeCategory(le.getExtension().category());
                String name = le.getManifest().getName();
                for (ToolbarContribution c : contributions) {
                    if (c != null && seen.add(c.getId())) {
                        out.add(new ContributedAction(category, name, c));
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("Extension {} toolbarContributions failed; ignored",
                        le.getManifest().getId(), e);
            }
        }
        return out;
    }

    private static String normalizeCategory(String category) {
        return (category == null || category.isBlank()) ? "General" : category.trim();
    }

    private EditorContext contextFor(LoadedExtension le) {
        return new EditorContext(le.getGranted(), sinks);
    }
}
