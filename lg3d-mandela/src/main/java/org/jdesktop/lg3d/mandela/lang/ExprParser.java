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
 * The expression half of the Mandela parser: precedence climbing, postfix
 * chains, primaries, lambda/map disambiguation, patterns and blocks.
 *
 * <p>Split from {@link Parser} so each file stays readable: this one owns
 * everything after a statement keyword, {@link Parser} owns statements, error
 * reporting and recovery. The two share the token cursor through the protected
 * helpers here.</p>
 *
 * <p>Precedence, loosest first:</p>
 * <ol>
 *   <li>{@code |>} pipe (left)</li>
 *   <li>{@code ??} elvis (left)</li>
 *   <li>{@code ||} / {@code or} (left, short-circuit)</li>
 *   <li>{@code &&} / {@code and} (left, short-circuit)</li>
 *   <li>comparisons {@code == != < <= > >=} and the {@code is} / {@code !is}
 *       type test (left)</li>
 *   <li>{@code ..} / {@code ..<} range (left)</li>
 *   <li>{@code + -} (left)</li>
 *   <li>{@code * / %} (left)</li>
 *   <li>prefix {@code - ! not}</li>
 *   <li>{@code **} exponentiation (right; {@code -2 ** 2} is {@code -(2 ** 2)})</li>
 *   <li>postfix {@code () [] . ?. as}, then primaries</li>
 * </ol>
 */
abstract class ExprParser {

    /** Every token of the source, with a trailing {@link TokenKind#EOF}. */
    protected List<Token> tokens = List.of();

    /** The cursor into {@link #tokens}. */
    protected int pos;

    /** The logical source name used in diagnostics. */
    protected String sourceName = "<script>";

    /** Sets the token stream and resets the cursor. */
    protected void init(List<Token> tokenList, String name) {
        this.tokens = tokenList;
        this.sourceName = (name == null) ? "<script>" : name;
        this.pos = 0;
    }

    // -- cursor --------------------------------------------------------------

    protected Token current() {
        return tokens.get(pos);
    }

    protected Token peek(int ahead) {
        int i = pos + ahead;
        return (i < tokens.size()) ? tokens.get(i) : tokens.get(tokens.size() - 1);
    }

    protected boolean check(TokenKind kind) {
        return current().is(kind);
    }

    /** @return true when the cursor is on the word {@code word}, without consuming it */
    protected boolean checkWord(String word) {
        return current().isKeyword(word);
    }

    /** @return true at the end of the token stream. */
    protected boolean atEnd() {
        return current().is(TokenKind.EOF);
    }

    protected Token advance() {
        Token t = current();
        if (!t.is(TokenKind.EOF)) {
            pos++;
        }
        return t;
    }

    protected boolean match(TokenKind kind) {
        if (check(kind)) {
            pos++;
            return true;
        }
        return false;
    }

    protected boolean matchWord(String word) {
        if (checkWord(word)) {
            pos++;
            return true;
        }
        return false;
    }

    /** Consumes {@code kind} or reports {@code what} as expected. */
    protected Token expect(TokenKind kind, String what) {
        if (check(kind)) {
            return advance();
        }
        throw error("expected " + what + " but found " + describe(current()));
    }

    /** Consumes the word {@code word} or reports it as expected. */
    protected void expectWord(String word) {
        if (!matchWord(word)) {
            throw error("expected '" + word + "' but found " + describe(current()));
        }
    }

    /** Consumes an identifier that is not a reserved word. */
    protected String expectIdentifier(String role) {
        Token t = current();
        if (!t.is(TokenKind.IDENT)) {
            throw error("expected " + role + " but found " + describe(t));
        }
        if (Keywords.isReserved(t.text())) {
            throw error("'" + t.text() + "' is a reserved word and cannot be a " + role);
        }
        advance();
        return t.text();
    }

    protected LangException error(String message) {
        return new LangException(sourceName, current(), message);
    }

    protected LangException error(String message, Token at) {
        return new LangException(sourceName, at, message);
    }

    protected static String describe(Token t) {
        if (t.is(TokenKind.EOF)) {
            return "end of file";
        }
        if (t.is(TokenKind.NEWLINE)) {
            return "end of line";
        }
        if (t.is(TokenKind.STRING)) {
            return "the string \"" + shorten(t.text()) + "\"";
        }
        if (t.is(TokenKind.INTERP)) {
            return "an interpolated string";
        }
        if (t.is(TokenKind.IDENT)) {
            return "'" + t.text() + "'";
        }
        return "'" + t.text() + "'";
    }

    private static String shorten(String text) {
        return (text.length() <= 16) ? text : text.substring(0, 15) + "\u2026";
    }

    // -- separators ----------------------------------------------------------

    /** Consumes every statement separator (newline or {@code ;}) at the cursor. */
    protected void skipSeparators() {
        while (check(TokenKind.NEWLINE) || check(TokenKind.SEMICOLON)) {
            pos++;
        }
    }

    /**
     * Requires a separator after a statement: a newline, {@code ;}, the end of a
     * block, or end of file. Anything else is a missing separator, which is the
     * single most common Mandela syntax error and so gets its own message.
     */
    protected void expectStatementEnd() {
        if (check(TokenKind.NEWLINE) || check(TokenKind.SEMICOLON)
                || check(TokenKind.RBRACE) || atEnd()) {
            return;
        }
        throw error("expected an end of statement (';' or a line break) but found "
                + describe(current()));
    }

