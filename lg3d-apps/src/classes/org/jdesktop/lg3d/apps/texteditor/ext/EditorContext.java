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
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
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
    private final EditorSinks sinks;

    /**
     * Back-compatible form without the output-console capabilities.
     *
     * @param granted     the permissions the user granted this extension
     * @param showMessage writes a line to the editor status line (always allowed)
     * @param openFile    opens a file dialog (gated on {@link TextEditorPermission#FILE_IO})
     * @param saveFile    saves the current document (gated on FILE_IO)
     */
    public EditorContext(Set<TextEditorPermission> granted, Consumer<String> showMessage,
                         Runnable openFile, Runnable saveFile) {
        this(granted, EditorSinks.legacy(showMessage, openFile, saveFile, null, null));
    }

    /**
     * Back-compatible form with the output-console capabilities only.
     *
     * @param granted     the permissions the user granted this extension
     * @param showMessage writes a line to the editor status line (always allowed)
     * @param openFile    opens a file dialog (gated on {@link TextEditorPermission#FILE_IO})
     * @param saveFile    saves the current document (gated on FILE_IO)
     * @param showOutput  appends a titled block to the editor's south output
     *                    console (gated on FILE_IO; title, body)
     * @param clearOutput empties the output console (gated on FILE_IO)
     */
    public EditorContext(Set<TextEditorPermission> granted, Consumer<String> showMessage,
                         Runnable openFile, Runnable saveFile,
                         BiConsumer<String, String> showOutput, Runnable clearOutput) {
        this(granted, EditorSinks.legacy(showMessage, openFile, saveFile, showOutput, clearOutput));
    }

    /**
     * The full form: every capability delegate is supplied through
     * {@link EditorSinks}, which may leave any surface unset.
     *
     * @param granted the permissions the user granted this extension
     * @param sinks   the editor's capability delegates (may be null for none)
     */
    public EditorContext(Set<TextEditorPermission> granted, EditorSinks sinks) {
        this.granted = (granted == null || granted.isEmpty())
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(granted));
        this.sinks = (sinks != null) ? sinks : EditorSinks.builder().build();
    }

    /** @return true when the extension was granted {@code p}. */
    public boolean has(TextEditorPermission p) {
        return p != null && granted.contains(p);
    }

    /** Writes {@code message} to the editor status line; always allowed. */
    public void showMessage(String message) {
        if (sinks.showMessage != null && message != null) {
            sinks.showMessage.accept(message);
        }
    }

    /** Opens a file dialog; no-op without {@link TextEditorPermission#FILE_IO}. */
    public void openFile() {
        if (has(TextEditorPermission.FILE_IO) && sinks.openFile != null) {
            sinks.openFile.run();
        }
    }

    /** Saves the current document; no-op without FILE_IO. */
    public void saveFile() {
        if (has(TextEditorPermission.FILE_IO) && sinks.saveFile != null) {
            sinks.saveFile.run();
        }
    }

    /**
     * Appends a titled block ({@code title} + {@code body}) to the editor's
     * output console below the editor; no-op without
     * {@link TextEditorPermission#FILE_IO}.
     */
    public void showOutput(String title, String body) {
        if (has(TextEditorPermission.FILE_IO) && sinks.showOutput != null) {
            sinks.showOutput.accept(title, body);
        }
    }

    /** Empties the output console; no-op without FILE_IO. */
    public void clearOutput() {
        if (has(TextEditorPermission.FILE_IO) && sinks.clearOutput != null) {
            sinks.clearOutput.run();
        }
    }

    /**
     * Replaces the editor's diagnostics for {@code path} (gutter markers,
     * squiggles and the Problems panel); no-op without
     * {@link TextEditorPermission#DIAGNOSE}. An empty list clears them.
     */
    public void reportDiagnostics(String path, List<Diagnostic> diagnostics) {
        if (has(TextEditorPermission.DIAGNOSE) && sinks.reportDiagnostics != null) {
            sinks.reportDiagnostics.accept(path,
                    (diagnostics == null) ? List.of() : diagnostics);
        }
    }

    /** Drops the diagnostics for {@code path}; no-op without DIAGNOSE. */
    public void clearDiagnostics(String path) {
        if (has(TextEditorPermission.DIAGNOSE) && sinks.clearDiagnostics != null) {
            sinks.clearDiagnostics.accept(path);
        }
    }

    /**
     * Replaces the Structure panel's outline for {@code path} with
     * {@code symbols}; no-op without {@link TextEditorPermission#DIAGNOSE}. An
     * empty list clears the outline.
     */
    public void showStructure(String path, List<StructureSymbol> symbols) {
        if (has(TextEditorPermission.DIAGNOSE) && sinks.showStructure != null) {
            sinks.showStructure.accept(path,
                    (symbols == null) ? List.of() : symbols);
        }
    }

    /** Appends a line to the Debug panel's output; no-op without DEBUG. */
    public void appendDebugOutput(String line) {
        if (has(TextEditorPermission.DEBUG) && sinks.appendDebugOutput != null
                && line != null) {
            sinks.appendDebugOutput.accept(line);
        }
    }

    /** Sets the Debug panel's state label (running/paused/detached); no-op without DEBUG. */
    public void setDebugState(String state) {
        if (has(TextEditorPermission.DEBUG) && sinks.setDebugState != null && state != null) {
            sinks.setDebugState.accept(state);
        }
    }

    /** Replaces the Debug panel's call-stack rows; no-op without DEBUG. */
    public void showStack(List<String> frames) {
        if (has(TextEditorPermission.DEBUG) && sinks.showStack != null) {
            sinks.showStack.accept((frames == null) ? List.of() : frames);
        }
    }

    /** Replaces the Debug panel's local-variable rows; no-op without DEBUG. */
    public void showLocals(List<String> locals) {
        if (has(TextEditorPermission.DEBUG) && sinks.showLocals != null) {
            sinks.showLocals.accept((locals == null) ? List.of() : locals);
        }
    }

    /** Replaces the Debug panel's breakpoint list; no-op without DEBUG. */
    public void setBreakpoints(List<String> breakpoints) {
        if (has(TextEditorPermission.DEBUG) && sinks.setBreakpoints != null) {
            sinks.setBreakpoints.accept((breakpoints == null) ? List.of() : breakpoints);
        }
    }
}
