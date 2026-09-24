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
package org.jdesktop.lg3d.widgets.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the two Java 3D-free, peer-free pieces of logic in {@link WeatherCard}:
 * the WMO weather-code &rarr; text mapping ({@link WeatherCard#condition(int)},
 * package-private) and the compact dependency-free JSON reader it uses to decode
 * the Open-Meteo response.
 *
 * <p>The JSON reader is a {@code private static final} nested class
 * ({@code WeatherCard$Json}) whose only entry point is {@code parse(String)}, so
 * it is driven reflectively here rather than through the network fetch path.
 * Everything else in the card is {@code Graphics2D} painting, which is
 * probe-verified per {@code lg3d-core/AGENTS.md}.</p>
 */
class WeatherCardLogicTest {

    // ------------------------------------------------------------------
    // WMO weather-code descriptions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("condition() maps every WMO code group to its text")
    void conditionMapsCodes() {
        assertEquals("Clear sky", WeatherCard.condition(0));
        assertEquals("Mainly clear", WeatherCard.condition(1));
        assertEquals("Partly cloudy", WeatherCard.condition(2));
        assertEquals("Overcast", WeatherCard.condition(3));
        assertEquals("Fog", WeatherCard.condition(45));
        assertEquals("Fog", WeatherCard.condition(48));
        assertEquals("Drizzle", WeatherCard.condition(51));
        assertEquals("Drizzle", WeatherCard.condition(53));
        assertEquals("Drizzle", WeatherCard.condition(55));
        assertEquals("Freezing drizzle", WeatherCard.condition(56));
        assertEquals("Freezing drizzle", WeatherCard.condition(57));
        assertEquals("Rain", WeatherCard.condition(61));
        assertEquals("Rain", WeatherCard.condition(63));
        assertEquals("Rain", WeatherCard.condition(65));
        assertEquals("Freezing rain", WeatherCard.condition(66));
        assertEquals("Freezing rain", WeatherCard.condition(67));
        assertEquals("Snow", WeatherCard.condition(71));
        assertEquals("Snow", WeatherCard.condition(73));
        assertEquals("Snow", WeatherCard.condition(75));
        assertEquals("Snow grains", WeatherCard.condition(77));
        assertEquals("Rain showers", WeatherCard.condition(80));
        assertEquals("Rain showers", WeatherCard.condition(81));
        assertEquals("Rain showers", WeatherCard.condition(82));
        assertEquals("Snow showers", WeatherCard.condition(85));
        assertEquals("Snow showers", WeatherCard.condition(86));
        assertEquals("Thunderstorm", WeatherCard.condition(95));
        assertEquals("Thunderstorm, hail", WeatherCard.condition(96));
        assertEquals("Thunderstorm, hail", WeatherCard.condition(99));
    }

    @Test
    @DisplayName("condition() falls back to an em dash for an unknown code")
    void conditionUnknownCode() {
        assertEquals("\u2014", WeatherCard.condition(42));
        assertEquals("\u2014", WeatherCard.condition(-1));
    }

    // ------------------------------------------------------------------
    // Minimal JSON reader (reflective: the class is private)
    // ------------------------------------------------------------------

    private static Object parse(String text) {
        try {
            Class<?> json = Class.forName(
                    "org.jdesktop.lg3d.widgets.builtin.WeatherCard$Json");
            Method m = json.getDeclaredMethod("parse", String.class);
            m.setAccessible(true);
            return m.invoke(null, text);
        } catch (InvocationTargetException e) {
            // Re-throw the parser's own failure so assertThrows sees it.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reach WeatherCard$Json.parse", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        assertTrue(o instanceof Map, "expected a Map but got " + o);
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        assertTrue(o instanceof List, "expected a List but got " + o);
        return (List<Object>) o;
    }

    @Test
    @DisplayName("parse reads an object of mixed scalar values")
    void parsesObject() {
        Map<String, Object> m = asMap(parse("{\"i\":42,\"d\":-3.5,\"s\":\"hi\","
                + "\"t\":true,\"f\":false,\"n\":null}"));
        assertEquals(42.0, m.get("i"));
        assertEquals(-3.5, m.get("d"));
        assertEquals("hi", m.get("s"));
        assertEquals(Boolean.TRUE, m.get("t"));
        assertEquals(Boolean.FALSE, m.get("f"));
        assertTrue(m.containsKey("n"));
        assertNull(m.get("n"));
    }

    @Test
    @DisplayName("parse reads empty and nested objects/arrays")
    void parsesNestedStructures() {
        assertTrue(asMap(parse("{}")).isEmpty());
        assertTrue(asList(parse("[]")).isEmpty());

        Map<String, Object> root = asMap(parse(
                "{\"current\":{\"temperature_2m\":12.5},"
                        + "\"hourly\":{\"time\":[\"00:00\",\"01:00\"],"
                        + "\"vals\":[1,2,3]}}"));
        Map<String, Object> current = asMap(root.get("current"));
        assertEquals(12.5, current.get("temperature_2m"));
        Map<String, Object> hourly = asMap(root.get("hourly"));
        assertEquals(List.of("00:00", "01:00"), asList(hourly.get("time")));
        assertEquals(List.of(1.0, 2.0, 3.0), asList(hourly.get("vals")));
    }

    @Test
    @DisplayName("parse reads a top-level array and exponent numbers")
    void parsesArrayAndExponents() {
        assertEquals(List.of(1.0, 2.0, 3.0), asList(parse("[1, 2, 3]")));
        assertEquals(100.0, parse("1e2"));
        assertEquals(-0.25, parse("-2.5e-1"));
        assertEquals(7.0, parse("  +7  "));
        // A signed exponent puts a '+' inside the number token: the reader's
        // char-class check treats index 0 ('+') as a match, so this parses.
        assertEquals(100.0, parse("1e+2"));
        assertEquals(100.0, parse("1E+2"));
    }

    @Test
    @DisplayName("parse tolerates whitespace around every structural token")
    void parsesWithWhitespace() {
        // Space between the braces/brackets: the reader must skip it before
        // testing for the empty-object / empty-array close.
        assertTrue(asMap(parse("{ }")).isEmpty());
        assertTrue(asList(parse("[ ]")).isEmpty());

        // Space after a comma (before the next key), around the colon, and
        // before the closing brace / comma in an array.
        Map<String, Object> spaced = asMap(parse("{ \"a\" : 1, \"b\" : 2 }"));
        assertEquals(1.0, spaced.get("a"));
        assertEquals(2.0, spaced.get("b"));
        assertEquals(List.of(1.0, 2.0), asList(parse("[1 ,2 ]")));
    }

    @Test
    @DisplayName("parse rejects a truncated structure at the end of input")
    void rejectsTruncatedStructure() {
        // peek()/next() must report the end of input as an IllegalStateException,
        // not run off the end of the string.
        assertThrows(IllegalStateException.class, () -> parse("["));
        assertThrows(IllegalStateException.class, () -> parse("{"));
    }

    @Test
    @DisplayName("parse decodes every string escape")
    void parsesStringEscapes() {
        assertEquals("a\nb", parse("\"a\\nb\""));
        assertEquals("a\tb", parse("\"a\\tb\""));
        assertEquals("q\"q", parse("\"q\\\"q\""));
        assertEquals("b\\b", parse("\"b\\\\b\""));
        assertEquals("s/s", parse("\"s\\/s\""));
        assertEquals("\b\f\r", parse("\"\\b\\f\\r\""));
        // Build the backslash-u escape by concatenation so javac's own
        // unicode-escape preprocessing cannot touch it: JSON "\u0041" -> 'A'.
        assertEquals("A", parse("\"" + "\\" + "u0041\""));
    }

    @Test
    @DisplayName("parse reads bare literals")
    void parsesLiterals() {
        assertEquals(Boolean.TRUE, parse("true"));
        assertEquals(Boolean.FALSE, parse("false"));
        assertNull(parse("null"));
        assertEquals("solo", parse("\"solo\""));
    }

    @Test
    @DisplayName("parse rejects malformed documents")
    void rejectsMalformed() {
        assertThrows(IllegalStateException.class, () -> parse(""));
        assertThrows(IllegalStateException.class, () -> parse("{\"a\"}"));
        assertThrows(IllegalStateException.class, () -> parse("{\"a\":1,}"));
        assertThrows(IllegalStateException.class, () -> parse("[1,2"));
        assertThrows(IllegalStateException.class, () -> parse("\"bad\\q\""));
        assertThrows(IllegalStateException.class, () -> parse("tru"));
        assertThrows(RuntimeException.class, () -> parse("{\"a\":1]"));
    }
}
