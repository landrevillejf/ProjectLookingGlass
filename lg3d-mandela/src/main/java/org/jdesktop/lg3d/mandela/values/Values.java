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

/**
 * The value semantics of Mandela in one place: what a value is called, when it
 * is true, how it prints, how it compares, how it converts and how arithmetic
 * behaves on it.
 *
 * <p>Every runtime value is an ordinary Java object. The mapping is fixed and
 * nothing else may claim these slots:</p>
 * <table>
 *   <caption>Mandela value to Java representation</caption>
 *   <tr><th>Mandela</th><th>Java</th></tr>
 *   <tr><td>{@code null}</td><td>{@code null}</td></tr>
 *   <tr><td>{@code Bool}</td><td>{@link Boolean}</td></tr>
 *   <tr><td>{@code Int}</td><td>{@link Long}</td></tr>
 *   <tr><td>{@code Double}</td><td>{@link Double}</td></tr>
 *   <tr><td>{@code Str}</td><td>{@link String}</td></tr>
 *   <tr><td>{@code List}</td><td>{@link MandelaList}</td></tr>
 *   <tr><td>{@code Map}</td><td>{@link MandelaMap}</td></tr>
 *   <tr><td>{@code Range}</td><td>{@link MandelaRange}</td></tr>
 *   <tr><td>{@code Fun}</td><td>{@link Callable}</td></tr>
 *   <tr><td>declared type</td><td>{@link MandelaInstance}</td></tr>
 *   <tr><td>{@code Class}</td><td>{@link MandelaClass}</td></tr>
 *   <tr><td>{@code Err}</td><td>{@link MandelaError}</td></tr>
 *   <tr><td>captured variable</td><td>{@link Ref}</td></tr>
 * </table>
 *
 * <p>Keeping values as plain Java objects is what makes the host boundary nearly
 * free: a value crosses into Java without conversion, a {@code Long} becomes a
 * Kotlin {@code Long}, and the desktop's own Swing widgets can be handed to a
 * script unchanged.</p>
 */
public final class Values {

    /**
     * The marker the machine leaves in a parameter slot the caller did not fill.
     *
     * <p>A defaulted parameter is applied by the callee, because the default
     * expression is code that may read the parameters before it. The caller
     * therefore cannot evaluate defaults; it stores {@code ABSENT} and the
     * prologue tests it with {@link org.jdesktop.lg3d.mandela.code.Op#IS_ABSENT}.
     * The marker never escapes into a script: a function called with a hole it
     * has no default for fails at the call site.</p>
     */
    public static final Object ABSENT = new Absent();

    /** The type of the {@link #ABSENT} marker, kept private so nothing else can make one. */
    private static final class Absent {
        @Override
        public String toString() {
            return "<absent>";
        }
    }

    /** @return true when {@code value} is the unfilled-argument marker */
    public static boolean isAbsent(Object value) {
        return value == ABSENT;
    }

    private Values() {
        // Static helpers only.
    }

    // -- names -------------------------------------------------------------

    /** @return the type name {@code type()} and the {@code is} operator report */
    public static String typeName(Object value) {
        if (value == null) {
            return "Null";
        }
        if (value instanceof Boolean) {
            return "Bool";
        }
        if (value instanceof Long) {
            return "Int";
        }
        if (value instanceof Double) {
            return "Double";
        }
        if (value instanceof String) {
            return "Str";
        }
        if (value instanceof MandelaList) {
            return "List";
        }
        if (value instanceof MandelaMap) {
            return "Map";
        }
        if (value instanceof MandelaRange) {
            return "Range";
        }
        if (value instanceof MandelaClass) {
            return "Class";
        }
        if (value instanceof MandelaInstance instance) {
            return instance.typeName();
        }
        if (value instanceof MandelaError) {
            return "Err";
        }
        if (value instanceof Callable) {
            return "Fun";
        }
        if (value == ABSENT) {
            return "Absent";
        }
        if (value instanceof Ref) {
            return "Ref";
        }
        return "Host";
    }

    /**
     * Whether a value is one the machine can run on exactly as it stands.
     *
     * <p>This is the same classification {@link #typeName} reports: anything it
     * calls {@code Host} is a Java object the language has no meaning for. A host
     * boundary that wants to fail early rather than deep inside a script compares
     * against this instead of guessing.</p>
     */
    public static boolean isMandelaValue(Object value) {
        return !"Host".equals(typeName(value));
    }

