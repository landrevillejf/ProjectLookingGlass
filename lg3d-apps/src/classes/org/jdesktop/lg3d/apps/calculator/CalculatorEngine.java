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
package org.jdesktop.lg3d.apps.calculator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Headless math engine behind {@link CalculatorPanel}: a recursive-descent
 * evaluator over a small expression language plus the state a scientific
 * calculator needs (angle mode, memory register, last answer).
 *
 * <p>Grammar, loosest binding first:</p>
 * <pre>
 *   expression := term { ('+' | '-') term }
 *   term       := factor { ('*' | '/' | 'mod') factor }
 *   factor     := ('-' | '+') factor | power
 *   power      := postfix [ '^' factor ]          (right associative)
 *   postfix    := primary { '!' | '%' }
 *   primary    := number | '(' expression ')' | constant
 *               | function '(' expression ')'
 * </pre>
 *
 * <p>Functions: {@code sin cos tan asin acos atan} (angle-mode aware),
 * {@code ln log sqrt abs}. Constants: {@code pi}, {@code e}, {@code Ans}
 * (the last evaluated result). Postfix {@code !} is the factorial and
 * {@code %} divides by 100.</p>
 *
 * <p>Deliberately free of Swing/AWT imports so it can be exercised headless.</p>
 */
public final class CalculatorEngine {

    /** Angle unit for the trigonometric functions. */
    public enum AngleMode { DEGREES, RADIANS }

