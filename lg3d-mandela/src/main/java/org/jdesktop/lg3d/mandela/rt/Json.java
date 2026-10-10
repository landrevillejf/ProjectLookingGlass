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
package org.jdesktop.lg3d.mandela.rt;

import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.MandelaMap;
import org.jdesktop.lg3d.mandela.values.Values;

/**
 * JSON in and out, with no third-party library.
 *
 * <p>This is the format every host needs: a Web Browser page hands a script a
 * JSON message, an Espresso panel persists its settings as JSON, and a desktop
 * app talks to a local service over HTTP with a JSON body. RFC 8259 is a small
 * language, so the reader is a hundred lines of direct descent rather than a
 * dependency, and it stays strict on purpose &mdash; no comments, no trailing
 * commas, no {@code NaN} &mdash; because a document that two parsers disagree
 * about is a bug nobody can find later.</p>
 *
 * <p>The mapping to Mandela values is the obvious one and the one a Java or
 * Kotlin host expects: {@code null}, {@code Bool}, {@code Int} (a {@code long}),
 * {@code Double} (a {@code double}), {@code Str}, list, map. Nothing is wrapped,
 * so a decoded document can be indexed and walked with the same syntax as one
 * the script built itself.</p>
 *
 * <p>Both directions are bounded. Nesting is capped at {@link #MAX_DEPTH} so a
 * hostile document cannot overflow the Java stack, and the writer refuses values
 * JSON cannot hold &mdash; a function, an instance of a script class &mdash;
 * rather than inventing a rendering that the reader could not restore. Use
 * {@link #stringify} when the point is a log line instead of a wire format.</p>
 */
public final class Json {

    /** How deep a document may nest before it is rejected. */
    public static final int MAX_DEPTH = 256;

    private Json() {
        // Static namespace.
    }

    // -- reading -------------------------------------------------------------

    /**
     * Reads one JSON document.
     *
     * @param text the document; may not be null
     * @return the value it describes, as Mandela values
     * @throws MandelaError with kind {@code JsonError} when the text is not JSON
     */
    public static Object parse(String text) {
        if (text == null) {
            throw MandelaError.of("JsonError", "cannot read JSON from nothing");
        }
        Reader reader = new Reader(text);
        reader.skipSpace();
        Object value = reader.value(0);
        reader.skipSpace();
        if (!reader.atEnd()) {
            throw reader.fail("the document ends after the first value");
        }
        return value;
    }

    /** @return true when {@code text} is a complete JSON document, for a validator */
    public static boolean canParse(String text) {
        try {
            parse(text);
            return true;
        } catch (MandelaError error) {
            return false;
        }
    }

    /** The cursor. One pass, no tokens, no backtracking. */
    private static final class Reader {

        private final String src;
        private int pos;

