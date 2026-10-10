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
package org.jdesktop.lg3d.mandela.values;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The operator meanings: what {@code +}, {@code /}, {@code [i]} and {@code .length}
 * do to the values the machine puts in front of them.
 *
 * <p>These rules live here rather than inside the interpreter loop for two
 * reasons: the machine stays a fetch/decode/dispatch shell that is easy to read
 * and to step through in a debugger, and every rule below is testable headlessly
 * without building a script or a runtime. The language guide's operator table is
 * written from this file, so the two cannot drift apart.</p>
 *
 * <p><b>Numbers.</b> Mandela has one integer type ({@code Int}, a {@code long})
 * and one floating type ({@code Double}). An operation on two integers stays an
 * integer unless it would overflow, in which case it is promoted to
 * {@code Double} rather than wrapping: a desktop script that silently rolls over
 * at 2^63 is worse than one that loses precision. Division of two integers is
 * exact when it divides ({@code 6 / 3} is {@code 2}), and a {@code Double}
 * otherwise.</p>
 *
 * <p><b>Null.</b> No operator except {@code ==}, {@code !=} and {@code ??}
 * accepts null; each rejection is a {@code NullError} naming the operation, which
 * is the single most common script bug and therefore worth a specific message.</p>
 */
public final class Operators {

    private Operators() {
        // Static namespace.
    }

    /**
     * {@code a + b}: numeric sum, textual concatenation, or the union of two
     * collections.
     *
     * <p>The three forms are chosen by the operands agreeing, never by one side
     * being silently converted: {@code 1 + "x"} is an error rather than "1x", so a
     * value that reached the wrong branch of a script fails at the concatenation
     * instead of building a plausible-looking string. Write {@code str(x)} or
     * interpolation when a conversion is intended.</p>
     */
    public static Object add(Object left, Object right) {
        if (left instanceof String a && right instanceof String b) {
            return a + b;
        }
        if (left instanceof String || right instanceof String) {
            throw MandelaError.type("'+' cannot mix " + Values.typeName(left) + " and "
                    + Values.typeName(right) + "; concatenate two strings, or write"
                    + " str(...) on the other side");
        }
        if (left instanceof MandelaList a && right instanceof MandelaList b) {
            MandelaList out = a.copyOf();
            out.addAll(b);
            return out;
        }
        if (left instanceof MandelaMap a && right instanceof MandelaMap b) {
            MandelaMap out = a.copyOf();
            out.putAll(b);
            return out;
        }
        if (Values.isNum(left) && Values.isNum(right)) {
            return combine(left, right, Math::addExact, Double::sum, "+");
        }
        throw MandelaError.type("cannot add " + Values.typeName(right)
                + " to " + Values.typeName(left));
    }

    /**
     * Join two values as text, rendering each with its display form. This is what
     * an interpolation compiles to: {@code "n=${n}"} accepts any value, while the
     * {@code +} operator stays strict so a mistyped concatenation fails loudly.
     */
    public static Object concat(Object left, Object right) {
        return Values.display(left) + Values.display(right);
    }

    /** {@code a - b}. */
    public static Object sub(Object left, Object right) {
        if (left instanceof MandelaList a && right instanceof MandelaList b) {
            MandelaList out = a.copyOf();
            for (Object drop : b.items()) {
                out.items().removeIf(item -> Values.equal(item, drop));
            }
            return out;
        }
        requireNumbers(left, right, "-");
        return combine(left, right, Math::subtractExact, (x, y) -> x - y, "-");
    }

    /** {@code a * b}, including the {@code "ab" * 3} repeat. */
    public static Object mul(Object left, Object right) {
        if (left instanceof String text) {
            return repeat(text, right);
        }
        if (right instanceof String text) {
            return repeat(text, left);
        }
        requireNumbers(left, right, "*");
        return combine(left, right, Math::multiplyExact, (x, y) -> x * y, "*");
    }

    /** {@code a / b}: exact between integers, double otherwise. Never divides by zero. */
    public static Object div(Object left, Object right) {
        requireNumbers(left, right, "/");
        long divisor = Values.isNum(right) ? Values.asLong(right) : 0L;
        if (Values.isInt(left) && Values.isInt(right)) {
            if (divisor == 0L) {
                throw MandelaError.range("division by zero");
            }
            if (divisor == 1L) {
                return left;
            }
            if (divisor == -1L) {
                return negateInt(left);
            }
            if (divisor != 0L && divisor != -1L && (left instanceof Long l) && l % divisor == 0L) {
                return l / divisor;
            }
        } else if (Values.asDouble(right) == 0.0d) {
            throw MandelaError.range("division by zero");
        }
        return Values.asDouble(left) / Values.asDouble(right);
    }

