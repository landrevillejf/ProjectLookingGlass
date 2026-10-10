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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The Mandela parser: a recursive-descent parser that turns source text into a
 * list of {@link Ast.Stmt statements}.
 *
 * <p>Two entry points, because two very different hosts consume it:</p>
 * <ul>
 *   <li>{@link #parseStrict} for the command line and the embedding API &mdash;
 *       throws {@link LangException} at the first problem.</li>
 *   <li>{@link #parse} for editors &mdash; never throws. It reports every error it
 *       can, resynchronising at the next statement boundary after each one, so a
 *       single typo does not hide the rest of the file. That is what lets Espresso
 *       paint a full Problems list while the author is mid-edit.</li>
 * </ul>
 *
 * <p>The parser also enforces the <em>contextual</em> rules the grammar cannot
 * express: {@code break}/{@code continue} only inside a loop, {@code return} only
 * inside a function, {@code this}/{@code super} only inside a class. Those are
 * reported here rather than at compile time because the position of the mistake
 * is exactly what an editor needs to show.</p>
 *
 * <p>Instances are not thread-safe and are used once.</p>
 */
public final class Parser extends ExprParser {

    /** What a successful parse hands back. */
    public record Program(List<Ast.Stmt> statements, List<Diagnostic> diagnostics) {

        /** @return true when at least one {@link Diagnostic.Severity#ERROR} was found */
        public boolean hasErrors() {
            for (Diagnostic d : diagnostics) {
                if (d.isError()) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final boolean strict;

    /** Saved loop depths, one per nested function body (a {@code break} cannot escape one). */
    private final Deque<Integer> loopStack = new ArrayDeque<>();

    private int loops;
    private int functions;
    private int classes;

    private Parser(String source, String sourceName, boolean strict) {
        this.strict = strict;
        List<Token> tokenList;
        try {
            tokenList = new Lexer(source, sourceName).tokenize();
        } catch (LangException e) {
            // A lex error is unrecoverable: there is no token stream to parse.
            if (strict) {
                throw e;
            }
            tokenList = List.of(Token.eof(1, 1));
            diagnostics.add(e.toDiagnostic());
            init(tokenList, sourceName);
            return;
        }
        init(tokenList, sourceName);
    }

    /**
     * Parses leniently, collecting every recoverable error.
     *
     * @param source     the script text
     * @param sourceName the logical source name used in diagnostics
     * @return the partial program and its diagnostics; {@link Program#hasErrors()}
     *         says whether the tree is complete
     */
    public static Program parse(String source, String sourceName) {
        Parser parser = new Parser(source, sourceName, false);
        List<Ast.Stmt> statements = parser.program();
        return new Program(statements, List.copyOf(parser.diagnostics));
    }

    /**
     * Parses strictly: the first problem aborts with an exception.
     *
     * @param source     the script text
     * @param sourceName the logical source name
     * @return the statement list
     * @throws LangException on the first lexical or syntax error
     */
    public static List<Ast.Stmt> parseStrict(String source, String sourceName) {
        Parser parser = new Parser(source, sourceName, true);
        return parser.program();
    }

    // -- program -------------------------------------------------------------

    private List<Ast.Stmt> program() {
        List<Ast.Stmt> out = new ArrayList<>();
        while (true) {
            skipSeparators();
            if (atEnd()) {
                break;
            }
            if (check(TokenKind.RBRACE)) {
                // A stray '}' at top level is an error, not a terminator.
                report(error("unexpected '}' at the top level of the script"));
                advance();
                continue;
            }
            try {
                Ast.Stmt statement = statement();
                out.add(statement);
                expectStatementEnd();
            } catch (LangException e) {
                report(e);
                if (strict) {
                    throw e;
                }
                synchronize();
            }
        }
        return List.copyOf(out);
    }

    /**
     * Records an error: rethrown in strict mode, otherwise stored as a diagnostic
     * so parsing can carry on past it.
     */
    private void report(LangException e) {
        if (strict) {
            throw e;
        }
        diagnostics.add(e.toDiagnostic());
    }

    /** Skips tokens until the next plausible statement boundary. */
    private void synchronize() {
        while (!atEnd()) {
            if (check(TokenKind.NEWLINE) || check(TokenKind.SEMICOLON)) {
                advance();
                return;
            }
            if (check(TokenKind.RBRACE)) {
                return;
            }
            Token t = current();
            if (t.is(TokenKind.IDENT) && startsStatement(t.text())) {
                return;
            }
            advance();
        }
    }

    private static boolean startsStatement(String word) {
        switch (word) {
            case "let":
            case "var":
            case "fun":
            case "type":
            case "class":
            case "enum":
            case "if":
            case "while":
            case "for":
            case "loop":
            case "return":
            case "throw":
            case "defer":
            case "try":
            case "break":
            case "continue":
            case "import":
            case "use":
            case "export":
            case "match":
                return true;
            default:
                return false;
        }
    }

    // -- statements ----------------------------------------------------------

    @Override
    protected Ast.Stmt statement() {
        Token t = current();
        if (t.is(TokenKind.IDENT)) {
            switch (t.text()) {
                case "let":
                    return declaration(false, false);
                case "var":
                    return declaration(true, false);
                case "fun":
                    return functionDeclaration(false);
                case "type":
                    return typeDeclaration(false);
                case "class":
                    return classDeclaration(false);
                case "enum":
                    return enumDeclaration(false);
                case "export":
                    return exportDeclaration();
                case "if":
                    return ifStatement();
                case "while":
                    return whileStatement();
                case "for":
                    return forStatement();
                case "loop":
                    return loopStatement();
                case "return":
                    return returnStatement();
                case "throw":
                    return throwStatement();
                case "defer":
                    return deferStatement();
                case "try":
                    return tryStatement();
                case "break":
                case "continue":
                    return jumpStatement();
                case "import":
                    return importStatement();
                case "use":
                    return useStatement();
                default:
                    break;
            }
        }
        if (check(TokenKind.LBRACE)) {
            return blockStatement();
        }
        return expressionStatement();
    }

    /** Parses a {@code let} / {@code var} declaration. */
    private Ast.Stmt declaration(boolean mutable, boolean exported) {
        Token kw = advance();
        Ast.Pattern target = declarationTarget(kw);
        String type = match(TokenKind.COLON) ? typeName() : "";
        Ast.Expr value = match(TokenKind.ASSIGN) ? expression() : null;
        if (!mutable && value == null) {
            throw error("a 'let' binding needs an initial value", kw);
        }
        if (exported && !(target instanceof Ast.Pattern.Named)) {
            // The module surface is a list of names, and a destructuring pattern has
            // no single name to put on it; asking the author to write the names out
            // keeps an import's contract readable from the exporting file alone.
            throw error("'export' needs a single name; bind the pattern with 'let'"
                    + " and export the names it gives you", kw);
        }
        if (value == null) {
            value = Ast.Literal.ofNull(kw.line(), kw.column());
        }
        return new Ast.Stmt.Let(target, type, value, mutable, exported,
                kw.line(), kw.column());
    }

    /**
     * The left side of a {@code let} / {@code var}, which is a narrower thing than a
     * match pattern: only forms that actually bind something are allowed.
     *
     * <p>{@code pattern()} also accepts a literal, because {@code match} needs it
     * ({@code 1 => 'one'}). Reusing it here unchecked made {@code let 1 = 2} a
     * legal-looking declaration that bound nothing and could never be read back.</p>
     *
     * @param kw the {@code let} / {@code var} keyword, for the error position
     * @return a named or destructuring pattern
     */
    private Ast.Pattern declarationTarget(Token kw) {
        Ast.Pattern target = pattern();
        if (!(target instanceof Ast.Pattern.Named)
                && !(target instanceof Ast.Pattern.Destructure)) {
            throw error("a declaration needs a name, or a pattern of names, to bind;"
                    + " found a value that binds nothing", kw);
        }
        return target;
    }

    /** Parses {@code export fun|let|type|class|enum ...}. */
    private Ast.Stmt exportDeclaration() {
        Token at = advance();
        if (checkWord("fun")) {
            return functionDeclaration(true);
        }
        if (checkWord("let")) {
            return declaration(false, true);
        }
        if (checkWord("var")) {
            // An import sees a module's values once, at import time, so an exported
            // name a module could re-assign would silently disagree with what its
            // importers already hold. Refusing it is the honest rule.
            throw error("'export var' is not allowed: a module's public values do not"
                    + " change once imported; write 'export let'", at);
        }
        if (checkWord("type")) {
            return typeDeclaration(true);
        }
        if (checkWord("class")) {
            return classDeclaration(true);
        }
        if (checkWord("enum")) {
            return enumDeclaration(true);
        }
        throw error("expected 'fun', 'let', 'type', 'class' or 'enum' after 'export'"
                + " but found " + describe(current()), at);
    }

    /** Parses a named function declaration. */
    private Ast.Stmt functionDeclaration(boolean exported) {
        Token at = advance();
        String name = expectIdentifier("a function name");
        List<Ast.Param> params = parameterList();
        matchReturnAnnotation();
        Ast.Stmt.Block body = functionBody(at);
        Ast.Function function = new Ast.Function(name, params, body, at.line(), at.column());
        return new Ast.Stmt.Fun(function, exported, at.line(), at.column());
    }

    /** Parses a {@code type} value declaration. */
    private Ast.Stmt typeDeclaration(boolean exported) {
        Token at = advance();
        String name = expectIdentifier("a type name");
        List<Ast.Param> fields = parameterList();
        List<Ast.Stmt.Fun> methods = new ArrayList<>();
        // A brace only opens the method list when it sits on the same line as the
        // closing paren. Skipping separators first would let a paren-less
        // `type P(x: Int)` eat the block of the statement that follows it.
        if (check(TokenKind.LBRACE) && tokens.get(pos - 1).line() == current().line()) {
            methods.addAll(typeMethods(name));
        }
        return new Ast.Stmt.TypeDecl(name, fields, List.copyOf(methods), exported,
                at.line(), at.column());
    }

    /**
     * Parses a {@code class} declaration: primary constructor, optional base
     * clause, fields, methods and {@code init} blocks.
     */
    private Ast.Stmt classDeclaration(boolean exported) {
        Token at = advance();
        String name = expectIdentifier("a class name");
        List<Ast.Param> params = check(TokenKind.LPAREN)
                ? parameterList() : List.of();
        String parent = "";
        List<Ast.Expr> parentArgs = List.of();
        if (matchWord("extends")) {
            parent = typeName();
            if (check(TokenKind.LPAREN)) {
                parentArgs = argumentList();
                takeNames();
            }
        }
        List<Ast.Stmt.Let> fields = new ArrayList<>();
        List<Ast.Stmt.Fun> methods = new ArrayList<>();
        List<Ast.Stmt.Block> initializers = new ArrayList<>();
        // A class may declare nothing but its primary constructor, `class Row(a: Int)`,
        // and a `class Empty()`. The body brace only belongs to the declaration when
        // it sits on the header's last line; otherwise the block of the statement
        // that follows would be swallowed as a class body.
        if (check(TokenKind.LBRACE) && tokens.get(pos - 1).line() == current().line()) {
            readClassMembers(name, at, fields, methods, initializers);
        }
        return new Ast.Stmt.ClassDecl(name, params, parent, parentArgs,
                List.copyOf(fields), List.copyOf(methods), List.copyOf(initializers),
                exported, at.line(), at.column());
    }

    /** Reads a class declaration's brace-delimited member list. */
    private void readClassMembers(String name, Token at, List<Ast.Stmt.Let> fields,
                                  List<Ast.Stmt.Fun> methods,
                                  List<Ast.Stmt.Block> initializers) {
        expect(TokenKind.LBRACE, "'{' opening the class body");
        enterMemberContext();
        try {
            while (true) {
                skipSeparators();
                if (check(TokenKind.RBRACE)) {
                    advance();
                    break;
                }
                if (atEnd()) {
                    throw error("unterminated class body: expected '}' to close '"
                            + name + "'", at);
                }
                Token member = current();
                if (member.isKeyword("let") || member.isKeyword("var")) {
                    Ast.Stmt declared = statement();
                    if (!(declared instanceof Ast.Stmt.Let field)) {
                        throw error("a class field must be a 'let' or 'var' declaration",
                                member);
                    }
                    fields.add(field);
                } else if (member.isKeyword("fun")) {
                    methods.add((Ast.Stmt.Fun) functionDeclaration(false));
                } else if (member.isKeyword("init")) {
                    Token init = advance();
                    Ast.Stmt.Block block = block();
                    initializers.add(new Ast.Stmt.Block(block.statements(),
                            init.line(), init.column()));
                } else {
                    throw error("expected a field ('let'/'var'), a method ('fun')"
                            + " or an 'init' block in a class body but found "
                            + describe(member), member);
                }
                expectStatementEnd();
            }
        } finally {
            exitMemberContext();
        }
    }

    /** Parses an {@code enum} declaration. */
    private Ast.Stmt enumDeclaration(boolean exported) {
        Token at = advance();
        String name = expectIdentifier("an enum name");
        expect(TokenKind.LBRACE, "'{' opening the enum constants");
        List<String> constants = new ArrayList<>();
        while (true) {
            skipSeparators();
            if (check(TokenKind.RBRACE)) {
                advance();
                break;
            }
            if (atEnd()) {
                throw error("unterminated enum: expected '}'", at);
            }
            constants.add(expectIdentifier("an enum constant name"));
            skipSeparators();
            if (!match(TokenKind.COMMA)) {
                if (check(TokenKind.RBRACE)) {
                    advance();
                    break;
                }
                throw error("expected ',' or '}' between enum constants");
            }
        }
        if (constants.isEmpty()) {
            throw error("an enum must declare at least one constant", at);
        }
        return new Ast.Stmt.EnumDecl(name, List.copyOf(constants), exported,
                at.line(), at.column());
    }

    /**
     * Parses the brace-delimited member body of a {@code type} declaration,
     * where only methods are legal.
     *
     * @param typeName the enclosing type name, used in the error text
     * @return the declared methods
     */
    private List<Ast.Stmt.Fun> typeMethods(String typeName) {
        List<Ast.Stmt.Fun> methods = new ArrayList<>();
        expect(TokenKind.LBRACE, "'{' opening the type body");
        enterMemberContext();
        try {
            while (true) {
                skipSeparators();
                if (check(TokenKind.RBRACE)) {
                    advance();
                    break;
                }
                if (atEnd()) {
                    throw error("unterminated 'type' body: expected '}'");
                }
                Token member = current();
                if (!member.isKeyword("fun")) {
                    throw error("a 'type' body may only contain 'fun' methods;"
                            + " its fields are declared in the parentheses"
                            + " (found " + describe(member) + ")", member);
                }
                methods.add((Ast.Stmt.Fun) functionDeclaration(false));
                expectStatementEnd();
            }
        } finally {
            exitMemberContext();
        }
        return methods;
    }

    private Ast.Stmt blockStatement() {
        return block();
    }

    /**
     * Matches a continuation clause keyword, allowing the line breaks that may sit
     * between a closing '}' and the keyword.
     *
     * <p>The separators are only consumed when the keyword is really there. A
     * plain {@code skipSeparators()} first would swallow the terminator of a
     * finished {@code if} or {@code try} statement and then report the next line
     * as "expected an end of statement".</p>
     */
    private boolean matchClauseWord(String word) {
        int mark = pos;
        skipSeparators();
        if (matchWord(word)) {
            return true;
        }
        pos = mark;
        return false;
    }

    private Ast.Stmt ifStatement() {
        Token at = advance();
        Ast.Expr test = condition("if");
        Ast.Stmt.Block thenBlock = block();
        Ast.Stmt elseBlock = null;
        // A line break may sit between the closing '}' and 'else'.
        if (matchClauseWord("else")) {
            skipSeparators();
            if (checkWord("if")) {
                elseBlock = ifStatement();
            } else {
                elseBlock = block();
            }
        }
        return new Ast.Stmt.If(test, thenBlock, elseBlock, at.line(), at.column());
    }

    private Ast.Stmt whileStatement() {
        Token at = advance();
        Ast.Expr test = condition("while");
        Ast.Stmt.Block body = loopBody();
        return new Ast.Stmt.While(test, body, at.line(), at.column());
    }

    private Ast.Stmt forStatement() {
        Token at = advance();
        // The header may be parenthesised, exactly as an 'if' or 'while' condition
        // may, so the muscle memory Java and Kotlin authors bring (for (i in 0..3))
        // parses instead of being reported as a stray '('.
        boolean parenthesised = match(TokenKind.LPAREN);
        Ast.Pattern pattern = loopPattern();
        expectWord("in");
        Ast.Expr iterable = expression();
        if (parenthesised) {
            expect(TokenKind.RPAREN, "')' after the 'for' header");
        }
        Ast.Stmt.Block body = loopBody();
        return new Ast.Stmt.For(pattern, iterable, body, at.line(), at.column());
    }

    /**
     * The loop variable pattern: a name, a destructuring form, or the two-name
     * shorthand {@code for key, value in map}, which binds one variable per entry
     * half. The whole header may also be wrapped in parentheses, as in
     * {@code for ([k, v] in map)}. {@code for (k, v) in map} is deliberately not a
     * third spelling: inside the parentheses the pattern is a pattern, so the pair
     * is written {@code for (k, v in map)}.
     */
    private Ast.Pattern loopPattern() {
        Ast.Pattern first = pattern();
        if (!match(TokenKind.COMMA)) {
            return first;
        }
        if (!(first instanceof Ast.Pattern.Named named)) {
            throw error("only a plain name may be paired with a second loop variable");
        }
        String other = expectIdentifier("the second loop variable");
        return new Ast.Pattern.Destructure("",
                List.of(named.name(), other), named.line(), named.column());
    }

    private Ast.Stmt loopStatement() {
        Token at = advance();
        Ast.Stmt.Block body = loopBody();
        return new Ast.Stmt.Loop(body, at.line(), at.column());
    }

    /**
     * Parses a loop body inside the loop context, so {@code break} / {@code
     * continue} bind here and not to some outer loop. The counter is restored in
     * a {@code finally} because a syntax error inside the body must not leave the
     * parser believing it is still in a loop.
     */
    private Ast.Stmt.Block loopBody() {
        loops++;
        try {
            return block();
        } finally {
            loops--;
        }
    }

    private Ast.Stmt returnStatement() {
        Token at = advance();
        if (functions == 0) {
            throw error("'return' is only legal inside a function", at);
        }
        Ast.Expr value = null;
        if (!check(TokenKind.NEWLINE) && !check(TokenKind.SEMICOLON)
                && !check(TokenKind.RBRACE) && !atEnd()) {
            value = expression();
        }
        return new Ast.Stmt.Return(value, at.line(), at.column());
    }

    private Ast.Stmt throwStatement() {
        Token at = advance();
        if (check(TokenKind.NEWLINE) || check(TokenKind.SEMICOLON)
                || check(TokenKind.RBRACE) || atEnd()) {
            throw error("'throw' needs a value to throw", at);
        }
        return new Ast.Stmt.Throw(expression(), at.line(), at.column());
    }

    private Ast.Stmt deferStatement() {
        Token at = advance();
        if (check(TokenKind.NEWLINE) || check(TokenKind.SEMICOLON)
                || check(TokenKind.RBRACE) || atEnd()) {
            throw error("'defer' needs a statement to run later", at);
        }
        if (functions == 0) {
            throw error("'defer' is only legal inside a function", at);
        }
        return new Ast.Stmt.Defer(statement(), at.line(), at.column());
    }

    private Ast.Stmt tryStatement() {
        Token at = advance();
        Ast.Stmt.Block body = block();
        String errorName = null;
        String errorType = "";
        Ast.Stmt.Block handler = null;
        Ast.Stmt.Block finalizer = null;
        if (matchClauseWord("catch")) {
            boolean parenthesised = match(TokenKind.LPAREN);
            errorName = expectIdentifier("an error name");
            if (match(TokenKind.COLON)) {
                errorType = typeName();
            }
            if (parenthesised) {
                expect(TokenKind.RPAREN, "')' after the catch clause");
            }
            handler = block();
        }
        if (matchClauseWord("finally")) {
            finalizer = block();
        }
        if (handler == null && finalizer == null) {
            throw error("a 'try' block needs a 'catch' or a 'finally' clause", at);
        }
        return new Ast.Stmt.Try(body, errorName, errorType, handler, finalizer,
                at.line(), at.column());
    }

    private Ast.Stmt jumpStatement() {
        Token at = advance();
        boolean isBreak = at.text().equals("break");
        if (loops == 0) {
            throw error("'" + at.text() + "' is only legal inside a loop", at);
        }
        return isBreak
                ? new Ast.Stmt.Break(at.line(), at.column())
                : new Ast.Stmt.Continue(at.line(), at.column());
    }

    private Ast.Stmt importStatement() {
        Token at = advance();
        Token path = current();
        if (!path.is(TokenKind.STRING)) {
            throw error("'import' needs a quoted module path, e.g. import \"lib/util.mn\"",
                    path);
        }
        advance();
        String alias = "";
        if (matchWord("as")) {
            alias = expectIdentifier("a module alias");
        }
        return new Ast.Stmt.Import(path.text(), alias, at.line(), at.column());
    }

    private Ast.Stmt useStatement() {
        Token at = advance();
        List<String> segments = new ArrayList<>();
        segments.add(expectIdentifier("a module name"));
        while (check(TokenKind.DOT)) {
            advance();
            segments.add(expectIdentifier("a module name after '.'"));
        }
        return new Ast.Stmt.Use(List.copyOf(segments), at.line(), at.column());
    }

    /** Parses a bare expression, an assignment or a compound assignment. */
    private Ast.Stmt expressionStatement() {
        Token start = current();
        Ast.Expr left = expression();
        TokenKind kind = current().kind();
        String compound = compoundOperator(kind);
        if (kind == TokenKind.ASSIGN) {
            advance();
            Ast.Expr value = expression();
            requireAssignable(left, start);
            return new Ast.Stmt.Assign(left, value, start.line(), start.column());
        }
        if (compound != null) {
            advance();
            Ast.Expr value = expression();
            requireAssignable(left, start);
            return new Ast.Stmt.CompoundAssign(left, compound, value,
                    start.line(), start.column());
        }
        return new Ast.Stmt.Expression(left, start.line(), start.column());
    }

    private void requireAssignable(Ast.Expr target, Token at) {
        if (!(target instanceof Ast.Name) && !(target instanceof Ast.Member)
                && !(target instanceof Ast.Index) && !(target instanceof Ast.SafeMember)) {
            throw error("this expression cannot be assigned to", at);
        }
    }

    private static String compoundOperator(TokenKind kind) {
        switch (kind) {
            case PLUSEQ:
                return "+";
            case MINUSEQ:
                return "-";
            case STAREQ:
                return "*";
            case SLASHEQ:
                return "/";
            case PERCENTEQ:
                return "%";
            case STARSTAREQ:
                return "**";
            default:
                return null;
        }
    }

    // -- context bookkeeping -------------------------------------------------

    /**
     * Pushes the loop context that the class and type member bodies install: a
     * {@code break} inside a method belongs to the method, never to an enclosing
     * loop in the script.
     */
    private void enterMemberContext() {
        loopStack.push(loops);
        loops = 0;
        classes++;
    }

    /** Restores what {@link #enterMemberContext()} saved. */
    private void exitMemberContext() {
        classes--;
        loops = loopStack.isEmpty() ? 0 : loopStack.pop();
    }

    @Override
    protected void checkThisContext(Token at) {
        if (classes == 0) {
            throw error("'this' is only legal inside a class or type method", at);
        }
    }

    @Override
    protected void checkSuperContext(Token at) {
        if (classes == 0) {
            throw error("'super' is only legal inside a class that extends another", at);
        }
    }

    /** A hook so {@link ExprParser} can enforce context it cannot see itself. */
    @Override
    protected void onFunctionBodyEntered() {
        loopStack.push(loops);
        loops = 0;
        functions++;
    }

    @Override
    protected void onFunctionBodyExited() {
        functions--;
        loops = loopStack.isEmpty() ? 0 : loopStack.pop();
    }
}
