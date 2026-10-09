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
 * The editor facade handed to a {@link TextEditorExtension} at install time.
 * Every operation applies to the <em>currently selected tab</em> when the
 * extension's actions run; an editor with no open tab answers with empty
 * strings and ignores mutations rather than throwing.
 *
 * <p>The surface is intentionally minimal &mdash; text, selection, messages
 * and toolbar contributions &mdash; so the contract stays stable across
 * releases. All methods run on the Swing event dispatch thread.</p>
 */
public interface EditorContext {

    /** The full text of the current tab ("" when there is none). */
    String documentText();

    /** Replaces the whole text of the current tab (one undo step). */
    void setDocumentText(String text);

    /** The selected text of the current tab ("" when nothing is selected). */
    String selectedText();

    /** Replaces the selection (or inserts at the caret) with {@code text}. */
    void replaceSelection(String text);

    /** The current tab's file name, or null for an unsaved tab. */
    String currentFileName();

    /** Shows a transient message on the editor's status line. */
    void showMessage(String message);

    /**
     * Registers a toolbar action under the extension's submenu. Labels are
     * de-duplicated per install; the action runs on the EDT when invoked.
     */
    void addAction(String label, Runnable action);
}
