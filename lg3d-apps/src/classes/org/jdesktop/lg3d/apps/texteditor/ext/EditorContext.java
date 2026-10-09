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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The capability facade the editor hands an extension at
 * {@link TextEditorExtension#onEditorStarted}. Every capability is gated on the
 * permissions the user granted: calling a method whose permission was not
 * granted is a silent no-op (and {@link #has(TextEditorPermission)} reports the truth),
 * so an over-reaching extension degrades gracefully instead of misbehaving.
 *
 * <p>The delegates are wired by the broker to the live editor panel; in
 * headless tests they are stubs, which keeps this type AWT-free.</p>
 */
public final class EditorContext {

    private final Set<TextEditorPermission> granted;
    private final Consumer<String> showMessage;
    private final Runnable openFile;
    private final Runnable saveFile;

    /**
     * @param granted    the permissions the user granted this extension
     * @param showMessage writes a line to the editor status line (always allowed)
     * @param openFile   opens a file dialog (gated on {@link TextEditorPermission#FILE_IO})
     * @param saveFile   saves the current document (gated on FILE_IO)
     */
    public EditorContext(Set<TextEditorPermission> granted, Consumer<String> showMessage,
                         Runnable openFile, Runnable saveFile) {
        this.granted = (granted == null || granted.isEmpty())
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(granted));
        this.showMessage = showMessage;
        this.openFile = openFile;
        this.saveFile = saveFile;
    }

    /** @return true when the extension was granted {@code p}. */
    public boolean has(TextEditorPermission p) {
        return p != null && granted.contains(p);
    }

    /** Writes {@code message} to the editor status line; always allowed. */
    public void showMessage(String message) {
        if (showMessage != null && message != null) {
            showMessage.accept(message);
        }
    }

    /** Opens a file dialog; no-op without {@link TextEditorPermission#FILE_IO}. */
    public void openFile() {
        if (has(TextEditorPermission.FILE_IO) && openFile != null) {
            openFile.run();
        }
    }

    /** Saves the current document; no-op without FILE_IO. */
    public void saveFile() {
        if (has(TextEditorPermission.FILE_IO) && saveFile != null) {
            saveFile.run();
        }
    }
}
