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
 * A single editor diagnostic reported by an extension granted
 * {@link TextEditorPermission#DIAGNOSE} through
 * {@link EditorContext#reportDiagnostics}. It is an immutable, AWT-free value:
 * the extension builds it (typically from a compiler's own diagnostic stream),
 * and the editor paints it as a gutter marker, an underline over the offending
 * range, and a row in the Problems panel.
 *
 * <p>Positions are 1-based line/column pairs, matching javac and kotlinc output.
 * {@code endLine}/{@code endCol} may be zero to mean "no explicit range", in
 * which case the editor underlines the single character at {@code line}:{@code col}.</p>
 *
 * @param path    the file this diagnostic belongs to, or "" for the current document
 * @param line    the 1-based start line ({@code >= 1})
 * @param col     the 1-based start column ({@code >= 1})
 * @param endLine the 1-based end line, or 0 when the range is a single point
 * @param endCol  the 1-based end column, or 0 when the range is a single point
 * @param kind    the severity
 * @param message the human-readable text shown in the Problems panel
 */
public record Diagnostic(String path, int line, int col,
                         int endLine, int endCol, Kind kind, String message) {

    /** Diagnostic severity, from most to least urgent. */
    public enum Kind {
        /** A compile error; blocks a correct build. */
        ERROR,
        /** A warning; the build may still succeed. */
        WARNING,
        /** An informational note. */
        INFO,
        /** A non-blocking suggestion. */
        HINT
    }

    public Diagnostic {
        path = (path == null) ? "" : path;
        kind = (kind == null) ? Kind.INFO : kind;
        message = (message == null) ? "" : message;
        line = Math.max(1, line);
        col = Math.max(1, col);
        endLine = Math.max(0, endLine);
        endCol = Math.max(0, endCol);
    }

    /**
     * A single-point diagnostic on {@code line}:{@code col} (the common compiler
     * case where only a start position is known).
     */
    public static Diagnostic of(String path, int line, int col,
                                Kind kind, String message) {
        return new Diagnostic(path, line, col, 0, 0, kind, message);
    }

    /** @return the 1-based end line, defaulting to the start line when unset. */
    public int endLineOrStart() {
        return (endLine > 0) ? endLine : line;
    }

    /** @return the 1-based end column, defaulting to the start column when unset. */
    public int endColOrStart() {
        return (endCol > 0) ? endCol : col;
    }

    /** @return a short severity label for the Problems panel. */
    public String severityLabel() {
        return switch (kind) {
            case ERROR -> "Error";
            case WARNING -> "Warning";
            case INFO -> "Info";
            case HINT -> "Hint";
        };
    }
}