    // -- statements (supplied by the subclass) -------------------------------

    /**
     * Parses one statement. Implemented by {@link Parser}, which owns the
     * statement grammar, error recovery and the contextual rules; this class
     * only needs statements to fill blocks, lambda bodies and {@code defer}.
     *
     * @return the parsed statement, never null
     */
    protected abstract Ast.Stmt statement();

    /**
     * Hook invoked before a {@code this} primary is built, so the subclass can
     * reject it outside a class body. The base accepts it.
     *
     * @param at the {@code this} token
     */
    protected void checkThisContext(Token at) {
        // No context of its own: statements are not parsed here.
    }

    /**
     * Hook invoked before a {@code super} primary is built.
     *
     * @param at the {@code super} token
     */
    protected void checkSuperContext(Token at) {
        // No context of its own: statements are not parsed here.
    }

    /**
     * Hook invoked around every function body &mdash; declaration, lambda and
     * anonymous {@code fun} alike &mdash; so {@code return}, {@code defer} and
     * {@code break} are judged against the body they appear in rather than the
     * surrounding script.
     */
    protected void onFunctionBodyEntered() {
        // No context of its own.
    }

    /** Restores whatever {@link #onFunctionBodyEntered()} recorded. */
    protected void onFunctionBodyExited() {
        // No context of its own.
    }

    // -- blocks --------------------------------------------------------------

    /**
     * Parses {@code { stmt* }} into a {@link Ast.Stmt.Block}. Newlines directly
     * after the opening brace, and before each statement, are skipped.
     */
    protected Ast.Stmt.Block block() {
        Token open = expect(TokenKind.LBRACE, "'{'");
        List<Ast.Stmt> statements = new ArrayList<>();
        while (true) {
            skipSeparators();
            if (check(TokenKind.RBRACE)) {
                advance();
                break;
            }
            if (atEnd()) {
                throw error("unterminated block: expected '}' to close the block"
                        + " opened here", open);
            }
            statements.add(statement());
            expectStatementEnd();
        }
        return new Ast.Stmt.Block(List.copyOf(statements), open.line(), open.column());
    }

    /**
     * Parses the condition of {@code if} / {@code while} / {@code match}.
     *
     * <p>Both spellings are accepted: the parenthesised one Java and C authors
     * reach for, and the Kotlin-style bare expression. A condition is always
     * followed by a {@code &#123;} or a value, never by another statement, so dropping
     * the parentheses cannot make the grammar ambiguous.</p>
     *
     * <p>A condition that is missing altogether gets its own message. Left to the
     * expression parser, {@code if &#123; 1 &#125;} reads its body as a map literal and
     * complains about a map key, which sends the author looking at the wrong half of
     * the line.</p>
     *
     * @param construct the keyword being parsed, for the error text
     * @return the condition expression
     */
    protected Ast.Expr condition(String construct) {
        if (check(TokenKind.LBRACE)) {
            throw error("a '" + construct + "' needs a condition before its '{'"
                    + " but found none", current());
        }
        if (!check(TokenKind.LPAREN)) {
            return expression();
        }
        expect(TokenKind.LPAREN, "'(' after " + construct);
        Ast.Expr condition = expression();
        expect(TokenKind.RPAREN, "')' after the " + construct + " condition");
        return condition;
    }

    // -- expressions ---------------------------------------------------------

    /** Parses one expression at the loosest precedence. */
    protected Ast.Expr expression() {
        return pipe();
    }

    private Ast.Expr pipe() {
        Ast.Expr left = elvis();
        while (check(TokenKind.PIPE_GT)) {
            Token op = advance();
            Ast.Expr right = pipeRight(op, left);
            left = right;
        }
        return left;
    }

    /**
     * Realises {@code left |> ...}. {@code |> f(a)} becomes {@code f(left, a)};
     * {@code |> .m(a)} becomes {@code left.m(a)}; a bare {@code |> m} becomes
     * {@code m(left)}. The left operand is a single node, so it is still
     * evaluated exactly once.
     */
    private Ast.Expr pipeRight(Token op, Ast.Expr left) {
        if (match(TokenKind.DOT) || match(TokenKind.SAFE_DOT)) {
            boolean safe = op.kind() == TokenKind.SAFE_DOT;
            String name = expectIdentifier("a method name after '|>.'");
            if (check(TokenKind.LPAREN)) {
                List<Ast.Expr> args = withTrailingLambda(argumentList());
                return safe
                        ? new Ast.SafeMethodCall(left, name, args, op.line(), op.column())
                        : new Ast.MethodCall(left, name, args, op.line(), op.column());
            }
            if (trailingLambdaFollows()) {
                List<Ast.Expr> args = List.of(lambda());
                return safe
                        ? new Ast.SafeMethodCall(left, name, args, op.line(), op.column())
                        : new Ast.MethodCall(left, name, args, op.line(), op.column());
            }
            return safe
                    ? new Ast.SafeMember(left, name, op.line(), op.column())
                    : new Ast.Member(left, name, op.line(), op.column());
        }
        Ast.Expr callee = unary();
        if (callee instanceof Ast.Call call) {
            List<Ast.Expr> args = new ArrayList<>();
            args.add(left);
            args.addAll(call.args());
            return new Ast.Call(call.callee(), List.copyOf(args),
                    withLeadingPositional(call.argNames()), call.line(), call.column());
        }
        return new Ast.Call(callee, List.of(left), null, op.line(), op.column());
    }