        Reader(String source) {
            this.src = source;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw fail("the document nests deeper than " + MAX_DEPTH
                        + " levels, which is the limit for one value");
            }
            if (atEnd()) {
                throw fail("the document ends in the middle of a value");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        private MandelaMap object(int depth) {
            pos++; // '{'
            MandelaMap out = new MandelaMap();
            skipSpace();
            if (match('}')) {
                return out;
            }
            while (true) {
                skipSpace();
                if (atEnd()) {
                    throw fail("the object is not closed");
                }
                if (src.charAt(pos) != '"') {
                    throw fail("an object key must be a quoted string");
                }
                String key = string();
                skipSpace();
                if (!match(':')) {
                    throw fail("expected ':' after the key " + key);
                }
                skipSpace();
                out.put(key, value(depth + 1));
                skipSpace();
                if (match(',')) {
                    continue;
                }
                if (match('}')) {
                    return out;
                }
                throw fail("expected ',' or '}' in an object");
            }
        }

        private MandelaList array(int depth) {
            pos++; // '['
            MandelaList out = new MandelaList();
            skipSpace();
            if (match(']')) {
                return out;
            }
            while (true) {
                skipSpace();
                out.add(value(depth + 1));
                skipSpace();
                if (match(',')) {
                    continue;
                }
                if (match(']')) {
                    return out;
                }
                throw fail("expected ',' or ']' in an array");
            }
        }

        private String string() {
            pos++; // opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw fail("the string is not closed");
                }
                char c = src.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw fail("a string may not contain the raw control character"
                                + " 0x" + Integer.toHexString(c));
                    }
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw fail("the escape is cut off");
                }
                char escape = src.charAt(pos++);
                switch (escape) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case '/':
                        out.append('/');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > src.length()) {
                            throw fail("\\u needs four hex digits");
                        }
                        String hex = src.substring(pos, pos + 4);
                        int code;
                        try {
                            code = Integer.parseInt(hex, 16);
                        } catch (NumberFormatException notHex) {
                            throw fail("'" + hex + "' is not a \\uXXXX code point");
                        }
                        pos += 4;
                        out.append((char) code);
                        break;
                    default:
                        throw fail("'\\' + '" + escape + "' is not a JSON escape");
                }
            }
        }

        private Object number() {
            int start = pos;
            match('-');
            while (!atEnd() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            boolean real = false;
            if (!atEnd() && src.charAt(pos) == '.' && pos + 1 < src.length()
                    && Character.isDigit(src.charAt(pos + 1))) {
                real = true;
                pos++;
                while (!atEnd() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                real = true;
                pos++;
                if (!atEnd() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                while (!atEnd() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            String text = src.substring(start, pos);
            if (text.isEmpty() || text.equals("-")) {
                throw fail("expected a value");
            }
            if (!real) {
                Long whole = Values.parseLong(text);
                if (whole != null) {
                    return whole;
                }
                // A number beyond long range arrives as a double; a JSON producer
                // that needs every digit should send it as a string.
                real = true;
            }
            Double decimal = Values.parseNumber(text);
            if (decimal == null) {
                throw fail("'" + text + "' is not a number");
            }
            return decimal;
        }

        private void expect(String word) {
            if (!src.startsWith(word, pos)) {
                throw fail("expected '" + word + "'");
            }
            pos += word.length();
        }

        private boolean match(char c) {
            if (!atEnd() && src.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        /** Skips the four JSON whitespace characters, and only those. */
        void skipSpace() {
            while (!atEnd()) {
                char c = src.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        MandelaError fail(String message) {
            int line = 1;
            int column = pos + 1;
            for (int i = 0; i < pos && i < src.length(); i++) {
                if (src.charAt(i) == '\n') {
                    line++;
                    column = pos - i;
                }
            }
            return MandelaError.of("JsonError", "json: " + message + " (at " + line
                    + ":" + column + ")");
        }
    }

    // -- writing -------------------------------------------------------------

    /**
     * Writes a value as a JSON document.
     *
     * @param value a null, Bool, Int, Double, Str, list or map
     * @return the document
     * @throws MandelaError when the value is not expressible in JSON
     */
    public static String encode(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0, false);
        return out.toString();
    }

    /**
     * Writes a value as text, rendering whatever JSON cannot hold the way
     * {@code println} would.
     *
     * <p>This is the debugging form: a script printing a map that happens to hold
     * a function or a class instance gets a readable line rather than an error,
     * and the shape stays the same as {@link #encode} for everything JSON can
     * carry.</p>
     *
     * @param value anything
     * @return the text
     */
    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, 0, true);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, int depth,
                              boolean lenient) {
        if (depth > MAX_DEPTH) {
            throw MandelaError.value("json: the value nests deeper than " + MAX_DEPTH
                    + " levels; it is probably self-referential");
        }
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
            return;
        }
        if (value instanceof Long || value instanceof Integer) {
            out.append(value);
            return;
        }
        if (value instanceof Double real) {
            if (real.isNaN() || real.isInfinite()) {
                if (!lenient) {
                    throw MandelaError.value("json cannot hold " + real);
                }
                out.append('"').append(real).append('"');
                return;
            }
            out.append(Values.formatDouble(real));
            return;
        }
        if (value instanceof String text) {
            quote(text, out);
            return;
        }
        if (value instanceof MandelaList list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(list.get(i), out, depth + 1, lenient);
            }
            out.append(']');
            return;
        }
        if (value instanceof MandelaMap map) {
            out.append('{');
            boolean first = true;
            for (java.util.Map.Entry<String, Object> entry : map.entries().entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                quote(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out, depth + 1, lenient);
            }
            out.append('}');
            return;
        }
        if (!lenient) {
            throw MandelaError.type("json cannot encode a " + Values.typeName(value)
                    + "; only null, Bool, Int, Double, Str, list and map have a JSON form");
        }
        // The lenient form falls back to the language's own rendering, which is
        // what a script printing its own data structures expects to see.
        out.append('"');
        quoteBody(Values.display(value), out);
        out.append('"');
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        quoteBody(text, out);
        out.append('"');
    }

    /** Escapes a string body, so the two quoting paths cannot drift apart. */
    private static void quoteBody(String text, StringBuilder out) {
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
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
    }
}
