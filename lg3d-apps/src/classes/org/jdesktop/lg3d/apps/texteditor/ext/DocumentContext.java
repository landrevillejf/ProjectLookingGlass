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

import java.util.function.Consumer;

/**
 * A loaded document, handed to extensions through
 * {@link TextEditorExtension#onDocumentOpened} and
 * {@link TextEditorExtension#onDocumentSaved}.
 *
 * <p>The text mutator is supplied by the broker: it is the real document
 * mutator for extensions granted {@link TextEditorPermission#WRITE} and a
 * silent no-op otherwise, so the permission gate is enforced without the
 * extension having to check it.</p>
 */
public final class DocumentContext {

    private final String filePath;
    private final String fileName;
    private final String fullText;
    private final String selectedText;
    private final Consumer<String> textMutator;
    private final Consumer<String> selectionMutator;

    /**
     * @param filePath         the full file path, or null for an unsaved document
     * @param fileName         the file name, or null for an unsaved document
     * @param fullText         the full document text
     * @param selectedText     the currently selected text (empty if nothing selected)
     * @param textMutator      replaces the full document (gated on WRITE)
     * @param selectionMutator replaces the selection (gated on WRITE)
     */
    public DocumentContext(String filePath, String fileName, String fullText, String selectedText,
                          Consumer<String> textMutator, Consumer<String> selectionMutator) {
        this.filePath = (filePath == null) ? "" : filePath;
        this.fileName = (fileName == null) ? "" : fileName;
        this.fullText = (fullText == null) ? "" : fullText;
        this.selectedText = (selectedText == null) ? "" : selectedText;
        this.textMutator = textMutator;
        this.selectionMutator = selectionMutator;
    }

    public String getFilePath() { return filePath; }
    public String getFileName() { return fileName; }
    public String getFullText() { return fullText; }
    public String getSelectedText() { return selectedText; }

    /**
     * Replaces the full document text. A no-op when the extension was
     * not granted {@link TextEditorPermission#WRITE}.
     *
     * @param text the new document text
     */
    public void setFullText(String text) {
        if (text != null && textMutator != null) {
            textMutator.accept(text);
        }
    }

    /**
     * Replaces the selection (or inserts at the caret). A no-op when the
     * extension was not granted {@link TextEditorPermission#WRITE}.
     *
     * @param text the text to insert
     */
    public void replaceSelection(String text) {
        if (text != null && selectionMutator != null) {
            selectionMutator.accept(text);
        }
    }

    @Override
    public String toString() {
        return "DocumentContext[" + fileName + "]";
    }
}