    /** Keeps a call's parallel name list aligned after the pipe inserts its left operand. */
    private static List<String> withLeadingPositional(List<String> names) {
        if (names == null) {
            return null;
        }
        List<String> shifted = new ArrayList<>();
        shifted.add("");
        shifted.addAll(names);
        return List.copyOf(shifted);
    }

    private Ast.Expr elvis() {
        Ast.Expr left = or();
        while (check(TokenKind.ELVIS)) {
            Token op = advance();
            Ast.Expr right = or();
            left = new Ast.Binary("??", left, right, op.line(), op.column());
        }
        return left;
    }

    private Ast.Expr or() {
        Ast.Expr left = and();
        while (check(TokenKind.OROR) || checkWord("or")) {
            Token op = advance();
            Ast.Expr right = and();
            left = new Ast.Binary(orSymbol(op), left, right, op.line(), op.column());
        }
        return left;
    }

    private Ast.Expr and() {
        Ast.Expr left = comparison();
        while (check(TokenKind.ANDAND) || checkWord("and")) {
            Token op = advance();
            Ast.Expr right = comparison();
            left = new Ast.Binary(andSymbol(op), left, right, op.line(), op.column());
        }
        return left;
    }

    private static String orSymbol(Token op) {
        return op.kind() == TokenKind.OROR ? "||" : "or";
    }

    private static String andSymbol(Token op) {
        return op.kind() == TokenKind.ANDAND ? "&&" : "and";
    }

    private Ast.Expr comparison() {
        Ast.Expr left = range();
        while (true) {
            if (checkWord("is")) {
                Token at = advance();
                String type = typeName();
                left = new Ast.Test(left, type, false, at.line(), at.column());
                continue;
            }
            if (check(TokenKind.BANG) && peek(1).isKeyword("is")) {
                Token at = advance();
                advance();
                String type = typeName();
                left = new Ast.Test(left, type, true, at.line(), at.column());
                continue;
            }
            // 'in' and 'not in' sit here rather than in unary() because they are
            // infix, and 'in' is already reserved for the 'for' header, so no
            // program can be reading one meaning while the parser sees the other.
            if (checkWord("in")) {
                Token at = advance();
                left = new Ast.Contains(left, range(), false, at.line(), at.column());
                continue;
            }
            if (checkWord("not") && peek(1).isKeyword("in")) {
                Token at = advance();
                advance();
                left = new Ast.Contains(left, range(), true, at.line(), at.column());
                continue;
            }
            TokenKind kind = current().kind();
            if (kind != TokenKind.EQ && kind != TokenKind.NEQ && kind != TokenKind.LT
                    && kind != TokenKind.LTE && kind != TokenKind.GT
                    && kind != TokenKind.GTE) {
                return left;
            }
            Token op = advance();
            Ast.Expr right = range();
            left = new Ast.Binary(op.text(), left, right, op.line(), op.column());
        }
    }

    private Ast.Expr range() {
        Ast.Expr left = additive();
        if (check(TokenKind.DOTDOT) || check(TokenKind.DOTDOTLT)) {
            Token op = advance();
            Ast.Expr right = additive();
            // 'step' is a soft keyword: it only carries meaning directly after a
            // range, so a program stays free to name a variable or a method 'step'.
            // The additive level is the right binding strength -- '0..8 step 1 + 1'
            // means a stride of 2, and a stray '*' after it is the outer expression's.
            Ast.Expr stride = matchWord("step") ? additive() : null;
            return new Ast.RangeLit(left, right, op.kind() == TokenKind.DOTDOTLT,
                    stride, op.line(), op.column());
        }
        return left;
    }

    private Ast.Expr additive() {
        Ast.Expr left = multiplicative();
        while (check(TokenKind.PLUS) || check(TokenKind.MINUS)) {
            Token op = advance();
            Ast.Expr right = multiplicative();
            left = new Ast.Binary(op.text(), left, right, op.line(), op.column());
        }
        return left;
    }

    private Ast.Expr multiplicative() {
        Ast.Expr left = unary();
        while (check(TokenKind.STAR) || check(TokenKind.SLASH) || check(TokenKind.PERCENT)) {
            Token op = advance();
            Ast.Expr right = unary();
            left = new Ast.Binary(op.text(), left, right, op.line(), op.column());
        }
        return left;
    }

    private Ast.Expr unary() {
        if (check(TokenKind.MINUS) || check(TokenKind.BANG) || checkWord("not")) {
            Token op = advance();
            Ast.Expr operand = unary();
            return new Ast.Unary(op.text(), operand, op.line(), op.column());
        }
        return power();
    }

    private Ast.Expr power() {
        Ast.Expr base = postfix();
        if (check(TokenKind.STARSTAR)) {
            Token op = advance();
            Ast.Expr exponent = unary();
            return new Ast.Binary("**", base, exponent, op.line(), op.column());
        }
        return base;
    }

