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
 * The lexical classes the Advanced Text Editor's syntax colouriser
 * distinguishes. Kept small and universal on purpose: every built-in
 * {@link Language} maps onto these kinds, and an {@link EditorTheme} carries
 * exactly one colour per kind, so new languages and themes stay trivial to
 * add.
 */
public enum TokenKind {

    /** Ordinary text; also the kind used for merged "everything else" runs. */
    PLAIN,

    /** A reserved word of the language (keywords and built-in type names). */
    KEYWORD,

    /** A string or character literal. */
    STRING,

    /** A line or block comment. */
    COMMENT,

    /** A numeric literal (decimal, hexadecimal, floating point). */
    NUMBER,

    /** A preprocessor / compiler directive line (C family {@code #include}). */
    DIRECTIVE,

    /** A markup tag, including its delimiters and attribute text (XML/HTML). */
    TAG,

    /** A markup document type declaration or processing instruction. */
    DECLARATION
}