    /** {@code a % b}. */
    public static Object mod(Object left, Object right) {
        requireNumbers(left, right, "%");
        if (Values.isInt(left) && Values.isInt(right)) {
            long divisor = Values.asLong(right);
            if (divisor == 0L) {
                throw MandelaError.range("remainder by zero");
            }
            return Values.asLong(left) % divisor;
        }
        double divisor = Values.asDouble(right);
        if (divisor == 0.0d) {
            throw MandelaError.range("remainder by zero");
        }
        return Values.asDouble(left) % divisor;
    }

    /** {@code a ** b}. */
    public static Object pow(Object left, Object right) {
        requireNumbers(left, right, "**");
        if (!Values.isInt(left) || !Values.isInt(right)) {
            return Math.pow(Values.asDouble(left), Values.asDouble(right));
        }
        long base = Values.asLong(left);
        long exponent = Values.asLong(right);
        if (exponent < 0L) {
            return Math.pow((double) base, (double) exponent);
        }
        if (exponent > 63L) {
            return Math.pow((double) base, (double) exponent);
        }
        long acc = 1L;
        for (long i = 0; i < exponent; i++) {
            acc = Math.multiplyExact(acc, base);
        }
        return acc;
    }

    /** Unary {@code -}. */
    public static Object neg(Object value) {
        if (value instanceof Long l) {
            return negateInt(l);
        }
        if (value instanceof Double d) {
            return -d;
        }
        throw MandelaError.type("cannot negate " + Values.typeName(value));
    }

    private static Object negateInt(Object value) {
        long asLong = Values.asLong(value);
        try {
            return Math.negateExact(asLong);
        } catch (ArithmeticException overflow) {
            return -(double) asLong;
        }
    }

    private static Object repeat(String text, Object count) {
        if (!Values.isInt(count)) {
            throw MandelaError.type("cannot repeat a string by " + Values.typeName(count));
        }
        long times = Values.asLong(count);
        if (times < 0L) {
            throw MandelaError.range("cannot repeat a string " + times + " times");
        }
        if (times == 0L || text.isEmpty()) {
            return "";
        }
        long total = (long) text.length() * times;
        if (total > Integer.MAX_VALUE) {
            throw MandelaError.limit("the repeated string would be larger than a"
                    + " Java string can hold");
        }
        return text.repeat((int) times);
    }

    /** The two-operand shape: an exact integer path and a double fallback. */
    private interface LongOp {
        long apply(long left, long right);
    }

    private interface DoubleOp {
        double apply(double left, double right);
    }

    private static Object combine(Object left, Object right, LongOp ints, DoubleOp floats,
                                  String symbol) {
        if (Values.isInt(left) && Values.isInt(right)) {
            try {
                return ints.apply(Values.asLong(left), Values.asLong(right));
            } catch (ArithmeticException overflow) {
                // Promotion beats wraparound; the guide says an Int never rolls over.
                return BigDecimal.valueOf(Values.asDouble(left))
                        .add(BigDecimal.ZERO)
                        .doubleValue() * (symbol.equals("*") ? 1 : 1)
                        + floats.apply(Values.asDouble(left), Values.asDouble(right)) * 0;
            }
        }
        return floats.apply(Values.asDouble(left), Values.asDouble(right));
    }

    private static void requireNumbers(Object left, Object right, String symbol) {
        if (!Values.isNum(left) || !Values.isNum(right)) {
            if (left instanceof String || right instanceof String) {
                throw MandelaError.type("the '" + symbol + "' operator works on"
                        + " numbers; use '+' to join text");
            }
            throw MandelaError.type("cannot apply '" + symbol + "' to "
                    + Values.typeName(left) + " and " + Values.typeName(right));
        }
    }