    private Ast.Expr postfix() {
        Ast.Expr expr = primary();
        while (true) {
            if (check(TokenKind.LPAREN)) {
                Token at = current();
                List<Ast.Expr> args = argumentList();
                List<String> names = takeNames();
                if (trailingLambdaFollows()) {
                    args = withTrailingLambda(args);
                    names = withTrailingName(names);
                }
                expr = new Ast.Call(expr, args, names, at.line(), at.column());
                continue;
            }
            if (check(TokenKind.LBRACKET)) {
                Token at = advance();
                Ast.Expr index = expression();
                expect(TokenKind.RBRACKET, "']' to close the index");
                expr = new Ast.Index(expr, index, at.line(), at.column());
                continue;
            }
            if (check(TokenKind.DOT) || check(TokenKind.SAFE_DOT)) {
                boolean safe = check(TokenKind.SAFE_DOT);
                Token at = advance();
                String name = expectIdentifier("a member name after '.'");
                List<Ast.Expr> args = null;
                if (check(TokenKind.LPAREN)) {
                    args = withTrailingLambda(argumentList());
                } else if (trailingLambdaFollows()) {
                    args = List.of(lambda());
                }
                if (args != null) {
                    if (expr instanceof Ast.SuperRef) {
                        expr = new Ast.SuperMethod(name, args, at.line(), at.column());
                    } else if (safe) {
                        expr = new Ast.SafeMethodCall(expr, name, args, at.line(), at.column());
                    } else {
                        expr = new Ast.MethodCall(expr, name, args, at.line(), at.column());
                    }
                } else if (expr instanceof Ast.SuperRef) {
                    expr = new Ast.Member(expr, name, at.line(), at.column());
                } else if (safe) {
                    expr = new Ast.SafeMember(expr, name, at.line(), at.column());
                } else {
                    expr = new Ast.Member(expr, name, at.line(), at.column());
                }
                continue;
            }
            if (checkWord("as")) {
                Token at = advance();
                String type = typeName();
                expr = new Ast.Coerce(expr, type, at.line(), at.column());
                continue;
            }
            return expr;
        }
    }

    /**
     * A type name in an {@code is} test or an {@code as} conversion: one
     * identifier, optionally dotted ({@code std.Str}).
     */
    protected String typeName() {
        StringBuilder name = new StringBuilder(expectIdentifier("a type name"));
        while (check(TokenKind.DOT) && peek(1).is(TokenKind.IDENT)) {
            advance();
            name.append('.').append(advance().text());
        }
        return name.toString();
    }

    private Ast.Expr primary() {
        Token t = current();
        switch (t.kind()) {
            case INT:
                advance();
                return Ast.Literal.ofInt(t.longValue(), t.line(), t.column());
            case DOUBLE:
                advance();
                return Ast.Literal.ofDouble(t.doubleValue(), t.line(), t.column());
            case STRING:
                advance();
                return Ast.Literal.ofString(t.text(), t.line(), t.column());
            case INTERP:
                advance();
                return interpolated(t);
            case LPAREN: {
                advance();
                Ast.Expr inner = expression();
                expect(TokenKind.RPAREN, "')' to close the parenthesised expression");
                return inner;
            }
            case LBRACKET: {
                advance();
                List<Ast.Expr> items = new ArrayList<>();
                while (true) {
                    skipSeparators();
                    if (check(TokenKind.RBRACKET)) {
                        advance();
                        break;
                    }
                    if (atEnd()) {
                        throw error("unterminated list literal: expected ']'", t);
                    }
                    items.add(expression());
                    skipSeparators();
                    if (!match(TokenKind.COMMA)) {
                        expect(TokenKind.RBRACKET, "']' or ',' in a list literal");
                        break;
                    }
                }
                return new Ast.ListLit(List.copyOf(items), t.line(), t.column());
            }
            case LBRACE:
                return braceExpression();
            case IDENT:
                switch (t.text()) {
                    case "true":
                        advance();
                        return Ast.Literal.ofBool(true, t.line(), t.column());
                    case "false":
                        advance();
                        return Ast.Literal.ofBool(false, t.line(), t.column());
                    case "null":
                        advance();
                        return Ast.Literal.ofNull(t.line(), t.column());
                    case "this":
                        checkThisContext(t);
                        advance();
                        return new Ast.ThisRef(t.line(), t.column());
                    case "super":
                        checkSuperContext(t);
                        advance();
                        return new Ast.SuperRef(t.line(), t.column());
                    case "fun":
                        return anonymousFunction();
                    case "if":
                        return conditionalExpression();
                    case "match":
                        return matchExpression();
                    default:
                        break;
                }
                advance();
                if (Keywords.isReserved(t.text())) {
                    throw error("'" + t.text() + "' is a reserved word and cannot be"
                            + " used as a name here", t);
                }
                return new Ast.Name(t.text(), t.line(), t.column());
            default:
                throw error("expected an expression but found " + describe(t), t);
        }
    }

    /** Turns a lexed interpolated string into {@link Ast.Interpolated}. */
    private Ast.Expr interpolated(Token t) {
        List<Ast.Expr> parts = new ArrayList<>();
        for (Token.InterpPiece piece : t.parts()) {
            if (piece instanceof Token.InterpPiece.Text text) {
                parts.add(Ast.Literal.ofString(text.value(), t.line(), t.column()));
            } else {
                Token.InterpPiece.Expression expr = (Token.InterpPiece.Expression) piece;
                if (expr.source().isBlank()) {
                    throw error("empty interpolation: '${...}' needs an expression", t);
                }
                parts.add(subExpression(expr));
            }
        }
        return new Ast.Interpolated(List.copyOf(parts), t.line(), t.column());
    }