    /**
     * Widens an ordinary Java value into the Mandela value the machine runs on.
     *
     * <p>Only the shapes a host reaches for without thinking are converted: the
     * narrower number types, anything text-like, a {@code java.util} collection,
     * an array and a map with string-ish keys. Anything else comes back unchanged,
     * which is what lets a caller tell "already a value" from "not one" &mdash;
     * deciding what a domain object means is a host decision, and
     * {@code org.jdesktop.lg3d.mandela.api.Interop} is where the language makes it,
     * because that is also where a function-shaped object gets its callable.</p>
     *
     * @param value the candidate, possibly null
     * @return the Mandela form, or {@code value} itself when there is nothing to do
     */
    public static Object fromHost(Object value) {
        if (value == null || isMandelaValue(value)) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float || value instanceof Double) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof java.math.BigDecimal exact) {
            return exact.doubleValue();
        }
        if (value instanceof java.math.BigInteger whole) {
            try {
                return whole.longValueExact();
            } catch (ArithmeticException tooWide) {
                return whole.doubleValue();
            }
        }
        if (value instanceof Character letter) {
            return letter.toString();
        }
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        if (value instanceof java.util.Collection<?> items) {
            MandelaList out = new MandelaList(items.size());
            for (Object element : items) {
                out.add(fromHost(element));
            }
            return out;
        }
        if (value instanceof java.util.Map<?, ?> pairs) {
            MandelaMap out = new MandelaMap(pairs.size());
            for (java.util.Map.Entry<?, ?> entry : pairs.entrySet()) {
                out.put(hostKey(entry.getKey()), fromHost(entry.getValue()));
            }
            return out;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            MandelaList out = new MandelaList(length);
            for (int i = 0; i < length; i++) {
                out.add(fromHost(java.lang.reflect.Array.get(value, i)));
            }
            return out;
        }
        return value;
    }

    /**
     * @param key a map key arriving from a host
     * @return the name the script will read it under
     */
    private static String hostKey(Object key) {
        if (key instanceof String text) {
            return text;
        }
        if (key == null) {
            throw MandelaError.value("a map handed to a script may not have a null key");
        }
        // Numbers and enums make reasonable keys, and a host that builds a map from
        // them should not have to stringify first.
        return display(fromHost(key));
    }

    /**
     * Converts a Mandela value into the plain Java form a host can hold onto.
     *
     * <p>This is the mirror of {@link #fromHost}, and it has to exist because
     * {@link MandelaList} is only {@code Iterable} and {@link MandelaMap} is nothing
     * at all &mdash; a host lambda declared as {@code Function<List, …>} cannot be
     * handed a script list, however much the two look alike. A {@link MandelaInstance}
     * reads as its field map for the same reason {@code api.Interop.toJava} does it:
     * a host can do something with a map, and with a script object it cannot.</p>
     *
     * <p>The copies are deep, so a host keeping a result never keeps a live scene the
     * script can still change underneath it. Functions, ranges and errors are passed
     * through as themselves; they have no honest Java equivalent and a host that
     * wants one takes it through {@code api.Interop}.</p>
     *
     * @param value the value a script produced; may be null
     * @return the Java form
     */
    public static Object toHost(Object value) {
        if (value instanceof MandelaList list) {
            java.util.List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object element : list.items()) {
                out.add(toHost(element));
            }
            return out;
        }
        if (value instanceof MandelaMap map) {
            java.util.Map<String, Object> out =
                    new java.util.LinkedHashMap<>(map.size());
            for (java.util.Map.Entry<String, Object> entry : map.entries().entrySet()) {
                out.put(entry.getKey(), toHost(entry.getValue()));
            }
            return out;
        }
        if (value instanceof MandelaInstance instance) {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (String name : instance.fieldNames()) {
                out.put(name, toHost(instance.get(name)));
            }
            return out;
        }
        return value;
    }

    /** @return true when {@code value} belongs to the named builtin type */
    public static boolean isType(Object value, String name) {
        switch (name) {
            case "Any":
                return true;
            case "Null":
                return value == null;
            case "Bool":
                return value instanceof Boolean;
            case "Int":
                return value instanceof Long;
            case "Double":
                return value instanceof Double;
            case "Num":
                return value instanceof Long || value instanceof Double;
            case "Str":
                return value instanceof String;
            case "List":
                return value instanceof MandelaList;
            case "Map":
                return value instanceof MandelaMap;
            case "Range":
                return value instanceof MandelaRange;
            case "Fun":
                return value instanceof Callable;
            case "Class":
                return value instanceof MandelaClass;
            case "Obj":
                return value instanceof MandelaInstance;
            case "Err":
                return value instanceof MandelaError;
            default:
                // A declared type name: instances answer to their own class and
                // to any class they extend.
                return value instanceof MandelaInstance instance
                        && instance.owner().name().equals(name);
        }
    }

    /**
     * {@code is} against a declared class, which must also accept subclasses.
     *
     * @param value    the tested value
     * @param expected the class value to test against
     * @return true when {@code value} is an instance of {@code expected} or of a
     *         class derived from it
     */
    public static boolean isInstanceOf(Object value, MandelaClass expected) {
        return value instanceof MandelaInstance instance
                && instance.owner().isSubclassOf(expected);
    }

    // -- truth -------------------------------------------------------------

    /**
     * The truthiness rule, chosen to match what the comparisons already say:
     * everything except {@code null}, {@code false}, {@code 0}, {@code 0.0} and
     * {@code ""} is true. An empty list or map is <em>true</em>, because
     * {@code [] == []} is true and a caller must be able to keep a collection
     * out of a boolean test by accident only when it is really a scalar.
     */
    public static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Long number) {
            return number != 0L;
        }
        if (value instanceof Double number) {
            return number != 0.0d;
        }
        if (value instanceof String text) {
            return !text.isEmpty();
        }
        return true;
    }

    // -- printing ----------------------------------------------------------

    /**
     * The display form used by {@code println} and string interpolation: strings
     * appear as they are, everything else appears as a literal-ish description.
     *
     * @param value the value to render
     * @return the text, never null
     */
    public static String display(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Double number) {
            return formatDouble(number);
        }
        return repr(value);
    }

    /**
     * The quoted / structural form used inside collections, in error messages
     * and by {@code str()}: the text a reader could paste back into a script.
     *
     * @param value the value to render
     * @return the representation, never null
     */
    public static String repr(Object value) {
        StringBuilder out = new StringBuilder();
        appendRepr(value, out, 0);
        return out.toString();
    }

    private static void appendRepr(Object value, StringBuilder out, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(text, out);
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Double number) {
            out.append(formatDouble(number));
        } else if (value instanceof Long number) {
            out.append(number.toString());
        } else if (value instanceof Ref cell) {
            appendRepr(cell.value, out, depth);
        } else if (depth >= MAX_DEPTH) {
            out.append("...");
        } else if (value instanceof MandelaList list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                appendRepr(list.get(i), out, depth + 1);
            }
            out.append(']');
        } else if (value instanceof MandelaMap map) {
            out.append('{');
            boolean first = true;
            for (String key : map.keys()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                quote(key, out);
                out.append(": ");
                appendRepr(map.get(key), out, depth + 1);
            }
            out.append('}');
        } else if (value instanceof MandelaRange range) {
            out.append(range.toString());
        } else if (value instanceof MandelaInstance instance) {
            out.append(instance.typeName()).append('(');
            boolean first = true;
            for (String key : instance.fieldNames()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append(key).append(": ");
                appendRepr(instance.get(key), out, depth + 1);
            }
            out.append(')');
        } else if (value instanceof MandelaClass declared) {
            out.append('<').append(declared.kind().name().toLowerCase(java.util.Locale.ROOT))
                    .append(' ').append(declared.name()).append('>');
        } else if (value instanceof MandelaError error) {
            out.append(error.kind()).append(": ").append(error.getMessage());
        } else if (value instanceof Callable function) {
            out.append(function.toString());
        } else {
            out.append(String.valueOf(value));
        }
    }

    /** How deep {@link #repr(Object)} nests before it replaces a value with {@code "..."}. */
    private static final int MAX_DEPTH = 16;

    /** Writes {@code text} as a Mandela string literal. */
    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    /**
     * Prints a double the way an author writes one: {@code 2.0} rather than
     * {@code 2.0E0}, and the shortest decimal that still round-trips, so
     * {@code 0.1} prints as {@code 0.1} and not as its binary expansion.
     */
    public static String formatDouble(double value) {
        if (value == Double.POSITIVE_INFINITY) {
            return "Inf";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        if (Double.isNaN(value)) {
            return "NaN";
        }
        double magnitude = Math.abs(value);
        if (magnitude != 0.0d && (magnitude >= 1e16 || magnitude < 1e-6)) {
            // Too wide or too small for plain notation; keep the exponent form.
            return Double.toString(value);
        }
        String text = BigDecimal.valueOf(value).toPlainString();
        if (text.indexOf('.') < 0) {
            text = text + ".0";
        }
        return text;
    }

    // -- equality and order ------------------------------------------------

    /**
     * Mandela equality: value equality for scalars, ranges, lists, maps and
     * {@code type} instances; identity for objects, classes and functions.
     */
    public static boolean equal(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Long && right instanceof Double) {
            return ((Long) left).doubleValue() == (Double) right;
        }
        if (left instanceof Double && right instanceof Long) {
            return (Double) left == ((Long) right).doubleValue();
        }
        if (left instanceof MandelaInstance && right instanceof MandelaInstance) {
            return left.equals(right);
        }
        return left.equals(right);
    }

    /**
     * Ordering for {@code < <= > >=}, defined between numbers, between strings,
     * and not at all between anything else.
     *
     * @return a negative, zero or positive int
     * @throws MandelaError when the two values cannot be ordered
     */
    public static int compare(Object left, Object right) {
        if (isNum(left) && isNum(right)) {
            return Double.compare(asDouble(left), asDouble(right));
        }
        if (left instanceof String && right instanceof String) {
            return ((String) left).compareTo((String) right);
        }
        throw MandelaError.type("cannot order a " + typeName(left) + " against a "
                + typeName(right) + "; use <, <=, >, >= only on numbers or strings");
    }

    /** @return true for {@code Int} and {@code Double} */
    public static boolean isNum(Object value) {
        return value instanceof Long || value instanceof Double;
    }

    /** @return true for an {@code Int}, which is always a boxed {@code long} */
    public static boolean isInt(Object value) {
        return value instanceof Long;
    }

    /** @return the value as a long, or 0 when it is not an integer */
    public static long asLong(Object value) {
        return (value instanceof Long number) ? number : 0L;
    }

    /** @return the value as a double, widening an {@code Int} or parsing a string */
    public static double asDouble(Object value) {
        if (value instanceof Double number) {
            return number;
        }
        if (value instanceof Long number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1.0d : 0.0d;
        }
        if (value instanceof String text) {
            Double parsed = parseNumber(text);
            if (parsed != null) {
                return parsed;
            }
        }
        throw MandelaError.type("cannot use a " + typeName(value) + " as a number");
    }

    /** @return the value as a long, truncating a {@code Double} and parsing a string */
    public static long asInt(Object value) {
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Double number) {
            return number.longValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1L : 0L;
        }
        if (value instanceof String text) {
            Long exact = parseLong(text);
            if (exact != null) {
                return exact;
            }
            Double parsed = parseNumber(text);
            if (parsed != null) {
                return parsed.longValue();
            }
        }
        throw MandelaError.type("cannot use a " + typeName(value) + " as an integer");
    }

    /** Parses {@code text} as an integer, returning null when it is not one. */
    public static Long parseLong(String text) {
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Parses {@code text} as a number, returning null when it is not one. */
    public static Double parseNumber(String text) {
        try {
            return Double.valueOf(text.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // -- conversion (the `as` operator) ------------------------------------

    /**
     * Applies {@code x as Type}.
     *
     * @param value the value to convert
     * @param name  the target type name
     * @return the converted value
     * @throws MandelaError when the conversion does not exist
     */
    public static Object convert(Object value, String name) {
        switch (name) {
            case "Any":
            case "Str":
            case "String":
                return display(value);
            case "Bool":
                return truthy(value);
            case "Int":
                return asInt(value);
            case "Double":
            case "Num":
            case "Number":
                return asDouble(value);
            case "Null":
                return null;
            default:
                break;
        }
        if (value instanceof MandelaInstance instance
                && instance.owner().name().equals(name)) {
            return value;
        }
        if (value instanceof MandelaClass declared && declared.name().equals(name)) {
            return value;
        }
        throw MandelaError.type("cannot convert a " + typeName(value) + " to " + name);
    }
}
