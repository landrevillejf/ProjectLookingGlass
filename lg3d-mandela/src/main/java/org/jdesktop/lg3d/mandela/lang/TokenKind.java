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

/**
 * The lexical classes of the Mandela language.
 *
 * <p>The lexer maps every character of a source file onto exactly one of these
 * kinds, so the parser never looks at raw characters. Keywords are not modelled
 * as separate kinds: they arrive as {@link #IDENT} and the parser tests
 * {@link Token#text()} against {@link Keywords} &mdash; which keeps Mandela's
 * contextual words (such as {@code init} or a field named {@code type}) legal
 * identifiers and makes the reserved-word set a single, editable constant.</p>
 *
 * <p>{@link #NEWLINE} is a real token: Mandela separates statements by line as
 * well as by {@code ;}, so the lexer has to know where lines end. It suppresses
 * the token inside brackets and after a token that cannot end a statement, which
 * is what lets a script break a long expression across lines without any
 * trailing-comma or line-continuation ceremony.</p>
 */
public enum TokenKind {

    /** End of input. */
    EOF,
    /** A line break that ends a statement (suppressed inside brackets). */
    NEWLINE,

    /** An identifier or a keyword; the exact word is in {@link Token#text()}. */
    IDENT,
    /** An integer literal; the value is in {@link Token#longValue()}. */
    INT,
    /** A floating-point literal; the value is in {@link Token#doubleValue()}. */
    DOUBLE,
    /** A string literal with no interpolation; the value is in {@link Token#text()}. */
    STRING,
    /** A string literal containing {@code ${...}}; pieces in {@link Token#parts()}. */
    INTERP,

    /** {@code (} */
    LPAREN,
    /** {@code )} */
    RPAREN,
    /** {@code [} */
    LBRACKET,
    /** {@code ]} */
    RBRACKET,
    /** {@code {} */
    LBRACE,
    /** {@code }} */
    RBRACE,
    /** {@code ,} */
    COMMA,
    /** {@code .} */
    DOT,
    /** {@code :} */
    COLON,
    /** {@code ;} */
    SEMICOLON,
    /** {@code ?} */
    QUESTION,
    /** {@code ?.} safe navigation */
    SAFE_DOT,
    /** {@code ??} elvis */
    ELVIS,
    /** {@code ..} inclusive range */
    DOTDOT,
    /** {@code ..<} exclusive range */
    DOTDOTLT,
    /** {@code |>} pipe */
    PIPE_GT,

    /** {@code =} */
    ASSIGN,
    /** {@code ==} */
    EQ,
    /** {@code !=} */
    NEQ,
    /** {@code <} */
    LT,
    /** {@code <=} */
    LTE,
    /** {@code >} */
    GT,
    /** {@code >=} */
    GTE,
    /** {@code !} */
    BANG,
    /** {@code &&} */
    ANDAND,
    /** {@code ||} */
    OROR,

    /** {@code +} */
    PLUS,
    /** {@code -} */
    MINUS,
    /** {@code *} */
    STAR,
    /** {@code /} */
    SLASH,
    /** {@code %} */
    PERCENT,
    /** {@code **} exponentiation */
    STARSTAR,

    /** {@code +=} */
    PLUSEQ,
    /** {@code -=} */
    MINUSEQ,
    /** {@code *=} */
    STAREQ,
    /** {@code /=} */
    SLASHEQ,
    /** {@code %=} */
    PERCENTEQ,
    /** {@code **=} */
    STARSTAREQ,

    /** {@code ->} function/lambda return arrow */
    ARROW,
    /** {@code =>} match-arm and expression-bodied-function arrow */
    FAT_ARROW;

    /**
     * @return true when this kind is a binary or prefix operator that cannot end
     *         a statement, so a following line break must not split the
     *         expression (the lexer's newline-suppression rule).
     */
    public boolean continuesStatement() {
        switch (this) {
            case NEWLINE:
            case EOF:
                return false;
            case IDENT:
            case INT:
            case DOUBLE:
            case STRING:
            case INTERP:
                return false;
            case RPAREN:
            case RBRACKET:
            case RBRACE:
            case SEMICOLON:
            case COMMA:
                return this == COMMA;
            case LBRACE:
                // `x = {` opens a lambda or map on the next line.
                return true;
            default:
                // Every remaining kind is an operator, an open bracket or a
                // punctuation mark (`.` `:` `?`) that expects a continuation.
                return true;
        }
    }
}