    /**
     * Parses the source inside one {@code ${ ... }}. The inner text has no
     * surrounding context, so a fresh token stream is lexed and parsed for it,
     * and a single-line position error is shifted onto the {@code $} so the
     * caret lands inside the string the reader is looking at.
     */
    private Ast.Expr subExpression(Token.InterpPiece.Expression piece) {
        try {
            SubParser sub = new SubParser(piece.source(), sourceName);
            return sub.parseSingle();
        } catch (LangException e) {
            if (piece.source().indexOf('\n') < 0) {
                throw new LangException(sourceName, piece.line(),
                        piece.column() + e.column() - 1,
                        "in string interpolation: " + e.bareMessage());
            }
            throw e;
        }
    }

    /** Parses {@code { ... }} as either a lambda or a map literal. */
    private Ast.Expr braceExpression() {
        if (looksLikeLambda()) {
            return lambda();
        }
        return mapLiteral();
    }

    /**
     * Kotlin's trailing-lambda form: {@code xs.map({ x -> x * 2 })} written as
     * {@code xs.map { x -> x * 2 }}.
     *
     * <p>Two conditions make a {@code &#123;} after a call's {@code )} a lambda and not
     * something else. It must open on the <em>same line</em> as that {@code )}, so
     * a map literal that starts a new statement is still a separate statement; and
     * it must carry a parameter arrow at nesting level one (the
     * {@link #looksLikeLambda()} scan), which is what keeps
     * {@code for x in items() { ... }} and {@code while ready() { ... }} reading as
     * loops with bodies instead of calls with one more argument.</p>
     *
     * <p>The lambda becomes the <em>last</em> argument, exactly as Kotlin defines
     * it, and the parentheses may be left out entirely when it is the only one
     * ({@code xs.map { x -> x * 2 }}). Mandela has no implicit {@code it}: a
     * trailing lambda names its parameters, because a name-less parameter on a
     * value that is not a one-argument callback would be a guess.</p>
     *
     * @return true when the cursor is on a trailing lambda
     */
    private boolean trailingLambdaFollows() {
        return check(TokenKind.LBRACE)
                && pos > 0 && peek(-1).line() == current().line()
                && looksLikeTrailingLambda();
    }

    /**
     * The trailing-position half of {@link #looksLikeLambda()}: a {@code ->} at
     * nesting level one, and nothing else.
     *
     * <p>The arm arrow is deliberately <em>not</em> accepted here. A
     * {@code match} block reads {@code { 1 => … }}, so honouring {@code =>} would
     * turn {@code match f(2) { … }} into a call of {@code f} with the whole match
     * block as an argument &mdash; a wrong parse that still compiles. A lambda
     * written with {@code =>} remains legal <em>inside</em> parentheses, where
     * there is no second reading.</p>
     *
     * @return true when a lambda with a parameter list opens at the cursor
     */
    private boolean looksLikeTrailingLambda() {
        int depth = 0;
        for (int i = pos; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            switch (t.kind()) {
                case LBRACE:
                    depth++;
                    break;
                case RBRACE:
                    depth--;
                    if (depth == 0) {
                        return false;
                    }
                    break;
                case ARROW:
                    if (depth == 1) {
                        return true;
                    }
                    break;
                case EOF:
                    return false;
                default:
                    break;
            }
        }
        return false;
    }

    /**
     * Appends the trailing lambda at the cursor to an argument list.
     *
     * @param args the arguments written inside the parentheses
     * @return the same list when no trailing lambda is at the cursor
     */
    private List<Ast.Expr> withTrailingLambda(List<Ast.Expr> args) {
        if (!trailingLambdaFollows()) {
            return args;
        }
        List<Ast.Expr> withTail = new ArrayList<>(args);
        withTail.add(lambda());
        return List.copyOf(withTail);
    }

    /**
     * Keeps {@code Ast.Call.argNames()} parallel to the argument list when a
     * trailing lambda was appended to a call that used named arguments.
     *
     * @param names the recorded names, or null when every argument is positional
     * @return the names with an unnamed slot on the end, or null
     */
    private static List<String> withTrailingName(List<String> names) {
        if (names == null) {
            return null;
        }
        List<String> extended = new ArrayList<>(names);
        extended.add("");
        return List.copyOf(extended);
    }

    /**
     * Decide whether the {@code &#123;} at the cursor opens a lambda.
     *
     * <p>Kotlin's rule, adapted: scan the matching braces; if an arrow appears
     * at nesting level one, it is a lambda. A {@code &#123; &#125;} with no arrow is a
     * map literal (possibly empty), and a nested {@code &#123; ... =&gt; ... &#125;} inside a
     * map value does not count, because it is not at level one.</p>
     *
     * <p>Either arrow works, because a lambda has no return-type annotation to
     * tell apart from a body arrow: {@code { x -> x * 2 }} reads the way Kotlin
     * authors expect and {@code { x => x * 2 }} matches the arm arrow a
     * {@code match} already uses.</p>
     *
     * @return true when a lambda starts here
     */
    private boolean looksLikeLambda() {
        int depth = 0;
        for (int i = pos; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            switch (t.kind()) {
                case LBRACE:
                    depth++;
                    break;
                case RBRACE:
                    depth--;
                    if (depth == 0) {
                        return false;
                    }
                    break;
                case FAT_ARROW:
                case ARROW:
                    if (depth == 1) {
                        return true;
                    }
                    break;
                case EOF:
                    return false;
                default:
                    break;
            }
        }
        return false;
    }

