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

import java.util.ArrayList;
import java.util.List;

/**
 * The Mandela scanner: source text in, {@link Token} list out.
 *
 * <p>The lexer is single-pass and context-free &mdash; it never decides what a
 * token means, only where it starts and ends. It does track bracket depth,
 * because Mandela separates statements on line breaks as well as with {@code ;}
 * and must not turn the newline inside {@code [ ... ]} or {@code f( ... )} into a
 * statement boundary. A line break directly after a token that cannot end a
 * statement (an operator, a comma, a dot) is suppressed for the same reason,
 * which is what makes a multi-line expression readable without continuation
 * syntax.</p>
 *
 * <p>String literals are the one place the scanner has to parse: an
 * {@code "...${x}..."} literal becomes a {@link TokenKind#INTERP} token carrying
 * literal {@link Token.InterpPiece.Text} runs and {@link
 * Token.InterpPiece.Expression} runs whose text is the exact inner source. The
 * parser re-lexes that inner text, so interpolation nests to any depth and a
 * nested string may itself interpolate.</p>
 *
 * <p>String forms:
 * <ul>
 *   <li>{@code "..."} &mdash; backslash escapes and {@code $} interpolation, one line</li>
 *   <li>{@code '...'} &mdash; escapes, <em>no</em> interpolation, one line</li>
 *   <li>{@code """..."""} &mdash; multi-line, interpolation, no backslash escapes</li>
 *   <li>{@code '''...'''} &mdash; multi-line raw: neither interpolation nor escapes</li>
 * </ul>
 *
 * <p>Instances are not thread-safe: one lexer per source, used once.</p>
 */
public final class Lexer {

    private final String src;
    private final String sourceName;

    private int pos;
    private int line = 1;
    private int lineStart;

    private int depth;
    private TokenKind previous;

    /**
     * Creates a scanner over {@code source}.
     *
     * @param source     the script text; never mutated
     * @param sourceName the logical name used in diagnostics (path, buffer title,
     *                   {@code <repl>}); may be null
     */
    public Lexer(String source, String sourceName) {
        if (source == null) {
            throw new IllegalArgumentException("source is null");
        }
        this.src = source;
        this.sourceName = sourceName;
    }

