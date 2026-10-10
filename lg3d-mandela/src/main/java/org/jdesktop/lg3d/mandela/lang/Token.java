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
package org.jdesktop.lg3d.mandela.lang;

import java.util.List;

/**
 * One immutable lexical token, with the position needed to report a problem back
 * to a human.
 *
 * <p>Positions are 1-based line and column pairs, the convention every JVM
 * compiler (javac, kotlinc) and the desktop's Espresso editor use for gutter
 * markers, so a Mandela {@link LangException} maps onto an editor diagnostic
 * without arithmetic.</p>
 *
 * <p>Token payloads are typed by kind: {@link #text()} carries an identifier,
 * keyword or string body; {@link #longValue()} and {@link #doubleValue()} carry
 * numeric literals; {@link #parts()} carries the pieces of an interpolated
 * string. Unused fields stay at their default, which keeps the record compact
 * and the accessors honest (see the {@code isX()} helpers).</p>
 *
 * @param kind     the lexical class
 * @param text     the lexeme (identifiers, operators) or the decoded body (strings)
 * @param line     the 1-based start line
 * @param column   the 1-based start column
 * @param endColumn the 1-based column just past the token
 * @param longValue the value of an {@link TokenKind#INT} literal
 * @param doubleValue the value of a {@link TokenKind#DOUBLE} literal
 * @param parts    the pieces of an {@link TokenKind#INTERP} literal, otherwise empty
 */
public record Token(TokenKind kind, String text, int line, int column, int endColumn,
                    long longValue, double doubleValue, List<InterpPiece> parts) {

    /** A chunk of an interpolated string: either literal text or an embedded expression. */
    public sealed interface InterpPiece {

        /**
         * A run of literal characters.
         *
         * @param value the decoded text (escapes already applied)
         */
        record Text(String value) implements InterpPiece { }

        /**
         * A {@code ${...}} expression.
         *
         * @param source the inner source text, re-lexed and parsed by the parser
         * @param line   the 1-based line the {@code $} appeared on
         * @param column the 1-based column of the {@code $}
         */
        record Expression(String source, int line, int column) implements InterpPiece { }
    }

    /** Builds an operator / punctuation token. */
    public static Token of(TokenKind kind, String text, int line, int column) {
        return new Token(kind, text, line, column, column + text.length(),
                0L, 0.0d, List.of());
    }

    /** Builds an identifier or keyword token. */
    public static Token identifier(String text, int line, int column) {
        return of(TokenKind.IDENT, text, line, column);
    }

    /** Builds an integer-literal token. */
    public static Token integer(long value, String text, int line, int column) {
        return new Token(TokenKind.INT, text, line, column, column + text.length(),
                value, 0.0d, List.of());
    }

    /** Builds a floating-point-literal token. */
    public static Token floating(double value, String text, int line, int column) {
        return new Token(TokenKind.DOUBLE, text, line, column, column + text.length(),
                0L, value, List.of());
    }

    /** Builds a plain string-literal token. */
    public static Token string(String body, String lexeme, int line, int column) {
        return new Token(TokenKind.STRING, body, line, column,
                column + lexeme.length(), 0L, 0.0d, List.of());
    }

    /** Builds an interpolated string-literal token. */
    public static Token interpolated(List<InterpPiece> parts, int line, int column,
                                     int endColumn) {
        return new Token(TokenKind.INTERP, "", line, column, endColumn,
                0L, 0.0d, List.copyOf(parts));
    }

    /** Builds a line-separator token. */
    public static Token newline(int line, int column) {
        return of(TokenKind.NEWLINE, "\n", line, column);
    }

    /** Builds the end-of-input sentinel. */
    public static Token eof(int line, int column) {
        return of(TokenKind.EOF, "", line, column);
    }

    /** @return true when this token is {@code kind}. */
    public boolean is(TokenKind kind) {
        return kind == null ? false : kind == this.kind;
    }

    /** @return true when this token is an identifier whose text is {@code word}. */
    public boolean isKeyword(String word) {
        return this.kind == TokenKind.IDENT && this.text.equals(word);
    }

    /** @return true when this token can end a statement (newline-suppression rule). */
    public boolean endsStatement() {
        return !kind.continuesStatement();
    }
}