    /** Parses a lambda, {@code { a, b -> body }} or {@code { a, b => body }}. */
    private Ast.Expr lambda() {
        Token open = expect(TokenKind.LBRACE, "'{'");
        List<Ast.Param> params = new ArrayList<>();
        if (!check(TokenKind.FAT_ARROW) && !check(TokenKind.ARROW)) {
            do {
                int line = current().line();
                int col = current().column();
                String name = expectIdentifier("a lambda parameter name");
                params.add(Ast.Param.of(name, line, col));
            } while (match(TokenKind.COMMA));
        }
        if (!match(TokenKind.FAT_ARROW)) {
            expect(TokenKind.ARROW, "'->' or '=>' after the lambda parameters");
        }
        List<Ast.Stmt> statements = new ArrayList<>();
        onFunctionBodyEntered();
        try {
            while (true) {
                skipSeparators();
                if (check(TokenKind.RBRACE)) {
                    advance();
                    break;
                }
                if (atEnd()) {
                    throw error("unterminated lambda: expected '}'", open);
                }
                statements.add(statement());
                expectStatementEnd();
            }
        } finally {
            onFunctionBodyExited();
        }
        Ast.Stmt.Block body = new Ast.Stmt.Block(List.copyOf(statements),
                open.line(), open.column());
        return new Ast.Function("", List.copyOf(params), body, open.line(), open.column());
    }

    /** Parses a map literal, {@code { "k": v, name: v }}. */
    private Ast.Expr mapLiteral() {
        Token open = expect(TokenKind.LBRACE, "'{'");
        List<Ast.Expr> keys = new ArrayList<>();
        List<Ast.Expr> values = new ArrayList<>();
        while (true) {
            skipSeparators();
            if (check(TokenKind.RBRACE)) {
                advance();
                break;
            }
            if (atEnd()) {
                throw error("unterminated map literal: expected '}'", open);
            }
            keys.add(mapKey());
            expect(TokenKind.COLON, "':' after a map key");
            values.add(expression());
            skipSeparators();
            if (!match(TokenKind.COMMA)) {
                expect(TokenKind.RBRACE, "'}' or ',' in a map literal");
                break;
            }
        }
        return new Ast.MapLit(List.copyOf(keys), List.copyOf(values),
                open.line(), open.column());
    }

    /**
     * A map key: a quoted string, an expression in parentheses, or a bare word
     * that becomes a string key.
     */
    private Ast.Expr mapKey() {
        Token t = current();
        if (t.is(TokenKind.STRING)) {
            advance();
            return Ast.Literal.ofString(t.text(), t.line(), t.column());
        }
        if (t.is(TokenKind.INTERP)) {
            advance();
            return interpolated(t);
        }
        if (t.is(TokenKind.IDENT)) {
            advance();
            return Ast.Literal.ofString(t.text(), t.line(), t.column());
        }
        if (t.is(TokenKind.INT) || t.is(TokenKind.DOUBLE)) {
            advance();
            return Ast.Literal.ofString(t.text(), t.line(), t.column());
        }
        if (t.is(TokenKind.LPAREN)) {
            advance();
            Ast.Expr inner = expression();
            expect(TokenKind.RPAREN, "')' to close the map key");
            return inner;
        }
        throw error("expected a map key but found " + describe(t), t);
    }

    /** Parses an anonymous {@code fun} expression. */
    private Ast.Expr anonymousFunction() {
        Token at = expectWordReturn("fun");
        List<Ast.Param> params = parameterList();
        matchReturnAnnotation();
        Ast.Stmt.Block body = functionBody(at);
        return new Ast.Function("", List.copyOf(params), body, at.line(), at.column());
    }

    /**
     * Parses an expression-bodied or block-bodied function tail, with the
     * function context installed so {@code return} / {@code defer} are legal and
     * an enclosing {@code break} is not.
     */
    protected Ast.Stmt.Block functionBody(Token at) {
        onFunctionBodyEntered();
        try {
            if (check(TokenKind.ASSIGN) || check(TokenKind.FAT_ARROW)) {
                Token arrow = advance();
                Ast.Expr value = expression();
                List<Ast.Stmt> statements = new ArrayList<>();
                statements.add(new Ast.Stmt.ExprValue(value, arrow.line(), arrow.column()));
                return new Ast.Stmt.Block(List.copyOf(statements), at.line(), at.column());
            }
            return block();
        } finally {
            onFunctionBodyExited();
        }
    }

    /** Parses {@code if (c) a else b} used as an expression. */
    private Ast.Expr conditionalExpression() {
        Token at = advance();
        Ast.Expr condition = condition("if");
        Ast.Expr thenValue = expression();
        if (!matchWord("else")) {
            throw error("a conditional expression needs an 'else' branch", at);
        }
        skipSeparators();
        Ast.Expr elseValue = expression();
        return new Ast.Conditional(condition, thenValue, elseValue, at.line(), at.column());
    }