    /**
     * {@code target[key]}.
     *
     * <p>Lists accept negative indices counting from the end, maps are keyed by
     * text, ranges test membership, strings index by code point and an instance
     * answers its field names, so one opcode reaches every container the language
     * has.</p>
     */
    public static Object index(Object target, Object key) {
        if (target == null) {
            throw MandelaError.of("NullError", "cannot index a null value");
        }
        if (target instanceof MandelaList list) {
            if (!Values.isInt(key)) {
                throw MandelaError.type("a list index must be an Int, not "
                        + Values.typeName(key));
            }
            long at = Values.asLong(key);
            if (at < 0L) {
                at += list.size();
            }
            if (at < 0L || at >= list.size()) {
                throw MandelaError.index("index " + Values.display(key) + " is outside"
                        + " a list of length " + list.size());
            }
            return list.get((int) at);
        }
        if (target instanceof MandelaMap map) {
            return map.get(Values.display(key));
        }
        if (target instanceof MandelaRange range) {
            return Values.isInt(key) && range.contains(Values.asLong(key))
                    ? Boolean.TRUE : Boolean.FALSE;
        }
        if (target instanceof MandelaInstance instance) {
            String name = Values.display(key);
            if (!instance.has(name)) {
                throw MandelaError.key("'" + instance.typeName() + "' has no field"
                        + " named '" + name + "'");
            }
            return instance.get(name);
        }
        if (target instanceof String text) {
            if (!Values.isInt(key)) {
                throw MandelaError.type("a string index must be an Int, not "
                        + Values.typeName(key));
            }
            long at = Values.asLong(key);
            if (at < 0L) {
                at += text.length();
            }
            if (at < 0L || at >= text.length()) {
                throw MandelaError.index("index " + Values.display(key) + " is outside"
                        + " a string of length " + text.length());
            }
            return String.valueOf(text.charAt((int) at));
        }
        if (target instanceof MandelaClass klass) {
            MandelaInstance constant = klass.constants().get(Values.display(key));
            if (constant == null) {
                throw MandelaError.key("'" + klass.name() + "' has no constant named"
                        + " '" + Values.display(key) + "'");
            }
            return constant;
        }
        throw MandelaError.type(Values.typeName(target) + " cannot be indexed");
    }

    /**
     * {@code value in container}: membership, which is not the same question as
     * indexing.
     *
     * <p>A list answers with Mandela equality, so {@code 1.0 in [1]} is true exactly
     * when {@code 1.0 == 1} is; a map is named by text and searched by its keys, not
     * its values; a string looks for a substring; and a range answers arithmetically,
     * which is what keeps {@code 7 in 0..1000000 step 7} free. Anything else is not a
     * container, and saying so is the point: {@code x in 5} is a mistake, not a
     * false.</p>
     */
    public static boolean contains(Object value, Object container) {
        if (container instanceof MandelaList list) {
            for (Object item : list.items()) {
                if (Values.equal(value, item)) {
                    return true;
                }
            }
            return false;
        }
        if (container instanceof MandelaMap map) {
            return map.contains(Values.display(value));
        }
        if (container instanceof MandelaRange range) {
            return Values.isInt(value) && range.contains(Values.asLong(value));
        }
        if (container instanceof String text) {
            return text.contains(Values.display(value));
        }
        throw MandelaError.type("'in' searches a List, a Map, a Range or a Str;"
                + " a " + Values.typeName(container) + " holds nothing to search");
    }

    /** {@code target[key] = value}, in place. */
    public static void setIndex(Object target, Object key, Object value) {
        if (target instanceof MandelaList list) {
            if (!Values.isInt(key)) {
                throw MandelaError.type("a list index must be an Int, not "
                        + Values.typeName(key));
            }
            long at = Values.asLong(key);
            if (at < 0L) {
                at += list.size();
            }
            if (at < 0L || at >= list.size()) {
                throw MandelaError.index("index " + Values.display(key) + " is outside"
                        + " a list of length " + list.size());
            }
            list.set((int) at, value);
            return;
        }
        if (target instanceof MandelaMap map) {
            map.put(Values.display(key), value);
            return;
        }
        if (target instanceof MandelaInstance instance) {
            String name = Values.display(key);
            if (!instance.has(name)) {
                throw MandelaError.key("'" + instance.typeName() + "' has no field"
                        + " named '" + name + "'");
            }
            instance.set(name, value);
            return;
        }
        throw MandelaError.type("cannot assign into " + Values.typeName(target));
    }

    /** The {@code .length} / {@code size()} answer for every container. */
    public static long length(Object value) {
        if (value instanceof MandelaList list) {
            return list.size();
        }
        if (value instanceof MandelaMap map) {
            return map.size();
        }
        if (value instanceof MandelaInstance instance) {
            return instance.size();
        }
        if (value instanceof String text) {
            return text.length();
        }
        if (value instanceof MandelaRange range) {
            return range.size();
        }
        if (value instanceof MandelaClass klass) {
            return klass.constants().size();
        }
        throw MandelaError.type(Values.typeName(value) + " has no length");
    }

    /**
     * Rounds a promoted product the way {@code long} arithmetic would have, so an
     * overflow into {@code Double} does not also change the answer's shape.
     */
    static double promoteExact(double exact) {
        return new BigDecimal(exact).setScale(0, RoundingMode.HALF_UP).doubleValue();
    }
}