    /** Thrown for any unparseable or mathematically invalid expression. */
    public static class CalcException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CalcException(String message) {
            super(message);
        }
    }

    private enum TokKind { NUM, IDENT, OP }

    private static final class Token {
        final TokKind kind;
        final String text;
        final double value;

        Token(TokKind kind, String text, double value) {
            this.kind = kind;
            this.text = text;
            this.value = value;
        }
    }

    private AngleMode angleMode = AngleMode.DEGREES;
    private double memory = 0.0;
    private boolean memorySet = false;
    private double lastAnswer = 0.0;

    /** Parser cursor; only touched between tokenize and the top-level return. */
    private List<Token> tokens = new ArrayList<>();
    private int pos = 0;

    public AngleMode getAngleMode() {
        return angleMode;
    }

    public void setAngleMode(AngleMode mode) {
        this.angleMode = mode;
    }

    public double getMemory() {
        return memory;
    }

    public boolean isMemorySet() {
        return memorySet;
    }

    public void storeMemory(double value) {
        memory = value;
        memorySet = true;
    }

    public void addToMemory(double value) {
        memory += value;
        memorySet = true;
    }

    public void clearMemory() {
        memory = 0.0;
        memorySet = false;
    }

    public double getLastAnswer() {
        return lastAnswer;
    }

    public void setLastAnswer(double value) {
        this.lastAnswer = value;
    }

    /**
     * Evaluates {@code expression} and returns its value.
     *
     * @throws CalcException if the text does not parse or a sub-expression is
     *         undefined (division by zero, out-of-domain function, bad factorial)
     */
    public double evaluate(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new CalcException("Empty expression");
        }
        tokens = tokenize(expression);
        pos = 0;
        double value = parseExpression();
        if (pos < tokens.size()) {
            throw new CalcException("Unexpected '" + tokens.get(pos).text + "'");
        }
        return value;
    }

    /**
     * Renders a result for the display: plain decimal trimmed of floating-point
     * noise up to 12 significant digits, scientific notation beyond +/-1e15 and
     * below 1e-9.
     */
    public static String format(double value) {
        if (Double.isNaN(value)) {
            throw new CalcException("Not a number");
        }
        if (Double.isInfinite(value)) {
            throw new CalcException("Overflow");
        }
        if (value == 0.0) {
            return "0";
        }
        double abs = Math.abs(value);
        if (abs >= 1e15 || abs < 1e-9) {
            String[] parts = String.format(Locale.ROOT, "%.9e", value).split("e");
            return stripZeros(parts[0]) + "e" + parts[1];
        }
        return stripZeros(new BigDecimal(value, new MathContext(12)).toPlainString());
    }

    private static String stripZeros(String s) {
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ lexer

    private static List<Token> tokenize(String src) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c)
                    || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
                int start = i;
                while (i < n && Character.isDigit(src.charAt(i))) {
                    i++;
                }
                if (i < n && src.charAt(i) == '.') {
                    i++;
                    while (i < n && Character.isDigit(src.charAt(i))) {
                        i++;
                    }
                }
                // Exponent notation only when 'e' is followed by a digit or a
                // sign then a digit, so a bare constant 'e' still ends a number.
                if (i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
                    int j = i + 1;
                    if (j < n && (src.charAt(j) == '+' || src.charAt(j) == '-')) {
                        j++;
                    }
                    if (j < n && Character.isDigit(src.charAt(j))) {
                        i = j;
                        while (i < n && Character.isDigit(src.charAt(i))) {
                            i++;
                        }
                    }
                }
                String text = src.substring(start, i);
                out.add(new Token(TokKind.NUM, text, Double.parseDouble(text)));
                continue;
            }
            if (Character.isLetter(c)) {
                int start = i;
                while (i < n && Character.isLetter(src.charAt(i))) {
                    i++;
                }
                out.add(new Token(TokKind.IDENT, src.substring(start, i), 0.0));
                continue;
            }
            if ("+-*/^()!%".indexOf(c) >= 0) {
                out.add(new Token(TokKind.OP, String.valueOf(c), 0.0));
                i++;
                continue;
            }
            throw new CalcException("Unexpected character '" + c + "'");
        }
        return out;
    }

    // ---------------------------------------------------------------- parser

    private double parseExpression() {
        double value = parseTerm();
        while (peekOp("+", "-")) {
            String op = consume().text;
            double right = parseTerm();
            value = op.equals("+") ? value + right : value - right;
        }
        return value;
    }

    private double parseTerm() {
        double value = parseFactor();
        for (;;) {
            if (peekOp("*", "/")) {
                String op = consume().text;
                double right = parseFactor();
                if (op.equals("*")) {
                    value *= right;
                } else {
                    if (right == 0.0) {
                        throw new CalcException("Division by zero");
                    }
                    value /= right;
                }
            } else if (peekIdent("mod")) {
                consume();
                double right = parseFactor();
                if (right == 0.0) {
                    throw new CalcException("Division by zero");
                }
                value %= right;
            } else {
                return value;
            }
        }
    }

    private double parseFactor() {
        if (peekOp("-")) {
            consume();
            return -parseFactor();
        }
        if (peekOp("+")) {
            consume();
            return parseFactor();
        }
        return parsePower();
    }

    private double parsePower() {
        double base = parsePostfix();
        if (peekOp("^")) {
            consume();
            // Right associative, and the exponent may carry its own sign.
            double exponent = parseFactor();
            double result = Math.pow(base, exponent);
            if (Double.isNaN(result)) {
                throw new CalcException("Out of domain");
            }
            return result;
        }
        return base;
    }

    private double parsePostfix() {
        double value = parsePrimary();
        while (peekOp("!", "%")) {
            String op = consume().text;
            if (op.equals("!")) {
                value = factorial(value);
            } else {
                value /= 100.0;
            }
        }
        return value;
    }

    private double parsePrimary() {
        Token token = peek();
        if (token == null) {
            throw new CalcException("Unexpected end of expression");
        }
        if (token.kind == TokKind.NUM) {
            consume();
            return token.value;
        }
        if (token.kind == TokKind.OP && token.text.equals("(")) {
            consume();
            double value = parseExpression();
            expectClose();
            return value;
        }
        if (token.kind == TokKind.IDENT) {
            String name = token.text.toLowerCase(Locale.ROOT);
            switch (name) {
                case "pi":
                    consume();
                    return Math.PI;
                case "e":
                    consume();
                    return Math.E;
                case "ans":
                    consume();
                    return lastAnswer;
                case "sin":
                case "cos":
                case "tan":
                case "asin":
                case "acos":
                case "atan":
                case "ln":
                case "log":
                case "sqrt":
                case "abs":
                    consume();
                    expectOpen();
                    double arg = parseExpression();
                    expectClose();
                    return applyFunction(name, arg);
                default:
                    throw new CalcException("Unknown name '" + token.text + "'");
            }
        }
        throw new CalcException("Unexpected '" + token.text + "'");
    }

    private double applyFunction(String name, double x) {
        double result;
        switch (name) {
            case "sin":
                result = Math.sin(toInput(x));
                break;
            case "cos":
                result = Math.cos(toInput(x));
                break;
            case "tan":
                result = Math.tan(toInput(x));
                break;
            case "asin":
                result = fromOutput(Math.asin(x));
                break;
            case "acos":
                result = fromOutput(Math.acos(x));
                break;
            case "atan":
                result = fromOutput(Math.atan(x));
                break;
            case "ln":
                result = Math.log(x);
                break;
            case "log":
                result = Math.log10(x);
                break;
            case "sqrt":
                result = Math.sqrt(x);
                break;
            case "abs":
                result = Math.abs(x);
                break;
            default:
                throw new CalcException("Unknown function '" + name + "'");
        }
        if (Double.isNaN(result)) {
            throw new CalcException("Out of domain");
        }
        if (Double.isInfinite(result)) {
            throw new CalcException("Overflow");
        }
        return result;
    }

    private double toInput(double x) {
        return angleMode == AngleMode.DEGREES ? Math.toRadians(x) : x;
    }

    private double fromOutput(double r) {
        return angleMode == AngleMode.DEGREES ? Math.toDegrees(r) : r;
    }

    private static double factorial(double x) {
        if (x < 0.0 || x > 170.0 || Math.rint(x) != x) {
            throw new CalcException("Factorial of invalid value");
        }
        double result = 1.0;
        for (long i = 2; i <= (long) x; i++) {
            result *= i;
        }
        return result;
    }

    // --------------------------------------------------------- cursor helpers

    private Token peek() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }

    private Token consume() {
        return tokens.get(pos++);
    }

    private boolean peekOp(String... ops) {
        Token token = peek();
        if (token == null || token.kind != TokKind.OP) {
            return false;
        }
        for (String op : ops) {
            if (op.equals(token.text)) {
                return true;
            }
        }
        return false;
    }

    private boolean peekIdent(String name) {
        Token token = peek();
        return token != null && token.kind == TokKind.IDENT
                && name.equals(token.text.toLowerCase(Locale.ROOT));
    }

    private void expectOpen() {
        if (!peekOp("(")) {
            throw new CalcException("Missing '('");
        }
        consume();
    }

    private void expectClose() {
        if (!peekOp(")")) {
            throw new CalcException("Missing ')'");
        }
        consume();
    }
}