    /** Parses a {@code match} expression in expression position. */
    protected Ast.Expr matchExpression() {
        Token at = advance();
        Ast.Expr subject = parenthesizedMatchSubject(at);
        expect(TokenKind.LBRACE, "'{' after the match subject");
        List<Ast.Arm> arms = new ArrayList<>();
        while (true) {
            skipSeparators();
            if (check(TokenKind.RBRACE)) {
                advance();
                break;
            }
            if (atEnd()) {
                throw error("unterminated 'match': expected '}'", at);
            }
            arms.add(matchArm());
            skipSeparators();
            if (!match(TokenKind.COMMA) && !match(TokenKind.SEMICOLON)) {
                if (check(TokenKind.RBRACE)) {
                    advance();
                    break;
                }
                throw error("expected ',' or '}' after a match arm but found "
                        + describe(current()));
            }
        }
        if (arms.isEmpty()) {
            throw error("a 'match' must have at least one arm", at);
        }
        return new Ast.Match(subject, List.copyOf(arms), at.line(), at.column());
    }

    private Ast.Expr parenthesizedMatchSubject(Token at) {
        if (match(TokenKind.LPAREN)) {
            Ast.Expr subject = expression();
            expect(TokenKind.RPAREN, "')' after the match subject");
            return subject;
        }
        Ast.Expr subject = expression();
        if (!check(TokenKind.LBRACE)) {
            throw error("expected '{' after the match subject", at);
        }
        return subject;
    }

    private Ast.Arm matchArm() {
        Ast.Pattern pattern = pattern();
        skipSeparators();
        Ast.Expr guard = null;
        if (checkWord("if")) {
            advance();
            guard = expression();
        }
        expect(TokenKind.FAT_ARROW, "'=>' after a match pattern");
        skipSeparators();
        Ast.Expr body = expression();
        return new Ast.Arm(pattern, guard, body);
    }

    /** Parses an argument list, including named arguments for construction. */
    protected List<Ast.Expr> argumentList() {
        expect(TokenKind.LPAREN, "'(' opening the argument list");
        List<Ast.Expr> args = new ArrayList<>();
        List<String> names = new ArrayList<>();
        boolean anyNamed = false;
        while (true) {
            skipSeparators();
            if (check(TokenKind.RPAREN)) {
                advance();
                break;
            }
            if (atEnd()) {
                throw error("unterminated argument list: expected ')'");
            }
            String named = namedArgumentKey();
            if (named != null) {
                anyNamed = true;
                names.add(named);
            } else {
                names.add("");
            }
            args.add(expression());
            skipSeparators();
            if (!match(TokenKind.COMMA)) {
                expect(TokenKind.RPAREN, "')' or ',' in an argument list");
                break;
            }
        }
        lastNames = anyNamed ? List.copyOf(names) : null;
        return List.copyOf(args);
    }

    /**
     * Out-of-band channel for {@link Ast.Call#argNames()}: {@link #argumentList()}
     * records the parallel name list here and the caller adopts it. Only used by
     * {@link Ast.Call} construction, which is where named arguments are legal.
     */
    private List<String> lastNames;

    /** @return the pending named-argument list, clearing it */
    protected List<String> takeNames() {
        List<String> names = lastNames;
        lastNames = null;
        return names;
    }

    /**
     * Detects {@code name:} in argument position. A ternary
     * ({@code flag ? a : b}) is not a named argument because its first token is
     * followed by {@code ?}, not {@code :}.
     */
    private String namedArgumentKey() {
        if (current().is(TokenKind.IDENT) && peek(1).is(TokenKind.COLON)) {
            String name = advance().text();
            advance();
            return name;
        }
        return null;
    }

    /** Parses a parameter list, {@code (a, b = 1, c: Str)}. */
    protected List<Ast.Param> parameterList() {
        expect(TokenKind.LPAREN, "'(' opening the parameter list");
        List<Ast.Param> params = new ArrayList<>();
        while (true) {
            skipSeparators();
            if (check(TokenKind.RPAREN)) {
                advance();
                break;
            }
            if (atEnd()) {
                throw error("unterminated parameter list: expected ')'");
            }
            Token name = current();
            String role = "a parameter name";
            if (name.is(TokenKind.INT) || name.is(TokenKind.DOUBLE)) {
                throw error("expected " + role + " but found a number literal", name);
            }
            String param = expectIdentifier(role);
            String type = "";
            if (match(TokenKind.COLON)) {
                type = typeName();
            }
            Ast.Expr defaultValue = match(TokenKind.ASSIGN) ? expression() : null;
            params.add(new Ast.Param(param, type, defaultValue, name.line(), name.column()));
            skipSeparators();
            if (!match(TokenKind.COMMA)) {
                expect(TokenKind.RPAREN, "')' or ',' in a parameter list");
                break;
            }
        }
        return List.copyOf(params);
    }

    /** Consumes a {@code -> ReturnType} annotation on a function declaration. */
    protected void matchReturnAnnotation() {
        if (check(TokenKind.ARROW)) {
            advance();
            typeName();
        }
    }