    /**
     * Scans the whole source.
     *
     * @return the token list, always terminated by one {@link TokenKind#EOF} token
     * @throws LangException on an unterminated string or block comment, an
     *         invalid escape, a malformed number, or an unknown character
     */
    public List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        skipShebang();
        while (true) {
            Token t = next();
            out.add(t);
            if (t.is(TokenKind.EOF)) {
                return out;
            }
            previous = t.kind();
        }
    }

    // -- core loop -----------------------------------------------------------

    private Token next() {
        while (true) {
            skipSpaces();
            if (pos >= src.length()) {
                return Token.eof(line, column());
            }
            char c = src.charAt(pos);

            if (c == '\n' || c == '\r') {
                int tokenLine = line;
                int tokenCol = column();
                breakLine();
                if (depth > 0 || (previous != null && previous.continuesStatement())) {
                    continue;
                }
                return Token.newline(tokenLine, tokenCol);
            }
            if (c == '/' && peek(1) == '/') {
                skipLineComment();
                continue;
            }
            if (c == '/' && peek(1) == '*') {
                skipBlockComment();
                continue;
            }
            if (isDigit(c) || (c == '.' && isDigit(peek(1)))) {
                return number();
            }
            if (c == '"' || c == '\'') {
                return stringLiteral(c);
            }
            if (isIdentStart(c)) {
                return identifier();
            }
            return operator();
        }
    }

    private Token identifier() {
        int start = pos;
        int startLine = line;
        int startCol = column();
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        return Token.identifier(src.substring(start, pos), startLine, startCol);
    }

    private Token number() {
        int startLine = line;
        int startCol = column();
        int start = pos;

        if (src.charAt(pos) == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            pos += 2;
            int digits = pos;
            while (pos < src.length() && (isHexDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            if (pos == digits) {
                throw error(startLine, startCol, "hexadecimal literal has no digits");
            }
            long hex = Long.parseLong(underscores(src.substring(digits, pos)), 16);
            return finishedNumber(Token.integer(hex, src.substring(start, pos),
                    startLine, startCol), start);
        }
        if (src.charAt(pos) == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            pos += 2;
            int digits = pos;
            while (pos < src.length() && (src.charAt(pos) == '0' || src.charAt(pos) == '1'
                    || src.charAt(pos) == '_')) {
                pos++;
            }
            if (pos == digits) {
                throw error(startLine, startCol, "binary literal has no digits");
            }
            long bin = Long.parseLong(underscores(src.substring(digits, pos)), 2);
            return finishedNumber(Token.integer(bin, src.substring(start, pos),
                    startLine, startCol), start);
        }

        while (pos < src.length() && (isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
            pos++;
        }
        boolean floating = false;
        if (pos < src.length() && src.charAt(pos) == '.' && isDigit(peek(1))) {
            floating = true;
            pos++;
            while (pos < src.length() && (isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            int save = pos;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            if (pos < src.length() && isDigit(src.charAt(pos))) {
                floating = true;
                while (pos < src.length() && isDigit(src.charAt(pos))) {
                    pos++;
                }
            } else {
                pos = save;
            }
        }
        String lexeme = src.substring(start, pos);
        String cleaned = underscores(lexeme);
        if (floating) {
            return finishedNumber(
                    Token.floating(Double.parseDouble(cleaned), lexeme, startLine, startCol),
                    start);
        }
        try {
            return finishedNumber(Token.integer(Long.parseLong(cleaned), lexeme,
                    startLine, startCol), start);
        } catch (NumberFormatException e) {
            throw error(startLine, startCol, "integer literal '" + lexeme
                    + "' is outside the 64-bit integer range");
        }
    }

    /** Rejects {@code 123abc}: a number must not run straight into an identifier. */
    private Token finishedNumber(Token token, int start) {
        if (pos < src.length() && isIdentPart(src.charAt(pos))) {
            int end = pos;
            while (end < src.length() && isIdentPart(src.charAt(end))) {
                end++;
            }
            throw error(token.line(), token.column(),
                    "malformed numeric literal '" + src.substring(start, end) + "'");
        }
        return token;
    }

    private Token operator() {
        int startLine = line;
        int startCol = column();
        char c = src.charAt(pos);
        char n1 = peek(1);
        char n2 = peek(2);

        String lexeme;
        TokenKind kind;

        switch (c) {
            case '(':
                depth++;
                lexeme = "(";
                kind = TokenKind.LPAREN;
                break;
            case ')':
                depth = Math.max(0, depth - 1);
                lexeme = ")";
                kind = TokenKind.RPAREN;
                break;
            case '[':
                depth++;
                lexeme = "[";
                kind = TokenKind.LBRACKET;
                break;
            case ']':
                depth = Math.max(0, depth - 1);
                lexeme = "]";
                kind = TokenKind.RBRACKET;
                break;
            case '{':
                // A brace is not a continuation: `{` opens a block, whose statements
                // are separated by line breaks, or a map literal, whose newlines the
                // parser skips itself. Counting it here would silence every newline
                // inside a function body.
                lexeme = "{";
                kind = TokenKind.LBRACE;
                break;
            case '}':
                lexeme = "}";
                kind = TokenKind.RBRACE;
                break;
            case ',':
                lexeme = ",";
                kind = TokenKind.COMMA;
                break;
            case ';':
                lexeme = ";";
                kind = TokenKind.SEMICOLON;
                break;
            case ':':
                lexeme = ":";
                kind = TokenKind.COLON;
                break;
            case '?':
                if (n1 == '?') {
                    lexeme = "??";
                    kind = TokenKind.ELVIS;
                } else if (n1 == '.') {
                    lexeme = "?.";
                    kind = TokenKind.SAFE_DOT;
                } else {
                    lexeme = "?";
                    kind = TokenKind.QUESTION;
                }
                break;
            case '.':
                if (n1 == '.' && n2 == '<') {
                    lexeme = "..<";
                    kind = TokenKind.DOTDOTLT;
                } else if (n1 == '.') {
                    lexeme = "..";
                    kind = TokenKind.DOTDOT;
                } else {
                    lexeme = ".";
                    kind = TokenKind.DOT;
                }
                break;
            case '=':
                if (n1 == '=') {
                    lexeme = "==";
                    kind = TokenKind.EQ;
                } else if (n1 == '>') {
                    lexeme = "=>";
                    kind = TokenKind.FAT_ARROW;
                } else {
                    lexeme = "=";
                    kind = TokenKind.ASSIGN;
                }
                break;
            case '!':
                if (n1 == '=') {
                    lexeme = "!=";
                    kind = TokenKind.NEQ;
                } else {
                    lexeme = "!";
                    kind = TokenKind.BANG;
                }
                break;
            case '<':
                if (n1 == '=') {
                    lexeme = "<=";
                    kind = TokenKind.LTE;
                } else {
                    lexeme = "<";
                    kind = TokenKind.LT;
                }
                break;
            case '>':
                if (n1 == '=') {
                    lexeme = ">=";
                    kind = TokenKind.GTE;
                } else {
                    lexeme = ">";
                    kind = TokenKind.GT;
                }
                break;
            case '&':
                if (n1 == '&') {
                    lexeme = "&&";
                    kind = TokenKind.ANDAND;
                } else {
                    throw error(startLine, startCol,
                            "unexpected '&' (write '&&' or the 'and' word)");
                }
                break;
            case '|':
                if (n1 == '|') {
                    lexeme = "||";
                    kind = TokenKind.OROR;
                } else if (n1 == '>') {
                    lexeme = "|>";
                    kind = TokenKind.PIPE_GT;
                } else {
                    throw error(startLine, startCol,
                            "unexpected '|' (write '||' or the 'or' word)");
                }
                break;
            case '+':
                if (n1 == '=') {
                    lexeme = "+=";
                    kind = TokenKind.PLUSEQ;
                } else {
                    lexeme = "+";
                    kind = TokenKind.PLUS;
                }
                break;
            case '-':
                if (n1 == '=') {
                    lexeme = "-=";
                    kind = TokenKind.MINUSEQ;
                } else if (n1 == '>') {
                    lexeme = "->";
                    kind = TokenKind.ARROW;
                } else {
                    lexeme = "-";
                    kind = TokenKind.MINUS;
                }
                break;
            case '*':
                if (n1 == '*' && n2 == '=') {
                    lexeme = "**=";
                    kind = TokenKind.STARSTAREQ;
                } else if (n1 == '*') {
                    lexeme = "**";
                    kind = TokenKind.STARSTAR;
                } else if (n1 == '=') {
                    lexeme = "*=";
                    kind = TokenKind.STAREQ;
                } else {
                    lexeme = "*";
                    kind = TokenKind.STAR;
                }
                break;
            case '/':
                if (n1 == '=') {
                    lexeme = "/=";
                    kind = TokenKind.SLASHEQ;
                } else {
                    lexeme = "/";
                    kind = TokenKind.SLASH;
                }
                break;
            case '%':
                if (n1 == '=') {
                    lexeme = "%=";
                    kind = TokenKind.PERCENTEQ;
                } else {
                    lexeme = "%";
                    kind = TokenKind.PERCENT;
                }
                break;
            default:
                throw error(startLine, startCol, "unexpected character '" + c + "'");
        }
        pos += lexeme.length();
        return Token.of(kind, lexeme, startLine, startCol);
    }

    // -- strings -------------------------------------------------------------

    private Token stringLiteral(char quote) {
        int startLine = line;
        int startCol = column();
        int start = pos;
        boolean triple = peek(1) == quote && peek(2) == quote;
        String delim = triple
                ? String.valueOf(quote) + quote + quote
                : String.valueOf(quote);
        pos += delim.length();

        boolean escapes = !triple;
        boolean interpolates = quote == '"';

        List<Token.InterpPiece> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();

        while (true) {
            if (pos >= src.length()) {
                throw error(startLine, startCol, "unterminated string literal");
            }
            char c = src.charAt(pos);
            if (src.startsWith(delim, pos)) {
                pos += delim.length();
                break;
            }
            if (c == '\n' || c == '\r') {
                if (!triple) {
                    throw error(startLine, startCol,
                            "unterminated string literal (a one-line string cannot"
                                    + " contain a raw newline; use \"\"\" for a"
                                    + " multi-line string)");
                }
                breakLine();
                literal.append('\n');
                continue;
            }
            if (escapes && c == '\\') {
                literal.append(escape());
                continue;
            }
            if (interpolates && c == '$') {
                char after = peek(1);
                if (after == '{' || isIdentStart(after)) {
                    if (literal.length() > 0) {
                        parts.add(new Token.InterpPiece.Text(literal.toString()));
                        literal.setLength(0);
                    }
                    int exprLine = line;
                    int exprCol = column();
                    pos++;
                    if (after == '{') {
                        // Step over the brace as well: the body is what lies between
                        // `${` and the `}` that closes it.
                        pos++;
                        String inner = scanInterpolationBody(exprLine, exprCol);
                        parts.add(new Token.InterpPiece.Expression(inner, exprLine, exprCol));
                    } else {
                        int from = pos;
                        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
                            pos++;
                        }
                        parts.add(new Token.InterpPiece.Expression(
                                src.substring(from, pos), exprLine, exprCol));
                    }
                    continue;
                }
                literal.append('$');
                pos++;
                continue;
            }
            literal.append(c);
            pos++;
        }

        if (literal.length() > 0) {
            parts.add(new Token.InterpPiece.Text(literal.toString()));
        }
        boolean interpolated = false;
        for (Token.InterpPiece piece : parts) {
            if (piece instanceof Token.InterpPiece.Expression) {
                interpolated = true;
                break;
            }
        }
        if (!interpolated) {
            String body = parts.isEmpty() ? "" : ((Token.InterpPiece.Text) parts.get(0)).value();
            return Token.string(body, src.substring(start, pos), startLine, startCol);
        }
        return Token.interpolated(parts, startLine, startCol, column());
    }

    /**
     * Scans the text between {@code ${} and its matching brace, tracking bracket
     * depth and stepping over nested string literals so a nested {@code "}
     * containing braces cannot end the interpolation early.
     *
     * @param openLine the line of the {@code $}
     * @param openCol  the column of the {@code $}
     * @return the inner source text, without the braces
     */
    private String scanInterpolationBody(int openLine, int openCol) {
        int from = pos;
        int nested = 1;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n' || c == '\r') {
                breakLine();
                continue;
            }
            if (c == '"' || c == '\'') {
                skipNestedString();
                continue;
            }
            if (c == '{' || c == '(' || c == '[') {
                nested++;
            } else if (c == '}' || c == ')' || c == ']') {
                nested--;
                if (nested < 0) {
                    throw error(openLine, openCol,
                            "unbalanced bracket inside string interpolation");
                }
                if (c == '}' && nested == 0) {
                    String inner = src.substring(from, pos);
                    pos++;
                    return inner;
                }
            }
            pos++;
        }
        throw error(openLine, openCol,
                "unterminated string interpolation: expected '}' to close the expression");
    }

    /** Steps over a string literal nested inside {@code ${ ... }}. */
    private void skipNestedString() {
        int startLine = line;
        int startCol = column();
        char quote = src.charAt(pos);
        pos++;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n' || c == '\r') {
                throw error(startLine, startCol,
                        "unterminated string inside a string interpolation");
            }
            if (c == '\\') {
                pos += 2;
                continue;
            }
            pos++;
            if (c == quote) {
                return;
            }
        }
        throw error(startLine, startCol, "unterminated string inside a string interpolation");
    }

    /** Reads the escape sequence at the cursor and returns the characters it denotes. */
    private String escape() {
        int escLine = line;
        int escCol = column();
        pos++;
        if (pos >= src.length()) {
            throw error(escLine, escCol, "unterminated escape sequence");
        }
        char c = src.charAt(pos++);
        switch (c) {
            case 'n':
                return "\n";
            case 't':
                return "\t";
            case 'r':
                return "\r";
            case 'b':
                return "\b";
            case 'f':
                return "\f";
            case '0':
                return "\0";
            case '\\':
                return "\\";
            case '"':
                return "\"";
            case '\'':
                return "'";
            case '$':
                return "$";
            case 'u': {
                if (pos + 4 > src.length()) {
                    throw error(escLine, escCol, "'\\u' needs four hexadecimal digits");
                }
                String hex = src.substring(pos, pos + 4);
                for (int i = 0; i < hex.length(); i++) {
                    if (!isHexDigit(hex.charAt(i))) {
                        throw error(escLine, escCol, "invalid unicode escape '\\u" + hex + "'");
                    }
                }
                pos += 4;
                return String.valueOf((char) Integer.parseInt(hex, 16));
            }
            default:
                throw error(escLine, escCol, "unknown escape sequence '\\" + c + "'");
        }
    }

    // -- trivia -------------------------------------------------------------

    private void skipSpaces() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\f') {
                pos++;
            } else if (c == '\r' && peek(1) != '\n') {
                pos++;
            } else {
                return;
            }
        }
    }

    private void skipLineComment() {
        while (pos < src.length() && src.charAt(pos) != '\n' && src.charAt(pos) != '\r') {
            pos++;
        }
    }

    private void skipBlockComment() {
        int startLine = line;
        int startCol = column();
        pos += 2;
        while (true) {
            if (pos >= src.length()) {
                throw error(startLine, startCol, "unterminated block comment");
            }
            if (src.charAt(pos) == '*' && peek(1) == '/') {
                pos += 2;
                return;
            }
            if (src.charAt(pos) == '\n' || src.charAt(pos) == '\r') {
                breakLine();
            } else {
                pos++;
            }
        }
    }

    /** Ignores a {@code #!} header so a script can be made executable. */
    private void skipShebang() {
        if (src.startsWith("#!")) {
            skipLineComment();
            if (pos < src.length() && (src.charAt(pos) == '\n' || src.charAt(pos) == '\r')) {
                breakLine();
            }
        }
    }

    // -- helpers ------------------------------------------------------------

    /** Consumes one line break ({@code \n}, {@code \r\n} or a lone {@code \r}). */
    private void breakLine() {
        char c = src.charAt(pos);
        pos++;
        if (c == '\r' && pos < src.length() && src.charAt(pos) == '\n') {
            pos++;
        }
        line++;
        lineStart = pos;
    }

    private int column() {
        return pos - lineStart + 1;
    }

    private char peek(int offset) {
        int i = pos + offset;
        return (i < src.length()) ? src.charAt(i) : '\0';
    }

    private LangException error(int lineNo, int col, String message) {
        return new LangException(sourceName, lineNo, col, message);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String underscores(String lexeme) {
        return lexeme.replace("_", "");
    }
}