    /** Consumes a keyword and hands back its token. */
    protected Token expectWordReturn(String word) {
        Token t = current();
        expectWord(word);
        return t;
    }

    // -- patterns ------------------------------------------------------------

    /**
     * Parses a match or destructuring pattern.
     *
     * <p>The rule that keeps this unambiguous: a bare identifier is a
     * <em>binding</em>, never a type reference, so a type test is always written
     * {@code is Type} and a type destructure {@code Point(x, y)}.</p>
     */
    protected Ast.Pattern pattern() {
        Token t = current();
        if (t.is(TokenKind.IDENT) && t.text().equals("_")) {
            advance();
            return new Ast.Pattern.Any(t.line(), t.column());
        }
        if (checkWord("is")) {
            Token at = advance();
            String type = typeName();
            return new Ast.Pattern.TypeOf(type, null, at.line(), at.column());
        }
        if (t.is(TokenKind.LBRACKET)) {
            advance();
            List<String> names = patternNames("']'");
            return new Ast.Pattern.Destructure("", names, t.line(), t.column());
        }
        if (t.is(TokenKind.IDENT)) {
            if (peek(1).is(TokenKind.LPAREN)) {
                String type = advance().text();
                advance();
                List<String> names = patternNames("')'");
                return new Ast.Pattern.Destructure(type, names, t.line(), t.column());
            }
            if (peek(1).isKeyword("is")) {
                String bind = advance().text();
                advance();
                String type = typeName();
                return new Ast.Pattern.TypeOf(type, bind, t.line(), t.column());
            }
            if (Keywords.isReserved(t.text())) {
                throw error("'" + t.text() + "' cannot be used as a binding name", t);
            }
            advance();
            return new Ast.Pattern.Named(t.text(), t.line(), t.column());
        }
        if (t.is(TokenKind.STRING) || t.is(TokenKind.INT) || t.is(TokenKind.DOUBLE)
                || t.is(TokenKind.INTERP)
                || (t.is(TokenKind.IDENT)
                    && (t.text().equals("true") || t.text().equals("false")
                        || t.text().equals("null")))) {
            advance();
            return new Ast.Pattern.LiteralOf(literalPatternValue(t), t.line(), t.column());
        }
        if (check(TokenKind.MINUS)) {
            Token at = advance();
            Ast.Expr value = primary();
            return new Ast.Pattern.LiteralOf(value, at.line(), at.column());
        }
        throw error("expected a pattern but found " + describe(t), t);
    }

    private Ast.Expr literalPatternValue(Token t) {
        if (t.is(TokenKind.INT)) {
            return Ast.Literal.ofInt(t.longValue(), t.line(), t.column());
        }
        if (t.is(TokenKind.DOUBLE)) {
            return Ast.Literal.ofDouble(t.doubleValue(), t.line(), t.column());
        }
        if (t.is(TokenKind.STRING)) {
            return Ast.Literal.ofString(t.text(), t.line(), t.column());
        }
        if (t.is(TokenKind.INTERP)) {
            return interpolated(t);
        }
        if (t.isKeyword("true")) {
            return Ast.Literal.ofBool(true, t.line(), t.column());
        }
        if (t.isKeyword("false")) {
            return Ast.Literal.ofBool(false, t.line(), t.column());
        }
        return Ast.Literal.ofNull(t.line(), t.column());
    }

    private List<String> patternNames(String closer) {
        List<String> names = new ArrayList<>();
        while (true) {
            skipSeparators();
            if (check(TokenKind.RBRACKET) || check(TokenKind.RPAREN)) {
                advance();
                break;
            }
            if (atEnd()) {
                throw error("unterminated pattern: expected " + closer);
            }
            Token t = current();
            if (t.is(TokenKind.IDENT)) {
                advance();
                names.add(t.text());
            } else {
                throw error("expected a binding name in a destructuring pattern"
                        + " but found " + describe(t), t);
            }
            skipSeparators();
            if (!match(TokenKind.COMMA)) {
                if (check(TokenKind.RBRACKET) || check(TokenKind.RPAREN)) {
                    advance();
                    break;
                }
                throw error("expected ',' or " + closer + " in a destructuring pattern");
            }
        }
        if (names.isEmpty()) {
            throw new LangException(sourceName, current(),
                    "a destructuring pattern needs at least one name");
        }
        return List.copyOf(names);
    }

    /** A single expression parser used for {@code ${ ... }} inner source. */
    private static final class SubParser extends ExprParser {

        private final String text;

        SubParser(String text, String sourceName) {
            this.text = text;
            init(new Lexer(text, sourceName).tokenize(), sourceName);
        }

        Ast.Expr parseSingle() {
            Ast.Expr expr = expression();
            skipSeparators();
            if (!atEnd()) {
                throw error("expected the end of the interpolated expression but found "
                        + describe(current()));
            }
            return expr;
        }

        @Override
        protected Ast.Stmt statement() {
            // ${...} is an expression slot. Statements are not legal in it, but an
            // expression is -- which also covers the one shape authors do reach
            // for here, a lambda whose body is a single expression, as in
            // "${names.map { n -> n.upper() }.join(", ")}".
            Token start = current();
            Ast.Expr expr = expression();
            return new Ast.Stmt.Expression(expr, start.line(), start.column());
        }
    }
}
